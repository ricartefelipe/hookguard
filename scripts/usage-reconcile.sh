#!/usr/bin/env bash
# Conciliacao SOMENTE LEITURA do uso reportado ao Stripe para um cliente e um periodo.
# Compara o agregado do medidor no Stripe (GET /v1/billing/meters/{id}/event_summaries) com a
# contagem local de usage_outbox. O Stripe nao permite consultar um meter event por identifier; so ha
# agregado, entao a decisao e por quantidade: quantas linhas em review ainda faltam no Stripe.
#
# Uso:  STRIPE_API_KEY=sk_test_... PGHOST=... PGUSER=... PGDATABASE=... PGPASSWORD=... \
#       scripts/usage-reconcile.sh <meter_id> <stripe_customer_id> <start_epoch> <end_epoch>
# start/end: inicio inclusivo e fim exclusivo, alinhados a minuto. Use o mesmo intervalo de
# occurred_at (as linhas enviam occurred_at como timestamp). Aguarde alguns minutos apos o ultimo
# envio: o Stripe agrega de forma assincrona. Nada e alterado, nem aqui nem no Stripe.
set -euo pipefail
[[ $# -eq 4 ]] || { sed -n 2,12p "$0" >&2; exit 2; }
: "${STRIPE_API_KEY:?defina STRIPE_API_KEY no ambiente (nao passe como argumento)}"
METER="$1"; CUSTOMER="$2"; START="$3"; END="$4"
(( START % 60 == 0 && END % 60 == 0 )) || { echo "start/end precisam ser multiplos de 60" >&2; exit 2; }

AGG="$(curl -sf -G "https://api.stripe.com/v1/billing/meters/${METER}/event_summaries" \
  -u "${STRIPE_API_KEY}:" -d customer="$CUSTOMER" -d start_time="$START" -d end_time="$END" -d limit=100 \
  | python3 -I -c 'import json,sys; d=json.load(sys.stdin)
if d.get("has_more"): sys.exit("resposta paginada: reduza o intervalo")
print(sum(int(x["aggregated_value"]) for x in d["data"]))')"

count() { psql -tA -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM usage_outbox o JOIN accounts a ON a.id=o.account_id
  WHERE a.stripe_customer_id='${CUSTOMER}' AND o.state='$1'
    AND o.occurred_at >= to_timestamp(${START}) AND o.occurred_at < to_timestamp(${END})"; }
SENT="$(count sent)"; REVIEW="$(count review)"; FAILED="$(count failed)"; PENDING="$(count pending)"

echo "Stripe (agregado do periodo): ${AGG}"
echo "Local: sent=${SENT} review=${REVIEW} failed=${FAILED} pending=${PENDING}"
DELTA=$(( AGG - SENT ))
echo
echo "AVISO: o agregado do Stripe mostra quantos eventos existem, nao quais. Ele nao prova que um evento"
echo "individual foi contabilizado, e so e comparavel se este medidor/cliente/periodo tem UMA unica fonte"
echo "(esta aplicacao) e a agregacao do Stripe ja terminou. Nada e alterado por este script."
echo
if (( REVIEW == 0 && FAILED == 0 )); then
  if (( DELTA == 0 )); then echo "Resultado: sem linhas em review/failed e totais iguais. Nada a decidir."
  else echo "Resultado: sem linhas em review/failed, mas o Stripe difere de 'sent' em ${DELTA}. Investigue (outras fontes, atraso, periodo); nao e ajustavel por esta ferramenta."; fi
  exit 0
fi
if (( DELTA < 0 )); then
  echo "Resultado: INCONSISTENTE. O Stripe tem menos que as linhas 'sent' (${SENT}). Nao altere nada; investigue periodo, medidor, cliente e atraso de agregacao."
elif (( DELTA > REVIEW )); then
  echo "Resultado: INCONSISTENTE. O Stripe tem mais eventos do que 'sent'+'review' explicam (sobram $(( DELTA - REVIEW ))). Ha outra fonte ou duplicidade; nao reenvie nada."
elif (( DELTA == REVIEW )); then
  echo "Resultado: COMPATIVEL COM 'todas as ${REVIEW} linhas de review foram registradas'. Isto e indicio, nao prova. Se voce confirmou que esta e a unica fonte, a decisao de marca-las como sent e sua; a ferramenta nao a toma."
elif (( DELTA == 0 )); then
  echo "Resultado: COMPATIVEL COM 'nenhuma das ${REVIEW} linhas de review foi registrada'. Isto e indicio, nao prova. Reenviar, depois da janela de 24 h do identifier, e uma decisao sua: se estiver errada, o cliente e cobrado em duplicidade."
else
  echo "Resultado: PARCIAL. ${DELTA} de ${REVIEW} linhas de review parecem registradas, mas a contagem nao diz quais. Sem evidencia suficiente: mantenha TODAS em review e decida com o responsavel pela cobranca."
fi
echo "Linhas 'failed' (${FAILED}) e 'pending' (${PENDING}) nao entram na comparacao. Linhas em review permanecem em review ate uma decisao humana registrada."
