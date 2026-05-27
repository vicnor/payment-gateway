# Roadmap

Concrete tasks in dependency order. Each task has acceptance criteria and links to relevant
context.

The order matters — later tasks depend on earlier ones. Don't skip ahead.

When you finish a task, mark it done here (do not commit to git). When you're picking up work, find the
first unchecked task in the list.

---

## Phase 0 — Foundations

### ✅ 0.1 Parent POM and Maven structure

Set up the multi-module Maven project. No services yet, just the skeleton.

**Done when:**

- `pom.xml` at root with `packaging: pom` and `dependencyManagement` for Spring Boot BOM,
  AWS SDK BOM, Testcontainers BOM, JUnit BOM
- `shared/pom.xml` and `services/pom.xml` as aggregator POMs
- `.mvn/wrapper/` with current Maven version
- `./mvnw clean verify` runs (does nothing yet) and exits 0
- `.gitignore` covers target/, .idea/, .vscode/, node_modules/, .DS_Store
- `.editorconfig` for consistent formatting
- `spotless-maven-plugin` configured in parent POM with Google Java Format

**Context:** `docs/adr/0002-maven-multimodule-monorepo.md`

### 0.2 Shared modules — initial skeletons

Create empty-ish modules so other code can depend on them.

**Done when:**

- `shared/shared-events` — only the `BaseEvent` record (id, type, created) and an enum of
  event types
- `shared/shared-api` — empty for now, will hold OpenAPI specs
- `shared/shared-security` — empty for now
- `shared/shared-web` — `GlobalExceptionHandler` returning the error envelope from
  `docs/architecture/api.md`, request-id filter setting `X-Request-Id`, request-logging filter
- `shared/shared-testing` — `AbstractIntegrationTest` base with Testcontainers (Postgres,
  LocalStack, DynamoDB Local)

**Context:** `docs/architecture/overview.md` (event envelope shape, error envelope)

### 0.3 Local infra

Get docker-compose + LocalStack + DynamoDB Local working.

**Done when:**

- `infrastructure/docker-compose.yml` defines Postgres (multi-db), DynamoDB Local, LocalStack
- `infrastructure/scripts/init-multiple-postgres-dbs.sh` creates payment/webhook/merchant DBs
- `infrastructure/scripts/bootstrap-aws.sh` creates SNS topics, SQS queues + DLQs,
  subscriptions, KMS key, all DynamoDB tables
- Top-level `Makefile` with `dev-up`, `dev-down`, `dev-bootstrap`, `dev-reset`
- Running `make dev-up && make dev-bootstrap` succeeds and:
  - `aws --endpoint-url=http://localhost:4566 sns list-topics` returns 2 topics
  - `aws --endpoint-url=http://localhost:4566 sqs list-queues` returns 4 queues (2 + 2 DLQs)
  - `aws --endpoint-url=http://localhost:8000 dynamodb list-tables` returns 4+ tables

**Context:** `docs/development/local-setup.md`

---

## Phase 1 — merchant-service (everyone depends on it)

### 1.1 merchant-service scaffold

Empty Spring Boot service on port 8101.

**Done when:**

- `services/merchant-service` builds with `./mvnw -pl services/merchant-service verify`
- Starts on port 8101 with `spring-boot:run`
- `/actuator/health` returns 200
- Flyway migration `V001__init.sql` creates `merchants`, `api_keys`, `webhook_secrets` tables
- Integration test runs against Testcontainers Postgres and passes

### 1.2 Admin endpoints

The endpoints we use to manually onboard merchants in v1.

**Done when:**

- `POST /admin/merchants` creates a merchant, returns merchant body
- `POST /admin/merchants/{id}/api-keys` issues a key, returns plain key exactly once + key id
- `POST /admin/merchants/{id}/webhook-secret` rotates the signing secret, returns plain secret
- API key plain value is `sk_<mode>_<26 ULID>_<32 random>`, stored as Argon2id hash
- Tests cover: happy path, validation errors, hashing roundtrip, plain-value-not-logged

**Context:** `docs/architecture/data-model.md` (Merchant section)

### 1.3 Internal validation endpoints

Used by every other service to validate API keys and fetch merchant config.

**Done when:**

- `GET /internal/v1/api-keys/{prefix}` — given the first 16 chars of the key, returns the
  candidate key record (with hash). The shared-security filter compares hashes itself.
