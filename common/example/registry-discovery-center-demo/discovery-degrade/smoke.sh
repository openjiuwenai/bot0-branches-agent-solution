#!/usr/bin/env bash
# RDC 发现过滤 + 可选 L1/L2 降级 — 联机冒烟（无 Docker）。
#
# Usage:
#   RDC_URL=http://127.0.0.1:8092 \
#   DATABASE_URL=postgresql://agent_rdc:agent_rdc@127.0.0.1:5432/agent_rdc \
#   ./smoke.sh
#
#   ./smoke.sh --basic-only
#   ./smoke.sh --with-l1-degrade          # 需交互确认（或 DEGRADE_L1_YES=1）
#   ./smoke.sh --with-l2-degrade          # fake_rdc + fake_runtime + Gateway
#
# 依赖：curl、python3、psql；L2 另需 mvn（打包 Gateway）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/pg-ctl.sh
source "$ROOT/lib/pg-ctl.sh"

: "${RDC_URL:?RDC_URL is required (e.g. http://127.0.0.1:8092)}"
RDC="${RDC_URL%/}"
TENANT="smoke-tenant"
AGENT="smoke-agent"
BASIC_ONLY=0
WITH_L1=0
WITH_L2=0
for arg in "$@"; do
  case "$arg" in
    --basic-only) BASIC_ONLY=1 ;;
    --with-l1-degrade) WITH_L1=1 ;;
    --with-l2-degrade) WITH_L2=1 ;;
    -h|--help)
      sed -n '2,16p' "$0"
      exit 0
      ;;
    *)
      echo "unknown arg: $arg" >&2
      exit 2
      ;;
  esac
done

tmpdir="$(mktemp -d)"
FAKE_RDC_PID=""
FAKE_RT_PID=""
GW_PID=""
DB_BLOCKED=0
trap '
  [[ -n "${FAKE_RDC_PID:-}" ]] && kill "$FAKE_RDC_PID" 2>/dev/null || true
  [[ -n "${FAKE_RT_PID:-}" ]] && kill "$FAKE_RT_PID" 2>/dev/null || true
  [[ -n "${GW_PID:-}" ]] && kill "$GW_PID" 2>/dev/null || true
  if [[ "${DB_BLOCKED:-0}" -eq 1 ]]; then
    echo "  INFO  restoring agent_rdc CONNECT after L1..."
    unblock_agent_rdc_db || true
  fi
  rm -rf "$tmpdir"
' EXIT

pass=0
fail=0
skip=0

http_code() { tr -d '\r' <"$1"; }

assert_http() {
  local name="$1" expected="$2" code_file="$3"
  local got; got="$(http_code "$code_file")"
  if [[ "$got" == "$expected" ]]; then
    echo "  PASS  $name (HTTP $got)"; pass=$((pass + 1))
  else
    echo "  FAIL  $name (expected HTTP $expected, got $got) body=$(head -c 200 "$tmpdir/b" 2>/dev/null || true)"
    fail=$((fail + 1))
  fi
}

assert_json_array_min() {
  local name="$1" min="$2" body_file="$3"
  python3 - "$body_file" "$min" "$name" <<'PY' || true
import json, sys
path, vmin, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
try:
    data = json.load(open(path, encoding="utf-8"))
except Exception as e:
    print(f"  FAIL  {name} (json: {e})")
    sys.exit(2)
if not isinstance(data, list) or len(data) < vmin:
    print(f"  FAIL  {name} (len={len(data) if isinstance(data, list) else type(data).__name__})")
    sys.exit(2)
print(f"  PASS  {name} (len={len(data)})")
sys.exit(0)
PY
  local rc=$?
  if [[ $rc -eq 0 ]]; then pass=$((pass + 1)); else fail=$((fail + 1)); fi
}

assert_json_array_eq() {
  local name="$1" expected="$2" body_file="$3"
  python3 - "$body_file" "$expected" "$name" <<'PY' || true
import json, sys
path, exp, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
data = json.load(open(path, encoding="utf-8"))
ok = isinstance(data, list) and len(data) == exp
print(f"  {'PASS' if ok else 'FAIL'}  {name} (len={len(data) if isinstance(data, list) else type(data).__name__}, expected={exp})")
sys.exit(0 if ok else 2)
PY
  local rc=$?
  if [[ $rc -eq 0 ]]; then pass=$((pass + 1)); else fail=$((fail + 1)); fi
}

