#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${STRIPE_API_KEY:-}" ]]; then
  echo "Defina STRIPE_API_KEY no ambiente (chave de teste, sk_test_...) e rode de novo."
  exit 1
fi

# HOOKGUARD_STRIPE_API_BASE só serve para ensaiar o script contra um substituto local.
API_BASE="${HOOKGUARD_STRIPE_API_BASE:-https://api.stripe.com}"

api() {
  local method="$1"
  local path="$2"
  shift 2
  # A chave vai por configuração lida da entrada padrão: não aparece na linha de comando (ps) nem na saída.
  curl -sS -X "$method" "${API_BASE}/v1${path}" -K - "$@" <<<"user = \"${STRIPE_API_KEY}:\""
}

# Lê a resposta e imprime só o id. Em erro, mostra apenas tipo e código do Stripe, nunca o corpo nem a chave.
id_of() {
  python3 -I -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit("resposta inesperada do Stripe (nao e JSON)")
if "id" not in d:
    e = d.get("error", {}) if isinstance(d, dict) else {}
    sys.exit("o Stripe recusou o pedido: %s/%s" % (e.get("type", "?"), e.get("code", "?")))
print(d["id"])
'
}

echo "==> Criando produtos HookGuard (Pro / Business)"

PRO_PRODUCT="$(api POST /products \
  -d "name=HookGuard Pro" \
  -d "description=100.000 eventos/mês + overage" \
  | id_of)"

BUSINESS_PRODUCT="$(api POST /products \
  -d "name=HookGuard Business" \
  -d "description=1.000.000 eventos/mês + overage" \
  | id_of)"

PRO_PRICE="$(api POST /prices \
  -d "product=${PRO_PRODUCT}" \
  -d "unit_amount=4900" \
  -d "currency=brl" \
  -d "recurring[interval]=month" \
  | id_of)"

BUSINESS_PRICE="$(api POST /prices \
  -d "product=${BUSINESS_PRODUCT}" \
  -d "unit_amount=19900" \
  -d "currency=brl" \
  -d "recurring[interval]=month" \
  | id_of)"

echo
echo "STRIPE_API_KEY: já está configurada no ambiente (o valor não é exibido; coloque-a você mesmo no .env)."
echo "Identificadores (não secretos) para o .env do HookGuard:"
echo
echo "STRIPE_PRO_PRICE_ID=${PRO_PRICE}"
echo "STRIPE_BUSINESS_PRICE_ID=${BUSINESS_PRICE}"
echo
echo "Webhook (quando tiver URL pública):"
echo "  Endpoint: https://SEU_DOMINIO/v1/billing/stripe/webhook"
echo "  Eventos: customer.subscription.created, customer.subscription.updated, customer.subscription.deleted"
echo "  Depois: STRIPE_WEBHOOK_SECRET=whsec_..."
echo
echo "Preços criados: Pro R\$49/mês · Business R\$199/mês (ajuste no Dashboard se quiser)."
