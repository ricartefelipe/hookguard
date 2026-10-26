#!/usr/bin/env bash
# Homologacao do reporte de uso no SANDBOX do Stripe, com dados sinteticos e uma stack local descartavel
# (Postgres em container, API local, destino HTTP local). Nao toca em producao e recusa chave live.
#
# Requisitos (todos via ambiente; nada e gravado em arquivo nem impresso):
#   STRIPE_API_KEY             chave de TESTE (sk_test_... ou rk_test_...) com escrita em billing meter events
#                              e leitura de meters
#   STRIPE_METER_EVENT_NAME    event_name de um medidor de teste (valor padrao do payload: "value", soma)
#   STRIPE_TEST_METER_ID       id do medidor (mtr_...)
#   STRIPE_TEST_CUSTOMER_ID    cliente de teste (cus_...) usado SO por este teste: o agregado so e
#                              comparavel se nenhuma outra fonte enviar eventos para ele
# Opcional: HOOKGUARD_STRIPE_API_BASE (padrao https://api.stripe.com; so para ensaiar este script contra um
#          substituto local; a chave de teste continua obrigatoria), HOOKGUARD_HOMOLOG_WAIT_SECONDS (padrao 300) = espera maxima pela agregacao assincrona do Stripe.
#
# Saida: 0 = confirmado; 3 = INCONCLUSIVO (a agregacao do Stripe nao refletiu a tempo; repita a consulta
# com scripts/usage-reconcile.sh mais tarde, nao reenvie); 1 = falha real (estado local incorreto).
# O agregado prova quantidade, nao o resultado de um evento individual.
set -euo pipefail

for v in STRIPE_API_KEY STRIPE_METER_EVENT_NAME STRIPE_TEST_METER_ID STRIPE_TEST_CUSTOMER_ID; do
  [[ -n "${!v:-}" ]] || { echo "defina $v no ambiente (nao o passe como argumento nem o cole em arquivos)" >&2; exit 2; }
done
case "$STRIPE_API_KEY" in
  sk_test_*|rk_test_*) ;;
  *) echo "recusado: STRIPE_API_KEY nao e uma chave de teste (sk_test_/rk_test_)" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PG_NAME="hookguard-stripe-homolog-pg"
PG_PORT="${HOOKGUARD_DRILL_PORT:-55462}"
API_PORT="${HOOKGUARD_DRILL_API_PORT:-18090}"
DEST_PORT="${HOOKGUARD_DRILL_DEST_PORT:-18997}"
WAIT="${HOOKGUARD_HOMOLOG_WAIT_SECONDS:-300}"
STRIPE_BASE="${HOOKGUARD_STRIPE_API_BASE:-https://api.stripe.com}"
WORK="$(mktemp -d)"
docker ps -a --format '{{.Names}}' | grep -qx "$PG_NAME" && { echo "container $PG_NAME ja existe; recusando" >&2; exit 2; }
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [[ -n "$p" ]] && kill "$p" >/dev/null 2>&1 || true; done; docker rm -f "$PG_NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
fail() { echo "FALHOU: $*" >&2; exit 1; }
sql() { docker exec -i "$PG_NAME" psql -U hookguard -d hookguard -v ON_ERROR_STOP=1 -tA "$@"; }
summary() { # $1 start $2 end -> soma do agregado
  curl -sf -G "${STRIPE_BASE}/v1/billing/meters/${STRIPE_TEST_METER_ID}/event_summaries" -u "${STRIPE_API_KEY}:" \
    -d customer="$STRIPE_TEST_CUSTOMER_ID" -d start_time="$1" -d end_time="$2" -d limit=100 \
    | python3 -I -c 'import json,sys; d=json.load(sys.stdin); sys.exit("paginado") if d.get("has_more") else print(sum(int(x["aggregated_value"]) for x in d["data"]))'
}

docker run -d --name "$PG_NAME" -e POSTGRES_USER=hookguard -e POSTGRES_PASSWORD=hookguard -e POSTGRES_DB=hookguard \
  -p "127.0.0.1:${PG_PORT}:5432" postgres:16-alpine >/dev/null
