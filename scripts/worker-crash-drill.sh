#!/usr/bin/env bash
# Ensaio com processos reais: duas instancias da API sobre o mesmo Postgres descartavel, lote com
# latencia no destino e morte (kill -9) do worker em dois pontos:
#   A. logo depois do claim (job in_progress) e
#   B. depois que o destino recebeu a requisicao e antes do settle.
# O destino e um servidor local controlado. Nada sai da maquina.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PG_NAME="hookguard-crash-drill-pg"
PG_PORT="${HOOKGUARD_DRILL_PORT:-55452}"
DEST_PORT="${HOOKGUARD_DRILL_DEST_PORT:-18998}"
LOCK_TIMEOUT=10
WORK="$(mktemp -d)"

if docker ps -a --format '{{.Names}}' | grep -qx "$PG_NAME"; then
  echo "container $PG_NAME ja existe; recusando" >&2; exit 1
fi
PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do [[ -n "$pid" ]] && kill -9 "$pid" >/dev/null 2>&1 || true; done
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
fail() { echo "FALHOU: $*" >&2; exit 1; }
sql() { docker exec -i "$PG_NAME" psql -U hookguard -d hookguard -v ON_ERROR_STOP=1 -tA "$@"; }

docker run -d --name "$PG_NAME" -e POSTGRES_USER=hookguard -e POSTGRES_PASSWORD=hookguard -e POSTGRES_DB=hookguard \
  -p "127.0.0.1:${PG_PORT}:5432" postgres:16-alpine >/dev/null
for _ in $(seq 1 40); do docker exec "$PG_NAME" pg_isready -U hookguard -d hookguard >/dev/null 2>&1 && break; sleep 1; done

# destino: registra cada requisicao; DELAY_MS por requisicao vem do arquivo de modo
cat > "$WORK/dest.py" <<'PY'
import http.server, sys, time, threading, os
LOG, MODE = sys.argv[2], sys.argv[3]
lock = threading.Lock(); seen = {}
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        ev = self.headers.get("X-HookGuard-Event-Id", "?")
        with lock:
            seen[ev] = seen.get(ev, 0) + 1
            n = seen[ev]
            with open(LOG, "a") as f: f.write(f"{ev} {self.headers.get('X-HookGuard-Attempt')} {n}\n")
        mode = open(MODE).read().strip()
        if mode == "hold" and n == 1: time.sleep(40)
        elif mode == "latency": time.sleep(0.3)
        self.send_response(200); self.send_header("Content-Length", "2"); self.end_headers()
        try: self.wfile.write(b"ok")
        except Exception: pass
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
echo latency > "$WORK/mode"; : > "$WORK/dest.log"
python3 -I "$WORK/dest.py" "$DEST_PORT" "$WORK/dest.log" "$WORK/mode" &
PIDS+=($!)

(cd "$ROOT/apps/api" && mvn -q -B -DskipTests package)
JAR="$(ls "$ROOT"/apps/api/target/hookguard-api-*.jar | grep -v original | head -1)"
# Instancia com ponto de pausa deterministico: so existe no classpath de teste, nunca no jar.
(cd "$ROOT/apps/api" && mvn -q -B test-compile dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=test)
TEST_CP="$ROOT/apps/api/target/classes:$ROOT/apps/api/target/test-classes:$(cat "$WORK/cp.txt")"

start_api() { # $1 name  $2 port  [pause-dir]
  local launcher=(java -jar "$JAR")
  if [[ -n "${3:-}" ]]; then
    launcher=(java -cp "$TEST_CP" br.com.ricarte.hookguard.HookguardApplication)
  fi
  HOOKGUARD_TEST_PAUSE_BEFORE_SEND_DIR="${3:-}" HOOKGUARD_DB_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/hookguard" HOOKGUARD_DB_USER=hookguard HOOKGUARD_DB_PASSWORD=hookguard \
  HOOKGUARD_SMTP_HOST=127.0.0.1 HOOKGUARD_SMTP_PORT=1 HOOKGUARD_WORKER_ENABLED=true HOOKGUARD_DELIVERY_ALLOW_HTTP=true \
  HOOKGUARD_WORKER_LOCK_TIMEOUT="$LOCK_TIMEOUT" HOOKGUARD_WORKER_POLL_MS=200 HOOKGUARD_WORKER_BATCH=5 \
  HOOKGUARD_INGEST_RATE_LIMIT=0 HOOKGUARD_PORT="$2" "${launcher[@]}" >"$WORK/$1.log" 2>&1 &
  eval "PID_$1=$!"; PIDS+=($!)
  for _ in $(seq 1 90); do curl -sf "http://127.0.0.1:$2/actuator/health/readiness" >/dev/null 2>&1 && return 0; sleep 2; done
  tail -20 "$WORK/$1.log" >&2; fail "instancia $1 nao ficou pronta"
}

