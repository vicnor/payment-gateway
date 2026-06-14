# Local development setup

## Mental model

**Infrastructure runs in docker-compose. Services run from your IDE.**

This means: Postgres, DynamoDB Local, LocalStack (for SNS/SQS/KMS) live in long-running
containers. The Spring Boot services run from IntelliJ (or `./mvnw spring-boot:run`) against
those endpoints. The Next.js frontend runs from `pnpm dev`.

The only service that lives in compose is `test-acquirer-service`, behind a `--profile full` flag
— useful for when you don't want to run it from the IDE but still need it for the payment-service
to authorize against.

For CI and integration tests, **don't** use the compose stack. Use Testcontainers in
`shared-testing`, which spins up isolated containers per test class. Compose is for
human-driven local development; Testcontainers is for automated tests.

## Prerequisites

- Docker Desktop (or compatible — colima, OrbStack on macOS work fine)
- Java 25 (Temurin or similar — `sdkman` recommended)
- Node 20+ (for the frontend — `fnm` or `nvm`)
- `pnpm` for the frontend
- AWS CLI v2 (for talking to LocalStack via `aws --endpoint-url=...`)
- `jq` (used by bootstrap script)
- `python3` (used by `make wait-for-ready` to parse LocalStack health JSON)
- IntelliJ IDEA Ultimate or Community (with Maven support)

Versions are pinned in `.tool-versions` (asdf compatible) at the repo root.

## docker-compose.yml

Located at `infrastructure/docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    ports: ["5432:5432"]
    environment:
      POSTGRES_USER: gateway
      POSTGRES_PASSWORD: gateway
      POSTGRES_MULTIPLE_DATABASES: payment,webhook,merchant
    volumes:
      - ./scripts/init-multiple-postgres-dbs.sh:/docker-entrypoint-initdb.d/init.sh
      - pg-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U gateway"]
      interval: 5s
      timeout: 5s
      retries: 5

  dynamodb-local:
    image: amazon/dynamodb-local:2.5.2
    ports: ["8000:8000"]
    # -inMemory: avoids volume permission issues; dev-down -v wipes state anyway
    command: ["-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory"]
    # No healthcheck: the image ships no curl/wget. Readiness is probed by
    # the wait-for-ready Makefile target before bootstrap runs.

  localstack:
    image: localstack/localstack:4.2
    ports: ["4566:4566"]
    environment:
      SERVICES: sns,sqs,kms
      DEBUG: 0
      PERSISTENCE: 1
      AWS_DEFAULT_REGION: eu-north-1
    volumes:
      - localstack-data:/var/lib/localstack
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4566/_localstack/health"]
      interval: 5s
      retries: 10

  # test-acquirer is opt-in. Will fail to build until task 3.1 creates
  # services/test-acquirer-service. Use: docker compose --profile full up
  test-acquirer:
    build: ../services/test-acquirer-service
    ports: ["8090:8080"]
    profiles: ["full"]
    depends_on:
      localstack: { condition: service_healthy }

volumes:
  pg-data:
  localstack-data:
```

### Notes on DynamoDB Local

DynamoDB Local runs in `-inMemory` mode — no persistent volume. State is lost on container
restart, which is fine because `dev-down -v` wipes all state and `dev-bootstrap` reprovisions
everything from scratch.

### Notes on LocalStack 4.x

LocalStack 4.x reports service status as `"available"` (ready to use, lazy init) or `"running"`
(already initialized). Both mean the service is callable — `"available"` services initialize on
first API call. The `wait-for-ready` Makefile target waits for SNS to reach either state.

### Multi-db init for Postgres

`infrastructure/scripts/init-multiple-postgres-dbs.sh`:

```bash
#!/usr/bin/env bash
set -e
for db in $(echo "$POSTGRES_MULTIPLE_DATABASES" | tr ',' ' '); do
  echo "Creating database '$db'"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE $db;
    GRANT ALL PRIVILEGES ON DATABASE $db TO $POSTGRES_USER;
EOSQL
done
```

### AWS bootstrap script

`infrastructure/scripts/bootstrap-aws.sh` runs on the **host** (not inside a container).
It targets LocalStack at `localhost:4566` and DynamoDB Local at `localhost:8000` using plain
`aws --endpoint-url=...` with `--region eu-north-1`. The script is **fully idempotent** —
re-running it against an already-bootstrapped stack is safe.

Resources provisioned:

