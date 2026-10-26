# Operação

## Instalação

Desenvolvimento, com Postgres e Mailpit locais:

```bash
docker compose up -d
cp .env.example .env
cd apps/api && mvn spring-boot:run
cd apps/web && npm install && npm run dev
```

Produção usa `docker-compose.prod.yml`. `HOOKGUARD_DELIVERY_ALLOW_HTTP` fica `false`. Não publique `HOOKGUARD_EXPOSE_MAGIC_LINK=true`. O proxy público só encaminha `/v1/*`, `/actuator/health*` e `/actuator/info*`. Prometheus continua no processo, fora do hostname público. O provisionamento interno não entra no proxy público.

`HOOKGUARD_TRUST_FORWARDED_HOST` só tem efeito com `HOOKGUARD_FORWARDED_HOST_SUFFIX` (por exemplo `trycloudflare.com`). Sem o sufixo, o link de login usa `HOOKGUARD_APP_BASE_URL`.

## Semântica que o operador precisa repetir

- Aceite é durável: `202` só sai depois de gravar o evento e o job. Falha de banco não vira aceite.
- Entrega é at-least-once. O consumidor deduplica por `X-HookGuard-Event-Id`.
- HTTP de entrega roda fora da transação. O claim usa `FOR UPDATE SKIP LOCKED` e um token de lease. Settle só grava se o token ainda for o do worker.
- Cada job é reservado imediatamente antes do envio (nunca um lote inteiro de uma vez), e o lease é renovado com o token logo antes do HTTP. O prazo total do HTTP é menor que o lease (`lock-timeout - 5 s`), então um envio iniciado não sobrevive ao seu lease; o worker que perdeu a posse não envia. Lease vencido (`hookguard.worker.lock-timeout-seconds`, padrão 120) só é recuperado se continuar vencido no momento do update; volta para a fila e a tentativa aberta fica com `lease_expired`. Os relógios das instâncias devem estar sincronizados com margem muito menor que 5 s. O tempo gasto entre a renovação e o início do HTTP sai do orçamento do envio; se restar menos de 1 s, o envio não começa e o lease expira normalmente. O que isso **não** elimina: uma pausa do processo (por exemplo parada longa de GC ou suspensão da VM) que ocorra com a requisição já em andamento pode durar mais que o lease; outro worker então reenvia, e o resultado do primeiro é descartado no settle. Duplicidade continua possível e o destino deve ser idempotente pelo `X-HookGuard-Event-Id`. O cliente usa o primeiro endereço validado da resolução (sem tentar os demais), de modo que o prazo total cobre uma única conexão.
- Se o worker morrer depois que o destino recebeu e antes do settle, o evento é reenviado (at-least-once): a segunda tentativa tem o mesmo `X-HookGuard-Event-Id`.
- Um evento tem no máximo um job aberto (índice único parcial); replays simultâneos recebem `409 replay_already_queued`. Teto total de 50 tentativas por evento, aplicado no replay e no claim.
- Corpo máximo 1 MiB. Resposta lida no máximo 64 KiB. Timeout de destino 500–30000 ms, sempre menor que o lease.

## Backup e restore

O estado está no Postgres: contas, projetos, eventos, jobs, tentativas, dedupe e uso mensal. Não há blob store separado.

```bash
./scripts/backup-restore-drill.sh
```

O script sobe Postgres e coletor de e-mail descartáveis, cria pela API conta, projetos, eventos com dedupe, entrega, falha com retry pendente e intenção de cobrança, faz `pg_dump`, restaura num banco **novo e vazio**, compara por SQL (conta, projeto, evento, bytes/fingerprint, jobs, tentativas, dedupe, uso, outbox, changelog) e então sobe a API sobre o restaurado, com worker desligado e entrega HTTP proibida, para validar login, listagem, leitura, dedupe e uso pela API. Nunca aponta para um banco existente.

`./scripts/worker-crash-drill.sh` sobe instâncias reais da API num Postgres descartável e entrega um lote com latência em duas instâncias. Depois mata (`kill -9`) um worker em dois pontos controlados: (1) com o claim já persistido e o HTTP ainda não iniciado — a instância é iniciada com `target/test-classes` no classpath e `HOOKGUARD_TEST_PAUSE_BEFORE_SEND_DIR`, que ativa um gancho de teste (`CrashDrillHook`) que escreve um arquivo e bloqueia antes do envio; o drill confere que o destino tem 0 requisições do evento antes e depois da morte; (2) depois que o destino recebeu e antes do settle. Reinicia e confere recuperação, histórico (`lease_expired` e depois 200) e contagem de requisições: 1 para o caso (1) e 2 para o caso (2), o que ilustra o at-least-once. O gancho existe só em `src/test` e não está no jar.

