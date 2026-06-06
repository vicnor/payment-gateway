# Payment Gateway

A card payment gateway. Merchants integrate via REST API, consumers pay on a hosted checkout page,
merchants receive webhooks with the outcome.

## Scope (v1)

In scope:

- Hosted checkout page (consumer redirected to `checkout.yourgateway.com`)
- Card payments only (Visa/Mastercard via test acquirer)
- Single-use tokens
- Server-to-server merchant API with API key auth
- Webhooks with HMAC signing
- Manual merchant onboarding (no self-service)

Explicitly out of scope for v1 — but architecture must accommodate them:

- 3DS / PSD2 SCA (deferred — see `docs/adr/0001-pci-scope-and-hosted-checkout.md`)
- Real acquirer integration (using `test-acquirer-service` instead)
- Reusable tokens / card vault
- Non-card methods (MobilePay, Apple Pay, etc.)
- Merchant dashboard / self-service onboarding
- Embedded fields / merchant-hosted checkout

When asked to build something deferred, do not build it. Flag it and reference the ADR.

## Architecture

Six backend services + Next.js frontend, AWS-native.

| Service                 | Purpose                                      | Data store     |
| ----------------------- | -------------------------------------------- | -------------- |
| `checkout-service`      | Session lifecycle                            | DynamoDB       |
| `token-service`         | Card tokenization (PCI-scoped)               | DynamoDB + KMS |
| `payment-service`       | Payment orchestration, acquirer routing      | PostgreSQL     |
| `webhook-service`       | Async outbound webhook delivery              | PostgreSQL     |
| `merchant-service`      | Merchant accounts, API keys, signing secrets | PostgreSQL     |
| `test-acquirer-service` | Mock acquirer for dev/test                   | In-memory      |

Inter-service comms: HTTP for synchronous calls inside trust boundary; SNS → SQS for events.
**Never** import code across services. Cross-cutting code goes in `shared-*` modules.

For full detail, see `docs/architecture/`.

## Repo layout

```
payment-gateway/
├── CLAUDE.md                        # this file
├── pom.xml                          # parent: dep mgmt, plugin mgmt
├── shared/                          # shared Maven modules
│   ├── shared-events/               # SNS/SQS event POJOs
│   ├── shared-api/                  # OpenAPI specs, generated stubs
│   ├── shared-security/             # API key filter, HMAC helpers
│   ├── shared-web/                  # error handlers, idempotency filter, tracing
│   └── shared-testing/              # Testcontainers bases, fixtures
├── services/
│   ├── checkout-service/
│   ├── token-service/
│   ├── payment-service/
│   ├── webhook-service/
│   ├── merchant-service/
│   └── test-acquirer-service/
├── frontend/
│   └── checkout-ui/                 # Next.js — NOT in Maven reactor
├── infrastructure/
│   ├── docker-compose.yml
│   ├── scripts/                     # bootstrap-aws.sh, init dbs
│   ├── terraform/                   # AWS infra
│   └── k8s/
└── docs/
    ├── architecture/
    ├── development/
    └── adr/
```

## Tech stack

- **Backend**: Java 25, Spring Boot 3.5+, Maven (multi-module), Flyway, PostgreSQL 16, DynamoDB
- **Frontend**: Next.js (App Router, latest stable), TypeScript, Tailwind, next-intl (da-DK + en-US)
- **AWS**: SNS, SQS, KMS, Secrets Manager, eu-north-1 (Stockholm)
- **Local dev**: docker-compose + LocalStack + DynamoDB Local
- **Testing**: JUnit 5, Testcontainers, WireMock, Playwright (frontend)

## Commands

```bash
# Local infra (Postgres, DynamoDB Local, LocalStack)
make dev-up                          # start infra
make dev-bootstrap                   # create queues, topics, tables, kms key
make dev-seed                        # insert test merchant with known API key
make dev-down                        # tear down + remove volumes

# Build & test
./mvnw clean verify                  # build everything, run unit + integration tests
./mvnw -pl services/checkout-service verify   # one service only

# Run a service
# Multi-module projects require shared modules to be installed to local .m2 first.
# Run once after a fresh clone, and again whenever shared/* changes.
./mvnw install -DskipTests
./mvnw -pl services/merchant-service spring-boot:run   # then run any service by name

# Frontend
cd frontend/checkout-ui && pnpm dev  # next dev server on :3000
cd frontend/checkout-ui && pnpm test # vitest

# Formatting
./mvnw spotless:apply                # auto-format Java
cd frontend/checkout-ui && pnpm format
```