- `GET /internal/v1/merchants/{id}` — full merchant config (branding, callback URL, etc.)
- Caffeine cache with 5min TTL on both endpoints
- `dev-seed` Maven exec target inserts `mer_local_test` with API key
  `sk_test_local_01HQX_thisisafixedkeyforlocaldev0123` and webhook URL
  `http://host.docker.internal:9999`
- `make dev-seed` works after a fresh `make dev-up && make dev-bootstrap`

### 1.4 shared-security: API key filter

The Spring filter that every other service uses to validate inbound merchant API requests.

**Done when:**

- `ApiKeyAuthenticationFilter` in `shared-security`
- Reads `Authorization: Bearer ...` header, extracts prefix, calls merchant-service, verifies
  hash, populates a `MerchantPrincipal` in `SecurityContextHolder`
- Caffeine cache in front (1min TTL) so the merchant-service is not hit on every request
- Returns the standard error envelope on failure
- Test demonstrates: valid key → 200, invalid key → 401, revoked key → 401, missing header → 401
- One contract test per service later imports this and uses it

**Context:** `docs/architecture/api.md` (auth)

---

## Phase 2 — token-service (PCI scope)

### 2.1 token-service scaffold

Standalone service. Treated as the PCI-scoped service for the rest of the project.

**Done when:**

- `services/token-service` builds, starts on port 8103
- DynamoDB tables `tokens` and `data_keys` accessed via AWS SDK v2 enhanced client
- KMS client wired against LocalStack endpoint in `local` profile
- `/actuator/health` returns 200

### 2.2 Tokenization endpoint

The browser-facing endpoint.

**Done when:**

- `POST /checkout/{session_id}/tokens` accepts `{ card_number, exp_month, exp_year, cvv,
holder_name }`
- Validates: Luhn check on card_number, exp in future, CVV length matches brand
- Generates a DEK, calls KMS to encrypt it, stores encrypted DEK in `data_keys`
- AES-256-GCM encrypts the card data with the plain DEK, stores ciphertext in `tokens`
- Returns `{ token, brand, last4, exp_month, exp_year }`
- TTL set to 30 minutes on the `tokens` row
- **Hard test:** assert that the response body never contains the PAN or CVV, and that no log
  line at any level contains the PAN or CVV (use a custom appender in tests that fails on PAN
  patterns)
- CORS allows requests from `http://localhost:3000` (local Next.js) and the prod checkout
  domain only

### 2.3 Detokenization endpoint

Internal, called by payment-service.

**Done when:**

- `POST /internal/v1/tokens/{token}/detokenize` returns PAN + expiry
- Marks the token as `used: true` (conditional write — fails with 409 if already used)
- Returns 404 if token is expired or doesn't exist
- Allowlist of caller services in config — currently only payment-service
- Audit log entry per detokenize call (token id + caller service id + timestamp), in a
  separate log appender that goes to its own CloudWatch log group in prod

### 2.4 CORS, rate limiting, hardening

Token endpoint is exposed to the internet. Treat it as such.

**Done when:**

- CORS preflight handling complete
- Rate limit per session_id (100 token attempts per session — to limit BIN scraping if a
  session is leaked)
- Strict CSP headers on the endpoint host (`tokens.yourgateway.com`)
- No-cache headers on every response
- `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`

**Context:** `docs/architecture/services.md` (token-service hard rules), `docs/adr/0001-pci-scope-and-hosted-checkout.md`

---

## Phase 3 — test-acquirer-service

Built before payment-service so payment-service can integration-test against it.

### 3.1 test-acquirer-service

The mock acquirer.

**Done when:**

- `services/test-acquirer-service` builds, starts on port 8105 (8090 in compose)
- `POST /internal/v1/authorize` accepts `{ pan, exp_month, exp_year, cvv, amount, currency,
reference }`
- Returns deterministic outcome per the test card table in
  `docs/architecture/services.md` — `4242 4242 4242 4242` approves with `{ approved: true,
auth_code: "TEST123", acquirer_reference: "acq_test_..." }`; `4000 0000 0000 0002` declines
- Timeouts and errors are real — `4000 0000 0000 0341` actually sleeps for 30s
- No persistence (or optional in-memory log)
- Tests cover every card in the table

---

## Phase 4 — payment-service

### 4.1 payment-service scaffold + migrations

**Done when:**

- Service builds, starts on port 8102
- Flyway migrations create `payments`, `payment_attempts`, `payment_events`, `outbox`
- shared-security API key filter wired for Merchant API endpoints
- Integration tests pass against Testcontainers Postgres

