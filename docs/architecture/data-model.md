# Data model

Schemas for every persistent entity in the system. When adding a new field, update this doc
and the corresponding migration file in lockstep.

## Conventions

- **Money** — `BIGINT` in Postgres, `Number` (integer) in Dynamo. Always minor units. Currency
  separately as `CHAR(3)` ISO 4217.
- **Timestamps** — `TIMESTAMPTZ` in Postgres, epoch seconds (`Number`) in Dynamo.
- **External IDs** — `<prefix>_<ULID26>` — sortable, unique, opaque to consumers.
- **Internal IDs** — `UUID` v7 in Postgres (sortable). Dynamo uses the external id as PK directly.
- **JSONB** — used for flexible/method-specific data (`payment_method_details`, `metadata`,
  event payloads). Structural query fields are always columns.

---

## CheckoutSession (DynamoDB, checkout-service)

**Table:** `checkout_sessions`
**Primary key:** `session_id` (S)
**GSI:** `merchant-created-index` — HASH `merchant_id` (S), RANGE `created_at` (N)
**TTL attribute:** `expires_at` (N, epoch seconds)
**Billing:** `PAY_PER_REQUEST`

```json
{
  "session_id":              "cs_01HQX2YK3M4N5P6Q7R8S9T0V1W",
  "session_secret_hash":     "sha256:b3c9...",
  "merchant_id":             "mer_01HQX...",
  "merchant_reference":      "order-2026-00472",
  "amount":                  19900,
  "currency":                "DKK",
  "status":                  "CREATED",
  "payment_id":              null,
  "return_url":              "https://merchant.example/return?o=472",
  "cancel_url":              "https://merchant.example/cancel?o=472",
  "available_payment_methods": ["card"],
  "selected_payment_method": null,
  "customer": {
    "email":     "kunde@example.dk",
    "reference": "cust_9982"
  },
  "description":             "Order #472 — Acme Ltd.",
  "locale":                  "da-DK",
  "branding": {
    "logo_url":     "https://merchant.example/logo.png",
    "accent_color": "#0F6E56"
  },
  "metadata":                { "cart_id": "abc123" },
  "created_at":              1748160000,
  "updated_at":              1748160005,
  "completed_at":            null,
  "expires_at":              1748161800
}
```

**State machine:**

```
CREATED → IN_PROGRESS → COMPLETED
                      → CANCELLED
       → EXPIRED (TTL or explicit on read)
```

`IN_PROGRESS` is entered when the consumer first fetches `/checkout/{id}/session-details`.
`EXPIRED` is enforced on read if `now > expires_at` even before TTL deletes the record.

**Notes:**
- `session_secret_hash` is a SHA-256 of the secret carried in the URL as `?k=...`. The plain
  secret is never persisted — only its hash, compared on each browser request.
- `available_payment_methods` is `["card"]` always in v1, but the field stays in the model.

---

## Token (DynamoDB, token-service)

**Table:** `tokens`
**Primary key:** `token` (S) — e.g. `tok_01HQX...`
**TTL attribute:** `expires_at` (N) — set short for single-use (e.g. 30 min)
**Billing:** `PAY_PER_REQUEST`

```json
{
  "token":               "tok_01HQX2YK3M4N5P6Q7R8S9T0V1W",
  "session_id":          "cs_01HQX...",
  "merchant_id":         "mer_01HQX...",
  "encrypted_card_data": "<base64 ciphertext>",
  "data_key_id":         "dk_01HQX...",
  "card": {
    "brand":     "visa",
    "last4":     "4242",
    "exp_month": 12,
    "exp_year":  2027,
    "country":   "DK",
    "funding":   "debit"
  },
  "single_use":          true,
  "used":                false,
  "created_at":          1748160030,
  "expires_at":          1748161830
}
```

**Table:** `data_keys`
**Primary key:** `data_key_id` (S)

```json
{
  "data_key_id":      "dk_01HQX...",
  "encrypted_dek":    "<base64 KMS-encrypted DEK>",
  "kms_key_id":       "alias/token-service-prod",
  "created_at":       1748160030
}
```

