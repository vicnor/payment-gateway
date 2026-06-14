# token-service — PCI Scope

This service is the **only** service in the gateway that ever handles raw card data (PAN, CVV,
expiry). All other services handle opaque tokens only.

## Hard rules (non-negotiable)

- **Never log card data** — not at TRACE, not in exceptions, not in request logs. Last4 and
  brand are OK. CVV must be discarded immediately after validation and never persisted.
- **No cross-service import** — no code outside `services/token-service` may import types from
  this service. Cross-service communication is over HTTP only.
- **Response field restriction** — the tokenize response exposes only `token`, `brand`, `last4`,
  `exp_month`, `exp_year`. No other card fields escape this service.

## Envelope encryption model

On **tokenize**:
1. Generate a 32-byte DEK locally (cryptographically secure random).
2. Call KMS `encrypt` with the CMK (`alias/token-service-dev` locally,
   `alias/token-service-prod` in production) — the CMK never leaves KMS.
3. Store the encrypted DEK in the `data_keys` DynamoDB table (HASH key: `data_key_id`).
4. AES-256-GCM encrypt the raw card data with the plain DEK.
5. Discard the plain DEK from memory immediately.
6. Store ciphertext in `tokens.encrypted_card_data`.

On **detokenize**:
1. Fetch the `tokens` row and look up `data_keys` by `data_key_id`.
2. Call KMS `decrypt` to recover the plain DEK.
3. AES-256-GCM decrypt the card data.
4. Return PAN + expiry to payment-service.

## DynamoDB tables

| Table       | Partition key   | TTL attr    | Notes                          |
|-------------|-----------------|-------------|--------------------------------|
| `tokens`    | `token` (S)     | `expires_at`| 30-min TTL; single-use flag     |
| `data_keys` | `data_key_id`(S)| —           | Encrypted DEKs; no TTL by default |

## Key rules per endpoint

**`POST /checkout/{session_id}/tokens`** (browser-facing):
- Validate: Luhn check, future expiry, CVV length matches brand.
- Caller: Next.js checkout page (CORS allowed from `http://localhost:3000` and prod checkout domain).
- Rate-limited per `session_id` (100 attempts max — anti-BIN-scraping).

**`POST /internal/v1/tokens/{token}/detokenize`** (service-mesh only):
- Caller allowlist in config — only `payment-service` in v1.
- Marks `used: true` via DynamoDB conditional write; returns 409 if already used.
- Returns 404 if token expired or not found.
- Each call gets an audit log entry (token id + caller + timestamp) to a dedicated appender.

## Deferred (do NOT build)

- 3DS / PSD2 SCA (`next_action` field reserved in contract, always null in v1) — ADR-0001.
- CORS/rate-limit/CSP hardening lives in task 2.4, not the scaffold.
- Detokenize business logic lives in task 2.3, not the scaffold.

## Testing constraint

Every test that exercises any tokenize/detokenize path must assert:
- The response body does **not** contain the PAN or CVV.
- No log line at any level contains the PAN or CVV (use a custom appender in tests that fails
  on PAN patterns matching `\b\d{13,19}\b`).
