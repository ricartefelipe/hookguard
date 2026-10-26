# HookGuard

Confiabilidade de webhooks para times que não querem operar mensageria só para receber eventos.

Cole a URL de ingest no provedor, aponte o destino para o seu app. O HookGuard persiste o evento, entrega com retry e semântica **at-least-once**, move para DLQ quando esgota tentativas e permite replay manual. O destino precisa deduplicar efeitos pela `X-HookGuard-Event-Id`: timeout do cliente não prova que o destino não processou, e o produto não promete exactly-once remoto.

Guia de uso: [`docs/USAGE.md`](docs/USAGE.md). Operação e restauração: [`docs/OPERACAO.md`](docs/OPERACAO.md). Condição comercial: [`docs/COMERCIAL.md`](docs/COMERCIAL.md).

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

cd apps/api && HOOKGUARD_DELIVERY_ALLOW_HTTP=true HOOKGUARD_EXPOSE_MAGIC_LINK=true mvn spring-boot:run
cd apps/web && npm install && npm run dev
```

O `.env.example` traz valores seguros de produção (sem senha padrão, `ALLOW_HTTP=false`, link de login não exposto). Para desenvolver contra destinos locais, passe as duas variáveis acima na linha de comando, como no exemplo.

- API: `http://localhost:8080`
- Painel: `http://localhost:3000`
- Mailpit: `http://localhost:8025`
- Testes: `cd apps/api && mvn test` (use `HOOKGUARD_TEST_DB_URL` apontando para um Postgres descartável; a suíte apaga as tabelas de aplicação e cria/remove bancos temporários `hg_tmp_*` ao lado dele, então o usuário precisa de `CREATEDB`)
- Ensaios com processos reais, em containers descartáveis: `./scripts/backup-restore-drill.sh` e `./scripts/worker-crash-drill.sh`

## Demo gratuita (sem VPS/domínio)

```bash
./scripts/free-demo.sh
```

Painel em `http://localhost:9080` + URL HTTPS pública via túnel Cloudflare. Detalhes: [`docs/FREE.md`](docs/FREE.md).
O login usa magic link nativo ou GitHub. O TotalRecall não é modo de login do produto.

## Oracle Always Free (24/7)

Guia completo: [`docs/ORACLE.md`](docs/ORACLE.md) — VM Ampere + Docker + túnel Cloudflare, sem domínio.

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
