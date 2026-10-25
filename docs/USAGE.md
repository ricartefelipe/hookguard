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

O HookGuard faz POST no destino com o body original, byte a byte, e apenas estes headers de origem: assinaturas e identificadores de provedor em allowlist (`Stripe-Signature`, `X-Hub-Signature-256`, headers Shopify/PayPal/Svix/Slack e equivalentes). `Authorization`, `Cookie`, `X-Api-Key` e headers `X-HookGuard-*` recebidos na ingestão não são armazenados nem encaminhados.

Headers da plataforma, sempre gerados na entrega:

- `X-HookGuard-Event-Id` — chave de idempotência do consumidor
- `X-HookGuard-Job-Id` — tentativa lógica (muda no replay)
- `X-HookGuard-Attempt`
- `X-HookGuard-Timestamp` — unix seconds
- `X-HookGuard-Signature` — `t=<unix>,v1=<hex>` e, durante a janela de rotação, um segundo `v1`

O HMAC-SHA256 usa o segredo do endpoint sobre os bytes `timestamp + "." + eventId + "." + body`. Durante `signature-rotation-hours` (padrão 24) o segredo anterior também assina. O segredo completo só aparece na criação ou na rotação.

Seu endpoint deve responder `2xx` e ser idempotente pela `X-HookGuard-Event-Id`. `3xx` não é seguido. `408`, `429` e `5xx` geram retry; os demais `4xx` vão para `dead`. `Retry-After` atrasa a próxima tentativa, com teto de 6 horas, sem encurtar o backoff mínimo.

## 5. Falhas, DLQ e replay

- `5xx` / timeout / rede → retry com backoff e jitter
- `4xx` (exceto 408/429) → vai para `dead`
- Duas instâncias não pegam o mesmo job: o claim usa `FOR UPDATE SKIP LOCKED` e a chamada HTTP fica fora da transação. Se o processo morrer depois do claim, o lease vencido volta para a fila.
- Dedupe, quando o header do projeto está configurado: namespace do projeto, fingerprint SHA-256 do body e TTL (padrão 24 h). A mesma chave com o mesmo body devolve o evento original e não cobra de novo. Chave igual e body diferente responde `409 dedupe_payload_conflict`.
- No painel: abra o evento e informe o motivo do **Replay**. Replay não apaga tentativas, não cobra outro evento e é recusado com `409 replay_already_queued` enquanto já existe entrega pendente. Outro tenant recebe `404`.
- Pausar o projeto adia a entrega sem descartar o evento. Excluir o projeto remove eventos retidos daquela conta.

## 6. Planos, retenção e billing

| Plano     | Incluso / mês | Overage      |
|-----------|---------------|--------------|
| Free      | 5.000         | bloqueia     |
| Pro       | 100.000       | medido       |
| Business  | 1.000.000     | medido       |

No painel → Billing → Assinar Pro/Business (Stripe Checkout) ou abrir o portal do cliente.

A cota free estourada responde `429` com `free_quota_exceeded`. Retry e replay não incrementam o uso: a contagem é por evento aceito. Overage de plano pago, quando `STRIPE_METER_EVENT_NAME` está definido, é reportado depois do commit, com identificador igual ao id do evento.

Retenção padrão: 14 dias (`hookguard.retention.days`). Corpo e headers ficam no Postgres da instalação. Não há criptografia por cliente nesta versão; o disco e o backup são responsabilidade de quem opera. A prévia no painel mascara campos como `password`, `token` e `api_key`. Credenciais de origem não entram no banco.

Destinos: somente `https` em produção. `HOOKGUARD_DELIVERY_ALLOW_HTTP=true` existe para desenvolvimento e aceita loopback/rede privada, mas continua bloqueando link-local, metadata (`169.254.169.254`, `metadata.google.internal`) e URL com usuário/senha. A resolução usada na conexão é a mesma política; redirect não é seguido.

Limites padrão: corpo 1 MiB (`HOOKGUARD_MAX_BODY_BYTES`), resposta lida até 64 KiB, timeout de destino entre 500 ms e 30 s.

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