### 4.2 Payment authorization

The synchronous internal endpoint called by checkout-service.

**Done when:**

- `POST /internal/v1/payments` accepts `{ checkout_session_id, merchant_id, amount, currency,
merchant_reference, token, metadata }`
- Detokenizes via token-service
- Calls test-acquirer-service
- Writes Payment + outbox row in one DB transaction
- State machine enforced: only `PENDING → CAPTURED | FAILED` in v1 (see
  `docs/architecture/data-model.md`)
- Returns the Payment shape from `docs/architecture/api.md`
- Tests cover: approved, declined, acquirer timeout, token already used, token not found

### 4.3 Outbox publisher

Reads from `outbox`, publishes to SNS, marks `published_at`.

**Done when:**

- A `@Scheduled` job runs every 1s, claims up to 50 unpublished outbox rows with `SELECT ...
FOR UPDATE SKIP LOCKED`
- Publishes each to the right SNS topic via `shared-events`
- Updates `published_at` on success
- Test publishes a row and asserts it shows up in LocalStack SQS via the topic subscription
- Metrics: outbox lag (max age of unpublished rows), publish rate, publish error rate

### 4.4 Merchant API endpoints

The public read-only endpoints for payments.

**Done when:**

- `GET /v1/payments/{id}` returns the payment if it belongs to the authenticated merchant
- `GET /v1/payments?limit=&starting_after=` cursor-paginates
- Returns the shape in `docs/architecture/api.md`
- 404 if the payment doesn't belong to the authenticated merchant (not 403 — don't leak
  existence)

---

## Phase 5 — checkout-service

### 5.1 checkout-service scaffold

**Done when:**

- Service builds, starts on port 8100
- DynamoDB enhanced client wired for `checkout_sessions` and `checkout_idempotency_keys`

### 5.2 Merchant API for sessions

**Done when:**

- `POST /v1/checkout-sessions` validates input, creates session, returns shape from
  `docs/architecture/api.md`
- Idempotency-Key handling (shared-web filter from earlier)
- URL pattern validation against merchant config
- `merchant_reference` uniqueness per merchant (handled at insert time with a conditional
  write — `attribute_not_exists` on a separate GSI)
- `GET /v1/checkout-sessions/{id}`, `POST /v1/checkout-sessions/{id}/cancel`
- Tests: happy path, validation errors, idempotency, URL-pattern reject, cancel after
  completion → 409

### 5.3 Checkout API for the browser

**Done when:**

- `GET /checkout/{id}/session-details` validates `X-Checkout-Session-Secret`, returns the
  browser-safe shape
- `POST /checkout/{id}/complete` validates secret, calls payment-service, transitions session
  to COMPLETED or leaves it IN_PROGRESS (depending on outcome), returns `{ status, payment_id,
redirect_url }`
- `POST /checkout/{id}/cancel` transitions session to CANCELLED
- Session state transitions use DynamoDB conditional writes for safety
- Tests cover: secret mismatch, expired session, idempotent complete (retry posts same token →
  returns same outcome)

### 5.4 Outbox + events

Same pattern as payment-service.

**Done when:**

