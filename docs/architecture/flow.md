# Payment flow

End-to-end happy path, edge cases, and the sync/async boundary. When implementing anything that
touches the cross-service flow, this is the canonical reference.

## Happy path

```
Merchant      Consumer      Checkout UI    Token svc    Checkout svc    Payment svc   Acquirer    Webhook svc
   │             │               │              │             │              │            │            │
   │── 1. POST /v1/checkout-sessions ──────────────────────── ▶│             │            │            │
   │◀── 2. 201 { id, url, ... } ───────────────────────────── ─│             │            │            │
   │             │               │              │             │              │            │            │
   │── 3. 302 redirect to url ─▶ │               │             │              │            │            │
   │             │── 4. GET /checkout/{id} ──── ▶              │              │            │            │
   │             │               │── 4a. GET session-details ─▶│              │            │            │
   │             │               │◀── session data ────────── ─│              │            │            │
   │             │◀── render form ──             │             │              │            │            │
   │             │── 5. POST /checkout/{id}/tokens ──────────▶ │              │            │            │
   │             │               │  (PAN/CVV/expiry direct from browser to token-service)              │
   │             │◀── { token: "tok_..." } ──   │             │              │            │            │
   │             │── 6. POST /checkout/{id}/complete { token } ▶              │            │            │
   │             │               │              │             │── 6a. POST /internal/v1/payments ──▶   │
   │             │               │              │             │              │── 6b. detokenize ─▶     │
   │             │               │              │             │              │◀── PAN ── ─            │
   │             │               │              │             │              │── 6c. authorize ──────▶ │
   │             │               │              │             │              │◀── approved ───── ─    │
   │             │               │              │             │              │── 6d. write outbox ─   │
   │             │               │              │             │◀── payment OK ─              │            │
   │             │◀── { status: "completed", redirect_url: ... } ─            │              │            │
   │             │               │              │             │              │            │            │
   │             │               │              │             │  (outbox poller publishes SNS)         │
   │             │               │              │             │              │  ──▶ SQS  ──▶            │
   │             │               │              │             │              │            │── 7. POST callback_url ─▶
   │◀── 7. webhook (HMAC-signed payment.captured) ────────────────────────── ─│            │            │
   │             │── 8. 302 to merchant return_url ────────── ─│              │            │            │
```

## The flow in eight steps

**1. Create session (merchant → us, S2S):** Merchant's backend calls `POST /v1/checkout-sessions`
with API key, amount, currency, reference, URLs, idempotency key. Response includes a redirect
URL that contains both `session_id` and a `?k=session_secret` parameter.

**2. Redirect (merchant → consumer):** Merchant 302s the consumer's browser to the redirect URL.

**3. Load checkout page (consumer → us):** Browser fetches the Next.js page at
`checkout.yourgateway.com/{session_id}?k=...`. The page calls
`GET /checkout/{id}/session-details` (with the `k=` secret as a header) and renders the form.

**4. Tokenize card (browser → token-service direct):** When the consumer submits, the browser
posts PAN/CVV/expiry/holder_name to `POST /checkout/{session_id}/tokens` **on the
token-service host** (`tokens.yourgateway.com`). The Next.js server is bypassed entirely —
this is what keeps it out of PCI scope. token-service returns `{ token: "tok_..." }`.

**5. Complete session (browser → checkout-service):** Browser posts `{ token }` to
`POST /checkout/{id}/complete`. This kicks off the synchronous authorization chain.

**6. Authorize (internal):** checkout-service calls payment-service. payment-service detokenizes,
calls the acquirer, writes the payment + an outbox row in one DB transaction, returns the
outcome up the chain. Total budget: ~1–2 seconds.

**7. Webhook (async):** A background poller in payment-service reads from `outbox`, publishes to
SNS, marks as published. webhook-service consumes from its SQS subscription, signs the body,
POSTs to the merchant's callback URL with retry-on-failure semantics.

**8. Redirect back (consumer → merchant):** checkout-service's response to step 6 includes a
`redirect_url` that points to the session's `return_url` (or `cancel_url` on cancel). The
browser navigates there.

## Critical invariants

**The webhook is authoritative, not the redirect.** Merchants must NOT mark an order as paid
based on the consumer arriving at the `return_url`. They must wait for the webhook or
poll `GET /v1/payments/{id}`. Reason: the consumer may close the browser between step 7 and
step 8 — that's not a failure.

**Step 5 carries no card data.** Card data goes browser → token-service in step 4 and never
flows through checkout-service or payment-service in plaintext form. Only the token does.

**Step 6 is synchronous, step 7 is asynchronous.** The consumer's experience is bounded by
acquirer latency (~1–2s). Slow merchant endpoints retry async without blocking anything else.

**Idempotency on step 1.** Merchants will retry `POST /v1/checkout-sessions` after any network
hiccup. The Idempotency-Key header makes that safe — same key returns the same session.

**At-least-once webhook delivery.** Merchants MUST be idempotent on webhook receipt. We
deduplicate by `event_id` (in the body); they should too.

## Edge cases

### Consumer abandons (closes browser)
- Session sits in `IN_PROGRESS` until `expires_at` (30 min default)
- TTL deletes the Dynamo record
- A `checkout.expired` event publishes (currently from a periodic sweep, until Dynamo TTL
  events are wired in — see roadmap)
- Merchant receives `checkout.expired` webhook

### Acquirer times out
- payment-service uses an aggressive client timeout (~10s for test-acquirer, would be longer
  for real acquirers)
- On timeout: payment status → `FAILED` with `failure_code: acquirer_timeout`
- Future hardening: payment reconciliation via acquirer batch reports

### Consumer presses cancel button
- Browser calls `POST /checkout/{id}/cancel`
- Session → `CANCELLED`
- `checkout.cancelled` event published
- No payment ever created
- Consumer redirected to `cancel_url`

### Token is reused (single-use violation)
- token-service marks `used: true` on detokenization
- Second detokenize attempt → `409 Conflict`, error `token_already_used`
- payment-service surfaces this as `failure_code: token_already_used`

### Idempotency key collision
- Same key, same body → return cached response with `Idempotent-Replay: true` header
- Same key, different body → `409 Conflict`, error `idempotency_key_conflict`
- No retry will succeed until the key expires (24h)

### Merchant callback returns non-2xx
- webhook-service retries: 30s, 2m, 10m, 1h, 6h, 24h
- After 6 failures: status `DEAD`, alert fires, manual intervention via admin endpoint
- The payment itself is unaffected — money has moved

### Merchant callback is slow (no response)
- 10s client timeout on the outbound delivery
- Counts as a failed attempt, retry per schedule above
- Slow merchants do not consume webhook-service threads (use non-blocking HTTP client)

## Sequence numbering vs concurrency

Within a single session, ordering is total: `CREATED → IN_PROGRESS → COMPLETED` cannot interleave.
Across sessions everything is concurrent. The state transitions are guarded by:

- DynamoDB conditional writes on `checkout_sessions` (`status = :expected_status`)
- JPA optimistic locking via `version` on `payments`
- Postgres unique constraints on `(payment_id, attempt_number)` and `external_id`

Never use pessimistic locking. Conflicts return `409 Conflict` and the caller retries.