**Encryption model — envelope encryption:**
1. On tokenize: generate a 32-byte DEK locally; encrypt the DEK with KMS (the CMK never leaves
   KMS); store the encrypted DEK in `data_keys`; AES-256-GCM encrypt card data with the plain
   DEK; discard plain DEK; store ciphertext in `tokens.encrypted_card_data`.
2. On detokenize: fetch `tokens` row, look up `data_keys.encrypted_dek`, ask KMS to decrypt the
   DEK, AES-GCM decrypt card data with the plain DEK, return.

This keeps KMS calls per-token (not per-byte) and lets us rotate the CMK without re-encrypting
all card data.

---

## Payment (PostgreSQL, payment-service)

```sql
CREATE TABLE payments (
    id                      UUID PRIMARY KEY,
    external_id             VARCHAR(40) UNIQUE NOT NULL,        -- pay_01HQX...
    checkout_session_id     VARCHAR(40) NOT NULL,               -- soft FK to dynamo
    merchant_id             VARCHAR(40) NOT NULL,
    merchant_reference      VARCHAR(255) NOT NULL,

    amount                  BIGINT NOT NULL,                    -- minor units
    amount_captured         BIGINT NOT NULL DEFAULT 0,
    amount_refunded         BIGINT NOT NULL DEFAULT 0,
    currency                CHAR(3) NOT NULL,

    status                  VARCHAR(32) NOT NULL,
    payment_method          VARCHAR(32) NOT NULL,               -- 'card'
    payment_method_details  JSONB NOT NULL,

    acquirer                VARCHAR(64),
    acquirer_reference      VARCHAR(255),
    auth_code               VARCHAR(32),

    failure_code            VARCHAR(64),
    failure_message         TEXT,

    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    authorized_at           TIMESTAMPTZ,
    captured_at             TIMESTAMPTZ,

    version                 INTEGER NOT NULL DEFAULT 0          -- JPA optimistic lock
);

CREATE INDEX idx_payments_merchant_created  ON payments (merchant_id, created_at DESC);
CREATE INDEX idx_payments_checkout_session  ON payments (checkout_session_id);
CREATE INDEX idx_payments_status            ON payments (status)
    WHERE status IN ('PENDING','AUTHORIZED');

CREATE TABLE payment_attempts (
    id                  UUID PRIMARY KEY,
    payment_id          UUID NOT NULL REFERENCES payments(id),
    attempt_number      INTEGER NOT NULL,
    acquirer            VARCHAR(64) NOT NULL,
    request_payload     JSONB NOT NULL,                         -- token + amount, no PAN
    response_payload    JSONB,
    acquirer_status     VARCHAR(64),
    duration_ms         INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (payment_id, attempt_number)
);

CREATE TABLE payment_events (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      UUID NOT NULL REFERENCES payments(id),
    event_type      VARCHAR(64) NOT NULL,
    from_status     VARCHAR(32),
    to_status       VARCHAR(32),
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_events_payment ON payment_events (payment_id, created_at);

-- Outbox table for reliable SNS publishing
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_id    VARCHAR(40) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
```

**Payment state machine (v1 path bold):**

```
       ┌────────────┐
       │  PENDING   │  ── (acquirer authorize) ──┐
       └────────────┘                            │
                                                 ▼
                                       ┌────────────────┐
                            ┌──────────│  AUTHORIZED    │   (later: separate auth/capture)
                            │          └────────────────┘
                            │                   │
                            │                   ▼
                            │          ┌────────────────┐
                            │          │  **CAPTURED**  │  ── (refund) ──> PARTIALLY_REFUNDED / REFUNDED
                            │          └────────────────┘
                            │
       ┌────────────┐       │
       │  **FAILED**│ <─────┤  decline / error
       └────────────┘       │
                            │
       ┌────────────┐       │
       │ CANCELLED  │ <─────┘  void before capture
       └────────────┘
```

