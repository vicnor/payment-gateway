# Architecture Decision Records

These document significant architectural decisions: the context, the choice, and what it costs us.

When you find yourself wanting to revisit one of these decisions, first read the ADR. It might
still be the right call. If it's no longer the right call, write a new ADR that supersedes it
(don't edit the old one — strike it through and link forward).

## Format

Each ADR is short — 1–2 pages. Sections:

- **Status** — Proposed / Accepted / Superseded by ADR-NNN
- **Context** — What's the situation, what are the forces in play
- **Decision** — What we chose
- **Consequences** — What this costs us and gains us, including what it makes hard later

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-pci-scope-and-hosted-checkout.md) | PCI scope and hosted checkout for v1 | Accepted |
| [0002](0002-maven-multimodule-monorepo.md) | Maven multi-module monorepo | Accepted |
| [0003](0003-service-boundaries.md) | Six services, drawn now | Accepted |
| [0004](0004-tokenization-strategy.md) | Single-use tokens with vault extensibility | Accepted |
| [0005](0005-three-api-surfaces.md) | Three API surfaces, separately versioned | Accepted |
| [0006](0006-workflow-orchestration-deferred.md) | Workflow orchestration deferred to 3DS | Accepted |

## Adding a new ADR

Copy the most recent one. Number it sequentially. Link it in the index. Discuss before merging
if it changes anything in the index above.