| Resource | Details |
|---|---|
| SNS topics | `payment-events`, `checkout-events` |
| SQS queues | `webhook-dispatch` + DLQ, `payment-reconciliation` + DLQ (`maxReceiveCount=5`) |
| SNS→SQS subscription | `payment-events` → `webhook-dispatch` |
| KMS key | alias `alias/token-service-dev` (envelope encryption for token-service) |
| DynamoDB: `checkout_sessions` | HASH `session_id`, GSI `merchant-created-index`, TTL `expires_at` |
| DynamoDB: `tokens` | HASH `token`, TTL `expires_at` |
| DynamoDB: `data_keys` | HASH `key_id`, TTL `expires_at` (encrypted DEKs for token-service) |
| DynamoDB: `checkout_idempotency_keys` | HASH `idempotency_key`, TTL `expires_at` |
| DynamoDB: `payment_idempotency_keys` | HASH `idempotency_key`, TTL `expires_at` |

All DynamoDB tables use `PAY_PER_REQUEST` billing.

## Makefile

Top-level `Makefile`:

```makefile
.PHONY: dev-up dev-up-full dev-down dev-bootstrap dev-reset dev-logs wait-for-ready build format

COMPOSE := docker compose -f infrastructure/docker-compose.yml

dev-up:
	$(COMPOSE) up -d

dev-up-full:
	$(COMPOSE) --profile full up -d

dev-down:
	$(COMPOSE) down -v

# Poll until LocalStack SNS/SQS/KMS are available and DynamoDB Local responds.
# LocalStack 4.x reports "available" (not "running") for enabled services.
wait-for-ready:
	@echo "Waiting for LocalStack..."
	@bash -c '\
	  for i in $$(seq 1 30); do \
	    STATUS=$$(curl -fsS http://localhost:4566/_localstack/health 2>/dev/null \
	      | python3 -c "import sys,json; h=json.load(sys.stdin); print(h[\"services\"].get(\"sns\",\"off\"))" 2>/dev/null); \
	    if [ "$$STATUS" = "available" ] || [ "$$STATUS" = "running" ]; then \
	      echo "LocalStack ready (sns: $$STATUS)."; break; \
	    fi; \
	    echo "  ...$$((i*2))s (sns: $$STATUS)"; \
	    if [ $$i -eq 30 ]; then echo "ERROR: LocalStack timed out"; exit 1; fi; \
	    sleep 2; \
	  done'
	@echo "Waiting for DynamoDB Local..."
	@bash -c '\
	  for i in $$(seq 1 15); do \
	    if aws --region eu-north-1 --endpoint-url=http://localhost:8000 dynamodb list-tables --output json >/dev/null 2>&1; then \
	      echo "DynamoDB Local ready."; break; \
	    fi; \
	    echo "  ...$$((i*2))s"; \
	    if [ $$i -eq 15 ]; then echo "ERROR: DynamoDB Local timed out"; exit 1; fi; \
	    sleep 2; \
	  done'
	@echo "Infrastructure ready."

dev-bootstrap: wait-for-ready
	./infrastructure/scripts/bootstrap-aws.sh

# dev-seed is added in task 1.3 once merchant-service exists.
dev-reset: dev-down dev-up dev-bootstrap

dev-logs:
	$(COMPOSE) logs -f

build:
	./mvnw clean verify

format:
	./mvnw spotless:apply
	cd frontend/checkout-ui && pnpm format
```

`dev-seed` (added in task 1.3) creates a single test merchant with a deterministic API key:
```
Merchant: mer_local_test
API key:  sk_test_01JTESTAAAAAAA0000000000AA_localdevfixedkey000000000000000000000000000
Webhook:  http://host.docker.internal:9999  (any local listener, e.g. webhook.site)
```

That fixed key is hardcoded in `dev-seed` so curl/Postman calls work immediately.

## Spring profile setup

Each service has three profiles:

- `local` — points at docker-compose endpoints. Default when running from IDE.
- `test` — used by Testcontainers, endpoints injected by test setup.
- `aws` — real AWS endpoints (no `endpoint-override`). Used in deployed environments.

Example `services/checkout-service/src/main/resources/application-local.yml`:

```yaml
spring:
  profiles.active: local
  datasource:
    url: jdbc:postgresql://localhost:5432/payment   # if this service uses Postgres
    username: gateway
    password: gateway
  flyway:
    locations: classpath:db/migration

aws:
  region: eu-north-1
  endpoint-override: http://localhost:4566           # SNS/SQS/KMS
  dynamodb-endpoint: http://localhost:8000           # DynamoDB Local
  credentials:
    access-key: test
    secret-key: test

gateway:
  checkout:
    session-ttl: PT30M
    base-url: http://localhost:3000                  # Next.js dev server
  events:
    checkout-topic-arn: arn:aws:sns:eu-north-1:000000000000:checkout-events
  http:
    merchant-service-url: http://localhost:8101
    payment-service-url:  http://localhost:8102
    token-service-url:    http://localhost:8103

logging.level:
  com.gateway: DEBUG
  org.springframework.web: INFO
```

