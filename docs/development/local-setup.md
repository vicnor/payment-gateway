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
- Java 21 (Temurin or similar — `sdkman` recommended)
- Node 20+ (for the frontend — `fnm` or `nvm`)
- `pnpm` for the frontend
- AWS CLI v2 (for talking to LocalStack via `aws --endpoint-url=...`)
- `jq` (used by bootstrap script)
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
    command: ["-jar", "DynamoDBLocal.jar", "-sharedDb", "-dbPath", "/data"]
    volumes:
      - dynamo-data:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/"]
      interval: 5s
      retries: 5

  localstack:
    image: localstack/localstack:3.8
    ports: ["4566:4566"]
    environment:
      SERVICES: sns,sqs,kms
      DEBUG: 0
      PERSISTENCE: 1
      AWS_DEFAULT_REGION: eu-north-1
    volumes:
      - localstack-data:/var/lib/localstack
      - ./scripts/bootstrap-aws.sh:/etc/localstack/init/ready.d/bootstrap.sh
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4566/_localstack/health"]
      interval: 5s
      retries: 10

  test-acquirer:
    build: ../services/test-acquirer-service
    ports: ["8090:8080"]
    profiles: ["full"]   # opt-in: `docker compose --profile full up`
    depends_on:
      localstack: { condition: service_healthy }

volumes:
  pg-data:
  dynamo-data:
  localstack-data:
```

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

`infrastructure/scripts/bootstrap-aws.sh` (runs once when LocalStack becomes healthy):

```bash
#!/usr/bin/env bash
set -euo pipefail

# SNS topics
for topic in payment-events checkout-events; do
  awslocal sns create-topic --name "$topic"
done

# SQS queues with DLQs
create_queue_with_dlq() {
  local queue="$1"
  local dlq_url
  dlq_url=$(awslocal sqs create-queue --queue-name "${queue}-dlq" | jq -r .QueueUrl)
  local dlq_arn
  dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" \
            --attribute-names QueueArn | jq -r .Attributes.QueueArn)
  awslocal sqs create-queue --queue-name "$queue" \
    --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}"
}

create_queue_with_dlq webhook-dispatch
create_queue_with_dlq payment-reconciliation

# Subscribe webhook-dispatch to payment-events
PAY_TOPIC=$(awslocal sns list-topics | jq -r '.Topics[] | select(.TopicArn|endswith(":payment-events")) | .TopicArn')
WEBHOOK_Q=$(awslocal sqs get-queue-url --queue-name webhook-dispatch | jq -r .QueueUrl)
WEBHOOK_ARN=$(awslocal sqs get-queue-attributes --queue-url "$WEBHOOK_Q" \
              --attribute-names QueueArn | jq -r .Attributes.QueueArn)
awslocal sns subscribe --topic-arn "$PAY_TOPIC" --protocol sqs --notification-endpoint "$WEBHOOK_ARN"

# KMS key for token-service
awslocal kms create-key --description "token-service envelope encryption (dev)" >/dev/null
KEY_ID=$(awslocal kms list-keys | jq -r '.Keys[0].KeyId')
awslocal kms create-alias --alias-name alias/token-service-dev --target-key-id "$KEY_ID"

# DynamoDB tables (against the dynamodb-local container)
DDB_HOST="http://dynamodb-local:8000"
DDB_ARGS="--endpoint-url=$DDB_HOST"

awslocal dynamodb $DDB_ARGS create-table \
  --table-name checkout_sessions \
  --attribute-definitions AttributeName=session_id,AttributeType=S \
                          AttributeName=merchant_id,AttributeType=S \
                          AttributeName=created_at,AttributeType=N \
  --key-schema AttributeName=session_id,KeyType=HASH \
  --global-secondary-indexes \
    "[{\"IndexName\":\"merchant-created-index\",
       \"KeySchema\":[{\"AttributeName\":\"merchant_id\",\"KeyType\":\"HASH\"},
                      {\"AttributeName\":\"created_at\",\"KeyType\":\"RANGE\"}],
       \"Projection\":{\"ProjectionType\":\"ALL\"},
       \"BillingMode\":\"PAY_PER_REQUEST\"}]" \
  --billing-mode PAY_PER_REQUEST >/dev/null

awslocal dynamodb $DDB_ARGS update-time-to-live \
  --table-name checkout_sessions \
  --time-to-live-specification "Enabled=true, AttributeName=expires_at" >/dev/null

awslocal dynamodb $DDB_ARGS create-table \
  --table-name tokens \
  --attribute-definitions AttributeName=token,AttributeType=S \
  --key-schema AttributeName=token,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

awslocal dynamodb $DDB_ARGS update-time-to-live \
  --table-name tokens \
  --time-to-live-specification "Enabled=true, AttributeName=expires_at" >/dev/null

# Idempotency keys table (used by every service exposing POST endpoints)
for svc in checkout payment; do
  awslocal dynamodb $DDB_ARGS create-table \
    --table-name "${svc}_idempotency_keys" \
    --attribute-definitions AttributeName=idempotency_key,AttributeType=S \
    --key-schema AttributeName=idempotency_key,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST >/dev/null

  awslocal dynamodb $DDB_ARGS update-time-to-live \
    --table-name "${svc}_idempotency_keys" \
    --time-to-live-specification "Enabled=true, AttributeName=expires_at" >/dev/null
done

echo "✓ AWS bootstrap complete"
```

## Makefile

Top-level `Makefile`:

```makefile
.PHONY: dev-up dev-down dev-bootstrap dev-seed dev-logs dev-reset

dev-up:
	cd infrastructure && docker compose up -d

dev-up-full:
	cd infrastructure && docker compose --profile full up -d

dev-down:
	cd infrastructure && docker compose down -v

dev-bootstrap:
	cd infrastructure && docker compose exec localstack /etc/localstack/init/ready.d/bootstrap.sh

dev-seed:
	./mvnw -pl services/merchant-service exec:java \
		-Dexec.mainClass=com.gateway.merchant.tools.SeedLocalMerchant

dev-reset: dev-down dev-up
	@sleep 5
	$(MAKE) dev-bootstrap
	$(MAKE) dev-seed

dev-logs:
	cd infrastructure && docker compose logs -f

build:
	./mvnw clean verify

format:
	./mvnw spotless:apply
	cd frontend/checkout-ui && pnpm format
```

`dev-seed` creates a single test merchant with a deterministic API key:
```
Merchant: mer_local_test
API key:  sk_test_local_01HQX_thisisafixedkeyforlocaldev0123
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

## First-time run

```bash
git clone <repo>
cd payment-gateway

# Start infra
make dev-up

# Wait ~10s for LocalStack to be healthy, then bootstrap
make dev-bootstrap

# Seed a test merchant
make dev-seed

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

Smoke test:
```bash
# Create a session
curl -X POST http://localhost:8100/v1/checkout-sessions \
  -H "Authorization: Bearer sk_test_local_01HQX_thisisafixedkeyforlocaldev0123" \
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
        DockerImageName.parse("localstack/localstack:3.8")
    ).withServices(SNS, SQS, KMS);

    @Container
    static GenericContainer<?> dynamo = new GenericContainer<>("amazon/dynamodb-local:2.5.2")
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
