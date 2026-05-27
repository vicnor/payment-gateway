# ADR-0001 — PCI scope and hosted checkout for v1

## Status

Accepted — 2026-05-25

## Context

PCI DSS scope is the single biggest architectural force on a card payment gateway. The choice
of how card data is collected on the consumer's browser determines which audit regime applies
to us and to our merchants.

Three viable options exist:

(a) **Hosted fields / iframe.** Card fields are iframes served from our domain, embedded into a
checkout page (ours or the merchant's). PAN/CVV never touches the merchant's JS or our checkout
wrapper. Merchants stay at SAQ-A or SAQ-A-EP.

(b) **Gateway-hosted checkout page.** The whole checkout is on `checkout.yourgateway.com`.
Merchants redirect their consumers to it. Merchants stay at SAQ-A.

(c) **Direct integration.** The merchant's frontend collects PAN/CVV and posts it to our token
endpoint. Merchants are full SAQ-D — annual QSA audit, segmented network, the works. Most
merchants do not want this.

3D Secure 2 (SCA under PSD2) is also relevant — for EU card transactions it's effectively
mandatory. Building it from scratch alongside the rest of the gateway is a significant scope
expansion.

## Decision

- v1 ships with **option (b) only** — gateway-hosted checkout page.
- 3DS2 is **out of scope for v1**. The payment state machine reserves a `REQUIRES_CHALLENGE`
  state but the path is not implemented.
- Option (a) is the v2 target — same backend, different frontend delivery (a JS SDK that mounts
  iframes into the merchant's page).
- Option (c) is rejected. We will not offer this.

## Consequences

**What this buys us:**
- Merchants integrate in hours, not weeks. Server-side: create session, redirect. Done.
- Our PCI scope is bounded to `token-service` only. The other five services handle tokens
  (opaque strings), never PAN.
- We can ship v1 without a QSA audit. The compliance lift moves into v1.5 when real acquirers
  come online.
- No SCA implementation cost for v1.

**What it costs us:**
- We can't go live for real card transactions in v1 — 3DS is required for EU acquiring. The
  end state of v1 is a working end-to-end gateway against `test-acquirer-service` only.
- Some merchants want full-page-control checkout. We can't sell to them in v1.
- The Next.js checkout page is a piece of consumer-visible product that needs design,
  localization, and accessibility work — not just an SDK. This is a real cost.

**What we must preserve to enable v2 (hosted fields):**
- `token-service` is its own service with its own deployment, its own subdomain
  (`tokens.yourgateway.com`), and accepts CORS-controlled direct browser requests. v2 just
  changes the caller (iframe inside merchant page → same endpoint).
- The Checkout API auth model (`session_id + session_secret`) is reusable for embedded fields.

**What we must preserve to enable 3DS later:**
- Payment state machine reserves `REQUIRES_CHALLENGE`.
- Session response can include a `next_action` object (`{ type: "challenge", url: "..." }`) so
  the UI knows what to do next. Field stays in the API contract even if always null in v1.