assert_body_has() {
  local name="$1" needle="$2" body_file="$3"
  if grep -q "$needle" "$body_file"; then
    echo "  PASS  $name"; pass=$((pass + 1))
  else
    echo "  FAIL  $name (missing $needle) body=$(head -c 180 "$body_file")"; fail=$((fail + 1))
  fi
}

assert_body_lacks() {
  local name="$1" needle="$2" body_file="$3"
  if grep -q "$needle" "$body_file"; then
    echo "  FAIL  $name (unexpected $needle)"; fail=$((fail + 1))
  else
    echo "  PASS  $name"; pass=$((pass + 1))
  fi
}

psql_q() {
  if [[ -z "${DATABASE_URL:-}" ]]; then
    echo "DATABASE_URL is required for filter / seed mutations" >&2
    return 1
  fi
  local psql_bin
  psql_bin="$(find_psql)" || {
    echo "psql not found; install PostgreSQL client" >&2
    return 1
  }
  "$psql_bin" "$DATABASE_URL" -v ON_ERROR_STOP=1 -Atc "$1"
}

extract_handle() {
  python3 - "$1" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
print(data[0]["routeHandle"])
PY
}

echo "== RDC discovery/degrade online smoke → $RDC =="

echo "-- cleanup --"
curl -sS -o /dev/null -w "%{http_code}" -X DELETE \
  "$RDC/api/registry/deregister/${TENANT}/${AGENT}" >"$tmpdir/c" || true
echo "  INFO  deregister HTTP $(http_code "$tmpdir/c")"

echo "-- register --"
curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$RDC/api/registry/register" \
  -H 'Content-Type: application/json' \
  --data-binary @"$ROOT/bodies/register-alive.json" >"$tmpdir/c" || true
assert_http "register alive" "200" "$tmpdir/c"

curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$RDC/api/registry/register" \
  -H 'Content-Type: application/json' \
  --data-binary @"$ROOT/bodies/register-peer.json" >"$tmpdir/c" || true
assert_http "register peer" "200" "$tmpdir/c"

echo "-- list + resolve --"
curl -sS -o "$tmpdir/b" -w "%{http_code}" \
  "$RDC/api/registry/instances/${TENANT}/${AGENT}" >"$tmpdir/c" || true
assert_http "list instances" "200" "$tmpdir/c"
assert_json_array_min "list has >=1 ONLINE" 1 "$tmpdir/b"
assert_body_has "list carries routeHandle" "routeHandle" "$tmpdir/b"
assert_body_lacks "list hides endpointUrl" "endpointUrl" "$tmpdir/b"

