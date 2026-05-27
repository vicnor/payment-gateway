# API contracts

The gateway exposes three API surfaces:

1. **Merchant API** — public REST, server-to-server, API-key authenticated
2. **Checkout API** — browser-facing, session-scoped
3. **Webhooks** — outbound, HMAC-signed

This doc covers all three.

---

## Merchant API

Base URL: `https://api.yourgateway.com`
All endpoints under `/v1/`.

### Authentication

```
Authorization: Bearer sk_live_01HQX2YK3M4N5P6Q7R8S9T0V1W_a8f2c91b...
```

API keys are issued by `merchant-service` (admin endpoint) and returned to the creator exactly
once. The hash (Argon2id) is stored. Compromised key? Revoke + reissue.

Key modes:
- `sk_test_...` — routes to `test-acquirer-service`, never charges real cards
- `sk_live_...` — routes to a real acquirer (none in v1)

### Idempotency

Every POST endpoint **requires** an `Idempotency-Key` header (UUID recommended). Stored for 24h.

| Same key, same body | Same key, different body | New key |
|---|---|---|
| Return cached response with `Idempotent-Replay: true` | `409 Conflict`, `idempotency_key_conflict` | Process normally |

Merchants should generate one key per logical operation (one per "create this session"), not
one per HTTP retry. All retries of the same logical operation use the same key.

### Common headers

| Header | Direction | Purpose |
|---|---|---|
| `Authorization` | request | Bearer API key |
| `Idempotency-Key` | request | Required on POST |
| `X-Request-Id` | response | Echo this in support tickets |
| `Idempotent-Replay` | response | `true` if served from idempotency cache |
| `RateLimit-*` | response | Standard rate-limit headers per IETF draft |

### Error shape

```json
{
  "error": {
    "type":       "validation_error",
    "code":       "invalid_currency",
    "message":    "currency must be a valid ISO 4217 code",
    "param":      "currency",
    "request_id": "req_01HQX..."
  }
}
```

| `type` values | HTTP status |
|---|---|
| `validation_error` | 400 |
| `authentication_error` | 401 |
| `permission_error` | 403 |
| `not_found` | 404 |
| `idempotency_key_conflict` | 409 |
| `rate_limit_error` | 429 |
| `api_error` (us) | 500 |
| `acquirer_unavailable` | 503 |

### Endpoints (v1)

#### `POST /v1/checkout-sessions`

Create a checkout session.

**Request:**
```json
{
  "amount":             19900,
  "currency":           "DKK",
  "merchant_reference": "order-2026-00472",
  "return_url":         "https://merchant.example/return?o=472",
  "cancel_url":         "https://merchant.example/cancel?o=472",
  "customer":           { "email": "kunde@example.dk" },
  "description":        "Order #472",
  "locale":             "da-DK",
  "metadata":           { "cart_id": "abc123" }
}
```

**Response 201:**
```json
{
  "id":                 "cs_01HQX2YK3M4N5P6Q7R8S9T0V1W",
  "object":             "checkout_session",
  "status":             "CREATED",
  "url":                "https://checkout.yourgateway.com/cs_01HQX.../?k=hf83lq...",
  "amount":             19900,
  "currency":           "DKK",
  "merchant_reference": "order-2026-00472",
  "expires_at":         1748161800,
  "created":            1748160000,
  "livemode":           true,
  "payment_id":         null
}
```

**Validation rules:**
- `amount` ≥ 100 (1.00 in minor units of any currency; configurable per merchant later)
- `currency` ∈ supported set (start: DKK, EUR, USD, SEK, NOK)
- `return_url` and `cancel_url` must match merchant's allowed URL patterns
- `merchant_reference` unique per merchant (returns `409` on duplicate)

#### `GET /v1/checkout-sessions/{id}`

Retrieve. Returns the same shape as the create response, with the current status.

#### `POST /v1/checkout-sessions/{id}/cancel`

Cancel a session that is `CREATED` or `IN_PROGRESS`. Returns the session with `status: CANCELLED`.

#### `GET /v1/payments/{id}`

Retrieve a payment.

**Response 200:**
```json
{
  "id":                "pay_01HQX...",
  "object":            "payment",
  "checkout_session_id": "cs_01HQX...",
  "amount":            19900,
  "amount_captured":   19900,
  "amount_refunded":   0,
  "currency":          "DKK",
  "status":            "CAPTURED",
  "payment_method":    "card",
  "payment_method_details": {
    "brand":     "visa",
    "last4":     "4242",
    "exp_month": 12,
    "exp_year":  2027,
    "country":   "DK"
  },
  "merchant_reference": "order-2026-00472",
  "failure_code":      null,
  "failure_message":   null,
  "created":           1748160030,
  "authorized_at":     1748160031,
  "captured_at":       1748160031,
  "metadata":          { "cart_id": "abc123" },
  "livemode":          true
}
```

#### `GET /v1/payments?limit=&starting_after=`

List payments. Cursor-paginated, newest first.

Query params:
- `limit` (default 25, max 100)
- `starting_after` — `pay_...` id of the last item from the previous page
- `created.gte` / `created.lte` — Unix epoch seconds

### Versioning

URL-path versioning. `/v1/` for the current version. Breaking changes → `/v2/`. Additive
changes (new optional fields, new endpoints) do not bump the version. v1 controllers stay
running indefinitely once v2 ships.

### Rate limiting

