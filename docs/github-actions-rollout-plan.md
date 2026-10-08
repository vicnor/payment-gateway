# GitHub Actions rollout plan (temporary)

> Temporary implementation reference. Remove this file after the four workflows below are
> implemented, required checks are configured, and the acceptance checklist is complete.

## Goal

Add four complementary GitHub Actions checks without running the same expensive integration suite
multiple times:

1. Backend CI
2. Security checks
3. Local infrastructure smoke test
4. Database migration verification

This plan also updates the action versions in the existing Merchant API contract workflow as a
prerequisite.

## Guiding decisions

- Pin every third-party action to a full commit SHA and retain a version comment.
- Give each job only the permissions it needs. Default to `contents: read`.
- Use `pull_request` for untrusted PR code. Do not use `pull_request_target` to build or execute PR
  code.
- Add `workflow_dispatch` to each workflow so it can be diagnosed manually.
- Cancel obsolete runs for the same PR with workflow-level `concurrency`.
- Keep validation logic in Maven tests or repository scripts where practical. Workflows should
  orchestrate checks that developers can also run locally.
- Never upload application logs from card-processing tests without first ensuring they cannot
  contain PAN or CVV. Existing PAN/CVV log assertions remain mandatory.
- The token service remains independently deployable and PCI-scoped. These workflows must not
  introduce cross-service imports or expose token-service artifacts to unrelated jobs.

## Proposed delivery sequence

Implement this as four small PRs so each required check can settle before the next one is added.

| PR | Deliverable | Required check after merge |
|---|---|---|
| 1 | Action-pin refresh and Backend CI | `Backend CI / verify` |
| 2 | Security workflow and repository security configuration | Security jobs that are available on the repository's GitHub plan |
| 3 | Local infrastructure smoke test | `Local infrastructure / smoke` |
| 4 | Migration upgrade tests and workflow | `Database migrations / verify` |

Do not make a new check required until it has passed on `main` at least once. This avoids locking
the branch behind a misspelled or unavailable check.

## Prerequisite: refresh and centralize action pins

### Files

- Update `.github/workflows/merchant-api-contract.yml`.
- Use the same checkout and Java setup pins in the new workflows.
- Optionally add `.github/dependabot.yml` for the `github-actions` ecosystem so pin updates are
  proposed automatically.

### Work

1. Resolve the current stable releases and immutable commit SHAs for:
   - `actions/checkout`
   - `actions/setup-java`
   - every new action introduced by the security workflows
2. Replace the older checkout and setup-java pins in the Merchant API contract workflow.
3. Keep `fetch-depth: 0` only where history is required:
   - Merchant API base-revision comparison
   - full-history secret scanning
   Other jobs should use the default shallow checkout.
4. Keep Maven caching through `actions/setup-java` and Java 25 Temurin.
5. Configure Dependabot to group safe GitHub Action updates where useful, but continue pinning the
   resulting workflow references to full SHAs.

### Acceptance criteria

- The Merchant API contract workflow passes on a test PR.
- All `uses:` entries in `.github/workflows/*.yml` are immutable SHA references, except local
  actions if any are later added.
- Version comments beside SHA pins match the resolved releases.

## 1. Backend CI

### Purpose

Provide one authoritative full-reactor build covering every shared module and all currently
implemented services. The existing Merchant API workflow covers only checkout-service and
payment-service plus their Maven dependencies.

### File

- Add `.github/workflows/backend-ci.yml`.

### Triggers

Run on:

- Pull requests when any of these change:
  - `pom.xml`
  - `.mvn/**`
  - `mvnw`
  - `mvnw.cmd`
  - `shared/**`
  - `services/**`
  - `.github/workflows/backend-ci.yml`
- Pushes to `main` for the same paths.
- Manual `workflow_dispatch`.

Avoid including documentation-only changes unless a document is an executable contract. The
Merchant API workflow remains responsible for `docs/architecture/api.md` and the OpenAPI contract.

### Job design

Create a single `verify` job on `ubuntu-latest`:

1. Check out the repository.
2. Set up Temurin Java 25 with Maven caching.
3. Run:

   ```bash
   ./mvnw --batch-mode --no-transfer-progress clean verify
   ```

4. On failure, upload Surefire and Failsafe reports only:

   ```text
   **/target/surefire-reports/**
   **/target/failsafe-reports/**
   ```

   Use a short retention period. Do not upload general service logs, database volumes, DynamoDB
   data, environment dumps, or complete `target/` directories.