for _ in $(seq 1 40); do docker exec "$PG_NAME" pg_isready -U hookguard -d hookguard >/dev/null 2>&1 && break; sleep 1; done
cat > "$WORK/dest.py" <<'PY'
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.send_response(200); self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"ok")
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
python3 -I "$WORK/dest.py" "$DEST_PORT" & PIDS+=($!)
# coletor SMTP minimo: o login por magic link envia e-mail; nada sai da maquina
SMTP_PORT="${HOOKGUARD_DRILL_SMTP_PORT:-11026}"
cat > "$WORK/smtp.py" <<'PY'
import socketserver, sys
class H(socketserver.StreamRequestHandler):
    def handle(self):
        w = lambda m: (self.wfile.write((m + "\r\n").encode()), self.wfile.flush())
        w("220 sink")
        data = False
        for raw in self.rfile:
            line = raw.decode(errors="replace").rstrip("\r\n")
            if data:
                if line == ".": data = False; w("250 ok")
                continue
            cmd = line.upper()
            if cmd.startswith("DATA"): data = True; w("354 go")
            elif cmd.startswith("QUIT"): w("221 bye"); return
            else: w("250 ok")
socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
python3 -I "$WORK/smtp.py" "$SMTP_PORT" & PIDS+=($!)
(cd "$ROOT/apps/api" && mvn -q -B -DskipTests package)
JAR="$(ls "$ROOT"/apps/api/target/hookguard-api-*.jar | grep -v original | head -1)"
HOOKGUARD_DB_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/hookguard" HOOKGUARD_DB_USER=hookguard HOOKGUARD_DB_PASSWORD=hookguard \
HOOKGUARD_SMTP_HOST=127.0.0.1 HOOKGUARD_SMTP_PORT="$SMTP_PORT" HOOKGUARD_EXPOSE_MAGIC_LINK=true HOOKGUARD_DELIVERY_ALLOW_HTTP=true HOOKGUARD_WORKER_ENABLED=true \
HOOKGUARD_WORKER_POLL_MS=300 HOOKGUARD_BILLING_STRIPE_API_BASE="$STRIPE_BASE" HOOKGUARD_PORT="$API_PORT" HOOKGUARD_INGEST_RATE_LIMIT=0 \
java -jar "$JAR" >"$WORK/api.log" 2>&1 & PIDS+=($!)
for _ in $(seq 1 90); do curl -sf "http://127.0.0.1:${API_PORT}/actuator/health/readiness" >/dev/null 2>&1 && break; sleep 2; done
curl -sf "http://127.0.0.1:${API_PORT}/actuator/health/readiness" >/dev/null || { tail -20 "$WORK/api.log" >&2; fail "API nao subiu"; }

# conta sintetica em plano pago, ja no limite incluso; o cliente de teste e o do Stripe
ACC=$(sql -c "INSERT INTO accounts (id,email,name,plan,stripe_customer_id,created_at) VALUES (gen_random_uuid(),'homolog@example.com','Homolog','pro','${STRIPE_TEST_CUSTOMER_ID}',now()) RETURNING id" | head -1)
sql -c "INSERT INTO usage_monthly (account_id,year_month,event_count) VALUES ('$ACC', to_char(now() at time zone 'utc','YYYY-MM'), 100000)" >/dev/null
KEY="homolog$(date +%s)"
sql -c "INSERT INTO projects (id,account_id,name,project_key,destination_url,timeout_ms,max_attempts,dedupe_header,signing_secret,status,created_at)
        VALUES (gen_random_uuid(),'$ACC','homolog','$KEY','http://127.0.0.1:${DEST_PORT}/h',5000,3,'X-Idempotency-Key','whsec_homolog','active',now())" >/dev/null
API="http://127.0.0.1:${API_PORT}"

START=$(( $(date +%s) / 60 * 60 - 60 ))
BASE_END=$(( START + 60 ))
BASELINE=$(summary "$START" "$BASE_END" || echo 0)
echo "baseline do agregado (minuto anterior ao teste): ${BASELINE}"

echo "== ingere 3 eventos sinteticos identificados"
for i in 1 2 3; do
  curl -sf -X POST "$API/v1/ingest/$KEY" -H 'Content-Type: application/json' -H "X-Idempotency-Key: homolog-$i" -d "{\"synthetic\":$i}" >/dev/null
