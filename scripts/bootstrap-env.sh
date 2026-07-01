#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"

if [[ -f "${ENV_FILE}" && "${FORCE:-0}" != "1" ]]; then
  echo ".env already exists. Set FORCE=1 to regenerate it." >&2
  exit 1
fi

random_secret() {
  openssl rand -base64 36 | tr -d '\n'
}

random_password() {
  openssl rand -base64 18 | tr -d '+/=' | cut -c1-22
}

random_local_email() {
  local prefix="$1"
  printf "audienceflow-%s-%s@example.invalid" "${prefix}" "$(openssl rand -hex 4)"
}

POSTGRES_PASSWORD="$(random_secret)"
INGEST_API_KEY="$(random_secret)"
JWT_SECRET="$(random_secret)$(random_secret)"
ADMIN_EMAIL="$(random_local_email admin)"
TECHNICIAN_EMAIL="$(random_local_email tech)"
TEACHER_EMAIL="$(random_local_email teacher)"
ADMIN_PASSWORD="$(random_password)"
TECHNICIAN_PASSWORD="$(random_password)"
TEACHER_PASSWORD="$(random_password)"

cat > "${ENV_FILE}" <<EOF
POSTGRES_DB=audienceflow
POSTGRES_USER=audienceflow
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}

INGEST_API_KEY=${INGEST_API_KEY}
JWT_SECRET=${JWT_SECRET}

ADMIN_EMAIL=${ADMIN_EMAIL}
ADMIN_PASSWORD=${ADMIN_PASSWORD}
TECHNICIAN_EMAIL=${TECHNICIAN_EMAIL}
TECHNICIAN_PASSWORD=${TECHNICIAN_PASSWORD}
TEACHER_EMAIL=${TEACHER_EMAIL}
TEACHER_PASSWORD=${TEACHER_PASSWORD}

CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000
VITE_API_URL=http://localhost:8080/api
EOF

chmod 600 "${ENV_FILE}"

cat <<EOF
Generated ${ENV_FILE}

Initial credentials:
  ADMIN       ${ADMIN_EMAIL} / ${ADMIN_PASSWORD}
  TECHNICIAN  ${TECHNICIAN_EMAIL} / ${TECHNICIAN_PASSWORD}
  TEACHER     ${TEACHER_EMAIL} / ${TEACHER_PASSWORD}

These credentials are local only and are intentionally not committed.
EOF
