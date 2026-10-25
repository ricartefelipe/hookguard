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

### Autenticidade da origem

A `projectKey` é uma credencial de capacidade: quem a conhece consegue enviar eventos. Para provar que o payload veio do provedor, configure o modo do projeto (`PUT /v1/projects/{id}/source-auth`, corpo `{"mode","secret","toleranceSeconds"}`; o segredo nunca é devolvido):

| Modo | O que é verificado |
| --- | --- |
| `none` (padrão) | nada além da `projectKey`. **A origem não é verificada.** |
| `stripe` | header `Stripe-Signature`: HMAC-SHA256 de `t.corpo` com o segredo do endpoint (`whsec_...`), tolerância de timestamp (padrão 300 s, 30–3600). A mesma assinatura repetida dentro da janela devolve o mesmo evento, sem novo evento nem uso. |
| `github` | header `X-Hub-Signature-256` (`sha256=` + HMAC-SHA256 do corpo). O GitHub não envia timestamp: não há proteção contra repetição além do dedupe. |
| `generic_hmac` | header `X-Signature-256`, mesmo formato. O emissor é quem define o segredo e responde por ele; não é validação de um provedor. |

Só esses provedores são verificados nesta entrega; outro formato deve usar `none` ou `generic_hmac`. A verificação usa os bytes originais, ocorre antes de gravar ou contar o evento e responde `401 signature_missing`, `signature_invalid` ou `signature_expired`. Modo autenticado sem segredo utilizável responde `503 source_auth_misconfigured`, nunca aceita em silêncio. Cada projeto tem o seu segredo; a troca de segredo não tem janela de sobreposição. Este segredo é independente da assinatura de saída (`X-HookGuard-Signature`).

### Limite de taxa

`HOOKGUARD_INGEST_RATE_LIMIT` (padrão 120) é por projeto, em janela fixa de 60 s contada no Postgres, portanto vale somando todas as instâncias (na virada da janela é possível receber até o dobro em segundos). É checado antes de ler o corpo e conta também requisições rejeitadas. Excedido: `429 rate_limited` com `Retry-After`. Se o Postgres não responder: `503 rate_limiter_unavailable` (falha fechada). `HOOKGUARD_INGEST_MAX_CONCURRENT` (padrão 64) limita requisições de ingestão em andamento por instância (`503 ingest_busy`). Isso é diferente da cota mensal e do dedupe.

## 4. Validar entrega no seu app

O HookGuard faz POST no destino com o body original, byte a byte, e apenas estes headers de origem: assinaturas e identificadores de provedor em allowlist (`Stripe-Signature`, `X-Hub-Signature-256`, headers Shopify/PayPal/Svix/Slack e equivalentes). `Authorization`, `Cookie`, `X-Api-Key` e headers `X-HookGuard-*` recebidos na ingestão não são armazenados nem encaminhados.

Headers da plataforma, sempre gerados na entrega:

- `X-HookGuard-Event-Id` — chave de idempotência do consumidor
- `X-HookGuard-Job-Id` — tentativa lógica (muda no replay)
- `X-HookGuard-Attempt`
- `X-HookGuard-Timestamp` — unix seconds
- `X-HookGuard-Signature` — `t=<unix>,v1=<hex>` e, durante a janela de rotação, um segundo `v1`

O HMAC-SHA256 usa o segredo do endpoint sobre os bytes `timestamp + "." + eventId + "." + body`. Durante `signature-rotation-hours` (padrão 24) o segredo anterior também assina. O segredo completo só aparece na criação ou na rotação.

Seu endpoint deve responder `2xx` e ser idempotente pela `X-HookGuard-Event-Id`. `3xx` não é seguido. Respostas informativas `1xx` são ignoradas até a resposta final (no máximo 8). Resposta truncada, `chunked` inválido ou cabeçalhos ambíguos contam como falha de rede, nunca como sucesso. O prazo de `timeoutMs` vale para a operação inteira (DNS, conexão, TLS, escrita e leitura), inclusive resposta em gotejamento. URLs IPv6 literais (`https://[2606:4700::1111]/`) são aceitas se públicas. `408`, `429` e `5xx` geram retry; os demais `4xx` vão para `dead`. `Retry-After` atrasa a próxima tentativa, com teto de 6 horas, sem encurtar o backoff mínimo.

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

A cota free estourada responde `429` com `free_quota_exceeded`. Retry e replay não incrementam o uso: a contagem é por evento aceito. Overage de plano pago, quando `STRIPE_METER_EVENT_NAME` está definido, é gravado num ledger (`usage_outbox`) na mesma transação que conta o evento e enviado ao Stripe por um processo à parte, com identificador igual ao id do evento. Detalhes e reconciliação em [`STRIPE.md`](STRIPE.md). `GET /v1/billing/usage-reports` mostra o estado.

Retenção padrão: 14 dias (`hookguard.retention.days`). Corpo e headers ficam no Postgres da instalação. Não há criptografia por cliente nesta versão; o disco e o backup são responsabilidade de quem opera. A prévia do corpo e o trecho de resposta do destino são mascarados antes de serem cortados: em JSON válido (e formulário `x-www-form-urlencoded`) todo valor — texto, número, lista ou objeto — de campos sensíveis (`password`, `token`, `secret`, `api_key`, `cvv`, `card`, `authorization` etc., inclusive nomes escritos com escapes) é ocultado. JSON inválido, truncado ou de outro tipo não é exibido cru: a prévia do corpo mostra um aviso de omissão. O trecho de resposta do destino só é guardado se for JSON (válido, ou com aparência de JSON cortado, que passa por um varredor tolerante); HTML, texto livre e XML são substituídos por um aviso de omissão, e linhas guardadas por versões anteriores que não sejam JSON são omitidas na exibição. Mesmo assim, nenhuma varredura garante a remoção de todo segredo em texto livre embutido em campos não sensíveis. Os bytes entregues e assinados não mudam. Credenciais de origem não entram no banco.

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
