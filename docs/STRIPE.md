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

Com URL pública (túnel ou domínio):

1. Stripe Dashboard → Developers → Webhooks → Add endpoint  
2. URL: `https://SEU_HOST/v1/billing/stripe/webhook`  
3. Eventos: `checkout.session.completed`, `customer.subscription.updated`, `customer.subscription.deleted`  
4. Copie `whsec_...` → `STRIPE_WEBHOOK_SECRET` no `.env`

## Cartões de teste

- Sucesso: `4242 4242 4242 4242`
- Qualquer data futura / CVC

## Segurança

Se uma secret key vazou (chat, print, etc.), revogue em Dashboard → API keys → Roll key.
