# Payment Gateway

A card payment gateway. Merchants integrate via REST API; consumers pay on a hosted checkout
page; merchants receive webhooks with the outcome.

## What's here

This repo currently contains the **architectural design** and **roadmap** for v1. No code yet —
that's the next step.

- `CLAUDE.md` — project context loaded by Claude Code on every session
- `docs/architecture/` — the system design (start with `overview.md`)
- `docs/adr/` — decision records: the "why" behind the design
- `docs/development/` — local dev setup
- `docs/roadmap.md` — the build plan, in dependency order

## Getting started

If you're starting work on this with Claude Code:

```bash
cd payment-gateway
claude
```

Claude Code loads `CLAUDE.md` automatically. Tell it which task from `docs/roadmap.md` to start
with — e.g. _"Start with task 0.1 from the roadmap"_.

If you're reading the design first:

1. `docs/architecture/overview.md` — the system shape
2. `docs/architecture/flow.md` — the end-to-end payment flow
3. `docs/architecture/services.md` — per-service detail
4. `docs/architecture/data-model.md` — schemas
5. `docs/architecture/api.md` — API contracts
6. `docs/adr/` — decision rationale

## Scope (v1)

In scope: hosted checkout page, card payments only, single-use tokens, manual merchant
onboarding, mock acquirer, webhooks.

Out of scope (architecture supports but doesn't implement): 3DS/SCA, real acquirer integration,
card vault, additional payment methods, merchant dashboard, embedded fields.

See `CLAUDE.md` for full scope and ADR-0001 for the PCI scope reasoning.

## Tech stack

Java 25, Spring Boot 3.5+, Maven, PostgreSQL, DynamoDB, AWS (SNS/SQS/KMS), Next.js, TypeScript.
Hosted on AWS, eu-north-1.
