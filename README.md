# HookGuard

Confiabilidade de webhooks para times que não querem operar mensageria só para receber eventos.

Cole a URL de ingest no provedor, aponte o destino para o seu app. O HookGuard registra o evento, entrega com retry, move para DLQ quando esgota tentativas e permite replay manual.

## Stack

- Java 21, Spring Boot 3, PostgreSQL 16
- Fila de entrega no Postgres (`SKIP LOCKED`)
- Painel operacional em Next.js
- Cobrança self-serve via Stripe

## Estrutura

```
apps/api   — ingest, worker de entrega, API do painel
apps/web   — dashboard
docs/      — especificação e plano
```

## Desenvolvimento local

```bash
docker compose up -d
cd apps/api && mvn spring-boot:run
cd apps/web && npm install && npm run dev
```

Variáveis sensíveis ficam em `.env` (não versionado). Use `.env.example` como referência quando disponível.

## Gitflow

- `feature/*` → PR para `develop`
- `release/*` → PR para `master`
- Branches mescladas são removidas (local e remoto)

## Pacote base

`br.com.ricarte.hookguard`
