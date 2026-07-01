#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ ! -f "${ROOT_DIR}/.env" ]]; then
  echo "Missing .env. Run ./scripts/bootstrap-env.sh first." >&2
  exit 1
fi

set -a
source "${ROOT_DIR}/.env"
set +a

curl --fail-with-body \
  --request POST \
  --header "Content-Type: application/json" \
  --header "X-Ingest-Key: ${INGEST_API_KEY}" \
  --data "{
    \"room_id\": 1,
    \"ts\": \"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\",
    \"count\": 24,
    \"confidence\": 0.91,
    \"worker_id\": \"manual-smoke-test\"
  }" \
  http://localhost:8081/v1/events
