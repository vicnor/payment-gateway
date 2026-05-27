# ADR-0004 — Single-use tokens with vault extensibility

## Status

Accepted — 2026-05-25

## Context

Card tokenization is the core abstraction that keeps card data isolated to one service. Two
shapes are common:

- **Single-use tokens.** Token can be used exactly once, expires after a short window (~30 min).
  Suitable for one-shot purchases.
- **Reusable tokens (vault).** Token is stored permanently, associated with a customer, can be
  used many times. Required for subscriptions, one-click reorder, merchant-initiated
  transactions (MITs).

Most gateways start with single-use and add vault later. Vault tokens have additional security
and compliance requirements: who can use them, when, for what amount, with what cardholder
consent — all of which become part of the data model and the auth model.

## Decision

- v1 ships **single-use tokens only.**
- The data model in `token-service` is shaped so vault tokens can be added without migration —
  the same record structure supports both, distinguished by a `single_use: bool` flag and the
  presence/absence of an `expires_at` TTL.
- The API namespace separates browser-created (single-use) tokens from server-created (vault)
  tokens. They will live at different endpoints when both exist.

## Consequences

**What this buys us:**
- Simpler v1. The token API has one entry point (`POST /checkout/{session_id}/tokens` from the
  browser) and one consumer (`POST /internal/v1/tokens/{token}/detokenize` from payment-service).
- TTL on DynamoDB handles cleanup. No background job needed.
- PCI scope is bounded — short-lived encrypted data, automatic deletion.

**What it costs us:**
- Can't sell to merchants who need subscriptions, recurring billing, or saved-card UX in v1.
  Acceptable for a v1 that doesn't even charge real cards yet.

**What we preserve for vault:**

When vault tokens are added, the model will become:

- New endpoint: `POST /v1/payment-methods` (server-to-server, Merchant API). Merchants create
  payment methods for a customer using either a fresh single-use token (typical: "save this
  card for next time") or by typing card data via a secure mechanism (later).
- New entity: `PaymentMethod` in payment-service, owning `{ id, customer_id, token, brand,
  last4, exp_month, exp_year, status }`. The `token` field references a `token-service` record
  with `single_use: false`.
- New scope on API keys: `payment_methods:read`, `payment_methods:write` — vault access is
  permission-gated.
- New tracking: per-merchant, per-customer card-on-file consent, the cardholder identity that
  authorized the storage, and the consent timestamp (PSD2 / SCA requirement for MITs).
- The `Customer` entity emerges. v1 has only `customer.email` and `customer.reference` as
  loose fields on the session; v2 introduces a real Customer aggregate.

**Hard rule:**
The token-service interface is the boundary between "card data" and "the rest of the system".
Any code that wants the card brand+last4 gets it as part of the token record. Any code that
wants the PAN must call `detokenize` and is in PCI scope by virtue of doing so. The set of
services that call `detokenize` is enumerated explicitly in `token-service`'s allowlist — only
`payment-service` is on that list today.

**When to revisit:**
- A merchant requirement for subscriptions/recurring becomes a real revenue driver
- We start integrating with a real acquirer that requires MIT flagging on stored-card use
