# Stripe (HookGuard)

## Já configurado (test mode)

Produtos criados via `scripts/stripe-setup.sh`:

| Plano | Preço | Price ID (exemplo) |
|-------|-------|--------------------|
| Pro | R$ 49/mês | `STRIPE_PRO_PRICE_ID` no `.env` |
| Business | R$ 199/mês | `STRIPE_BUSINESS_PRICE_ID` no `.env` |

Coloque no `.env` local (nunca commitar):

```bash
STRIPE_API_KEY=sk_test_...
STRIPE_PRO_PRICE_ID=price_...
STRIPE_BUSINESS_PRICE_ID=price_...
```

## Webhook

A origem confiável é a assinatura Stripe, não o IP de quem chamou. O endpoint `POST /v1/billing/stripe/webhook` só aceita o corpo se `Stripe-Signature` validar com `STRIPE_WEBHOOK_SECRET`. Sem segredo configurado a API responde `503 stripe_not_configured`. Assinatura inválida responde `400 invalid_stripe_signature`.

Eventos aplicados: `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`. `checkout.session.completed` não altera o plano por si só.

Com URL pública (túnel ou domínio):

1. Stripe Dashboard → Developers → Webhooks → Add endpoint
2. URL: `https://SEU_HOST/v1/billing/stripe/webhook`
3. Eventos: `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`
4. Copie `whsec_...` → `STRIPE_WEBHOOK_SECRET` no `.env`

Os preços da tabela acima são exemplo do script de setup, não a oferta pública fechada nesta entrega. Quem vende define o preço.

## Reporte de uso excedente (meter events)

Contrato usado (conferido na documentação do Stripe): `POST /v1/billing/meter_events`, corpo `application/x-www-form-urlencoded` com `event_name`, `payload[stripe_customer_id]`, `payload[value]`, `identifier` e `timestamp`. O Stripe garante unicidade do `identifier` numa janela móvel de **pelo menos 24 h** (não é garantia perpétua), aceita `timestamp` até 35 dias no passado e limita a 1000 chamadas/s e uma chamada concorrente por cliente/medidor (429). O processamento é assíncrono: eventos inválidos aparecem depois em `v1.billing.meter.error_report_triggered`. (Versões anteriores chamavam `/v2/billing/meter_events` com corpo de formulário, que não é o contrato do v1.)

Funcionamento:

1. Na mesma transação que conta o evento (plano pago acima do incluso) grava-se uma linha em `usage_outbox` com `identifier` = id do evento (única por evento: replay e dedupe não criam outra). Rollback da ingestão apaga a intenção; commit a preserva mesmo se o processo morrer.
2. Um processo à parte (`UsageOutboxWorker`, mesmo `HOOKGUARD_WORKER_ENABLED`) reserva linhas com `FOR UPDATE SKIP LOCKED` e lease, envia e registra o resultado. A deduplicação é pelo `identifier` do meter event. **Não** se envia `Idempotency-Key`: na API v1 o Stripe guarda e devolve a primeira resposta de uma chave, inclusive `500`, então reenviar com a mesma chave nunca sairia do erro dentro de 24 h. Os horários de retry são calculados pelo relógio do banco.
3. Estados: `pending` → `sent`; `failed` (rejeição 4xx, falta de credencial após 20 tentativas, ou intenção com mais de 30 dias); `review`.
4. `429` e `401/403`: nada foi processado, tenta de novo com backoff (1 min a 1 h). `5xx`, timeout e erro de rede são **ambíguos**: reenvia com o mesmo identifier, mas só até 23 h após a primeira tentativa; depois disso vira `review`, porque o Stripe poderia não reconhecer o identifier e cobrar duas vezes. Uma recusa 4xx depois de uma tentativa ambígua também vai para `review` (pode ser apenas o Stripe apontando identifier repetido, o que significaria que a primeira tentativa foi registrada); o código de erro fica em `last_error` (`http_400:<code>`), sem o texto da mensagem. Linha em `sending` com lease vencido é reassumida e marcada ambígua.
5. Sem `STRIPE_API_KEY`/`STRIPE_METER_EVENT_NAME` nada é enviado e as intenções ficam `pending`; conta sem `stripe_customer_id` espera.

Reconciliação (manual): o Stripe **não** permite consultar um meter event pelo `identifier`; só expõe agregados (`GET /v1/billing/meters/{id}/event_summaries`, por cliente e intervalo alinhado a minuto), processados de forma assíncrona. Um agregado **não prova** que um evento individual foi contabilizado: ele só é comparável se o medidor/cliente/período tem uma única fonte (esta aplicação) e a agregação já terminou. Por isso nada é reenviado nem encerrado automaticamente, e linhas `review`/`failed` ficam como estão até uma decisão humana.

