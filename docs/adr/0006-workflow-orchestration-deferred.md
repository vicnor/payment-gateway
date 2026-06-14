# ADR-0006 — Workflow orchestration deferred to 3DS

## Status

Accepted — 2026-06-14

## Context

The question came up whether a workflow-orchestration engine (Netflix/Orkes Conductor, Temporal,
AWS Step Functions) should run the payment flow.

The v1 architecture deliberately uses **choreography, not orchestration**:

- Cross-service events flow via the **transactional outbox → SNS → SQS** pattern
  (payment-service, checkout-service).
- Synchronous steps are **HTTP calls inside the trust boundary**.
- State machines are enforced *in-service* (`PENDING → CAPTURED | FAILED`), and webhook retries
  are a Postgres `FOR UPDATE SKIP LOCKED` + backoff worker.

The v1 payment flow is **short and synchronous**: complete → detokenize → authorize → write
payment+outbox happens within essentially one request. A workflow engine's defining capability is
**durable async wait-states** (suspend for minutes/hours/days, resume on a callback). v1 has none
of those — precisely because 3DS/SCA is deferred (see ADR-0001).

Two further forces argue against adopting one now:

1. **PCI scope.** A central orchestrator that brokers steps in the payment path can drift into —
   or adjacent to — PCI scope, which undermines the isolation of `token-service` (ADR-0003,
   ADR-0004). It would have to be disciplined to pass only tokens and references, never PAN/CVV.
2. **Operational cost.** Self-hosting Conductor adds a stateful server plus its own datastore
   (Cassandra/Postgres/Redis) to run, secure, and monitor in eu-north-1 — significant surface for
   a flow that is currently linear and fast.

## Decision

**Do not adopt a workflow orchestrator for v1.** Keep the choreography + outbox design.

Revisit the decision when the first genuine async wait-state enters the system. The concrete
trigger is **3DS2 / SCA** (redirect to issuer ACS → suspend → resume on async callback → continue
auth), which is exactly the workload orchestration engines exist for.

When that point is reached, evaluate in this order of fit for an AWS-native stack:

1. **AWS Step Functions** — native, managed, no stateful infra to operate; integrates with the
   SNS/SQS/Lambda already in use. Most likely the right call.
2. **Temporal** — best ergonomics for code-first sagas; heavier to operate.
3. **Conductor** — capable engine, but the most operational burden for the least AWS-native
   benefit here.

## Consequences

**What this buys us:**
- v1 stays simple: no central orchestrator, no extra stateful infra, no new PCI-scope risk.
- The loose coupling of event-driven choreography is preserved.

**What it costs us / what to watch:**
- The deferred and post-v1 workloads that *are* a natural fit for orchestration — **3DS2/SCA,
  refunds, disputes/chargebacks, reconciliation, and self-service onboarding/KYC** (all
  long-running, async-wait, compensation- or human-task-heavy) — are currently modelled (where
  they exist at all) as in-service logic. Each is a candidate to migrate onto an orchestrator
  when built.
- The migration cost is borne later, not now. That is an accepted trade: the saga seams are small
  in v1, and committing to an engine before the wait-state exists would be premature.

**When to revisit:** when 3DS2/SCA work starts (see Roadmap → After v1). At that point this ADR
should be superseded by one that records the chosen engine and the orchestration boundary.
