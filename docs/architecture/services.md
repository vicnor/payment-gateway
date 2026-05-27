# Services

Per-service detail. Each section lists purpose, key endpoints, data store, dependencies, and
events published/consumed.

---

## checkout-service

**Purpose:** Owns the `CheckoutSession` lifecycle. Creates sessions for merchants, serves session
data to the hosted checkout page, drives session completion.

**Data store:** DynamoDB. Single table `checkout_sessions` + GSI on merchant_id. TTL on
`expires_at`. See `data-model.md` for schema.

**Endpoints:**

*Merchant API (S2S):*
- `POST   /v1/checkout-sessions` — create
- `GET    /v1/checkout-sessions/{id}` — retrieve
- `POST   /v1/checkout-sessions/{id}/cancel` — cancel before completion

*Checkout API (browser):*
- `GET    /checkout/{id}/session-details` — what the page renders (amount, currency, branding)
- `POST   /checkout/{id}/complete` — finalize with token
- `POST   /checkout/{id}/cancel` — consumer pressed cancel

**Calls:**
- merchant-service (validate API key, fetch branding)
- payment-service (POST /v1/payments to authorize)

**Publishes:** `checkout.created`, `checkout.completed`, `checkout.cancelled`, `checkout.expired`

**Consumes:** nothing

---

## token-service ⚠ PCI scope

**Purpose:** Card tokenization. Accepts PAN/CVV/expiry, returns opaque token. Stores envelope-
encrypted card data. Detokenizes for payment-service (server-to-server only).

**Data store:** DynamoDB. Two tables: `tokens` (the encrypted vault) and `data_keys` (per-token
DEKs encrypted with the KMS CMK). TTL on `tokens` for single-use.

**Endpoints:**

*Checkout API (browser, no auth other than session):*
- `POST   /checkout/{session_id}/tokens` — tokenize (called from the Next.js page)

*Internal (service mesh, mTLS or VPC-private):*
- `POST   /internal/v1/tokens/{token}/detokenize` — return PAN+expiry to payment-service

**Calls:** KMS (for envelope encryption)

**Publishes:** nothing (token creation is not a domain event of interest outside the service)

**Consumes:** nothing

**Hard rules:**
- No code in this service may log card data, even at TRACE.
- No code outside `services/token-service` may import card types from this service.
- Tests assert that the only response field is the token string + last4/brand/expMonth/expYear.

---

## payment-service

**Purpose:** Orchestrates payment authorization. Owns the `Payment` aggregate and the payment
state machine. Routes to the acquirer (only `test-acquirer-service` in v1).

**Data store:** PostgreSQL. Tables: `payments`, `payment_attempts`, `payment_events`. See
`data-model.md` for schema.

**Endpoints:**

*Internal:*
- `POST   /internal/v1/payments` — authorize a payment given a token (called by checkout-service)
- `GET    /internal/v1/payments/{id}` — fetch by external id

*Merchant API:*
- `GET    /v1/payments/{id}` — retrieve
- `GET    /v1/payments?limit=&starting_after=` — list, cursor-paginated

**Calls:**
- token-service (detokenize)
- test-acquirer-service (authorize)
- merchant-service (validate API key on Merchant API calls)

**Publishes:** `payment.authorized`, `payment.captured`, `payment.failed`, `payment.cancelled`

**Consumes:** nothing in v1 (later: acquirer reconciliation events)

**Notes:**
- Uses outbox pattern: write payment + outbox row in one transaction, separate poller publishes
  to SNS. This guarantees we never lose a payment event due to a crash after DB commit but
  before SNS publish.

---

## webhook-service

**Purpose:** Reliably deliver outbound webhooks to merchant callback URLs. Owns retry policy,
HMAC signing, delivery log.

**Data store:** PostgreSQL. Tables: `webhook_deliveries`, `webhook_attempts`.

**Endpoints:** None public. Workers only.

**Calls:**
- merchant-service (fetch webhook signing secret + callback URL)
- Merchant HTTPS endpoint (the outbound delivery)

**Publishes:** nothing

**Consumes:** SQS queue `webhook-dispatch` (subscribed to `payment-events` and `checkout-events`
SNS topics)

**Retry policy:** exponential backoff: 30s, 2m, 10m, 1h, 6h, 24h. After 6 attempts → dead-letter
+ alert.

**Signing:** `X-Signature: t=<unix>,v1=<hex>` where the value is
`HMAC_SHA256(secret, "<unix>.<body>")`. Plus `X-Idempotency-Key` so merchants can dedupe.

---

## merchant-service

**Purpose:** Source of truth for merchants. Stores accounts, API keys (hashed), webhook signing
secrets, callback URLs, branding config. Read on every API request — cached aggressively.

**Data store:** PostgreSQL. Tables: `merchants`, `api_keys`, `webhook_secrets`. Secrets stored
hashed (Argon2id), original returned to creator exactly once.

**Endpoints:**

*Admin (manual onboarding only, no public exposure yet):*
- `POST   /admin/merchants` — create
- `POST   /admin/merchants/{id}/api-keys` — issue an API key
- `POST   /admin/merchants/{id}/webhook-secret` — rotate signing secret

*Internal:*
- `GET    /internal/v1/api-keys/{prefix}` — validate API key. Returns merchant_id + mode if valid.
- `GET    /internal/v1/merchants/{id}` — fetch merchant config (branding, callback URL, etc.)

**Calls:** nothing

**Publishes:** nothing in v1

**Consumes:** nothing

**Hard rules:**
- API key plain values are returned exactly once at issuance. Storage is Argon2id hash only.
- Plain values are never logged. The first 8 chars (the key prefix) are logged for traceability.

---

## test-acquirer-service

**Purpose:** Deterministic mock acquirer for local dev, integration tests, and CI. Outcome is
encoded in the test card number.

**Data store:** In-memory. Optionally a small log of received requests for debugging.

**Endpoints:**

*Internal:*
- `POST   /internal/v1/authorize` — authorize a payment. Inspects the PAN, returns deterministic
  outcome.

**Test card map (Stripe-compatible where possible):**

| PAN | Outcome | Code |
|---|---|---|
| `4242 4242 4242 4242` | Approved | (success) |
| `4000 0000 0000 0002` | Declined | `card_declined` |
| `4000 0000 0000 9995` | Insufficient funds | `insufficient_funds` |
| `4000 0000 0000 0341` | Times out (sleep 30s then 504) | `acquirer_timeout` |
| `4000 0000 0000 0119` | Random processing error | `processing_error` |
| `5555 5555 5555 4444` | Approved (Mastercard) | (success) |

This catalogue is the contract that integration tests rely on — extending it requires updating
both the service and `docs/architecture/services.md`.

**Calls:** nothing

**Publishes/Consumes:** nothing — this is a leaf service
