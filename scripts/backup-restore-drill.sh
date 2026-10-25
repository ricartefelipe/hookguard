#!/usr/bin/env bash
# Ensaio de backup/restore em ambiente descartavel.
#   1. sobe Postgres e um coletor SMTP locais (containers proprios, removidos no fim)
#   2. sobe a API (worker ligado) contra um destino HTTP local controlado e cria dados pela API:
#      conta, projeto, eventos com dedupe, entrega bem-sucedida, falha com retry pendente,
#      intencao de cobranca de uso excedente
#   3. pg_dump -> restaura em um banco NOVO e vazio
#   4. compara por SQL (conta, projeto, evento, payload/fingerprint, jobs, tentativas, dedupe, uso, outbox)
#   5. sobe a API sobre o banco restaurado com o worker DESLIGADO e entrega HTTP proibida, e valida
#      autenticacao, listagem, leitura, dedupe e uso pela API
# Nao aponta para nenhum banco existente e nao envia nada para fora da maquina.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PG_NAME="hookguard-restore-drill-pg"
MAIL_NAME="hookguard-restore-drill-mail"
PG_PORT="${HOOKGUARD_DRILL_PORT:-55442}"
SMTP_PORT="${HOOKGUARD_DRILL_SMTP_PORT:-11025}"
API_PORT="${HOOKGUARD_DRILL_API_PORT:-18080}"
DEST_PORT="${HOOKGUARD_DRILL_DEST_PORT:-18999}"
WORK="$(mktemp -d)"
EMAIL="drill@example.com"
KEY="drill-key-1"
BODY='{"order":1,"password":"drill-secret-value"}'

for name in "$PG_NAME" "$MAIL_NAME"; do
  if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
    echo "container $name ja existe; recusando para nao mexer em outro ambiente" >&2
    exit 1
  fi
done

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" >/dev/null 2>&1 || true
  done
  docker rm -f "$PG_NAME" "$MAIL_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

psql_in() { docker exec -i "$PG_NAME" psql -U hookguard -v ON_ERROR_STOP=1 -tA "$@"; }
jget() { python3 -I -c 'import json,sys; d=json.load(sys.stdin)
for p in sys.argv[1].split("."): d = d[int(p)] if p.isdigit() else d[p]
print(d)' "$1"; }
fail() { echo "FALHOU: $*" >&2; exit 1; }

echo "== infraestrutura descartavel"
docker run -d --name "$PG_NAME" -e POSTGRES_USER=hookguard -e POSTGRES_PASSWORD=hookguard \
  -e POSTGRES_DB=hookguard_src -p "127.0.0.1:${PG_PORT}:5432" postgres:16-alpine >/dev/null
docker run -d --name "$MAIL_NAME" -p "127.0.0.1:${SMTP_PORT}:1025" axllent/mailpit:latest >/dev/null
for _ in $(seq 1 40); do
  docker exec "$PG_NAME" pg_isready -U hookguard -d hookguard_src >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$PG_NAME" pg_isready -U hookguard -d hookguard_src >/dev/null

# destino controlado: /ok responde 200, /fail responde 500
cat > "$WORK/dest.py" <<'PY'
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        code = 200 if self.path.startswith("/ok") else 500
        self.send_response(code); self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"ok")
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
python3 -I "$WORK/dest.py" "$DEST_PORT" &
PIDS+=($!)

echo "== build da API"
(cd "$ROOT/apps/api" && mvn -q -B -DskipTests package)
JAR="$(ls "$ROOT"/apps/api/target/hookguard-api-*.jar | grep -v original | head -1)"

start_api() { # $1 database  $2 worker  $3 allow_http
  HOOKGUARD_DB_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/$1" HOOKGUARD_DB_USER=hookguard HOOKGUARD_DB_PASSWORD=hookguard \
  HOOKGUARD_SMTP_HOST=127.0.0.1 HOOKGUARD_SMTP_PORT="$SMTP_PORT" HOOKGUARD_EXPOSE_MAGIC_LINK=true \
  HOOKGUARD_WORKER_ENABLED="$2" HOOKGUARD_DELIVERY_ALLOW_HTTP="$3" HOOKGUARD_PORT="$API_PORT" \
  HOOKGUARD_WORKER_POLL_MS=500 STRIPE_API_KEY= STRIPE_METER_EVENT_NAME= \
  java -jar "$JAR" >"$WORK/api-$1.log" 2>&1 &
  API_PID=$!
  PIDS+=("$API_PID")
  for _ in $(seq 1 90); do
    curl -sf "http://127.0.0.1:${API_PORT}/actuator/health/readiness" >/dev/null 2>&1 && return 0
    sleep 2
  done
  tail -30 "$WORK/api-$1.log" >&2
  fail "API nao ficou pronta sobre $1"
}
stop_api() { kill "$API_PID" >/dev/null 2>&1 || true; wait "$API_PID" >/dev/null 2>&1 || true; }
login() {
  local link token
  link="$(curl -sf -X POST "http://127.0.0.1:${API_PORT}/v1/auth/magic-link" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${EMAIL}\"}" | jget magicLink)"
  token="${link##*token=}"
  curl -sf -X POST "http://127.0.0.1:${API_PORT}/v1/auth/verify" -H 'Content-Type: application/json' \
    -d "{\"token\":\"${token}\"}" | jget sessionToken
}
api() { curl -sf "$@" ; }