## Conventions

### Code style

- Java 25 features OK: records, pattern matching, sealed types, text blocks. Prefer records for DTOs.
- Use Lombok sparingly — `@Slf4j` is fine; avoid `@Data`/`@Builder` on JPA entities.
- Spring: constructor injection only. No field injection. No `@Autowired` on fields.
- All `Money` values are minor units (integer). Never floats anywhere.
- All timestamps are `Instant` in Java, `TIMESTAMPTZ` in Postgres, epoch seconds in DynamoDB.

### Package structure (per service)

```
com.gateway.<service>
├── api          # @RestController, request/response DTOs
├── domain       # entities, value objects, domain services
├── persistence  # repositories, JPA entities, Dynamo mappers
├── event        # SNS publishers, SQS listeners
├── config       # @Configuration beans
└── client       # HTTP clients to other services
```

### IDs

- External IDs: `<prefix>_<ULID>` — e.g. `cs_01HQX2YK3M4N5P6Q7R8S9T0V1W`, `pay_01HQX...`, `mer_01HQX...`
- Internal DB IDs: UUID v7 in Postgres, opaque strings in DynamoDB
- Generate ULIDs with `com.github.f4b6a3:ulid-creator`

### API design

- All endpoints under `/v1/`
- POST endpoints require `Idempotency-Key` header (UUID, stored 24h)
- All responses include `X-Request-Id`
- Errors use the shape in `docs/architecture/api.md` (`{error: {type, code, message, param?, request_id}}`)

### Testing

- Unit tests next to source (`src/test/java`), no Spring context
- Integration tests use `@SpringBootTest` + Testcontainers in `src/test/java/.../it/`
- Every controller has a `*ControllerTest` (web slice) and a `*IT` (full slice)
- No test depends on another test's state
- Every `*IT` class must declare `@SpringBootTest(classes = <ServiceApplication>.class, webEnvironment = RANDOM_PORT)` explicitly — do NOT rely on Spring Boot's upward package scan. The abstract bases in `shared-testing` (`AbstractPostgresIT`, `AbstractDynamoIT`, `AbstractLocalStackIT`) provide container infrastructure only; they do not carry `@SpringBootTest`.

### Spring Boot Maven plugin — every service

Every service pom must configure `spring-boot-maven-plugin` with `<classifier>exec</classifier>`:

```xml
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <configuration>
    <classifier>exec</classifier>
    ...
  </configuration>
</plugin>
```

Without this, `repackage` replaces the main artifact with a fat JAR where classes are nested under
`BOOT-INF/classes/`. Failsafe resolves the artifact JAR (not `target/classes`) for its test
classpath, so the standard JVM classloader cannot find the `@SpringBootApplication` class and the
Spring context fails to boot. `classifier=exec` keeps the original thin JAR as the main artifact;
the fat JAR is produced as `*-exec.jar` and used for deployment.

### Git

- Conventional commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`
- One service per commit when possible (`feat(checkout): ...`)
- Never commit secrets — pre-commit hook with `gitleaks` should run

## Always do

- Read `docs/architecture/overview.md` once at start of a new task to refresh context
- For ANY change touching auth, PCI scope, or money, check `docs/adr/` first
- When adding a new field to an event, treat it as a breaking change — update `shared-events`
  with a new event version, don't mutate the existing one
- When working on a specific service, also load that service's `CLAUDE.md` if present

## Never do

- Never log PAN, CVV, full card numbers, or session secrets. Last4 and brand are OK.
- Never use floats for money. Minor-units integers only.
- Never call another service's database directly. Cross-service = HTTP or event.
- Never write code that crosses the token-service boundary without explicit instruction —
  it's the PCI-scoped service and changes there have audit implications.
- Never bypass the idempotency filter on POST endpoints.

## More

- Architecture detail: `docs/architecture/`
- Decisions and rationale: `docs/adr/`
- Roadmap and next tasks: `docs/roadmap.md`
- Local dev setup: `docs/development/local-setup.md`