# bootstrap do schema pela primeira instancia, sem worker util ainda (fila vazia)
start_api A 18081
ACC=$(sql -c "INSERT INTO accounts (id,email,name,plan,created_at) VALUES (gen_random_uuid(),'crash@example.com','Crash','pro',now()) RETURNING id" | head -1)
PROJ_KEY="crashkey$(date +%s)"
sql -c "INSERT INTO projects (id,account_id,name,project_key,destination_url,timeout_ms,max_attempts,signing_secret,status,created_at)
        VALUES (gen_random_uuid(),'$ACC','crash','$PROJ_KEY','http://127.0.0.1:${DEST_PORT}/h',5000,6,'whsec_crashdrill','active',now())" >/dev/null
ingest() { curl -sf -X POST "http://127.0.0.1:$1/v1/ingest/${PROJ_KEY}" -H 'Content-Type: application/json' -d "{\"n\":$2}" >/dev/null; }

echo "== fase 1: duas instancias, lote e latencia"
start_api B 18082
for i in $(seq 1 16); do ingest $((18081 + (i % 2))) "$i"; done
for _ in $(seq 1 60); do
  [[ "$(sql -c "SELECT count(*) FROM events WHERE status='delivered'")" == "16" ]] && break; sleep 1
done
[[ "$(sql -c "SELECT count(*) FROM events WHERE status='delivered'")" == "16" ]] || fail "lote nao foi entregue"
[[ "$(sort "$WORK/dest.log" | awk '{print $1}' | uniq | wc -l)" == "16" ]] || fail "eventos distintos no destino"
[[ "$(wc -l < "$WORK/dest.log")" == "16" ]] || fail "houve entrega duplicada sem falha: $(wc -l < "$WORK/dest.log") requisicoes para 16 eventos"
[[ "$(sql -c "SELECT count(*) FROM delivery_attempts")" == "16" ]] || fail "historico de tentativas"
echo "ok: 16 eventos, 16 requisicoes, 16 tentativas, duas instancias"

echo "== fase 2: morte do worker depois do claim"
: > "$WORK/dest.log"; echo latency > "$WORK/mode"
kill -9 "$PID_A" "$PID_B" >/dev/null 2>&1 || true
wait "$PID_A" "$PID_B" 2>/dev/null || true
# instancia C pausa depois do claim persistido e antes do HTTP; a morte acontece nessa pausa
mkdir -p "$WORK/gate"; touch "$WORK/gate/hold"
start_api C 18083 "$WORK/gate"
ingest 18083 200
for _ in $(seq 1 100); do [[ -f "$WORK/gate/claimed" ]] && break; sleep 0.1; done
[[ -f "$WORK/gate/claimed" ]] || fail "o ponto de pausa nao foi atingido"
EV2=$(sql -c "SELECT id FROM events WHERE convert_from(body,'UTF8') LIKE '%\"n\":200%'" | head -1)
[[ "$(sql -c "SELECT state FROM delivery_jobs WHERE event_id='$EV2'")" == "in_progress" ]] || fail "claim nao esta persistido"
[[ "$(sql -c "SELECT count(*) FROM delivery_attempts WHERE event_id='$EV2' AND finished_at IS NULL")" == "1" ]] || fail "tentativa aberta esperada"
[[ "$(grep -c "^$EV2 " "$WORK/dest.log" || true)" == "0" ]] || fail "o destino nao deveria ter recebido nada ainda"
kill -9 "$PID_C"; wait "$PID_C" 2>/dev/null || true
sleep 1
[[ "$(grep -c "^$EV2 " "$WORK/dest.log" || true)" == "0" ]] || fail "o destino recebeu o evento apos a morte"
echo "worker morto com claim persistido e HTTP nao iniciado; destino com 0 requisicoes para o evento 200"