v1 collapses `AUTHORIZED → CAPTURED` into a single "sale" — no separate capture step. The
`AUTHORIZED` state stays in the enum reserved for when split auth/capture is added.

---

## Merchant (PostgreSQL, merchant-service)

```sql
CREATE TABLE merchants (
    id                  VARCHAR(40) PRIMARY KEY,             -- mer_01HQX...
    name                VARCHAR(255) NOT NULL,
    callback_url        TEXT NOT NULL,
    return_url_pattern  TEXT NOT NULL,                        -- regex, validates session.return_url
    cancel_url_pattern  TEXT NOT NULL,
    branding            JSONB NOT NULL DEFAULT '{}'::jsonb,   -- logo_url, accent_color
    mode                VARCHAR(8) NOT NULL,                  -- 'test' | 'live'
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id                  UUID PRIMARY KEY,
    merchant_id         VARCHAR(40) NOT NULL REFERENCES merchants(id),
    key_prefix          VARCHAR(16) NOT NULL,                -- 'sk_test_01HQX' — first chars, indexed
    key_hash            VARCHAR(255) NOT NULL,               -- Argon2id of full key
    mode                VARCHAR(8) NOT NULL,                 -- 'test' | 'live'
    label               VARCHAR(255),                        -- 'Production server, primary'
    revoked_at          TIMESTAMPTZ,
    last_used_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_keys_prefix ON api_keys (key_prefix) WHERE revoked_at IS NULL;

CREATE TABLE webhook_secrets (
    id                  UUID PRIMARY KEY,
    merchant_id         VARCHAR(40) NOT NULL REFERENCES merchants(id),
    secret_hash         VARCHAR(255) NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at          TIMESTAMPTZ
);
```

**API key format:** `sk_<mode>_<26-char ULID>_<32-char random>`
- `mode` is `test` or `live`
- The first 16 chars (`sk_test_01HQX...`) form the indexed `key_prefix` for cheap lookup
- The full key is hashed with Argon2id (cost params in `application.yml`)
- Plain key returned exactly once at creation

---

## Webhook delivery (PostgreSQL, webhook-service)

```sql
CREATE TABLE webhook_deliveries (
    id                  UUID PRIMARY KEY,
    external_id         VARCHAR(40) UNIQUE NOT NULL,         -- whd_01HQX...
    merchant_id         VARCHAR(40) NOT NULL,
    event_id            VARCHAR(40) NOT NULL,                -- evt_01HQX...
    event_type          VARCHAR(64) NOT NULL,
    callback_url        TEXT NOT NULL,
    payload             JSONB NOT NULL,
    status              VARCHAR(16) NOT NULL,                -- PENDING | DELIVERED | FAILED | DEAD
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deliveries_ready ON webhook_deliveries (next_attempt_at)
    WHERE status = 'PENDING';

CREATE TABLE webhook_attempts (
    id                  UUID PRIMARY KEY,
    delivery_id         UUID NOT NULL REFERENCES webhook_deliveries(id),
    attempt_number      INTEGER NOT NULL,
    request_headers     JSONB NOT NULL,
    response_status     INTEGER,
    response_body       TEXT,                                -- truncated to 4KB
    duration_ms         INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## Idempotency keys (DynamoDB, per service)

Each service that exposes a POST in the Merchant API owns its own `idempotency_keys` table.
The filter that handles this lives in `shared-web`.

**Table:** `idempotency_keys`
**Primary key:** `idempotency_key` (S) — composite `merchant_id#user_key`
**TTL attribute:** `expires_at` (N) — 24 hours from creation

```json
{
  "idempotency_key":    "mer_01HQX#5d7f3c2a-e1b8-4c91-9f6e-0a8b1d3c5e7f",
  "request_hash":       "sha256:...",
  "response_status":    201,
  "response_body":      "{ ... }",
  "created_at":         1748160000,
  "expires_at":         1748246400
}
```

**Semantics:**
- Same key + same `request_hash` → return cached response with `Idempotent-Replay: true` header
- Same key + different `request_hash` → `409 Conflict`
- New key → process the request and cache the response on success (2xx) only
