# ADR-0003 — Six services, drawn now

## Status

Accepted — 2026-05-25

## Context

The general advice "start with a monolith, extract services later" is sound for most products.
We are diverging from it. Reasons:

1. **PCI scope.** `token-service` must be a separate service from day one. Putting it inside a
   monolith puts the whole monolith in PCI scope, which is the most expensive audit regime.
2. **Different runtime profiles.** webhook delivery is async-heavy with retries and backoff;
   checkout is synchronous and latency-sensitive. Bundling them puts unrelated load on the same
   process.
3. **Different scale curves.** Payment authorization scales with transaction volume; merchant
   API key validation scales with API call volume (and is hot-path on every request). They
   should be tuned independently.
4. **Stated preference** of the project owner: "I don't like the idea of refactoring later."
5. **The seams are clearer to draw now**, before any code exists. Extracting a service from a
   monolith later means untangling shared transactions and schemas — much harder than
   designing the boundary in advance.

The risk is over-engineering: more deployment overhead, more network calls, more places where
things can fail, more cognitive load.

## Decision

Ship v1 with **six** backend services:

| Service | Reason it's its own service |
|---|---|
| `checkout-service` | Owns the session aggregate — distinct lifecycle and data store |
| `token-service` | PCI scope. Must be isolated. |
| `payment-service` | Owns the payment aggregate. Transactional, relational data. |
| `webhook-service` | Async workload, different runtime profile from sync services. |
| `merchant-service` | Hot path read on every request — must be cacheable and tuneable independently. |
| `test-acquirer-service` | Will be replaced by real acquirer integrations later; the interface needs to be HTTP. |

We do **not** start with services for: refunds (lives in payment-service), reconciliation
(lives in payment-service v1.5), reporting (lives wherever needed at first; later own service),
disputes (later own service).

## Consequences

**What this buys us:**
- `token-service` is a small, tightly-controlled PCI scope.
- Each service has one clear responsibility and one clear data store.
- The service boundary is the audit boundary, the deployment boundary, and the team boundary
  (once we have a team).
- The seams for v2 work (embedded fields, additional payment methods, real acquirers) are
  already in place.

**What it costs us:**
- More deployment artifacts. Mitigation: one shared Helm chart template parameterized per service.
- More network calls in the happy path (checkout → payment → token; payment → acquirer). The
  whole synchronous chain must complete within ~1–2s. Mitigation: HTTP/2, keep-alive, generous
  but not crazy timeouts, retry budgets.
- More failure modes. Mitigation: distributed tracing from day one (`shared-web` includes
  OpenTelemetry); structured logging with correlation ids in every log line.
- Local development complexity. Mitigation: `make dev-up` should just work.

**Hard rule:**
**No service is allowed to access another service's database, directly or via a shared schema.**
Cross-service interaction is either an HTTP call (sync, RPC-style) or an event (async, via
SNS/SQS). This is non-negotiable — the moment two services share a database, you've collapsed
them back into a distributed monolith with all the downsides and none of the upsides.

**When to revisit:**
- A service has zero code in it after 6 months — merge it into the most-related neighbour.
- Two services always change together and own related aggregates — they may want to merge.
- A service grows two distinct responsibilities — split it before the second one becomes
  entangled with the first.
