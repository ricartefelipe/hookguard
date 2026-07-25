# HookGuard MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a self-serve webhook reliability MVP: ingest, Postgres job queue, retry/DLQ/replay, thin dashboard API, Stripe metering hooks, published via gitflow.

**Architecture:** Modular Spring Boot monolith with in-process worker profile; PostgreSQL stores events and `delivery_jobs` claimed via `FOR UPDATE SKIP LOCKED`; Next.js dashboard talks to authenticated API; Stripe for plans/overage.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Spring Data JPA, Liquibase, PostgreSQL 16, Testcontainers, Next.js 15, Stripe Java SDK, Docker Compose.

## Global Constraints

- Base package: `br.com.ricarte.hookguard`
- Gitflow: `feature/*` → `develop`; `release/*` → `master`; delete orphan branches after merge
- Publish to GitHub under `ricartefelipe` as soon as the repo has an initial commit
- Never mention generative assistants or similar tools in any artifact (code, commits, PRs, docs)
- No code comments; clear names only
- Responses to the human partner in Portuguese; product code/docs may be PT-BR
- At-least-once delivery; customers must be idempotent
- No broker in MVP — Postgres outbox only
- Avoid inline imports; exhaustive switches where TypeScript unions appear
- Commits only as part of planned steps; PR via `gh`

## File map

```
hookguard/
  README.md
  .gitignore
  docker-compose.yml
  apps/
    api/
      pom.xml
      src/main/java/br/com/ricarte/hookguard/...
      src/main/resources/application.yml
      src/main/resources/db/changelog/...
      src/test/java/br/com/ricarte/hookguard/...
    web/
      package.json
      src/app/...
  docs/superpowers/specs/2026-07-25-hookguard-design.md
  docs/superpowers/plans/2026-07-25-hookguard-mvp.md
```

---

### Task 1: Repository bootstrap + gitflow + GitHub

**Files:**
- Create: `README.md`, `.gitignore`, `docker-compose.yml`
- Keep: design + plan docs already present

**Interfaces:**
- Produces: remote `origin`, branches `master` and `develop`, first commit on `master`

- [ ] **Step 1: Write root files**

`.gitignore` for Java/Node/IDE/env.  
`README.md` describing HookGuard, stack, how to run API + Postgres (no tool attributions).  
`docker-compose.yml` with Postgres 16 and Mailpit.

- [ ] **Step 2: Initial commit on master**

```bash
git add README.md .gitignore docker-compose.yml docs
git commit -m "$(cat <<'EOF'
Initial commit: HookGuard product spec and workspace baseline.

EOF
)"
```

- [ ] **Step 3: Create develop and publish**

```bash
git branch develop
gh repo create ricartefelipe/hookguard --private --source=. --remote=origin --push
git push -u origin master
git push -u origin develop
```

- [ ] **Step 4: Start feature branch**

```bash
git checkout develop
git checkout -b feature/mvp-foundation
```

---

### Task 2: Spring Boot API skeleton + Liquibase schema

**Files:**
- Create: `apps/api/pom.xml`
- Create: `apps/api/src/main/java/br/com/ricarte/hookguard/HookguardApplication.java`
- Create: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `apps/api/src/main/resources/db/changelog/changes/001-core-schema.yaml`
- Test: `apps/api/src/test/java/br/com/ricarte/hookguard/SchemaMigrationTest.java`

**Interfaces:**
- Produces: tables `accounts`, `projects`, `events`, `delivery_attempts`, `delivery_jobs`, `usage_monthly`

- [ ] **Step 1: Write failing migration test**

Testcontainers PostgreSQL + `@SpringBootTest` asserting Liquibase creates `events` and `delivery_jobs`.

- [ ] **Step 2: Run test — expect fail** (app missing)

```bash
cd apps/api && mvn -q test -Dtest=SchemaMigrationTest
```

- [ ] **Step 3: Implement skeleton + changelog 001**

Columns per design spec section 4. Indexes: `(projectId, dedupeKey)` unique where dedupe not null; `delivery_jobs(state, availableAt)`.

- [ ] **Step 4: Run test — expect pass**

- [ ] **Step 5: Commit**

```bash
git add apps/api
git commit -m "$(cat <<'EOF'
Add API skeleton and core schema for webhook delivery.

EOF
)"
```

---

### Task 3: Domain + ingest endpoint with dedupe

**Files:**
- Create entities/repos under `.../domain/` and `.../ingest/`
- Create: `IngestController`, `IngestService`, `ProjectRepository`, `EventRepository`, `DeliveryJobRepository`
- Test: `IngestServiceTest`, `IngestIntegrationTest`

**Interfaces:**
- Produces: `IngestService.accept(String projectKey, Map<String,String> headers, byte[] body, String contentType) -> UUID eventId`
- `POST /v1/ingest/{projectKey}` → `202 { "eventId": "..." }`

- [ ] **Step 1: Unit test dedupe returns same eventId and does not create second job**

- [ ] **Step 2: Run — fail**

- [ ] **Step 3: Implement accept + controller + unknown key 404 + suspended 402**

- [ ] **Step 4: Integration test with MockMvc + Testcontainers**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Accept inbound webhooks with dedupe and delivery job enqueue.

