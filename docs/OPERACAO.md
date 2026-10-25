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
- Lease vencido (`hookguard.worker.lock-timeout-seconds`, padrão 120) volta para a fila e a tentativa aberta fica com `lease_expired`.
- Corpo máximo 1 MiB. Resposta lida no máximo 64 KiB. Timeout de destino 500–30000 ms, sempre menor que o lease.

## Backup e restore

O estado está no Postgres: contas, projetos, eventos, jobs, tentativas, dedupe e uso mensal. Não há blob store separado.

```bash
./scripts/backup-restore-drill.sh
```

O script sobe um Postgres descartável, aplica o schema pela API, grava um marcador, faz `pg_dump`, apaga o schema, restaura e lê o marcador. Não use esse script contra um volume que já esteja em uso.

Restore manual:

```bash
pg_dump -Fc -h 127.0.0.1 -U hookguard hookguard > hookguard.dump
createdb hookguard_restored
pg_restore -d hookguard_restored hookguard.dump
```

Confirme pela API, não só pelo arquivo de dump: autentique, liste o projeto e leia um evento. Readiness (`/actuator/health/readiness`) observa banco e estado do processo; SMTP fora do ar não tira a API de serviço. O health agregado ainda reporta o correio. RPO é o intervalo entre dumps que você agendar. RTO é o tempo de subir Postgres vazio mais o restore; meça no seu disco. Esta entrega não publica um número de SLO.

Upgrade: suba a versão nova apontando para o mesmo banco. O Liquibase aplica só changesets novos. Não edite changesets já aplicados.

## Retenção e privacidade

O job das 03:20 UTC apaga eventos mais velhos que `hookguard.retention.days` (padrão 14), com tentativas, jobs e chaves de dedupe. Dedupe expirado sai mesmo que o evento ainda esteja na retenção. Não há chave por cliente. Criptografia em repouso é a do disco/volume. Logs não devem receber corpo, segredo ou `Authorization`.

## Troubleshooting

| Sintoma | Onde olhar |
| --- | --- |
| `202` não chega | log da API e conexão com o Postgres; erro de banco não é mascarado |
| evento `delivering` parado | lease; outro worker ou o próximo poll recupera após o timeout |
| destino não recebe `Authorization` | comportamento esperado; só a allowlist de assinatura é encaminhada |
| `409 dedupe_payload_conflict` | mesma chave e body diferente dentro do TTL |
| `409 replay_already_queued` | já existe job `pending` ou `in_progress` |
| `429 free_quota_exceeded` | plano free no mês UTC |
| destino interno recusado | SSRF; em produção só `https` público |
| assinatura do destino falha | formato `t=,v1=` com timestamp, event id e body; janela de rotação 24 h |

Consulta de fila:

```sql
SELECT state, count(*) FROM delivery_jobs GROUP BY state;
```

Métrica `hookguard.delivery` com tag `result` (`success`, `retry`, `dead`) sai no Prometheus do processo quando o scrape é interno.
