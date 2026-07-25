# HookGuard — Design Spec

**Date:** 2026-07-25  
**Status:** Ready for implementation planning  
**Mission:** Webhook delivery that monetizes itself (self-serve SaaS), with the smallest ops surface that still earns trust.

## 1. Product

### Promise

Seu webhook chega. Se falhar, a gente retenta, guarda e permite replay — sem você perder pedido/pagamento.

### Problem

Providers fire webhooks once. If the customer endpoint is down, slow, or non-idempotent, the event is lost. Teams reinvent retry/DLQ/replay for every integration.

### ICP

Small/medium teams that already receive payment, e-commerce, or ERP webhooks and do not want to operate a message bus just for inbound reliability.

### Success metric (MVP)

A user pastes the HookGuard URL into a provider, points delivery at their app, and within 15 minutes sees events plus at least one real retry — with zero sales call.

## 2. Scope

### In MVP

1. Unique ingest URL per project: `/v1/ingest/{projectKey}`
2. Forward to customer destination URL
3. Retry with exponential backoff, then DLQ
4. Deduplication via configurable header or body field → `dedupeKey`
5. Dashboard: event list, status, payload view, manual replay
6. Self-serve auth + Stripe billing (free tier by monthly event volume → paid + overage)
7. Project settings: destination URL, timeout, max attempts, dedupe rules, signing secret for outbound

### Out of MVP

- Payload transformation pipelines
- PagerDuty/Slack alert integrations (beyond a simple email/webhook on DLQ later)
- Multi-region active-active
- Multi-language SDKs (docs + curl/examples only)
- Connector marketplace
- Kafka/Rabbit/SQS as primary queue

## 3. Architecture decisions (locked)

| Decision | Choice | Why |
|---|---|---|
| Shape | Modular monolith (API + workers same deploy) | Fast ship, one deploy, split workers later |
| Backend | Java 21 + Spring Boot 3 | Team strength, production maturity |
| Queue | PostgreSQL outbox + `FOR UPDATE SKIP LOCKED` | No broker ops until volume forces it |
| DB | PostgreSQL 16 | Events, jobs, tenants, billing state |
| Frontend | Next.js (App Router) thin dashboard | Only what billing + ops need |
| Auth | Magic link (email) + GitHub OAuth | Low friction self-serve |
| Billing | Stripe Checkout + Customer Portal + metered overage | Monetizes without sales |
| Hosting target | Single region, container (Docker) | Simple ops for v1 |

### Components

1. **Ingest API** — validate `projectKey`, persist event `received`, enqueue delivery job, respond `202` fast
2. **Delivery Worker** — claim jobs, POST to destination, record attempts
3. **Retry Scheduler** — set `nextAttemptAt` with backoff; mark `dead` when exhausted
4. **Replay API** — re-queue from history/DLQ (same payload, new attempt chain)
5. **Dashboard API + Web** — projects, events, replay, settings
6. **Billing** — plan limits, usage counters, Stripe webhooks for subscription lifecycle

### Happy path

Provider → Ingest → store event + job → Worker → customer `2xx` → status `delivered`

### Failure path

Worker gets `5xx` / timeout / network error → schedule retry → attempts exhausted → status `dead` → user replays from dashboard

### Backoff (MVP defaults)

`30s → 2m → 10m → 1h → 6h` (5 retries after first try = 6 attempts total). Configurable per project within sane caps.

## 4. Data model (MVP)

### `accounts`

- `id`, `email`, `name`, `stripeCustomerId`, `plan`, `createdAt`

### `projects`

- `id`, `accountId`, `name`, `projectKey` (public ingest token), `destinationUrl`
- `timeoutMs`, `maxAttempts`, `dedupeHeader` (nullable), `signingSecret`
- `createdAt`, `status` (`active` \| `suspended`)

### `events`

- `id` (UUID), `projectId`, `receivedAt`
- `headers` (JSONB, filtered — strip hop-by-hop / sensitive if needed)
- `body` (BYTEA or TEXT; store raw)
- `contentType`, `dedupeKey` (nullable), `status` (`received` \| `delivering` \| `delivered` \| `dead`)
- Unique partial index on `(projectId, dedupeKey)` where `dedupeKey is not null`

### `delivery_attempts`

- `id`, `eventId`, `attemptNumber`, `startedAt`, `finishedAt`
- `httpStatus`, `errorMessage`, `responseBodySnippet` (capped)

### `delivery_jobs`

- `id`, `eventId`, `projectId`, `availableAt`, `lockedAt`, `lockedBy`
- `state` (`pending` \| `in_progress` \| `done` \| `dead`)

### `usage_monthly`

- `accountId`, `yearMonth`, `eventCount` — for plan enforcement and Stripe metering

## 5. API surface (MVP)

### Public ingest

