# HookGuard

Confiabilidade de webhooks para times que não querem operar mensageria só para receber eventos.

Cole a URL de ingest no provedor, aponte o destino para o seu app. O HookGuard registra o evento, entrega com retry, move para DLQ quando esgota tentativas e permite replay manual.

Guia de uso: [`docs/USAGE.md`](docs/USAGE.md)

## Stack

- Java 21, Spring Boot 3, PostgreSQL 16
- Fila de entrega no Postgres (`SKIP LOCKED`)
- Painel operacional em Next.js
- Auth: magic link + GitHub OAuth
- Cobrança self-serve via Stripe (Pro/Business + overage)

## Estrutura

```
apps/api   — ingest, worker, auth, billing
apps/web   — dashboard
deploy/    — Caddyfile
docs/      — especificação, plano e uso
```

## Desenvolvimento local

```bash
docker compose up -d
cp .env.example .env

cd apps/api && mvn spring-boot:run
cd apps/web && npm install && npm run dev
```

- API: `http://localhost:8080`
- Painel: `http://localhost:3000`
- Mailpit: `http://localhost:8025`
- Testes: `cd apps/api && mvn test`

## Demo gratuita (sem VPS/domínio)

```bash
./scripts/free-demo.sh
```

Painel em `http://localhost:9080` + URL HTTPS pública via túnel Cloudflare. Detalhes: [`docs/FREE.md`](docs/FREE.md).

## Produção

```bash
# .env com HOOKGUARD_DOMAIN, DB, SMTP, Stripe, GitHub OAuth
docker compose -f docker-compose.prod.yml up -d --build
```

HTTPS via Caddy. Detalhes em `docs/USAGE.md`.

## Gitflow

- `feature/*` → PR para `develop`
- `release/*` → PR para `master`
- Branches mescladas são removidas (local e remoto)

## Pacote base

`br.com.ricarte.hookguard`
