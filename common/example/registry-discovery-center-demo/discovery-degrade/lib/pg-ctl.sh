#!/usr/bin/env bash
# L1 / L2 degrade helpers for registry-discovery-center-demo (sourced by smoke.sh / run-online.sh).
# shellcheck shell=bash

find_psql() {
  if command -v psql >/dev/null 2>&1; then
    command -v psql
    return 0
  fi
  local c
  for c in \
    /opt/homebrew/opt/postgresql@16/bin/psql \
    /opt/homebrew/opt/libpq/bin/psql \
    /opt/homebrew/Cellar/postgresql@16/*/bin/psql
  do
    # shellcheck disable=SC2086
    if [[ -x $c ]]; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

detect_pgdata() {
  if [[ -n "${PGDATA:-}" && -d "$PGDATA" ]]; then
    echo "$PGDATA"
    return 0
  fi
  if [[ -d /opt/homebrew/var/postgresql@16 ]]; then
    echo /opt/homebrew/var/postgresql@16
    return 0
  fi
  if [[ -d /usr/local/var/postgresql@16 ]]; then
    echo /usr/local/var/postgresql@16
    return 0
  fi
  return 1
}

find_pg_ctl() {
  if command -v pg_ctl >/dev/null 2>&1; then
    command -v pg_ctl
    return 0
  fi
  local c
  for c in \
    /opt/homebrew/opt/postgresql@16/bin/pg_ctl \
    /opt/homebrew/Cellar/postgresql@16/*/bin/pg_ctl
  do
    if [[ -x $c ]]; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

pg_is_up() {
  local url="${1:-}"
  local psql_bin
  psql_bin="$(find_psql)" || return 1
  if [[ -n "$url" ]]; then
    "$psql_bin" "$url" -v ON_ERROR_STOP=1 -Atc 'SELECT 1' >/dev/null 2>&1
  else
    "$psql_bin" -h 127.0.0.1 -p 5432 -d postgres -Atc 'SELECT 1' >/dev/null 2>&1
  fi
}

admin_postgres_url() {
  python3 - "${PG_ADMIN_URL:-${DATABASE_URL:?DATABASE_URL required}}" <<'PY'
import sys
u = sys.argv[1]
print(u.rsplit("/", 1)[0] + "/postgres")
PY
}

# L1 default: block only database agent_rdc. Do NOT stop the whole Postgres —
# brew/pg smart stop hangs while RDC holds connections; agentbus shares the instance.
block_agent_rdc_db() {
  local psql_bin admin_pg
  psql_bin="$(find_psql)" || return 1
  admin_pg="$(admin_postgres_url)"
  echo "  INFO  blocking CONNECT to agent_rdc via $admin_pg ..."
  "$psql_bin" "$admin_pg" -v ON_ERROR_STOP=1 -c \
    "REVOKE CONNECT ON DATABASE agent_rdc FROM PUBLIC; REVOKE CONNECT ON DATABASE agent_rdc FROM agent_rdc;"
  "$psql_bin" "$admin_pg" -v ON_ERROR_STOP=1 -Atc \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'agent_rdc' AND pid <> pg_backend_pid();" \
    >/dev/null || true
  echo "  OK  agent_rdc connections blocked"
}

unblock_agent_rdc_db() {
  local psql_bin admin_pg
  psql_bin="$(find_psql)" || return 1
  admin_pg="$(admin_postgres_url)"
  echo "  INFO  restoring CONNECT on agent_rdc..."
  "$psql_bin" "$admin_pg" -v ON_ERROR_STOP=1 -c \
    "GRANT CONNECT ON DATABASE agent_rdc TO agent_rdc; GRANT CONNECT ON DATABASE agent_rdc TO PUBLIC;" \
    >/dev/null
  echo "  OK  agent_rdc CONNECT restored"
}

wait_pg_up() {
  local url="$1" tries="${2:-60}" i
  for i in $(seq 1 "$tries"); do
    if pg_is_up "$url"; then
      return 0
    fi
    sleep 1
  done
  return 1
}
