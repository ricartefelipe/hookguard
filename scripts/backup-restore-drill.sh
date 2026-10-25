#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="hookguard-restore-drill"
PORT="${HOOKGUARD_DRILL_PORT:-55432}"
API_PORT="${HOOKGUARD_DRILL_API_PORT:-18080}"
MARKER="drill@example.com"

if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  echo "container $NAME already exists; recusando para nao mexer em outro ambiente" >&2
  exit 1
fi

cleanup() {
  if [[ -n "${API_PID:-}" ]]; then
    kill "$API_PID" >/dev/null 2>&1 || true
    wait "$API_PID" >/dev/null 2>&1 || true
  fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name "$NAME" \
  -e POSTGRES_USER=hookguard \
  -e POSTGRES_PASSWORD=hookguard \
  -e POSTGRES_DB=hookguard \
  -p "127.0.0.1:${PORT}:5432" \
  postgres:16-alpine >/dev/null

for _ in $(seq 1 30); do
  if docker exec "$NAME" pg_isready -U hookguard -d hookguard >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$NAME" pg_isready -U hookguard -d hookguard >/dev/null

(
  cd "$ROOT/apps/api"
  HOOKGUARD_DB_URL="jdbc:postgresql://127.0.0.1:${PORT}/hookguard" \
  HOOKGUARD_DB_USER=hookguard \
  HOOKGUARD_DB_PASSWORD=hookguard \
  HOOKGUARD_WORKER_ENABLED=false \
  HOOKGUARD_PORT="$API_PORT" \
  HOOKGUARD_DELIVERY_ALLOW_HTTP=false \
  mvn -q -DskipTests spring-boot:run
) &
API_PID=$!

for _ in $(seq 1 90); do
  if curl -sf "http://127.0.0.1:${API_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
curl -sf "http://127.0.0.1:${API_PORT}/actuator/health/readiness" >/dev/null

docker exec "$NAME" psql -U hookguard -d hookguard -v ON_ERROR_STOP=1 -c \
  "INSERT INTO accounts (id, email, name, plan, created_at) VALUES ('11111111-1111-1111-1111-111111111111', '${MARKER}', 'Drill', 'free', now());"

docker exec "$NAME" pg_dump -U hookguard -d hookguard -Fc -f /tmp/hookguard.dump
docker exec "$NAME" psql -U hookguard -d hookguard -v ON_ERROR_STOP=1 -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
docker exec "$NAME" pg_restore -U hookguard -d hookguard --no-owner /tmp/hookguard.dump
FOUND="$(docker exec "$NAME" psql -U hookguard -d hookguard -tAc "SELECT email FROM accounts WHERE email = '${MARKER}'")"
if [[ "$FOUND" != "$MARKER" ]]; then
  echo "restore nao devolveu o marcador: [$FOUND]" >&2
  exit 1
fi

echo "restore ok: $MARKER"
