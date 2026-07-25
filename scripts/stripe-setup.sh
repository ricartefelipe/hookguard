#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${STRIPE_API_KEY:-}" ]]; then
  echo "Exporte STRIPE_API_KEY=sk_test_... (ou sk_live_...) e rode de novo."
  exit 1
fi

api() {
  local method="$1"
  local path="$2"
  shift 2
  curl -sS -X "$method" "https://api.stripe.com/v1${path}" \
    -u "${STRIPE_API_KEY}:" \
    "$@"
}

echo "==> Criando produtos HookGuard (Pro / Business)"

PRO_PRODUCT="$(api POST /products \
  -d "name=HookGuard Pro" \
  -d "description=100.000 eventos/mês + overage" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"

BUSINESS_PRODUCT="$(api POST /products \
  -d "name=HookGuard Business" \
  -d "description=1.000.000 eventos/mês + overage" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"

PRO_PRICE="$(api POST /prices \
  -d "product=${PRO_PRODUCT}" \
  -d "unit_amount=4900" \
  -d "currency=brl" \
  -d "recurring[interval]=month" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"

BUSINESS_PRICE="$(api POST /prices \
  -d "product=${BUSINESS_PRODUCT}" \
  -d "unit_amount=19900" \
  -d "currency=brl" \
  -d "recurring[interval]=month" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"

echo
echo "Cole no .env do HookGuard:"
echo
echo "STRIPE_API_KEY=${STRIPE_API_KEY}"
echo "STRIPE_PRO_PRICE_ID=${PRO_PRICE}"
echo "STRIPE_BUSINESS_PRICE_ID=${BUSINESS_PRICE}"
echo
echo "Webhook (quando tiver URL pública):"
echo "  Endpoint: https://SEU_DOMINIO/v1/billing/stripe/webhook"
echo "  Eventos: checkout.session.completed, customer.subscription.updated, customer.subscription.deleted"
echo "  Depois: STRIPE_WEBHOOK_SECRET=whsec_..."
echo
echo "Preços criados: Pro R\$49/mês · Business R\$199/mês (ajuste no Dashboard se quiser)."