Restore manual:

```bash
pg_dump -Fc -h 127.0.0.1 -U hookguard hookguard > hookguard.dump
createdb hookguard_restored
pg_restore -d hookguard_restored hookguard.dump
```

Confirme pela API, não só pelo arquivo de dump: autentique, liste o projeto e leia um evento. Readiness (`/actuator/health/readiness`) observa banco e estado do processo; SMTP fora do ar não tira a API de serviço. O health agregado ainda reporta o correio. RPO é o intervalo entre dumps que você agendar. RTO é o tempo de subir Postgres vazio mais o restore; meça no seu disco. Esta entrega não publica um número de SLO.

Upgrade: o Liquibase aplica só changesets novos; não edite os já aplicados. Para a versão que introduz os changesets 005–010 (dedupe por TTL, índice de job aberto, autenticação de origem/taxa, outbox de uso, estado dos webhooks de assinatura):

1. Faça `pg_dump` antes.
2. **Pare todas as instâncias da versão anterior** (parada controlada). Instâncias antigas não conhecem `ingest_dedupes` nem o índice de job aberto; coexistir com as novas reabre duplicidade.
3. Suba a versão nova. O 007 copia para `ingest_dedupes` os eventos anteriores com chave de dedupe ainda dentro da janela (`HOOKGUARD_DEDUPE_TTL_HOURS`, padrão 24 h, contada a partir de `received_at`) com o fingerprint SHA-256 dos bytes guardados; eventos mais antigos que a janela seguem o contrato novo e podem ser aceitos de novo. O 006 fecha como `dead` (sem apagar) jobs abertos duplicados do mesmo evento. Cada changeset roda numa transação: falha não deixa proteção parcial, e reexecutar é seguro. O 007 usa `sha256()` nativo do PostgreSQL ≥ 11 (sem extensões).
4. Tráfego só depois do startup concluir.

Retorno: as migrações são aditivas. Voltar o código antigo exige restaurar o dump anterior (o código antigo espera o índice único permanente de dedupe, que o 005 removeu). Reaplicar esse índice sobre dados novos pode falhar por duplicatas legítimas. Rollback do schema novo sem perder dados não está provado; não há downgrade automático.

## Dependências, imagens e riscos remanescentes

Auditoria de 2026-10-25 (trivy 0.76.1 sobre o SBOM CycloneDX do Maven e sobre as imagens, linux/amd64; `npm audit`; avisos oficiais conferidos em spring.io e github.com/advisories). Aplicado: Spring Boot 3.5.16 (a mais recente da linha 3.x na data) com overrides de patch para Tomcat 10.1.61, Jackson 2.21.8, pgjdbc 42.7.14, commons-lang3 3.18.0 e log4j-api 2.25.5; no painel, Next 15.5.28 com `sharp` 0.35.5 (dentro do intervalo `^0.35.5` que o próprio Next declara; só o lockfile mudou) e `overrides` de `postcss`, `nanoid` e `source-map-js`; `npm audit` sem achados; proxy `caddy:2.11-alpine`. `/_next/image*` responde 404 nos dois Caddyfiles de implantação (`deploy/Caddyfile` e `deploy/Caddyfile.free`, validados em execução), embora o painel não use `next/image`, imagens externas nem otimização (só há uma fonte via CSS do Google Fonts); o serviço `web` não é publicado em nenhuma porta do host nos Compose de produção, free e oracle (só o Caddy). O endpoint continua existindo dentro do Next; o bloqueio reduz a exposição, não remove o código. Reexecute os scans a cada release.

### Imagens: fixação por digest