Start with one reactor job rather than a service matrix. A matrix would rebuild shared modules and
start several sets of Testcontainers. Split it later only if measured duration warrants it.

### Interaction with Merchant API contract workflow

Once Backend CI is required, reduce duplicate Maven work in
`.github/workflows/merchant-api-contract.yml`:

- Keep OpenAPI syntax validation and the `oasdiff` breaking-change comparison there.
- Replace its checkout/payment reactor verification with the smallest command that validates the
  specification, currently:

  ```bash
  ./mvnw --batch-mode --no-transfer-progress -pl shared/shared-api -am verify
  ```

- Rely on Backend CI for controller and full integration tests when Java code or the shared API
  changes.

If dedicated OpenAPI-to-controller conformance tests are introduced later, run only those focused
tests in the contract workflow rather than both services' complete test suites.

### Workflow settings

- Permissions: `contents: read`.
- Concurrency group: workflow name plus PR number or Git ref.
- `cancel-in-progress: true` for pull requests.
- Set a reasonable job timeout so a stuck Testcontainer cannot consume a runner indefinitely.

### Acceptance criteria

- A clean PR runs the complete Maven reactor and passes.
- A failing unit test, integration test, Spotless check, or build fails the job.
- Testcontainers successfully starts PostgreSQL, DynamoDB Local, and LocalStack where required.
- A superseded PR run is cancelled.
- Failure reports are downloadable and contain no PAN/CVV or secrets.
- `Backend CI / verify` is configured as a required branch check after its first successful
  `main` run.

## 2. Security checks

### Purpose

Reject leaked secrets and newly introduced vulnerable dependencies, and continuously scan Java
code for security issues.

### Preflight decision

Before writing the workflow, check whether the repository is public or private and which GitHub
security features are available. Dependency Review and CodeQL availability varies by repository
visibility and GitHub plan. Record unavailable checks in the implementing PR instead of silently
adding jobs that can never pass.

### Files and settings

- Add `.github/workflows/security.yml` for Gitleaks and Dependency Review.
- Prefer GitHub CodeQL default setup when available. Add a custom CodeQL workflow only if default
  setup cannot build/analyze this Java 25 Maven project correctly.
- Add `.github/dependabot.yml` for Maven and GitHub Actions updates if it was not added in the
  prerequisite.
- Add `.gitleaks.toml` only when repository-specific allow-listing is genuinely required. Do not
  broadly exclude test or configuration directories.

### Job A: secret scanning

Run Gitleaks on pull requests, pushes to `main`, manual dispatch, and a weekly schedule.

Requirements:

- Use the maintained Node 24-compatible Gitleaks action, pinned to a full SHA.
- Check out full history with `fetch-depth: 0`.
- Start with `contents: read`. Add `pull-requests: write` only if inline PR comments are explicitly
  desired.
- Confirm whether an organization-owned repository requires a Gitleaks Action license. If that is
  undesirable, run the pinned Gitleaks CLI release instead and verify its checksum.
- Treat findings as failures. Add narrowly scoped ignore entries with an explanation and reviewer
  approval; never suppress by directory merely because secrets are test fixtures.

### Job B: dependency review

Run only for pull requests that change Maven dependency inputs:

- `pom.xml`
- `shared/**/pom.xml`
- `services/**/pom.xml`
- `.mvn/**`
- the dependency-review workflow itself

Configure the pinned `actions/dependency-review-action` to reject newly introduced dependencies
with known vulnerabilities at an agreed threshold. Start at `moderate` severity and tighten after
observing the first results. Decide and document license policy separately rather than enabling a
large deny-list without review.

Permissions should remain `contents: read` unless the chosen reporting mode requires more.

### Job C: CodeQL

Preferred approach:

1. Enable CodeQL default setup for Java/Kotlin in repository settings.
2. Enable scans for pull requests, pushes to `main`, and the standard scheduled scan.
3. Confirm the first analysis recognizes all Java modules.

If custom setup is necessary, use the current pinned CodeQL action, `security-events: write`, and a
manual Maven build compatible with Java 25. Avoid running another complete test suite merely to
compile for CodeQL; use the lightest supported build mode that analyzes the project correctly.

### Dependabot configuration

Configure at least:

