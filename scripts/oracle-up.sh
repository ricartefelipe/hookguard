#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "Crie .env a partir de env.oracle.example"
  exit 1
fi

# shellcheck disable=SC1091
set -a
source .env
set +a

if [[ -z "${HOOKGUARD_DB_PASSWORD:-}" ]]; then
  echo "HOOKGUARD_DB_PASSWORD obrigatório no .env"
  exit 1
fi

if [[ -z "${HOOKGUARD_PUBLIC_ORIGIN:-}" ]]; then
  echo "HOOKGUARD_PUBLIC_ORIGIN será preenchido após o túnel subir (deixe placeholder e rode de novo),"
  echo "ou defina agora se já tiver URL/domínio."
fi

echo "==> Build + up (na VM ARM — não use jar x86)"
docker compose -f docker-compose.oracle.yml up -d --build

echo "==> Aguardando túnel..."
PUBLIC_URL=""
for _ in $(seq 1 60); do
  PUBLIC_URL="$(docker compose -f docker-compose.oracle.yml logs tunnel 2>/dev/null \
    | grep -oE 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' \
    | tail -n 1 || true)"
  if [[ -n "${PUBLIC_URL}" ]]; then
    break
  fi
  sleep 2
done

if [[ -n "${PUBLIC_URL}" ]]; then
  if ! grep -q "^HOOKGUARD_PUBLIC_ORIGIN=${PUBLIC_URL}$" .env 2>/dev/null; then
    if grep -q '^HOOKGUARD_PUBLIC_ORIGIN=' .env; then
      sed -i "s|^HOOKGUARD_PUBLIC_ORIGIN=.*|HOOKGUARD_PUBLIC_ORIGIN=${PUBLIC_URL}|" .env
    else
      echo "HOOKGUARD_PUBLIC_ORIGIN=${PUBLIC_URL}" >> .env
    fi
    echo "==> Atualizei HOOKGUARD_PUBLIC_ORIGIN=${PUBLIC_URL} — recriando API"
    docker compose -f docker-compose.oracle.yml up -d --force-recreate api
  fi
  echo
  echo "HookGuard 24/7:"
  echo "  ${PUBLIC_URL}"
  echo "  Ingest: ${PUBLIC_URL}/v1/ingest/{projectKey}"
else
  echo "Sem URL de túnel ainda. Logs: docker compose -f docker-compose.oracle.yml logs -f tunnel"
fi