echo "== fase 1: dados criados pela API (banco de origem)"
start_api hookguard_src true true
API="http://127.0.0.1:${API_PORT}"
SESSION="$(login)"
AUTH=(-H "Authorization: Bearer ${SESSION}")
psql_in -d hookguard_src -c "UPDATE accounts SET plan='pro' WHERE email='${EMAIL}'" >/dev/null
OKP="$(api -X POST "$API/v1/projects" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"ok\",\"destinationUrl\":\"http://127.0.0.1:${DEST_PORT}/ok\",\"dedupeHeader\":\"X-Idempotency-Key\"}")"
OK_ID="$(echo "$OKP" | jget id)"; OK_KEY="$(echo "$OKP" | jget projectKey)"
FAILP="$(api -X POST "$API/v1/projects" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"fail\",\"destinationUrl\":\"http://127.0.0.1:${DEST_PORT}/fail\"}")"
FAIL_KEY="$(echo "$FAILP" | jget projectKey)"
E1="$(api -X POST "$API/v1/ingest/${OK_KEY}" -H 'Content-Type: application/json' -H "X-Idempotency-Key: ${KEY}" -d "$BODY" | jget eventId)"
api -X POST "$API/v1/ingest/${FAIL_KEY}" -H 'Content-Type: application/json' -d '{"order":2}' >/dev/null
# uso no limite do plano: o proximo evento gera intencao de cobranca (nada e enviado: Stripe sem credenciais)
psql_in -d hookguard_src -c "INSERT INTO usage_monthly (account_id, year_month, event_count)
  SELECT id, to_char(now() at time zone 'utc','YYYY-MM'), 100000 FROM accounts WHERE email='${EMAIL}'
  ON CONFLICT (account_id, year_month) DO UPDATE SET event_count = 100000" >/dev/null
api -X POST "$API/v1/ingest/${OK_KEY}" -H 'Content-Type: application/json' -H 'X-Idempotency-Key: drill-key-2' -d '{"order":3}' >/dev/null
for _ in $(seq 1 40); do
  done_n="$(psql_in -d hookguard_src -c "SELECT count(*) FROM delivery_attempts WHERE http_status IS NOT NULL")"
  [[ "$done_n" -ge 3 ]] && break
  sleep 1
done
[[ "$done_n" -ge 3 ]] || fail "tentativas esperadas nao foram registradas ($done_n)"
stop_api
# a partir daqui nenhum worker roda contra o banco de origem
USAGE_BEFORE="$(psql_in -d hookguard_src -c "SELECT event_count FROM usage_monthly")"

echo "== fase 2: dump e restore em banco novo e vazio"
docker exec "$PG_NAME" pg_dump -U hookguard -d hookguard_src -Fc -f /tmp/hookguard.dump
psql_in -d postgres -c "CREATE DATABASE hookguard_restored" >/dev/null
docker exec "$PG_NAME" pg_restore -U hookguard -d hookguard_restored --no-owner --exit-on-error /tmp/hookguard.dump

echo "== fase 3: comparacao por SQL"
SNAPSHOT="
SELECT 'accounts|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', id, email, plan), ',' ORDER BY id::text)),'') FROM accounts
UNION ALL SELECT 'projects|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', id, project_key, destination_url, signing_secret), ',' ORDER BY id::text)),'') FROM projects
UNION ALL SELECT 'events|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', id, encode(sha256(body),'hex'), payload_fingerprint, dedupe_key, status), ',' ORDER BY id::text)),'') FROM events
UNION ALL SELECT 'jobs|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', id, event_id, state, attempt_number, trigger_type), ',' ORDER BY id::text)),'') FROM delivery_jobs
UNION ALL SELECT 'attempts|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', id, event_id, attempt_number, http_status, error_message), ',' ORDER BY id::text)),'') FROM delivery_attempts
UNION ALL SELECT 'dedupes|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', project_id, dedupe_key, event_id, fingerprint), ',' ORDER BY project_id::text, dedupe_key)),'') FROM ingest_dedupes
UNION ALL SELECT 'usage|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', account_id, year_month, event_count), ',' ORDER BY account_id::text, year_month)),'') FROM usage_monthly
UNION ALL SELECT 'outbox|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', event_id, identifier, state), ',' ORDER BY event_id::text)),'') FROM usage_outbox
UNION ALL SELECT 'changelog|'||count(*)||'|'||coalesce(md5(string_agg(concat_ws('~', id, md5sum), ',' ORDER BY orderexecuted)),'') FROM databasechangelog"
psql_in -d hookguard_src -c "$SNAPSHOT" > "$WORK/src.txt"
psql_in -d hookguard_restored -c "$SNAPSHOT" > "$WORK/restored.txt"
diff "$WORK/src.txt" "$WORK/restored.txt" || fail "restore divergiu da origem"
cat "$WORK/restored.txt"
[[ "$(psql_in -d hookguard_restored -c "SELECT count(*) FROM events WHERE payload_fingerprint <> encode(sha256(body),'hex')")" == "0" ]] \
  || fail "fingerprint nao confere com os bytes originais"
