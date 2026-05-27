# ADR-0002 — Maven multi-module monorepo

## Status

Accepted — 2026-05-25

## Context

We need to ship multiple microservices. The Java options are:

(a) **Polyrepo** — one Git repo per service. Independent CI, independent releases, independent
versioning.

(b) **Monorepo with one Maven project per service** — single Git repo, but each service has its
own standalone `pom.xml` not aware of siblings. CI per service folder.

(c) **Multi-module Maven monorepo** — single Git repo, single parent `pom.xml`, services and
shared libraries as modules. One reactor build.

We're starting with one developer scaling to a small team. Services share Java version, Spring
Boot version, AWS SDK version, code style, security policies, and several utility libraries.

## Decision

Adopt **option (c)** — multi-module Maven monorepo. Single parent POM. Services and shared
libraries are all modules of the same reactor.

```
payment-gateway/
├── pom.xml              # parent
├── shared/              # aggregator pom
│   ├── shared-events/
│   ├── shared-api/
│   ├── shared-security/
│   ├── shared-web/
│   └── shared-testing/
└── services/            # aggregator pom
    ├── checkout-service/
    ├── token-service/
    ├── payment-service/
    ├── webhook-service/
    ├── merchant-service/
    └── test-acquirer-service/
```

The Next.js frontend is in the repo but **not** in the Maven reactor — it's built with `pnpm`.

## Consequences

**What this buys us:**
- Single source of truth for Java version, Spring Boot version, plugin configuration.
- `dependencyManagement` in parent → no version skew between services.
- `./mvnw verify` at the root builds everything, runs all tests, in dependency order.
- Cross-cutting refactors (e.g. updating a shared event POJO + every service that uses it)
  happen in one commit.
- Atomic PR reviews — reviewer sees the change everywhere it lands.
- Sharing code via `shared-*` modules is trivial — just declare a Maven dependency.

**What it costs us:**
- Every CI run rebuilds everything by default. Mitigation: turbo-build with Maven's
  `-pl :module -amd` and dependency-graph-aware CI (only rebuild affected modules).
- Releases are tied — bumping Spring Boot affects every service at once. This is arguably also
  a benefit (no service stuck on an old version), but it means we can't decouple service
  release cadences without effort.
- Repo gets bigger over time. Mitigation: stay disciplined about what goes in `shared-*`.

**Hard rule for this layout:**
**Services depend on `shared-*`, never on each other.** Cross-service code reuse → `shared-*`.
Cross-service communication → HTTP or events. Enforced via `maven-enforcer-plugin`'s
`bannedDependencies` rule, configured in the `services/` aggregator pom to ban any
`com.gateway.<service>:*` cross-dependency.

**When to revisit:**
- Team has 4+ engineers and per-service release cadence becomes a real constraint
- A service needs a fundamentally different stack (e.g. a service in Go for performance reasons)
- Build times exceed ~5 minutes despite incremental-build optimization
