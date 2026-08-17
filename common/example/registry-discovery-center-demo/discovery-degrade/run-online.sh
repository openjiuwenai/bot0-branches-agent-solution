#!/usr/bin/env bash
# 一键联机验收（无 Docker）：本机 PG → 起 RDC → smoke（过滤 + 可选 L1/L2）→ 收尾。
#
# Usage:
#   ./run-online.sh                         # 过滤 + L1(需确认) + L2
#   ./run-online.sh --basic-only            # 仅注册/列表/resolve
#   ./run-online.sh --skip-l1 --skip-l2     # 仅过滤
#   ./run-online.sh --filter-only           # 同 --skip-l1 --skip-l2
#   DEGRADE_L1_YES=1 ./run-online.sh        # L1 不交互确认
#   ./run-online.sh --keep                  # 结束后不杀 RDC
#   ./run-online.sh --reuse-rdc
#
# 依赖：curl、python3、mvn、psql；L2 会 package agent-gateway。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/pg-ctl.sh
source "$ROOT/lib/pg-ctl.sh"

RDC_MODULE="$(cd "$ROOT/../../../agent-bus/registry-discovery-center" && pwd)"
RDC_URL="${RDC_URL:-http://127.0.0.1:8092}"
RDC_PORT="${RDC_PORT:-8092}"
DATABASE_URL="${DATABASE_URL:-postgresql://agent_rdc:agent_rdc@127.0.0.1:5432/agent_rdc}"
JDBC_URL="${JDBC_URL:-jdbc:postgresql://127.0.0.1:5432/agent_rdc}"
JDBC_USER="${JDBC_USER:-agent_rdc}"
JDBC_PASSWORD="${JDBC_PASSWORD:-agent_rdc}"
KEEP=0
REUSE_RDC=0
BASIC_ONLY=0
WITH_L1=1
WITH_L2=1
SMOKE_ARGS=()

for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=1 ;;
    --reuse-rdc) REUSE_RDC=1 ;;
    --basic-only) BASIC_ONLY=1; WITH_L1=0; WITH_L2=0; SMOKE_ARGS+=(--basic-only) ;;
    --skip-l1) WITH_L1=0 ;;
    --skip-l2) WITH_L2=0 ;;
    --filter-only) WITH_L1=0; WITH_L2=0 ;;
    --with-l1-degrade) WITH_L1=1 ;;
    --with-l2-degrade) WITH_L2=1 ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *)
      echo "unknown arg: $arg" >&2
      exit 2
      ;;
  esac
done

[[ "$WITH_L1" -eq 1 && "$BASIC_ONLY" -eq 0 ]] && SMOKE_ARGS+=(--with-l1-degrade)
[[ "$WITH_L2" -eq 1 && "$BASIC_ONLY" -eq 0 ]] && SMOKE_ARGS+=(--with-l2-degrade)

RDC_PID=""
RDC_LOG="${ROOT}/.run-online-rdc.log"

cleanup() {
  local rc=$?
  if [[ "$KEEP" -eq 0 && -n "$RDC_PID" ]]; then
    echo "== cleanup RDC pid=$RDC_PID =="
    kill "$RDC_PID" 2>/dev/null || true
    # spring-boot:run may leave child java; kill process group if possible
    kill -- -"$RDC_PID" 2>/dev/null || true
    wait "$RDC_PID" 2>/dev/null || true
  fi
  exit "$rc"
}
trap cleanup EXIT

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing command: $1" >&2; exit 1; }
}

need_cmd curl
need_cmd python3
need_cmd mvn
if ! find_psql >/dev/null; then
  echo "missing command: psql (try: brew install libpq && brew link --force libpq)" >&2
  exit 1
fi
# Ensure smoke can find psql even if not on PATH
export PATH="$(dirname "$(find_psql)"):$PATH"

wait_http() {
  local url="$1" name="$2" tries="${3:-60}"
  local i code
  for i in $(seq 1 "$tries"); do
    if [[ -n "${RDC_PID:-}" ]] && ! kill -0 "$RDC_PID" 2>/dev/null; then
      echo "  FAIL  $name process exited early (pid=$RDC_PID)" >&2
      return 1
    fi
    code="$(curl -sS -o /dev/null -m 2 -w "%{http_code}" "$url" 2>/dev/null || true)"
    if [[ "$code" =~ ^[2345] ]]; then
      echo "  OK  $name ready (HTTP $code)"
      return 0
    fi
    if (( i % 10 == 0 )); then
      echo "  ... waiting $name (${i}s/${tries}s) last_http=${code:-none}"
    fi
    sleep 1
  done
  echo "  FAIL  $name not ready after ${tries}s → $url" >&2
  return 1
}

echo "== 1) 本机 PostgreSQL =="
echo "  DATABASE_URL=$DATABASE_URL"
if ! psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -Atc 'SELECT 1' >/dev/null; then
  echo "FAIL  连不上本机 PostgreSQL。" >&2
  exit 1
fi
echo "  OK  psql SELECT 1"

echo "== 2) RDC ($RDC_URL) =="
RDC_PROBE="$RDC_URL/api/registry/instances/smoke-tenant/smoke-agent"
if [[ "$REUSE_RDC" -eq 1 ]] && curl -sS -o /dev/null -m 2 -w "%{http_code}" "$RDC_PROBE" 2>/dev/null | grep -qE '^[2345]'; then
  echo "  OK  reuse existing RDC"
else
  if curl -sS -o /dev/null -m 2 -w "%{http_code}" "$RDC_PROBE" 2>/dev/null | grep -qE '^[2345]'; then
    echo "  WARN  port $RDC_PORT already answers; reuse it"
  else
    echo "  INFO  starting RDC (log: $RDC_LOG)"
    : >"$RDC_LOG"
    (
      cd "$RDC_MODULE"
      export SPRING_DATASOURCE_URL="$JDBC_URL"
      export SPRING_DATASOURCE_USERNAME="$JDBC_USER"
      export SPRING_DATASOURCE_PASSWORD="$JDBC_PASSWORD"
      export SERVER_PORT="$RDC_PORT"
      export RDC_DEPLOYMENT_DISCOVERY_ENABLED=false
      export SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:${ROOT}/application-example.yml"
      exec mvn -q spring-boot:run
    ) >"$RDC_LOG" 2>&1 &
    RDC_PID=$!
    if ! wait_http "$RDC_PROBE" "RDC" 180; then
      echo "---- RDC log (tail) ----" >&2
      tail -n 80 "$RDC_LOG" >&2 || true
      exit 1
    fi
  fi
fi

echo "== 3) smoke =="
export RDC_URL DATABASE_URL
"$ROOT/smoke.sh" "${SMOKE_ARGS[@]}"

echo "== ALL CHECKS PASSED =="