[[ "$(psql_in -d hookguard_restored -c "SELECT count(*) FROM usage_outbox WHERE state='pending'")" -ge 1 ]] \
  || fail "intencao de cobranca pendente nao veio no restore"
[[ "$(psql_in -d hookguard_restored -c "SELECT count(*) FROM delivery_jobs WHERE state='pending'")" -ge 1 ]] \
  || fail "job pendente da falha nao veio no restore"

echo "== fase 4: API sobre o banco restaurado (worker desligado, entrega HTTP proibida)"
start_api hookguard_restored false false
SESSION="$(login)"
AUTH=(-H "Authorization: Bearer ${SESSION}")
[[ "$(api "$API/v1/auth/me" "${AUTH[@]}" | jget email)" == "$EMAIL" ]] || fail "autenticacao na base restaurada"
PROJECTS="$(api "$API/v1/projects" "${AUTH[@]}")"
[[ "$(echo "$PROJECTS" | python3 -I -c 'import json,sys; print(len(json.load(sys.stdin)))')" == "2" ]] || fail "listagem de projetos"
EVENTS="$(api "$API/v1/events?projectId=${OK_ID}" "${AUTH[@]}")"
[[ "$(echo "$EVENTS" | python3 -I -c 'import json,sys; print(len(json.load(sys.stdin)))')" == "2" ]] || fail "listagem de eventos"
DETAIL="$(api "$API/v1/events/${E1}" "${AUTH[@]}")"
EXPECTED_FP="$(printf '%s' "$BODY" | sha256sum | cut -d' ' -f1)"
[[ "$(echo "$DETAIL" | jget payloadFingerprint)" == "$EXPECTED_FP" ]] || fail "fingerprint na API"
[[ "$(echo "$DETAIL" | jget attempts.0.httpStatus)" == "200" ]] || fail "tentativa registrada na API"
echo "$DETAIL" | grep -q 'drill-secret-value' && fail "previa expôs segredo"
# dedupe preservado: mesma chave e mesmo body devolvem o mesmo evento, sem novo evento nem uso
AGAIN="$(api -X POST "$API/v1/ingest/${OK_KEY}" -H 'Content-Type: application/json' -H "X-Idempotency-Key: ${KEY}" -d "$BODY" | jget eventId)"
[[ "$AGAIN" == "$E1" ]] || fail "dedupe nao sobreviveu ao restore"
CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/v1/ingest/${OK_KEY}" -H 'Content-Type: application/json' -H "X-Idempotency-Key: ${KEY}" -d '{"order":99}')"
[[ "$CODE" == "409" ]] || fail "body diferente deveria dar 409, deu $CODE"
[[ "$(psql_in -d hookguard_restored -c "SELECT event_count FROM usage_monthly")" == "$USAGE_BEFORE" ]] || fail "uso mudou apos reenvio"
[[ "$(api "$API/v1/billing/usage-reports" "${AUTH[@]}" | jget counts.pending)" -ge 1 ]] || fail "intencao de cobranca nao visivel pela API"
[[ "$(psql_in -d hookguard_restored -c "SELECT count(*) FROM delivery_attempts WHERE http_status IS NOT NULL")" -ge 3 ]] \
  || fail "tentativas alteradas"
sleep 3
[[ "$(psql_in -d hookguard_restored -c "SELECT count(*) FROM delivery_attempts")" == "$(psql_in -d hookguard_src -c "SELECT count(*) FROM delivery_attempts")" ]] \
  || fail "o banco restaurado recebeu tentativas novas: worker nao deveria rodar"
stop_api

echo "restore ok: conta, projeto, eventos, fingerprint, jobs, tentativas, dedupe, uso e outbox conferidos por SQL e pela API"
