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
cp .env.example .env

cd apps/api && mvn spring-boot:run
cd apps/web && npm install && npm run dev
```

- API: `http://localhost:8080`
- Painel: `http://localhost:3000`
- Mailpit: `http://localhost:8025`
- Testes da API: com Postgres no ar, `cd apps/api && mvn test`

Variáveis sensíveis ficam em `.env` (não versionado). Use `.env.example` como referência.

Fluxo rápido no painel: pedir magic link → abrir o e-mail no Mailpit (ou o link de dev) → criar projeto (guardar `projectKey`) → apontar o provedor para `/v1/ingest/{projectKey}` → acompanhar eventos e replay.

## Gitflow

- `feature/*` → PR para `develop`
- `release/*` → PR para `master`
- Branches mescladas são removidas (local e remoto)

## Pacote base

`br.com.ricarte.hookguard`