- `checkout_outbox` table in Dynamo (yes, a Dynamo outbox — slightly different mechanics from
  Postgres, see https://aws.amazon.com/blogs/database/the-transactional-outbox-pattern-using-amazon-dynamodb/)
- Events published: `checkout.created`, `checkout.completed`, `checkout.cancelled`,
  `checkout.expired`

---

## Phase 6 — webhook-service

### 6.1 webhook-service scaffold + migrations

**Done when:**

- Service starts on port 8104
- Postgres tables `webhook_deliveries`, `webhook_attempts`

### 6.2 SQS consumer

**Done when:**

- Listens on `webhook-dispatch` SQS queue
- Each message → fetch merchant callback URL + signing secret from merchant-service →
  create `webhook_deliveries` row in PENDING with `next_attempt_at = now()`
- Idempotent — same event id ignored if already delivered (lookup by `event_id` unique
  constraint)

### 6.3 Delivery worker

**Done when:**

- `@Scheduled` job claims `PENDING` deliveries where `next_attempt_at <= now()` with
  `FOR UPDATE SKIP LOCKED`
- POSTs the event body to callback URL with HMAC signature (see
  `docs/architecture/api.md` for header format)
- On 2xx: status → DELIVERED
- On non-2xx or error: status → still PENDING, `attempt_count++`, `next_attempt_at` set per
  backoff schedule (30s, 2m, 10m, 1h, 6h, 24h)
- On 6th failure: status → DEAD, log + metric
- Non-blocking HTTP client (Spring `RestClient` is fine in 3.2+, or `WebClient` if reactive)
- 10s connection + read timeout per attempt
- Tests cover: 200 → delivered, 500 → retry scheduled, timeout → retry scheduled, 6 failures
  → dead, signature format correct, replay protection on timestamp

### 6.4 Admin endpoint for redelivery

**Done when:**

- `POST /admin/deliveries/{id}/redeliver` resets a DEAD delivery to PENDING for retry
- Useful when a merchant fixes their endpoint and asks us to retry their queue

---

## Phase 7 — Frontend (Next.js checkout-ui)

### 7.1 Next.js skeleton

**Done when:**

- `frontend/checkout-ui` with Next.js (App Router), TypeScript, Tailwind, next-intl set up
  for da-DK + en-US
- `/checkout/[sessionId]` dynamic route renders a placeholder
- Strict CSP, no-store, `X-Frame-Options: DENY` configured in `next.config.js` and middleware
- Local dev: `pnpm dev` runs on :3000, talks to local checkout-service on :8100

### 7.2 Session loading + render

**Done when:**

- Page reads `sessionId` from path and `k` (secret) from query
- Calls `GET /checkout/{sessionId}/session-details` with secret in header
- Renders merchant logo, accent color, amount, currency, description
- Locale switches based on session `locale` + Accept-Language fallback
- Error states: invalid session, expired session, network error — each with a distinct UI

### 7.3 Card form + tokenization

**Done when:**

- Form fields: card number, expiry (MM/YY auto-advance), CVV, cardholder name
- Brand detection (regex on first 4–6 digits) with brand icon in the input
- Luhn validation client-side as soft check
- `inputmode`, `autocomplete`, `aria-*` attributes correct
- On submit: POST card data directly to `tokens.yourgateway.com` (not via Next.js server) —
  fetch with `mode: 'cors'`, no credentials
- Loading state during tokenization
- Error handling: invalid card → inline error; network → retry button

### 7.4 Complete + redirect

**Done when:**

- After tokenization succeeds, POST `{ token }` to checkout-service `/complete`
- On 200 with status COMPLETED: redirect to `redirect_url`
- On 200 with status FAILED: show error message, allow retry (re-tokenize)
- Cancel button: POST `/cancel`, redirect to returned `redirect_url`
- E2E test with Playwright: full happy path, declined card path

### 7.5 Accessibility, polish

**Done when:**

- Lighthouse a11y score ≥ 95
- Keyboard navigation through the whole form
- Visible focus indicators
- Tested with VoiceOver/NVDA briefly
- Mobile layout works at 360px viewport

---

## Phase 8 — wiring it all together

### 8.1 End-to-end test

**Done when:**

- Playwright test starts all services via Testcontainers (or assumes they're running) and
  drives the full flow: create session via merchant API → load checkout page → enter card →
  complete → verify payment exists → verify webhook delivered (to a local listener like
  `webhook.site` or a Testcontainers HTTP echo)
- Runs in CI

### 8.2 Distributed tracing

**Done when:**

- OpenTelemetry SDK in `shared-web`
- Trace context propagated across HTTP calls between services
- Local: traces visible in a Jaeger container added to docker-compose
- The whole flow is one trace from `POST /v1/checkout-sessions` to the merchant webhook
  delivery

### 8.3 Metrics + dashboards

**Done when:**

- Micrometer + Prometheus endpoint on every service
- Key metrics: session-creation rate, session-completion rate, authorization latency,
  outbox-publish lag, webhook-delivery success rate, token-mint rate
- Grafana dashboard JSON committed to `infrastructure/observability/`

---

## After v1

When the above is done, the natural next pieces are:

- **3DS2 integration** (PSD2 compliance) — the biggest gap before real acquiring
- **Real acquirer integration** (probably Nets or Adyen given Danish geography)
- **Merchant dashboard** + self-service onboarding
- **Refunds API**
- **Reusable tokens / vault** (see ADR-0004)
- **Embedded fields / merchant-hosted checkout** (see ADR-0001)
- **Additional payment methods**: MobilePay first (Denmark), then Apple Pay / Google Pay
- **Reconciliation** with acquirer batch files
- **Disputes / chargebacks** workflow

Each of these will get its own ADR before work starts.
