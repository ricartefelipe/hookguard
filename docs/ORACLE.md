# HookGuard na Oracle Cloud Always Free (24/7, sem gastar)

Caminho sem domínio próprio: VM Oracle (ARM) + Docker + túnel Cloudflare HTTPS.

## 0. No seu PC (já feito se você tem chave SSH)

Chave pública para colar na Oracle:

```bash
cat ~/.ssh/id_ed25519.pub
```

## 1. Criar conta Oracle (você faz no navegador)

1. Abra: https://www.oracle.com/cloud/free/
2. **Start for free** → país Brasil → complete cadastro.
3. Pedem cartão para verificação; no Always Free **não deve cobrar** se você só usar o free tier.
4. Escolha região com capacidade Ampere (ex.: `sa-saopaulo-1` ou outra disponível). Se A1 falhar por capacidade, tente outra home region / horário.

## 2. Criar a VM Ampere

Console → **Compute** → **Instances** → **Create instance**

| Campo | Valor |
|-------|--------|
| Name | `hookguard` |
| Image | **Ubuntu 22.04** ou **24.04** (aarch64) |
| Shape | **VM.Standard.A1.Flex** (Ampere) |
| OCPUs | 2 (ou 4 se couber no free) |
| Memory | 12 GB (ou 24 GB) |
| Networking | VCN default + **Assign public IPv4** |
| SSH keys | cole o conteúdo de `~/.ssh/id_ed25519.pub` |

**Create**. Anote o **Public IP**.

## 3. Abrir portas no Security List

VCN → Subnet → **Security Lists** → Ingress:

| Source | Protocol | Port |
|--------|----------|------|
| 0.0.0.0/0 | TCP | 22 |
| 0.0.0.0/0 | TCP | 80 |

(443 só precisa se depois usar domínio + Caddy sem túnel.)

## 4. Bootstrap na VM

No seu PC:

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@SEU_IP_PUBLICO
```

Na VM:

```bash
curl -fsSL https://raw.githubusercontent.com/ricartefelipe/hookguard/develop/scripts/oracle-bootstrap.sh | bash
exit
ssh -i ~/.ssh/id_ed25519 ubuntu@SEU_IP_PUBLICO
```

## 5. Subir o HookGuard

Na VM (repo privado — use SSH deploy key ou clone com sua chave):

```bash
git clone git@github.com:ricartefelipe/hookguard.git
cd hookguard
git checkout develop
cp env.oracle.example .env
nano .env   # troque HOOKGUARD_DB_PASSWORD
chmod +x scripts/oracle-up.sh
./scripts/oracle-up.sh
```

O script imprime a URL `https://….trycloudflare.com`. Magic link aparece na tela (`EXPOSE_MAGIC_LINK=true`).

**Importante:** o build roda **na VM ARM**. Não copie JAR/imagem x86 do seu notebook.

## 6. Depois (quando quiser)

| Recurso | Ação |
|---------|------|
| Domínio | Registro.br / Cloudflare → DNS para o IP → `docker-compose.prod.yml` |
| E-mail real | Resend/Brevo free → SMTP no `.env`, `EXPOSE_MAGIC_LINK=false` |
| Stripe | Conta + prices → variáveis Stripe |
| Túnel estável | Cloudflare named tunnel (URL fixa) em vez do quick tunnel |

## Problemas comuns

- **Out of capacity (A1):** mudar AD/região ou tentar de novo mais tarde; ou VM AMD micro (1 GB — apertada para este stack).
- **SSH timeout:** liberar porta 22 no Security List + usar IP público correto.
- **Imagem Docker errada (exec format):** rebuild na ARM (`docker compose … up -d --build`).