- `POST /v1/ingest/{projectKey}` — body opaque; return `202 { "eventId": "..." }`
- Idempotent on `(projectId, dedupeKey)` when dedupe configured: same key → `202` with original `eventId`, no double-count

### Authenticated dashboard/API

- CRUD projects (minimal: create/list/get/update destination + retry settings)
- `GET /v1/events?projectId&status&cursor`
- `GET /v1/events/{id}`
- `POST /v1/events/{id}/replay`
- Billing: create checkout session, portal link, usage summary

### Worker internals

- Poll `delivery_jobs` where `state = pending AND availableAt <= now()` with `SKIP LOCKED`
- Outbound POST includes headers:
  - original filtered headers (optional allowlist)
  - `X-HookGuard-Event-Id`
  - `X-HookGuard-Attempt`
  - `X-HookGuard-Signature` (HMAC-SHA256 of body with `signingSecret`)

## 6. Security & privacy

- `projectKey` is a high-entropy secret in the URL; rotatable
- TLS everywhere
- Payload retention default: 14 days (configurable later); hard delete job
- Dashboard auth required for payload view/replay
- Rate limit ingest per project
- Destination URL must be https in production (http allowed only in local/dev)
- No SSRF to link-local / private ranges from worker (blocklist)
- Stripe webhook signature verification mandatory
- Secrets in env / secret manager, never in repo

## 7. Error handling

| Case | Behavior |
|---|---|
| Unknown `projectKey` | `404` |
| Suspended project / over hard quota | `429` or `402` with clear body |
| Destination timeout | retry path |
| Destination `4xx` (except 408/429) | treat as dead sooner (no infinite retry on client bugs); `429`/`408` retry |
| Duplicate dedupeKey | return existing event, do not increment usage |
| Worker crash mid-delivery | lock TTL expires → job becomes claimable again (at-least-once; customer must be idempotent) |
| Stripe down at signup | account usable on free tier; billing retry path |

Delivery is **at-least-once**. Docs must state customers should key off `X-HookGuard-Event-Id` / dedupe.

## 8. Monetization (self-serve)

### Plans (initial)

| Plan | Monthly events | Price (target) |
|---|---|---|
| Free | 5_000 | R$ 0 |
| Pro | 100_000 | paid fixed |
| Business | 1_000_000 | higher fixed |
| Overage | beyond plan | metered per 1k events |

Exact BRL prices set at launch; product must not hardcode them in business logic — Stripe Price IDs + config.

### Enforcement

- Soft warning at 80% of quota
- Free: hard stop ingest at 100% with `429`
- Paid: allow overage + meter to Stripe
- Suspend on failed payment after Stripe retry cycle

### Activation loop

Signup → create project → copy ingest URL → first event → value → paywall when volume hurts.

## 9. Observability

- Structured logs with `eventId`, `projectId`, `attempt`
- Metrics: ingest rate, delivery success ratio, p95 delivery latency, DLQ depth, job lag
- Health: `/health` liveness, `/ready` DB-dependent
- No PII in logs beyond account id / project id; payloads only in DB

## 10. Testing strategy

- Unit: backoff, dedupe, signature, SSRF URL validation, plan quota math
- Integration: ingest → job → mock destination → delivered; failure → retry → dead → replay
- Contract: Stripe webhook handling with signed fixtures
- Load smoke: sustained ingest with worker catching up (local/k6 later)

## 11. Repo layout (target)

```
hookguard/
  apps/
    api/          # Spring Boot (ingest, dashboard API, workers)
    web/          # Next.js dashboard
  docs/
    superpowers/
      specs/
  docker-compose.yml  # postgres (+ mailpit for magic link in dev)
  README.md
```

Workers run in-process via Spring scheduling/`@Async` or a dedicated runner profile in the same artifact (`--spring.profiles.active=worker`). Same codebase, two process roles in compose when needed.

## 12. Non-goals & explicit trade-offs

- **Not** a general iPaaS or Zapier
- **Not** exactly-once delivery (impossible over HTTP without customer cooperation)
- Postgres queue is a deliberate trade-off: simpler until ~tens of events/sec sustained; migrate to broker when lag/ops data says so
- UI stays operational, not marketing-heavy inside the app; marketing site can wait

## 13. Implementation order (for the plan)

1. Domain + Postgres schema + ingest + job claim loop
2. Delivery + retry + DLQ + replay
3. Auth + project settings dashboard
4. Usage metering + Stripe
5. Hardening (SSRF, retention, rate limits) + polish

## 14. Open items (resolved by default)

| Item | Default |
|---|---|
| Broker day 1? | No — Postgres |
| Framework | Spring Boot 3 / Java 21 |
| Primary market copy | Portuguese (BR), English docs secondary later |
| Project path | `/home/frm/Documentos/wks/hookguard` |