- Maven dependencies from `/`, weekly.
- GitHub Actions from `/`, weekly.
- Group related Spring and AWS SDK updates to reduce PR noise where safe.
- Do not auto-merge security-sensitive dependency changes without the normal required checks.

### Acceptance criteria

- A test PR containing a harmless Gitleaks test signature fails, and removing it restores success.
- A PR introducing a known-vulnerable test dependency is rejected by Dependency Review when the
  feature is available.
- CodeQL completes a Java scan and reports against the repository's Security tab.
- Scheduled scans exist for findings that appear without code changes.
- Security jobs have only their documented permissions.
- No workflow uses long-lived AWS credentials or repository secrets on untrusted PR code.

## 3. Local infrastructure smoke test

### Purpose

Verify that Docker Compose, the Make targets, and AWS bootstrap script collectively create the
local infrastructure described by the repository.

### Files

- Add `.github/workflows/local-infra-smoke.yml`.
- Add `infrastructure/scripts/verify-local-infra.sh` so assertions run identically locally and in
  CI.
- If needed, add a dedicated `make dev-verify` target that calls the verification script.

### Triggers

Run on pull requests and pushes to `main` when any of these change:

- `Makefile`
- `infrastructure/docker-compose.yml`
- `infrastructure/scripts/**`
- `.github/workflows/local-infra-smoke.yml`
- service Dockerfiles referenced by Compose

Also support manual dispatch. A schedule is unnecessary while images use explicit versions; add a
weekly schedule later only if external image drift becomes a concern.

### Job design

Create one `smoke` job on `ubuntu-latest`:

1. Check out the repository.
2. Confirm required runner tools are present (`docker`, Docker Compose, AWS CLI, `jq`, `curl`, and
   `python3`). Install only tools not already available.
3. Validate configuration without starting containers:

   ```bash
   docker compose -f infrastructure/docker-compose.yml config --quiet
   ```

4. Run ShellCheck against `infrastructure/scripts/*.sh`. Pin the ShellCheck action or CLI version.
5. Start and bootstrap:

   ```bash
   make dev-up
   make dev-bootstrap
   ```

6. Run `infrastructure/scripts/verify-local-infra.sh`.
7. On failure, print bounded Docker service status and logs after applying a secret/PAN-safe
   filter. Do not dump container environments.
8. In an `if: always()` cleanup step, run:

   ```bash
   make dev-down
   ```

### Verification script assertions

Use exact resource names rather than count-only checks. Assert:

- PostgreSQL is healthy and the `payment`, `webhook`, and `merchant` databases exist.
- SNS topics:
  - `payment-events`
  - `checkout-events`
- SQS queues and DLQs:
  - `webhook-dispatch`
  - `webhook-dispatch-dlq`
  - `payment-reconciliation`
  - `payment-reconciliation-dlq`
- `webhook-dispatch` has a redrive policy with `maxReceiveCount=5`.
- `payment-events` is subscribed to `webhook-dispatch`.
- KMS aliases:
  - `alias/token-service-dev`
  - `alias/checkout-idempotency-dev`
- DynamoDB tables:
  - `checkout_sessions`
  - `checkout_merchant_references`
  - `tokens`
  - `data_keys`
  - `token_rate_limits`
  - `checkout_idempotency_keys`
  - `payment_idempotency_keys`
- Expected DynamoDB TTL attributes and the checkout session GSI are configured.

Run `make dev-bootstrap` a second time and rerun verification to prove bootstrap idempotency.

### Workflow settings

- Permissions: `contents: read`.
- Concurrency cancellation enabled for PRs.
- Add a timeout covering image pulls plus the existing readiness loops.
- Do not use the `full` Compose profile; building/running application services belongs in the
  future end-to-end workflow.

### Acceptance criteria

- The workflow passes from a completely clean GitHub runner.
- Both first and second bootstrap runs pass.
- Changing or removing an expected queue/table/alias causes a clear failure.
- Cleanup runs after both success and failure.
- The same verification command works locally after `make dev-up && make dev-bootstrap`.

## 4. Database migration verification

### Purpose

Prove both fresh installation and upgrade paths for PostgreSQL-owning services. Existing
integration tests primarily prove that the latest migrations work against a new database; they do
not guarantee that a populated database at the previous migration can be upgraded.

### Initial scope

- `services/merchant-service/src/main/resources/db/migration/**`
- `services/payment-service/src/main/resources/db/migration/**`
- Add webhook-service when that service and its migrations exist.