1. Veja o estado: `GET /v1/billing/usage-reports` ou `SELECT state, count(*) FROM usage_outbox GROUP BY state`.
2. Espere alguns minutos e rode, somente leitura, `STRIPE_API_KEY=... PG*=... scripts/usage-reconcile.sh <meter_id> <customer_id> <start_epoch> <end_epoch>`. A saída é um indício, em uma destas categorias: *inconsistente* (Stripe com menos que `sent`, ou com mais do que `sent`+`review` explicam) — não altere nada; *compatível com todas registradas* ou *compatível com nenhuma registrada* — indício, não prova; *parcial* — a contagem não diz quais, mantenha todas em `review`.
3. O que a ferramenta **permite** concluir: que há ou não uma divergência de quantidade a investigar. O que **não** permite: que um evento específico foi ou não cobrado; que é seguro reenviar depois da janela de 24 h do `identifier` (o Stripe deixa de deduplicar e um reenvio equivocado cobra em duplicidade); ou que um `UPDATE` em massa é correto. Só o operador, com a confirmação de fonte única e ciência do risco de cobrança dupla, decide marcar como `sent` (`UPDATE usage_outbox SET state='sent', sent_at=now() WHERE event_id=...`) ou reenfileirar (`state='pending', ambiguous=false, attempts=0, first_attempt_at=NULL, next_attempt_at=now()`). Sem essa evidência o caso permanece pendente de decisão.
4. Eventos com mais de 35 dias não são aceitos pelo Stripe (`failed`, `timestamp_window_exceeded`) e exigem ajuste de fatura decidido pelo proprietário.

O código nunca reenvia uma tentativa ambígua depois de 23 h (vira `review`), nem uma recusa 4xx depois de tentativa ambígua.

Para uma alternativa menos manual, o Stripe também notifica eventos inválidos (`v1.billing.meter.error_report_triggered`); esta entrega não consome essas notificações.

Homologação em sandbox — **pendente de credenciais do proprietário** (os testes JUnit usam um substituto local do endpoint: timeout após aceite, 429, 5xx, recusa e concorrência são simulações). Roteiro executável: `scripts/stripe-sandbox-homologation.sh`. Ele sobe Postgres, API e destino locais e descartáveis, recusa chave que não seja de teste, cria uma conta sintética em plano pago no limite incluso, ingere 3 eventos identificados (`homolog-1..3`), confere no banco 3 linhas `sent`, faz login pelo magic link (na stack local descartável do roteiro o link volta na resposta; um coletor SMTP local recebe o e-mail), executa um replay pela API autenticada (`POST /v1/events/{id}/replay`) e confere que ele é entregue sem criar intenção de cobrança nem alterar `usage_monthly` e consulta o agregado do Stripe com espera limitada (padrão 300 s, configurável). Saídas: `0` confirmado (agregado subiu exatamente 3), `3` inconclusivo (o Stripe ainda não refletiu: não reenvie, repita a consulta depois com `scripts/usage-reconcile.sh`), `1` falha real (estado local ou excesso no agregado). O agregado prova quantidade, não o resultado de cada evento.

Requisitos, via variáveis de ambiente (nunca em arquivos versionados nem na linha de comando):

```bash
export STRIPE_API_KEY=...            # sk_test_... ou rk_test_... com escrita em billing meter events e leitura de meters
export STRIPE_METER_EVENT_NAME=...   # event_name do medidor de teste
export STRIPE_TEST_METER_ID=mtr_...  # medidor de teste
export STRIPE_TEST_CUSTOMER_ID=cus_...  # cliente de teste usado só por este roteiro
./scripts/stripe-sandbox-homologation.sh
```

Pré-requisitos no Stripe (modo teste): um medidor com agregação `sum` sobre `value` e `event_name` igual a `STRIPE_METER_EVENT_NAME`, e um cliente de teste. Não induza falhas no Stripe. O roteiro foi ensaiado apenas contra um substituto local do Stripe (`HOOKGUARD_STRIPE_API_BASE`); a execução contra o sandbox real não foi feita.

## Cartões de teste

- Sucesso: `4242 4242 4242 4242`
- Qualquer data futura / CVC

## Segurança

Se uma secret key vazou (chat, print, etc.), revogue em Dashboard → API keys → Roll key.
