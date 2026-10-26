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
| Login | Magic link por e-mail, lido no Mailpit local (o link nunca volta pela API) |
| Stripe / GitHub | Opcional — plano Free funciona sem eles |
| Domínio próprio | Não precisa |

Como entrar: informe o e-mail no painel e abra o Mailpit em `http://localhost:18025` (publicado só em `127.0.0.1`; não passa pelo Caddy nem pelo túnel, então só quem está no seu PC o lê). A mensagem traz o link de acesso. A API não devolve o link nem o token na resposta, porque a demo tem túnel público e isso permitiria entrar em qualquer conta só informando o e-mail. Não há opção de exposição neste Compose; para desenvolver com o link na resposta, rode a API localmente com `HOOKGUARD_EXPOSE_MAGIC_LINK=true` (ver README), sem túnel.

## Limites

- O PC precisa ficar ligado enquanto o túnel estiver no ar
- A URL do túnel muda a cada `restart` do serviço `tunnel`
- Não é produção estável; serve para demo e testes com webhooks reais
- A demo pública não entrega para HTTP nem para rede privada. O destino precisa ser `https` público
- O magic link do túnel só usa host terminado em `trycloudflare.com`

## Quando tiver orçamento mínimo

1. Conta Stripe (modo teste é grátis; cobrança real só depois)
2. Domínio barato + VPS (~US$ 4–6/mês) ou Oracle Cloud Always Free
3. SMTP gratuito (Resend/Brevo free tier)
4. `docker compose -f docker-compose.prod.yml up -d --build`