The AWS SDK v2 client beans should read `endpoint-override` conditionally:

```java
@Bean
SqsAsyncClient sqsAsyncClient(AwsProperties props) {
    var builder = SqsAsyncClient.builder().region(Region.of(props.region()));
    if (props.endpointOverride() != null) {
        builder.endpointOverride(URI.create(props.endpointOverride()))
               .credentialsProvider(StaticCredentialsProvider.create(
                   AwsBasicCredentials.create(props.credentials().accessKey(),
                                              props.credentials().secretKey())));
    }
    return builder.build();
}
```

In `application-aws.yml` you omit `endpoint-override` and use IAM role credentials. Same code.

## Service port allocation

Fixed ports so URLs in config are stable:

| Service | Port |
|---|---|
| frontend (Next.js) | 3000 |
| checkout-service | 8100 |
| merchant-service | 8101 |
| payment-service | 8102 |
| token-service | 8103 |
| webhook-service | 8104 |
| test-acquirer-service | 8090 (compose) or 8105 (IDE) |
| Postgres | 5432 |
| DynamoDB Local | 8000 |
| LocalStack | 4566 |

**Port conflicts:** These are standard ports. If you have other projects using them, stop those
containers before `make dev-up` — `docker stop <name>` (not `docker rm`) preserves the other
project's data. Resume with `docker start <name>` when switching back.

## First-time run

```bash
git clone <repo>
cd payment-gateway

# Start infra (downloads images on first run)
make dev-up

# Bootstrap SNS/SQS/KMS/DynamoDB (wait-for-ready is automatic)
make dev-bootstrap

# Seed a test merchant (available after task 1.3)
# make dev-seed

# Install shared modules to local .m2 repo — required before running any service
# from the CLI. Maven resolves sibling-module JARs from .m2, not from target/.
# Re-run this whenever shared/* changes.
./mvnw install -DskipTests

# Run services from IntelliJ — open Spring Boot run configs, start each
# Or run the full stack from CLI:
./mvnw -pl services/merchant-service spring-boot:run &
./mvnw -pl services/token-service     spring-boot:run &
./mvnw -pl services/test-acquirer-service spring-boot:run &
./mvnw -pl services/payment-service   spring-boot:run &
./mvnw -pl services/checkout-service  spring-boot:run &
./mvnw -pl services/webhook-service   spring-boot:run &

# Frontend
cd frontend/checkout-ui && pnpm install && pnpm dev
```

Smoke test (requires task 1.3+ to be complete):
```bash
# Create a session
curl -X POST http://localhost:8100/v1/checkout-sessions \
  -H "Authorization: Bearer sk_test_01JTESTAAAAAAA0000000000AA_localdevfixedkey000000000000000000000000000" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 19900,
    "currency": "DKK",
    "merchant_reference": "test-001",
    "return_url": "http://localhost:9999/return",
    "cancel_url": "http://localhost:9999/cancel"
  }'

# Response includes `url` — open that in a browser to see the checkout page
```

## Verify bootstrap

After `make dev-bootstrap`, confirm all resources exist:

```bash
# 2 SNS topics
aws --region eu-north-1 --endpoint-url=http://localhost:4566 sns list-topics

# 4 SQS queues (webhook-dispatch, webhook-dispatch-dlq, payment-reconciliation, payment-reconciliation-dlq)
aws --region eu-north-1 --endpoint-url=http://localhost:4566 sqs list-queues

# 5 DynamoDB tables
aws --region eu-north-1 --endpoint-url=http://localhost:8000 dynamodb list-tables

# KMS alias
aws --region eu-north-1 --endpoint-url=http://localhost:4566 kms list-aliases

# 3 Postgres databases
PGPASSWORD=gateway psql -h localhost -U gateway -l
```

## Testcontainers note

In `shared-testing`, base classes spin up Postgres + LocalStack + DynamoDB containers and
inject the endpoints into `@DynamicPropertySource`. Example:

```java
@SpringBootTest
@Testcontainers
abstract class AbstractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:4.2")
    ).withServices(SNS, SQS, KMS);

    @Container
    static GenericContainer<?> dynamo = new GenericContainer<>("amazon/dynamodb-local:2.5.2")
        .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
        .withExposedPorts(8000);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",     postgres::getJdbcUrl);
        r.add("aws.endpoint-override",     localstack::getEndpoint);
        r.add("aws.dynamodb-endpoint",     () -> "http://localhost:" + dynamo.getMappedPort(8000));
    }
}
```

Each test class gets fresh containers, no shared state.