HANDLE="$(extract_handle "$tmpdir/b")"
curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$RDC/api/registry/route-handle/resolve" \
  -H 'Content-Type: application/json' \
  -H 'X-Caller-Ref: smoke' \
  -d "{\"routeHandle\":\"${HANDLE}\",\"tenantId\":\"${TENANT}\"}" >"$tmpdir/c" || true
assert_http "resolve handle" "200" "$tmpdir/c"
assert_body_has "resolve returns endpointUrl" "endpointUrl" "$tmpdir/b"

if [[ "$BASIC_ONLY" -eq 1 ]]; then
  echo
  echo "======== summary (basic-only) ========"
  echo "  PASS=$pass FAIL=$fail SKIP=$skip"
  [[ "$fail" -eq 0 ]]
  exit $?
fi

if [[ -z "${DATABASE_URL:-}" ]]; then
  echo "  SKIP  filter cases (set DATABASE_URL to enable)"
  skip=$((skip + 1))
else
  echo "-- filter: DRAINING excluded --"
  psql_q "UPDATE agent_registry_mvp SET status='DRAINING', lifecycle_status='DRAINING'
            WHERE tenant_id='${TENANT}' AND agent_id='${AGENT}'
              AND endpoint_url LIKE '%18091%';"
  curl -sS -o "$tmpdir/b" -w "%{http_code}" \
    "$RDC/api/registry/instances/${TENANT}/${AGENT}" >"$tmpdir/c" || true
  assert_http "list after DRAINING" "200" "$tmpdir/c"
  assert_json_array_eq "only non-DRAINING visible" 1 "$tmpdir/b"
  assert_body_lacks "no DRAINING health in list" '"health":"DRAINING"' "$tmpdir/b"

  echo "-- filter: DEGRADED within 15s still visible --"
  psql_q "UPDATE agent_registry_mvp SET status='DEGRADED', lifecycle_status='ACTIVE',
              last_heartbeat = NOW() - INTERVAL '10 seconds'
            WHERE tenant_id='${TENANT}' AND agent_id='${AGENT}'
              AND endpoint_url LIKE '%18090%';"
  curl -sS -o "$tmpdir/b" -w "%{http_code}" \
    "$RDC/api/registry/instances/${TENANT}/${AGENT}" >"$tmpdir/c" || true
  assert_http "list DEGRADED" "200" "$tmpdir/c"
  assert_json_array_min "DEGRADED still listed" 1 "$tmpdir/b"
  assert_body_has "health DEGRADED" "DEGRADED" "$tmpdir/b"

  echo "-- filter: heartbeat older than 15s excluded --"
  psql_q "UPDATE agent_registry_mvp SET status='ONLINE', lifecycle_status='ACTIVE',
              last_heartbeat = NOW() - INTERVAL '16 seconds'
            WHERE tenant_id='${TENANT}' AND agent_id='${AGENT}'
              AND endpoint_url LIKE '%18090%';"
  curl -sS -o "$tmpdir/b" -w "%{http_code}" \
    "$RDC/api/registry/instances/${TENANT}/${AGENT}" >"$tmpdir/c" || true
  assert_http "list after stale heartbeat" "200" "$tmpdir/c"
  assert_json_array_eq "stale heartbeat hidden" 0 "$tmpdir/b"

  psql_q "UPDATE agent_registry_mvp SET status='ONLINE', lifecycle_status='ACTIVE',
              last_heartbeat = NOW()
            WHERE tenant_id='${TENANT}' AND agent_id='${AGENT}'
              AND endpoint_url LIKE '%18090%';"
  psql_q "UPDATE agent_registry_mvp SET status='ONLINE', lifecycle_status='ACTIVE',
              last_heartbeat = NOW()
            WHERE tenant_id='${TENANT}' AND agent_id='${AGENT}'
              AND endpoint_url LIKE '%18091%';"
fi

# ---------- L1: block agent_rdc CONNECT, RDC serves cache ----------
if [[ "$WITH_L1" -eq 1 ]]; then
  echo "-- L1 degrade: warm cache then block agent_rdc CONNECT --"
  if [[ -z "${DATABASE_URL:-}" ]]; then
    echo "  FAIL  L1 needs DATABASE_URL"; fail=$((fail + 1))
  else
    curl -sS -o "$tmpdir/b" -w "%{http_code}" \
      "$RDC/api/registry/instances/${TENANT}/${AGENT}" >"$tmpdir/c" || true
    assert_http "L1 warm list" "200" "$tmpdir/c"
    assert_json_array_min "L1 warm has candidates" 1 "$tmpdir/b"

    if [[ "${DEGRADE_L1_YES:-}" == "1" ]]; then
      echo "  INFO  DEGRADE_L1_YES=1 — skip interactive confirm"
    else
      echo
      echo "  !!! L1 将暂时切断库 agent_rdc 的连接（不停止整个 PostgreSQL，不影响 agentbus）。"
      echo "  !!! 请输入 yes 继续（大小写均可），其它键跳过 L1："
      read -r ans || ans=""
      ans_norm="$(printf '%s' "$ans" | tr '[:upper:]' '[:lower:]')"
      if [[ "$ans_norm" != "yes" ]]; then
        echo "  SKIP  L1 (user declined)"
        skip=$((skip + 1))
        WITH_L1=0
      fi
    fi
  fi
fi

if [[ "$WITH_L1" -eq 1 ]]; then
  echo "  INFO  starting L1 block..."
  block_agent_rdc_db
  DB_BLOCKED=1
  sleep 1
  curl -sS -o "$tmpdir/b" -w "%{http_code}" \
    "$RDC/api/registry/instances/${TENANT}/${AGENT}" >"$tmpdir/c" || true
  assert_http "L1 list while agent_rdc blocked (cached)" "200" "$tmpdir/c"
  assert_json_array_min "L1 cached candidates returned" 1 "$tmpdir/b"

  unblock_agent_rdc_db
  DB_BLOCKED=0
  echo "  PASS  L1 agent_rdc CONNECT restored"; pass=$((pass + 1))
fi

# ---------- L2: fake RDC 503 + Gateway local cache ----------
if [[ "$WITH_L2" -eq 1 ]]; then
  echo "-- L2 degrade: fake_rdc fail-after + Gateway --"
  GW_MODULE="$(cd "$ROOT/../../../agent-bus/agent-gateway" && pwd)"
  GW_JAR="$GW_MODULE/target/agent-gateway-0.1.0.jar"
  FAKE_RDC_PORT="${FAKE_RDC_PORT:-18092}"
  FAKE_RT_PORT="${FAKE_RT_PORT:-18094}"
  GW_PORT="${GATEWAY_PORT:-8080}"
  GW_URL="http://127.0.0.1:${GW_PORT}"

  if ! command -v mvn >/dev/null 2>&1; then
    echo "  FAIL  mvn required for L2"; fail=$((fail + 1))
  else
    echo "  INFO  packaging agent-gateway (ensure latest HttpRdcRouteClient)..."
    mvn -f "$GW_MODULE/pom.xml" -q -DskipTests package
    if [[ ! -f "$GW_JAR" ]]; then
      echo "  FAIL  missing $GW_JAR"; fail=$((fail + 1))
    else
      python3 "$ROOT/stubs/fake_runtime.py" --port "$FAKE_RT_PORT" >"$tmpdir/fake-rt.log" 2>&1 &
      FAKE_RT_PID=$!
      python3 "$ROOT/stubs/fake_rdc.py" --port "$FAKE_RDC_PORT" --mode fail-after --ok-count 2 \
        --runtime-base "http://127.0.0.1:${FAKE_RT_PORT}" >"$tmpdir/fake-rdc.log" 2>&1 &
      FAKE_RDC_PID=$!
      sleep 1

      (
        export SERVER_PORT="$GW_PORT"
        export GATEWAY_RDC_BASE_URL="http://127.0.0.1:${FAKE_RDC_PORT}"
        export SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:${ROOT}/application-gateway-l2.yml"
        exec java -jar "$GW_JAR"
      ) >"$tmpdir/gateway.log" 2>&1 &
      GW_PID=$!

      gw_ready=0
      for _ in $(seq 1 90); do
        if ! kill -0 "$GW_PID" 2>/dev/null; then
          break
        fi
        code="$(curl -sS -o /dev/null -m 2 -w "%{http_code}" -X POST "$GW_URL/a2a" \
          -H 'Content-Type: application/json' -d '{}' 2>/dev/null || true)"
        # 401 AUTH_* means server is up
        if [[ "$code" == "401" || "$code" == "200" || "$code" == "400" ]]; then
          gw_ready=1
          break
        fi
        sleep 1
      done
      if [[ "$gw_ready" -ne 1 ]]; then
        echo "  FAIL  Gateway not ready"; fail=$((fail + 1))
        tail -n 40 "$tmpdir/gateway.log" || true
      else
        echo "  OK  Gateway ready"
        # 1st call: warm RDC cache (fake allows 2 OK: search+resolve)
        mid="smoke-l2-$(date +%s)-$$"
        python3 - "$ROOT/bodies/gateway-create.json" "$mid" "$tmpdir/create1.json" <<'PY'
import json, sys
src, mid, dst = sys.argv[1:4]
doc = json.load(open(src, encoding="utf-8"))
doc["id"] = mid
doc["params"]["message"]["messageId"] = mid + "-m"
json.dump(doc, open(dst, "w", encoding="utf-8"))
PY
        curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW_URL/a2a" \
          -H 'Content-Type: application/json' \
          -H 'Authorization: Bearer mock-token' \
          --data-binary @"$tmpdir/create1.json" >"$tmpdir/c" || true
        assert_http "L2 first /a2a (warm)" "200" "$tmpdir/c"
        assert_body_has "L2 first hits fake-runtime" "fake-runtime" "$tmpdir/b"

        # 2nd call: fake RDC returns 503; Gateway must use LocalRdcRouteCache
        mid2="${mid}-2"
        python3 - "$ROOT/bodies/gateway-create.json" "$mid2" "$tmpdir/create2.json" <<'PY'
import json, sys
src, mid, dst = sys.argv[1:4]
doc = json.load(open(src, encoding="utf-8"))
doc["id"] = mid
doc["params"]["message"]["messageId"] = mid + "-m"
json.dump(doc, open(dst, "w", encoding="utf-8"))
PY
        curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW_URL/a2a" \
          -H 'Content-Type: application/json' \
          -H 'Authorization: Bearer mock-token' \
          --data-binary @"$tmpdir/create2.json" >"$tmpdir/c" || true
        assert_http "L2 second /a2a (RDC 503, cached)" "200" "$tmpdir/c"
        assert_body_has "L2 second still reaches runtime" "fake-runtime" "$tmpdir/b"
        assert_body_lacks "L2 not ROUTE_NO_CANDIDATES" "ROUTE_NO_CANDIDATES" "$tmpdir/b"
      fi
    fi
  fi
fi

echo
echo "======== summary ========"
echo "  PASS=$pass FAIL=$fail SKIP=$skip"
if [[ "$fail" -ne 0 ]]; then
  exit 1
fi
echo "  OK"
