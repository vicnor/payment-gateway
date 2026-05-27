# Architecture overview

## System shape

The gateway is six backend services plus a Next.js checkout frontend, fronted by AWS API Gateway.
Services are independently deployable and own their own data store. Cross-service code reuse
goes through `shared-*` Maven modules; cross-service communication goes over HTTP (sync) or
SNS → SQS (async). No service directly accesses another service's database.

```
                  ┌──────────────────┐         ┌──────────────────┐
                  │ Merchant backend │         │ Consumer browser │
                  │  (server-to-S2S) │         │   (Next.js page) │
                  └────────┬─────────┘         └────────┬─────────┘
                           │                            │
                           │  API key auth              │  session_id + secret
                  ┌────────▼────────────────────────────▼─────────┐
                  │            AWS API Gateway (TLS)              │
                  └─┬───────────┬──────────────┬──────────────────┘
                    │           │              │
              ┌─────▼─────┐ ┌───▼─────┐ ┌──────▼─────┐
              │  Checkout │ │  Token  │ │  Payment   │
              │  service  │ │ service │ │  service   │
              │ (Dynamo)  │ │(PCI/KMS)│ │ (Postgres) │
              └─────┬─────┘ └─────────┘ └─────┬──────┘
                    │                         │
                    └──────────┬──────────────┘
                               │  publish events
                       ┌───────▼────────┐
                       │  SNS + SQS     │
                       └───────┬────────┘
                               │
                  ┌────────────┴────────────┐
            ┌─────▼──────┐          ┌───────▼──────┐
            │  Webhook   │          │  Test        │
            │  service   │          │  acquirer    │
            │ (Postgres) │          │  service     │
            └──────┬─────┘          └──────────────┘
                   │
                   ▼
           Merchant callback URL
```

`merchant-service` is consulted by every other service for API key validation and merchant
config — it sits behind a Caffeine cache and is not in the request path diagram for clarity.

## Service responsibilities

See `services.md` for per-service detail. Briefly:

- **checkout-service** — owns the `CheckoutSession` lifecycle. Sessions are created by merchants
  (server-to-server), driven by the consumer's browser, and end in `COMPLETED`, `CANCELLED`, or
  `EXPIRED`. The hosted checkout page reads from and writes to this service.

- **token-service** — owns card tokenization. The browser POSTs raw PAN/CVV/expiry; the service
  returns an opaque token, having encrypted the card data with envelope encryption (KMS data key).
  This is **the only service with PCI-scoped data**. Tightly access-controlled, separate VPC subnet,
  separate IAM role, separate deployment pipeline.

- **payment-service** — orchestrates money movement. On `complete(token)`, calls token-service
  (server-to-server) to detokenize, calls the acquirer, persists the payment, publishes events.
  Owns the `Payment` aggregate.

- **webhook-service** — consumes `payment.*` and `checkout.*` events from SQS and reliably
  delivers them to merchant callback URLs. Owns retries (exponential backoff), HMAC request
  signing, delivery log.

- **merchant-service** — owns `Merchant`, `ApiKey`, `WebhookSecret`. Every other service calls it
  to validate API keys; cached aggressively.

- **test-acquirer-service** — deterministic mock acquirer. Outcome is determined by the test card
  number (Stripe-style — `4242 4242 4242 4242` always approves, `4000 0000 0000 0002` always
  declines, etc.). When real acquirer integrations come in, this stays for tests.

## The three API surfaces

| Surface | Consumer | Auth | Description |
|---|---|---|---|
| **Merchant API** | Merchant backend (S2S) | `Authorization: Bearer sk_live_…` | Public REST API. Versioned (`/v1/`). |
| **Checkout API** | Consumer browser | `session_id` + `session_secret` | Session-scoped, short-lived. |
| **Webhooks** | Outbound to merchant | HMAC-SHA256 body signature | Async event delivery. |

See `api.md` for endpoint detail.

## Synchronous vs asynchronous boundaries

| Path | Mode | Why |
|---|---|---|
| Browser → token-service (`POST /tokens`) | sync | Browser needs the token immediately |
| Browser → checkout-service (`POST /complete`) | sync | Consumer needs immediate success/fail |
| checkout-service → payment-service | sync | Drives the consumer-visible outcome |
| payment-service → token-service (detokenize) | sync | Inline in the payment authorization |
| payment-service → acquirer | sync | Inline; acquirer SLA bounds the consumer wait |
| payment-service → SNS (publish event) | sync | Local outbox pattern, durable |
| webhook-service ← SQS (consume) | async | Merchant endpoint slowness must not block consumer |
| webhook-service → merchant callback | async, retried | At-least-once, retried up to 6 times over ~24h |

The consumer-visible flow is fully synchronous and bounded at ~1–2 seconds. All retries and
slow-merchant tolerance live in the async webhook path.

## Trust boundaries

Three concentric zones:

1. **Public** — Browser, merchant backend. Authenticated via session secret or API key.
2. **Service mesh** — All services. Talk via internal HTTP (mTLS later, or VPC-internal). Validate
   merchant API key at the edge once, propagate `merchant_id` via header.
3. **PCI** — `token-service` only. Stricter access controls, restricted IAM, separate logging
   pipeline (no raw card data in shared logs), keys in KMS with annual rotation.

A service in zone 2 calling token-service in zone 3 still needs an explicit allowlist —
checkout-service, payment-service, and the Next.js checkout page (which makes browser-direct
calls) are the only allowed callers.

## Eventing

One SNS topic per domain: `payment-events`, `checkout-events`. SQS queues subscribe per consumer.
Each SQS queue has a DLQ with `maxReceiveCount=5`.

Event envelope (matches webhook envelope — same wire format):
```json
{
  "id":      "evt_01HQX...",
  "type":    "payment.captured",
  "created": 1748160000,
  "data":    { ... }
}
```

Event versioning: never mutate an existing event shape. Add new fields as optional, or publish a
new event type with a version suffix (`payment.captured.v2`) if the change is breaking.

## Where things are decided

| Decision | Where |
|---|---|
| Session can be created? | merchant-service (API key valid?), then checkout-service |
| Payment state machine | payment-service exclusively |
| Whether to retry a webhook | webhook-service (delivery log + backoff policy) |
| Acquirer routing | payment-service (only test-acquirer for now) |
| Token TTL | token-service (Dynamo TTL attribute) |
| Idempotency cache | `shared-web` filter, per-service Dynamo table |

If you're not sure which service owns a decision, find it in this table first.