EOF
)"
```

---

### Task 4: Delivery worker, retry, DLQ, signature, SSRF guard

**Files:**
- Create: `DeliveryWorker`, `DeliveryClient`, `BackoffPolicy`, `DestinationUrlValidator`, `HookSignature`
- Test: `BackoffPolicyTest`, `DestinationUrlValidatorTest`, `DeliveryWorkerIntegrationTest`

**Interfaces:**
- Produces: worker claims jobs, POSTs destination, writes attempts, schedules retry or marks `dead`
- Outbound headers: `X-HookGuard-Event-Id`, `X-HookGuard-Attempt`, `X-HookGuard-Signature`

- [ ] **Step 1: Tests for backoff sequence and blocked private URLs**

- [ ] **Step 2: Run — fail**

- [ ] **Step 3: Implement validator, HMAC, worker loop (`SKIP LOCKED` via native query or pessimistic lock)**

- [ ] **Step 4: Integration: mock web server 500 → retry → eventually dead; 200 → delivered**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Deliver webhooks with retry, DLQ, signing, and SSRF protections.

EOF
)"
```

---

### Task 5: Replay + event query API

**Files:**
- Create: `EventQueryController`, `ReplayService`
- Test: `ReplayServiceTest`

**Interfaces:**
- `GET /v1/events`, `GET /v1/events/{id}`, `POST /v1/events/{id}/replay`
- Replay creates new pending job for existing event payload

- [ ] **Step 1–4:** TDD replay from `dead` and from `delivered`; list/filter by status
- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Expose event listing and manual replay for dead deliveries.

EOF
)"
```

---

### Task 6: Auth, projects CRUD, usage metering

**Files:**
- Create: security config (magic link + GitHub OAuth via Spring Security)
- Create: `ProjectController`, `UsageService`
- Test: project CRUD + usage increment on ingest

**Interfaces:**
- Authenticated project create returns `projectKey` once
- `UsageService.increment(accountId)` on new events (not on dedupe hits)
- Free plan hard-stop at quota → ingest `429`

- [ ] **Step 1–4:** TDD quota + project create
- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Add account auth, project settings, and free-tier usage limits.

EOF
)"
```

---

### Task 7: Stripe billing hooks

**Files:**
- Create: `BillingController`, `StripeWebhookController`, `BillingService`
- Test: signed webhook fixtures for subscription updated/deleted

**Interfaces:**
- Checkout session + customer portal endpoints
- Plan stored on `accounts.plan`; overage flag for paid plans

- [ ] **Step 1–4:** TDD plan transitions from Stripe events
- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Wire Stripe checkout and subscription lifecycle to account plans.

EOF
)"
```

---

### Task 8: Next.js dashboard (thin)

**Files:**
- Create: `apps/web/*` — login callback, projects list, event list, replay button, usage meter
- No decorative marketing inside app; operational UI only
- Avoid default Inter/purple SaaS look; distinctive but restrained ops aesthetic

**Interfaces:**
- Consumes authenticated API from Task 5–7

- [ ] **Step 1:** Scaffold Next.js + API client
- [ ] **Step 2:** Projects + events pages
- [ ] **Step 3:** Replay action + usage display
- [ ] **Step 4:** Commit

```bash
git commit -m "$(cat <<'EOF'
Add operational dashboard for projects, events, and replay.

EOF
)"
```

---

### Task 9: PR feature → develop

**Files:** none new

- [ ] **Step 1: Push feature branch**

```bash
git push -u origin feature/mvp-foundation
```

- [ ] **Step 2: Open PR into develop**

```bash
gh pr create --base develop --head feature/mvp-foundation --title "MVP foundation: ingest, delivery, dashboard" --body "$(cat <<'EOF'
## Summary
- Bootstrap HookGuard API with Postgres-backed delivery jobs
- Ingest, retry/DLQ/replay, usage limits, Stripe hooks
- Thin operational dashboard

## Test plan
- [ ] `cd apps/api && mvn test`
- [ ] `docker compose up -d` then smoke ingest → deliver → replay
- [ ] Dashboard: create project, list events, replay dead event
- [ ] Stripe test mode: checkout + portal (if keys present)

EOF
)"
```

- [ ] **Step 3: Merge PR (when green), delete feature branch local+remote**

```bash
gh pr merge --merge
git checkout develop
git pull
git branch -d feature/mvp-foundation
git push origin --delete feature/mvp-foundation
```

---

### Task 10: Release → master

When develop is stable for a cut:

- [ ] **Step 1:** `git checkout -b release/0.1.0 develop`
- [ ] **Step 2:** version bump / README runbook only if needed; commit
- [ ] **Step 3:** PR `release/0.1.0` → `master`, merge, tag `v0.1.0`
- [ ] **Step 4:** merge master back to develop if needed; delete `release/0.1.0`

---

## Spec coverage check

| Spec area | Task |
|---|---|
| Ingest + 202 | 3 |
| Retry/DLQ/replay | 4–5 |
| Dedupe | 3 |
| Dashboard | 5, 8 |
| Billing/Stripe | 7 |
| Postgres queue | 2, 4 |
| SSRF + signature | 4 |
| Usage/free stop | 6 |
| GitHub + gitflow | 1, 9, 10 |