Os Dockerfiles e os Compose de produção, free e oracle fixam `tag@sha256:<digest>` do índice multi-arquitetura (os digests de plataforma única não são usados; as plataformas do índice incluem amd64 e arm64). Fixar por digest torna o build reprodutível; **não corrige vulnerabilidade**. Para atualizar: `docker pull <tag>`, `docker buildx imagetools inspect <tag>` (copie o `Digest:` do índice), troque o digest nos cinco arquivos (`grep -rn "@sha256" docker-compose*.yml apps/*/Dockerfile`), rode o scan da imagem nova e suba o Compose antes de publicar. Em 2026-10-25 as tags usadas (`postgres:16-alpine`, `caddy:2.11-alpine`, `eclipse-temurin:21-jre`, `node:22-alpine`, `maven:3.9.9-eclipse-temurin-21`, mailpit e cloudflared) já apontavam para as versões mais novas disponíveis; não havia imagem corrigida para os achados abaixo.

| Risco | Produção? | Condição de exploração | Exposição na configuração real | Mitigação e limite | Correção / decisão |
| --- | --- | --- | --- | --- | --- |
| Spring Framework 6.2.19 — CVE-2026-47884 (`XsltView`; o scanner marca crítico, o aviso do Spring marca *Medium*) | sim (API) | usar `XsltView` com mapeamento `/**` que renderiza view sem nome explícito | a API é só REST/JSON; não há `XsltView`, `ViewResolver` nem template engine (verificado no código e no `pom.xml`) | não aplicável ao código atual; um futuro uso de views reabriria o risco | 6.2.20 existe **só para suporte comercial** (Central tem até 6.2.19, verificado); o OSS é 7.0.9 (exige Boot 4). Nada foi corrigido: o achado persiste no scanner. Decisão do proprietário |
| Spring Framework 6.2.19 — CVE-2026-47890 (SSE com fragmentos de view; aviso: *Low*) | sim (API) | SSE com fragmentos de view e dados controlados por outro usuário | não há SSE nem views | não aplicável ao código atual | mesma correção acima. Decisão do proprietário |
| `sharp` (libvips; aviso: *High*, CVSS v4 7.0, vetor local, afeta < 0.35.0) | painel (dependência opcional do Next) | processar imagem não confiável pelo otimizador do Next | **corrigido**: `sharp` 0.35.5 no lockfile, `npm audit` sem achados. Além disso `/_next/image` fica bloqueado nos dois Caddyfiles (o endpoint existe no Next mesmo com `images.unoptimized`, comprovado) | o bloqueio é redução de exposição, não correção; acesso direto ao container `web` na rede interna ainda alcança o endpoint | nenhuma pendente |
| Imagens: `gosu` no `postgres:16-alpine` (Go stdlib; 1 crítico CVE-2025-68121, 25 HIGH), `pebble` no `eclipse-temurin:21-jre` (4 HIGH, Go stdlib), npm embutido no `node:22-alpine` (11 HIGH, ferramenta que o painel não usa em runtime além de `npm run start`), `caddy:2.11-alpine` (6 HIGH, Go stdlib/x/net/brotli), `cloudflared:latest` (1 HIGH), `maven:3.9.9-eclipse-temurin-21` (só build) | sim, exceto Maven | depende do binário e do recurso afetado; não analisados individualmente | `gosu` só roda na partida do container; `pebble` (cliente ACME de teste) não é executado pela aplicação | sem correção disponível nas tags usadas | atualizar quando houver imagem corrigida (procedimento acima). `maven` só no build; `mailpit` só na demo free; `cloudflared` só na demo/Oracle |
| Segredos em texto no Postgres (`signing_secret`, `signing_secret_previous`, `source_secret`) | sim | acesso de leitura ao banco, ao dump ou ao backup | não saem em listagens, erros nem logs nos caminhos testados (`SecretExposureTest`); o segredo de saída aparece uma vez na criação e na rotação | **não há criptografia de campos**. Proteções necessárias: disco/volume cifrado, `pg_dump` guardado cifrado e com acesso restrito, usuário de banco sem acesso amplo, senha forte do banco (o Compose recusa subir sem `HOOKGUARD_DB_PASSWORD`). Criptografia com chave gerenciada exige definir gestão, migração e recuperação de chaves: decisão do proprietário |

Aceitar qualquer linha acima é decisão do proprietário; este documento não a toma.

## Autenticação GitHub (OAuth)