Do not access one service's database from another service. Each service owns and tests its migration
path independently.

### Files

- Add `.github/workflows/database-migrations.yml`.
- Add focused migration integration tests under each PostgreSQL service, preferably in an `it`
  package and using the shared PostgreSQL Testcontainers base.
- Add versioned test fixtures only when a migration requires representative existing rows.

### Test design

For each PostgreSQL-owning service, cover:

1. **Fresh migration**
   - Start an empty PostgreSQL 16 database.
   - Run Flyway to latest.
   - Run Flyway validation.
   - Assert the expected current schema objects exist.

2. **Previous-to-latest upgrade**
   - Start an empty PostgreSQL 16 database.
   - Migrate to the version immediately before the newest migration.
   - Insert minimal representative rows using a service-owned test fixture.
   - Migrate to latest and validate.
   - Assert data survives and new constraints, indexes, columns, and defaults behave as intended.

3. **Migration quality rules**
   - Reject edits to an already released/versioned migration; add a new migration instead.
   - Ensure migration descriptions and versions are unique.
   - Do not rely on another test's database state.

The upgrade test should be explicit about the prior version rather than inferring behavior solely
from an empty schema. When a PR adds multiple migrations, test the last released baseline through
all new migrations.

### Workflow design

Use a small matrix with one entry per PostgreSQL-owning service. Each entry runs only that module
and its required upstream modules, for example:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl services/merchant-service -am verify
```

and equivalently for payment-service.

Trigger on:

- The service's migration directory.
- That service's migration test/fixture directory.
- `shared/shared-testing/**`.
- Root, shared, and service POM changes that affect Flyway, PostgreSQL, or tests.
- The workflow file itself.
- Manual dispatch.

The matrix intentionally repeats a limited module build because it gives service-specific migration
status and runs only when migration-related paths change. Backend CI remains the authoritative full
reactor check.

### Released-migration immutability

Choose and document a baseline source before enforcing immutability:

- Preferred after releases exist: compare migration checksums/names with the latest release tag.
- Before the first release: compare existing migration files with the PR base revision and reject
  modifications unless the team explicitly marks the migration as unreleased.

Keep this comparison read-only and avoid automatically rewriting Flyway history.

### Workflow settings

- Permissions: `contents: read`.
- PostgreSQL comes from Testcontainers; do not maintain a separate workflow service container unless
  tests cannot use the existing shared base.
- Use Maven caching and Java 25 Temurin.
- Upload only focused Surefire/Failsafe reports on failure.

### Acceptance criteria

- Fresh migrations pass for merchant-service and payment-service.
- Payment-service successfully upgrades from V001 to V002 with representative existing rows.
- A deliberately invalid migration fails with a useful service-specific result.
- Modifying a migration considered released is rejected.
- Adding a valid next migration and its upgrade fixture passes.
- `Database migrations / verify (<service>)` checks are made required only after stable successful
  runs.

## Final repository configuration

After all four PRs are stable:

1. Require these checks on `main`:
   - `Backend CI / verify`
   - `Merchant API contract / validate`
   - available security checks
   - `Local infrastructure / smoke` when its paths match
   - migration matrix checks when their paths match
2. Require branches to be current before merging if that matches the team's merge policy.
3. Prevent required workflows from receiving unnecessary write permissions.
4. Review Actions retention settings so test reports do not live longer than needed.
5. Confirm forked pull requests receive no repository or environment secrets.

## Completion checklist

- [ ] Existing workflow action pins updated.
- [ ] Dependabot configured for GitHub Actions and Maven.
- [ ] Backend CI implemented, passing, and required.
- [ ] Duplicate Maven work removed from Merchant API contract workflow.
- [ ] Gitleaks implemented and scheduled.
- [ ] Dependency Review enabled or its GitHub-plan limitation documented.
- [ ] CodeQL default/custom setup enabled and confirmed to cover all Java modules.
- [ ] Local infrastructure verification script works locally and in CI.
- [ ] Infrastructure smoke workflow proves bootstrap idempotency and always cleans up.
- [ ] Fresh and upgrade migration tests exist for merchant-service and payment-service.
- [ ] Released migration immutability policy is enforced.
- [ ] Required checks and least-privilege permissions reviewed.
- [ ] This temporary planning file removed after implementation documentation is updated.