done
for _ in $(seq 1 60); do
  [[ "$(sql -c "SELECT count(*) FROM usage_outbox WHERE state='sent'")" == "3" ]] && break; sleep 2
done
SENT=$(sql -c "SELECT count(*) FROM usage_outbox WHERE state='sent'")
[[ "$SENT" == "3" ]] || { sql -c "SELECT event_id,state,attempts,last_http_status,last_error FROM usage_outbox"; fail "esperava 3 linhas sent, ha $SENT (veja last_http_status/last_error acima)"; }
echo "ok: 3 linhas sent no banco"

echo "== replay pela API autenticada nao pode criar nova intencao de cobranca"
# stack local e descartavel: so aqui o link de login volta na resposta, para obter a sessao pelo caminho publico
LINK=$(curl -sf -X POST "$API/v1/auth/magic-link" -H 'Content-Type: application/json' -d '{"email":"homolog@example.com"}' \
  | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["magicLink"])')
SESSION=$(curl -sf -X POST "$API/v1/auth/verify" -H 'Content-Type: application/json' -d "{\"token\":\"${LINK##*token=}\"}" \
  | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["sessionToken"])')
EV=$(sql -c "SELECT id FROM events WHERE dedupe_key='homolog-1'")
for _ in $(seq 1 30); do [[ "$(sql -c "SELECT count(*) FROM delivery_jobs WHERE event_id='$EV' AND state IN ('pending','in_progress')")" == "0" ]] && break; sleep 1; done
CODE=$(curl -s -o "$WORK/replay.json" -w '%{http_code}' -X POST "$API/v1/events/$EV/replay" \
  -H "Authorization: Bearer $SESSION" -H 'Content-Type: application/json' -d '{"reason":"homologacao do reporte de uso"}')
[[ "$CODE" == "200" ]] || fail "replay pela API respondeu $CODE"
for _ in $(seq 1 30); do [[ "$(sql -c "SELECT count(*) FROM delivery_jobs WHERE event_id='$EV' AND trigger_type='replay' AND state='done'")" == "1" ]] && break; sleep 1; done
[[ "$(sql -c "SELECT count(*) FROM delivery_jobs WHERE event_id='$EV' AND trigger_type='replay' AND state='done'")" == "1" ]] || fail "o job de replay nao foi entregue"
sleep 5
[[ "$(sql -c "SELECT count(*) FROM usage_outbox")" == "3" ]] || fail "replay criou nova intencao de cobranca"
[[ "$(sql -c "SELECT event_count FROM usage_monthly")" == "100003" ]] || fail "replay alterou o uso mensal"
echo "ok: replay via API (200, entregue) nao alterou usage_outbox (3) nem usage_monthly (100003)"

echo "== aguarda a agregacao assincrona do Stripe (ate ${WAIT}s)"
END=$(( ($(date +%s) / 60 + 2) * 60 ))
DEADLINE=$(( $(date +%s) + WAIT ))
GOT=""
while (( $(date +%s) < DEADLINE )); do
  GOT=$(summary "$START" "$END" 2>/dev/null || true)
  if [[ -n "$GOT" ]] && (( GOT - BASELINE >= 3 )); then break; fi
  sleep 15
done
echo "agregado no periodo: ${GOT:-n/d} (baseline ${BASELINE}, esperado >= $((BASELINE + 3)))"
if [[ -n "$GOT" ]] && (( GOT - BASELINE == 3 )); then
  echo "CONFIRMADO: o agregado cresceu exatamente 3 e o replay nao somou nada."
  exit 0
elif [[ -n "$GOT" ]] && (( GOT - BASELINE > 3 )); then
  fail "o agregado cresceu mais que 3: ha outra fonte para este cliente ou duplicidade; nao reenvie nada"
fi
echo "INCONCLUSIVO: o Stripe nao refletiu os 3 eventos dentro de ${WAIT}s (a agregacao e assincrona)."
echo "Nao reenvie. Repita depois: scripts/usage-reconcile.sh ${STRIPE_TEST_METER_ID} ${STRIPE_TEST_CUSTOMER_ID} ${START} ${END}"
exit 3
