# Demo gratuita (sem VPS, domínio, Stripe ou SMTP)

Roda tudo no seu PC e expõe HTTPS público via túnel Cloudflare (URL temporária).

## Subir

```bash
./scripts/free-demo.sh
```

Ou:

```bash
docker compose -f docker-compose.free.yml up -d --build
docker compose -f docker-compose.free.yml logs -f tunnel
```

## O que você ganha

| Item | Como |
|------|------|
| Hospedagem | Docker no seu PC |
| HTTPS público | `*.trycloudflare.com` (túnel) |
| Login | Magic link na tela (sem e-mail real) |
| Stripe / GitHub | Opcional — plano Free funciona sem eles |
| Domínio próprio | Não precisa |

Mailpit opcional: `http://localhost:18025` (o magic link já aparece na tela).

## Limites

- O PC precisa ficar ligado enquanto o túnel estiver no ar
- A URL do túnel muda a cada `restart` do serviço `tunnel`
- Não é produção estável; serve para demo e testes com webhooks reais

## Quando tiver orçamento mínimo

1. Conta Stripe (modo teste é grátis; cobrança real só depois)
2. Domínio barato + VPS (~US$ 4–6/mês) ou Oracle Cloud Always Free
3. SMTP gratuito (Resend/Brevo free tier)
4. `docker compose -f docker-compose.prod.yml up -d --build`