Per API key: 100 requests/second sustained, 200/second burst. Returns `429` with standard
`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers.

---

## Checkout API

Base URL: `https://checkout.yourgateway.com` (Next.js + checkout-service) and
`https://tokens.yourgateway.com` (token-service).

Browser-facing endpoints. Authenticated by session id + session secret, no API key.

### Authentication

Every request from the Next.js page (or any browser caller) sends:
```
X-Checkout-Session-Secret: hf83lqxk...
```

The session_id is in the URL path. Server validates `SHA256(secret) == session_secret_hash`
from the Dynamo record. Constant-time comparison.

### Endpoints

#### `GET /checkout/{session_id}/session-details`

What the Next.js page needs to render. Safe to expose: no card data, no merchant secrets.

```json
{
  "session_id":    "cs_01HQX...",
  "status":        "CREATED",
  "amount":        19900,
  "currency":      "DKK",
  "description":   "Order #472",
  "merchant": {
    "name":         "Acme Ltd.",
    "logo_url":     "https://merchant.example/logo.png",
    "accent_color": "#0F6E56"
  },
  "available_payment_methods": ["card"],
  "locale":        "da-DK",
  "expires_at":    1748161800
}
```

#### `POST /checkout/{session_id}/tokens` (on `tokens.yourgateway.com`)

**Request:**
```json
{
  "card_number":  "4242424242424242",
  "exp_month":    12,
  "exp_year":     2027,
  "cvv":          "123",
  "holder_name":  "Test Person"
}
```

**Response 201:**
```json
{
  "token":     "tok_01HQX...",
  "brand":     "visa",
  "last4":     "4242",
  "exp_month": 12,
  "exp_year":  2027
}
```

This response is the only thing that goes back to the browser besides the redirect — `last4`
and `brand` so the page can show "Pay with Visa ••4242" before confirmation.

#### `POST /checkout/{session_id}/complete`

```json
{ "token": "tok_01HQX..." }
```

**Response 200 (success):**
```json
{
  "status":       "COMPLETED",
  "payment_id":   "pay_01HQX...",
  "redirect_url": "https://merchant.example/return?o=472&checkout=cs_01HQX..."
}
```

**Response 200 (declined — note: still 200, the API call succeeded):**
```json
{
  "status":         "FAILED",
  "failure_code":   "card_declined",
  "failure_message":"Your card was declined.",
  "retry_allowed":  true,
  "redirect_url":   null
}
```

#### `POST /checkout/{session_id}/cancel`

Consumer pressed the cancel button. Marks session `CANCELLED`, returns:
```json
{ "redirect_url": "https://merchant.example/cancel?o=472" }
```

---

## Webhooks

We POST events to the merchant's configured `callback_url` whenever a notable thing happens.

### Headers

```
POST {merchant.callback_url} HTTP/1.1
Content-Type: application/json
User-Agent: YourGateway-Webhooks/1.0
X-Signature: t=1748160031,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
X-Event-Id: evt_01HQX...
X-Event-Type: payment.captured
```

`X-Signature`: `t=<unix ts>,v1=HMAC_SHA256(secret, "<unix ts>.<raw body>")`. Merchants verify by
recomputing. Reject if `now - t > 5 minutes` (replay protection).

### Event types (v1)

| Event | When |
|---|---|
| `checkout.created` | Session created (rarely needed; off by default per merchant) |
| `checkout.completed` | Session reached `COMPLETED` (payment captured) |
| `checkout.cancelled` | Consumer cancelled, or merchant cancelled via API |
| `checkout.expired` | Session reached `EXPIRED` without completion |
| `payment.authorized` | Payment reached `AUTHORIZED` (later, when split capture is added) |
| `payment.captured` | Payment reached `CAPTURED` |
| `payment.failed` | Payment reached `FAILED` |
| `payment.cancelled` | Payment voided before capture (later) |

### Event envelope

```json
{
  "id":      "evt_01HQX2YK3M4N5P6Q7R8S9T0V1W",
  "object":  "event",
  "type":    "payment.captured",
  "created": 1748160031,
  "data": {
    "object": {
      "id":                "pay_01HQX...",
      "object":            "payment",
      "status":            "CAPTURED",
      "amount":            19900,
      "currency":          "DKK",
      "merchant_reference":"order-2026-00472",
      ...
    }
  },
  "livemode": true
}
```

The `data.object` mirrors what `GET /v1/payments/{id}` returns.

### Delivery semantics

- At-least-once. Merchants must dedupe by `event.id`.
- Order is **not** guaranteed across event types. Within a single payment, the order is
  best-effort (`payment.authorized` before `payment.captured`) but consumers should rely on the
  current `status` in `data.object`, not infer from event order.
- Retry: 30s, 2m, 10m, 1h, 6h, 24h, then `DEAD`.
- A `2xx` response with any body means delivered. `3xx` does not follow redirects. `4xx`/`5xx`
  retry per schedule. Connection error or timeout (10s) → retry.

### Merchant-side verification (example, in their stack)

```python
def verify_signature(body: bytes, header: str, secret: str) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    timestamp = int(parts["t"])
    signature = parts["v1"]
    if abs(time.time() - timestamp) > 300:
        return False
    expected = hmac.new(
        secret.encode(),
        f"{timestamp}.{body.decode()}".encode(),
        hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature)
```

Publishing example verification code in multiple languages is a v1 task (see roadmap).