O início do fluxo grava um cookie `hg_oauth_state` (HttpOnly, SameSite=Lax, 10 min) e envia ao GitHub um `state` assinado (HMAC com o client secret) contendo o mesmo nonce e a expiração. O callback só prossegue se o cookie e o `state` conferirem, a assinatura for válida e o `state` não tiver expirado (`400 invalid_oauth_state`). Só e-mails verificados são usados. O token de sessão volta ao painel na query string do redirecionamento, o que o deixa no histórico do navegador; mudar isso não está no escopo desta entrega. A integração real com o GitHub **não foi exercitada**: os testes usam um substituto local. Por HTTPS pelo proxy foi verificado, sem contatar o GitHub (credenciais fictícias), que `/v1/auth/github` responde 302 para `github.com/login/oauth/authorize` com `redirect_uri=https://<host>/v1/auth/github/callback`, `state` assinado e o cookie `hg_oauth_state` com `HttpOnly; SameSite=Lax; Secure; Path=/v1/auth/github; Max-Age=600`.

Roteiro de homologação (conta e aplicação de teste, nunca de produção): em GitHub → Settings → Developer settings → OAuth Apps → New, `Homepage URL` = `https://<host>` e `Authorization callback URL` = exatamente `https://<host>/v1/auth/github/callback` (`<host>` é o valor de `HOOKGUARD_API_BASE_URL`, atrás do proxy). Defina `GITHUB_CLIENT_ID` e `GITHUB_CLIENT_SECRET` só no ambiente do Compose. Depois: (1) abra `https://<host>/v1/auth/github` e conclua o login (etapa interativa); (2) confirme o redirecionamento para `https://<host>/auth/callback?sessionToken=...` e `GET /v1/auth/me` com esse token; (3) repita com `state` adulterado ou sem o cookie (por exemplo, abrindo o callback em outro navegador) e confirme `400 invalid_oauth_state`; (4) entre com uma segunda conta de teste e confirme que ela não vê os projetos da primeira.

## Segredos em repouso

`projects.signing_secret`, `signing_secret_previous` e `source_secret` ficam em texto no Postgres (a assinatura de saída e a verificação de origem precisam do valor). Eles não saem em listagens (só um sufixo do segredo de saída; o de origem nunca), erros nem logs nos caminhos cobertos por `SecretExposureTest`; o segredo de saída é mostrado de propósito na criação e na rotação. Quem lê o banco ou o backup lê os segredos: proteja o disco, o dump e o acesso ao banco. Não há criptografia de campos. Criptografia desses campos com chave gerenciada é uma decisão do proprietário, fora desta entrega.

## Retenção e privacidade

O job das 03:20 UTC apaga eventos mais velhos que `hookguard.retention.days` (padrão 14), com tentativas, jobs e chaves de dedupe. Dedupe expirado sai mesmo que o evento ainda esteja na retenção. Não há chave por cliente. Criptografia em repouso é a do disco/volume. Logs não devem receber corpo, segredo ou `Authorization`.

## Troubleshooting

| Sintoma | Onde olhar |
| --- | --- |
| `202` não chega | log da API e conexão com o Postgres; erro de banco não é mascarado |
| evento `delivering` parado | lease; outro worker ou o próximo poll recupera após o timeout |
| destino não recebe `Authorization` | comportamento esperado; só a allowlist de assinatura é encaminhada |
| `409 dedupe_payload_conflict` | mesma chave e body diferente dentro do TTL |
| `409 replay_already_queued` | já existe job `pending` ou `in_progress` (inclui disputa simultânea) |
| `401 signature_*` / `503 source_auth_misconfigured` | autenticação de origem do projeto; ver USAGE |
| `429 rate_limited` / `503 rate_limiter_unavailable` | taxa por projeto, compartilhada pelo Postgres |
| `usage_outbox` em `failed`/`review` | uso não confirmado no Stripe; ver STRIPE |
| `429 free_quota_exceeded` | plano free no mês UTC |
| destino interno recusado | SSRF; em produção só `https` público |
| assinatura do destino falha | formato `t=,v1=` com timestamp, event id e body; janela de rotação 24 h |

Consulta de fila (e fila de cobrança):

```sql
SELECT state, count(*) FROM delivery_jobs GROUP BY state;
SELECT state, count(*) FROM usage_outbox GROUP BY state;
```

Métrica `hookguard.delivery` com tag `result` (`success`, `retry`, `dead`) sai no Prometheus do processo quando o scrape é interno.
