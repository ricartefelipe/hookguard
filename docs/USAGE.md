# HookGuard — Como usar

## 1. Entrar

1. Abra o painel.
2. Peça um magic link por e-mail **ou** use GitHub (se configurado).
3. Em local, o e-mail aparece no Mailpit (`http://localhost:8025`). Com `HOOKGUARD_EXPOSE_MAGIC_LINK=true`, o link também aparece na tela.

## 2. Criar projeto

No painel:

1. Informe nome e URL de destino (seu endpoint HTTPS).
2. Opcional: header de dedupe (ex.: `X-Idempotency-Key`).
3. **Copie a `projectKey`** na criação (ou use “Rotacionar projectKey” depois).

URL de ingest:

```text
https://SEU_DOMINIO/v1/ingest/{projectKey}
```

## 3. Apontar o provedor

Configure o webhook do Stripe, Mercado Pago, Shopify, ERP etc. para a URL de ingest.

Exemplo com curl:

```bash
curl -X POST "https://SEU_DOMINIO/v1/ingest/SUA_PROJECT_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: pedido-123" \
  -d '{"orderId":"123","status":"paid"}'
```

Resposta esperada:

```json
{"eventId":"..."}
```

## 4. Validar entrega no seu app

O HookGuard faz POST no destino com o body original e headers:

- `X-HookGuard-Event-Id`
- `X-HookGuard-Attempt`
- `X-HookGuard-Signature` (HMAC-SHA256 hex do body com o `signingSecret`)

Seu endpoint deve responder `2xx` e ser idempotente pela `X-HookGuard-Event-Id`.

## 5. Falhas, DLQ e replay

- `5xx` / timeout / rede → retry com backoff
- `4xx` (exceto 408/429) → vai para `dead`
- No painel: abra o evento e clique em **Replay**

## 6. Planos e billing

| Plano     | Incluso / mês | Overage      |
|-----------|---------------|--------------|
| Free      | 5.000         | bloqueia     |
| Pro       | 100.000       | medido       |
| Business  | 1.000.000     | medido       |

No painel → Billing → Assinar Pro/Business (Stripe Checkout) ou abrir o portal do cliente.

Configure no ambiente:

- `STRIPE_API_KEY`
- `STRIPE_WEBHOOK_SECRET` (endpoint `POST /v1/billing/stripe/webhook`)
- `STRIPE_PRO_PRICE_ID` / `STRIPE_BUSINESS_PRICE_ID`
- `STRIPE_METER_EVENT_NAME` (opcional, para overage)

## 7. Demo gratuita

Sem VPS, domínio, Stripe ou SMTP: veja [`FREE.md`](FREE.md) e rode `./scripts/free-demo.sh`.

## 8. Deploy produção

```bash
cp .env.example .env
# preencha HOOKGUARD_DOMAIN, senha do DB, SMTP, Stripe, GitHub OAuth

docker compose -f docker-compose.prod.yml up -d --build
```

O Caddy emite HTTPS para `HOOKGUARD_DOMAIN`.

Callback GitHub OAuth (API):

```text
https://SEU_DOMINIO/v1/auth/github/callback
```
