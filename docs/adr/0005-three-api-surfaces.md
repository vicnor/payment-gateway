# ADR-0005 — Three API surfaces, separately versioned

## Status

Accepted — 2026-05-25

## Context

A payment gateway has at least three consumer groups with different needs:

1. **Merchant developers** writing server-side integration code
2. **Consumers' browsers** loading the checkout page
3. **Merchant servers** receiving asynchronous webhook callbacks

Treating these as one API leads to either over-exposed internals (the merchant API leaks
browser-only details) or coupling that prevents independent evolution (a browser-side
breaking change forcing a merchant API version bump).

## Decision

Maintain **three distinct API surfaces**:

| Surface | Hostname | Auth | Versioning |
|---|---|---|---|
| Merchant API | `api.yourgateway.com` | API key (Bearer) | URL path: `/v1/`, `/v2/`, ... |
| Checkout API | `checkout.yourgateway.com` (HTML) + `tokens.yourgateway.com` (tokens) | session_id + session_secret | No version in URL — versioned with the hosted checkout page itself |
| Webhooks | Outbound to merchant | HMAC-SHA256 body | `X-Event-Type` carries version suffix when breaking changes are needed |

Each surface has its own change policy.

## Consequences

**Merchant API:**
- Stable. Breaking changes go in a new path version (`/v2/`). Both versions run concurrently
  until the old version is decommissioned (multi-quarter timeline, with deprecation warnings).
- Additive changes (new optional fields, new endpoints) don't bump the version.
- OpenAPI spec lives in `shared-api/`, versioned alongside the code.
- SDK generation happens from the OpenAPI spec; SDKs are versioned matching the API version.

**Checkout API:**
- Lives behind a hostname we fully control. Browsers always get the current version. No
  legacy clients to worry about.
- The Next.js page and the Checkout API are deployed together — they can change in lockstep.
- This is the "private" API even though it's reachable from the internet — the contract is
  between our frontend and our backend, not between us and a third party.

**Webhooks:**
- Versioned per-event-type. If `payment.captured` v1 needs a breaking change, ship
  `payment.captured.v2` alongside it. Merchants opt into v2 in their settings; v1 keeps firing
  until they migrate.
- This is by far the hardest surface to evolve, because we don't control the consumer code at
  all and we can't deprecate aggressively. Additive changes are heavily preferred.

**What this buys us:**
- Merchant API stability is independent of internal velocity. We can rewrite the Checkout API
  every quarter if we want; merchants don't notice.
- The smallest possible "lock-in" surface — only the Merchant API needs to be stable on
  multi-year horizons.
- Different auth models per surface fit different threat models. API keys for trusted servers;
  ephemeral session secrets for browsers (which can't keep secrets); HMAC for outbound traffic
  where the merchant is the verifier.

**What it costs us:**
- Three sets of docs to maintain.
- Three sets of integration tests.
- Some duplication of types — a `Payment` shape exists in the Merchant API, the Checkout API
  (within session-details), and in webhook payloads. We use OpenAPI shared schemas in
  `shared-api` to keep these aligned, but they're not literally one type because each surface
  may expose different fields.

**Hard rule:**
**The Checkout API is never called by merchants directly.** If a merchant looks like they're
calling `tokens.yourgateway.com`, something has gone wrong (or they're attempting to scrape
hosted checkout). The API gateway rejects requests to `tokens.yourgateway.com` with a
`User-Agent` typical of server-side HTTP clients.
