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
  local random_part
  random_part="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-24)"
  printf "%saA9!" "${random_part}"
}

random_local_email() {
  local prefix="$1"
  printf "audienceflow-%s-%s@example.invalid" "${prefix}" "$(openssl rand -hex 4)"
}

lowercase() {
  printf "%s" "$1" | tr '[:upper:]' '[:lower:]'
}

is_strong_password() {
  local value="$1"
  local lower
  lower="$(lowercase "${value}")"
  [[ ${#value} -ge 14 && ${#value} -le 128 ]] || return 1
  [[ "${lower}" != "admin" ]] || return 1
  [[ "${lower}" != *password* ]] || return 1
  [[ "${lower}" != *qwerty* ]] || return 1
  [[ "${lower}" != audienceflow* ]] || return 1

  local classes=0
  [[ "${value}" =~ [a-z] ]] && classes=$((classes + 1))
  [[ "${value}" =~ [A-Z] ]] && classes=$((classes + 1))
  [[ "${value}" =~ [0-9] ]] && classes=$((classes + 1))
  [[ "${value}" =~ [^a-zA-Z0-9] ]] && classes=$((classes + 1))
  [[ ${classes} -ge 3 ]]
}

prompt_email() {
  local label="$1"
  local value
  while true; do
    printf "%s email: " "${label}" >&2
    read -r value
    if [[ "${value}" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] && [[ "$(lowercase "${value}")" != "admin" ]]; then
      printf "%s" "${value}"
      return
    fi
    echo "Enter a real email address, not a default login." >&2
  done
}

prompt_password() {
  local label="$1"
  local value
  local confirm
  while true; do
    printf "%s password (blank = generate secure password): " "${label}" >&2
    read -r -s value
    echo >&2
    if [[ -z "${value}" ]]; then
      value="$(random_password)"
      printf "%s generated password: %s\n" "${label}" "${value}" >&2
      printf "%s" "${value}"
      return
    fi
    if ! is_strong_password "${value}"; then
      echo "Password must be 14-128 chars, use at least 3 character classes, and avoid common words." >&2
      continue
    fi
    printf "Repeat %s password: " "${label}" >&2
    read -r -s confirm
    echo >&2
    if [[ "${value}" != "${confirm}" ]]; then
      echo "Passwords do not match." >&2
      continue
    fi
    printf "%s" "${value}"
    return
  done
}

POSTGRES_PASSWORD="$(random_secret)"
INGEST_API_KEY="$(random_secret)"
JWT_SECRET="$(random_secret)$(random_secret)"

if [[ "${INTERACTIVE:-0}" == "1" ]]; then
  if [[ ! -t 0 ]]; then
    echo "INTERACTIVE=1 requires a terminal." >&2
    exit 1
  fi
  echo "Interactive first-run setup. Enter real emails. Leave a password blank to generate one." >&2
  ADMIN_EMAIL="$(prompt_email "Admin")"
  TECHNICIAN_EMAIL="$(prompt_email "Technician")"
  TEACHER_EMAIL="$(prompt_email "Teacher")"
  ADMIN_PASSWORD="$(prompt_password "Admin")"
  TECHNICIAN_PASSWORD="$(prompt_password "Technician")"
  TEACHER_PASSWORD="$(prompt_password "Teacher")"
else
  ADMIN_EMAIL="$(random_local_email admin)"
  TECHNICIAN_EMAIL="$(random_local_email tech)"
  TEACHER_EMAIL="$(random_local_email teacher)"
  ADMIN_PASSWORD="$(random_password)"
  TECHNICIAN_PASSWORD="$(random_password)"
  TEACHER_PASSWORD="$(random_password)"
fi

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
EOF

if [[ "${INTERACTIVE:-0}" == "1" ]]; then
  cat <<EOF
Initial users:
  ADMIN       ${ADMIN_EMAIL}
  TECHNICIAN  ${TECHNICIAN_EMAIL}
  TEACHER     ${TEACHER_EMAIL}

Passwords were entered interactively or printed only when generated.
The .env file is local-only and has permissions 600.
EOF
else
  cat <<EOF
Initial credentials:
  ADMIN       ${ADMIN_EMAIL} / ${ADMIN_PASSWORD}
  TECHNICIAN  ${TECHNICIAN_EMAIL} / ${TECHNICIAN_PASSWORD}
  TEACHER     ${TEACHER_EMAIL} / ${TEACHER_PASSWORD}

These credentials are local only and are intentionally not committed.
EOF
fi