echo "== recuperacao do evento 200 (lease vence, outro worker conclui)"
start_api R 18086
for _ in $(seq 1 60); do
  [[ "$(sql -c "SELECT status FROM events WHERE id='$EV2'")" == "delivered" ]] && break; sleep 1
done
[[ "$(sql -c "SELECT status FROM events WHERE id='$EV2'")" == "delivered" ]] || { sql -c "SELECT j.state,j.locked_by,e.status FROM delivery_jobs j JOIN events e ON e.id=j.event_id WHERE e.id='$EV2'"; tail -5 "$WORK/R.log"; fail "evento 200 nao recuperado"; }
[[ "$(grep -c "^$EV2 " "$WORK/dest.log")" == "1" ]] || fail "evento 200 deveria ter chegado ao destino uma unica vez"
kill -9 "$PID_R"; wait "$PID_R" 2>/dev/null || true

echo "== fase 3: morte depois que o destino recebeu e antes do settle"
echo hold > "$WORK/mode"
start_api D 18084
ingest 18084 300
EV3=$(sql -c "SELECT id FROM events WHERE convert_from(body,'UTF8') LIKE '%\"n\":300%'" | head -1)
for _ in $(seq 1 100); do grep -q "^$EV3 " "$WORK/dest.log" && break; sleep 0.1; done
grep -q "^$EV3 " "$WORK/dest.log" || fail "destino nao recebeu o evento 300"
kill -9 "$PID_D"; wait "$PID_D" 2>/dev/null || true
[[ "$(sql -c "SELECT state FROM delivery_jobs WHERE event_id='$EV3'")" == "in_progress" ]] || fail "job deveria estar preso em in_progress"

echo "== fase 4: reinicio e recuperacao"
echo latency > "$WORK/mode"
start_api E 18085
for _ in $(seq 1 90); do
  [[ "$(sql -c "SELECT count(*) FROM events WHERE status <> 'delivered'")" == "0" ]] && break; sleep 1
done
[[ "$(sql -c "SELECT count(*) FROM events WHERE status <> 'delivered'")" == "0" ]] || { sql -c "SELECT status,count(*) FROM events GROUP BY 1"; fail "eventos nao recuperados"; }
[[ "$(sql -c "SELECT count(*) FROM delivery_jobs WHERE state IN ('pending','in_progress')")" == "0" ]] || fail "jobs abertos restantes"
REQ3=$(grep -c "^$EV3 " "$WORK/dest.log")
[[ "$REQ3" == "2" ]] || fail "evento 300 deveria ter sido reenviado uma vez (at-least-once); requisicoes=$REQ3"
echo "historico do evento 300:"; sql -c "SELECT attempt_number, coalesce(http_status::text,'-'), coalesce(error_message,'-') FROM delivery_attempts WHERE event_id='$EV3' ORDER BY attempt_number"
[[ "$(sql -c "SELECT count(*) FROM delivery_attempts WHERE event_id='$EV3' AND error_message='lease_expired'")" == "1" ]] || fail "tentativa interrompida deveria constar como lease_expired"
[[ "$(sql -c "SELECT count(*) FROM delivery_attempts WHERE event_id='$EV3' AND http_status=200")" == "1" ]] || fail "entrega final deveria constar com 200"
echo "historico do evento 200 (morto depois do claim, antes do HTTP):"; sql -c "SELECT attempt_number, coalesce(http_status::text,'-'), coalesce(error_message,'-') FROM delivery_attempts WHERE event_id='$EV2' ORDER BY attempt_number"
[[ "$(sql -c "SELECT status FROM events WHERE id='$EV2'")" == "delivered" ]] || fail "evento 200 nao entregue"
[[ "$(grep -c "^$EV2 " "$WORK/dest.log")" == "1" ]] || fail "evento 200 deveria chegar ao destino exatamente uma vez (a primeira tentativa nunca saiu)"
[[ "$(sql -c "SELECT count(*) FROM delivery_attempts WHERE event_id='$EV2' AND error_message='lease_expired' AND http_status IS NULL")" == "1" ]] || fail "tentativa interrompida"
[[ "$(sql -c "SELECT count(*) FROM delivery_attempts WHERE event_id='$EV2' AND http_status=200")" == "1" ]] || fail "entrega final"
echo "ok: sem requisicao duplicada quando a morte precede o HTTP; reenvio (at-least-once) quando o destino ja havia recebido"
