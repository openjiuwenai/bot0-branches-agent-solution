#!/usr/bin/env bash
# DFX-001 OTel 轨迹上报 smoke 验证：
#   1. 启动模拟 Collector（otlp_relay.py serve，落 jsonl）
#   2. 运行 OtlpRelayCheckIT（经真实 OTLP gRPC 发送合同 span 树）
#   3. 校验 jsonl：span 树形 / 同 trace / session.id 全覆盖 / JSON 合法性
#
# 用法：
#   RELAY_SCRIPT=/path/to/otlp_relay.py bash smoke-otel-trajectory.sh
#   若不提供 RELAY_SCRIPT，则假定 4317 已有 Collector（jsonl 校验跳过）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
MVN="${MVN:-mvn}"
GRPC_PORT="${GRPC_PORT:-4317}"
TMP_DIR="$(mktemp -d)"
JSONL="$TMP_DIR/smoke.jsonl"
RELAY_PID=""

if command -v python3 >/dev/null 2>&1; then PYTHON=python3;
elif command -v python >/dev/null 2>&1; then PYTHON=python;
elif command -v py >/dev/null 2>&1; then PYTHON=py;
else echo "FAIL: python3/python/py required" >&2; exit 1; fi

cleanup() {
  if [ -n "$RELAY_PID" ]; then kill "$RELAY_PID" 2>/dev/null || true; fi
  rm -rf "$TMP_DIR" 2>/dev/null || true  # Windows 下 relay 退出瞬间 jsonl 可能仍被占用
}
trap cleanup EXIT

fail() { printf 'FAIL %s\n' "$1" >&2; exit 1; }
pass() { printf 'PASS %s\n' "$1"; }

# 1) 起模拟 Collector（可选）
if [ -n "${RELAY_SCRIPT:-}" ]; then
  [ -f "$RELAY_SCRIPT" ] || fail "RELAY_SCRIPT not found: $RELAY_SCRIPT"
  "$PYTHON" "$RELAY_SCRIPT" serve --jsonl "$JSONL" --grpc-port "$GRPC_PORT" >"$TMP_DIR/relay.log" 2>&1 &
  RELAY_PID=$!
  sleep 3
  kill -0 "$RELAY_PID" 2>/dev/null || { cat "$TMP_DIR/relay.log" >&2; fail "relay failed to start"; }
  pass "relay started on :$GRPC_PORT"
else
  printf 'INFO RELAY_SCRIPT not set, assume collector already on :%s\n' "$GRPC_PORT"
fi

# 2) 发送合同 span 树（真实 OTLP gRPC）
( cd "$MODULE_DIR" && "$MVN" -q test -Dtest=OtlpRelayCheckIT -DfailIfNoTests=false ) \
  || fail "OtlpRelayCheckIT failed"
pass "OtlpRelayCheckIT sent spans"

# 3) 校验 jsonl
[ -n "${RELAY_SCRIPT:-}" ] || { pass "no local jsonl to validate (external collector mode)"; exit 0; }
sleep 2
[ -s "$JSONL" ] || fail "jsonl empty: $TMP_DIR/smoke.jsonl (see $TMP_DIR/relay.log)"
JSONL_PATH="$JSONL" "$PYTHON" - <<'PYEOF'
import json, os, sys

spans = [json.loads(line) for line in open(os.environ["JSONL_PATH"], encoding="utf-8")]
names = {s["name"] for s in spans}
errors = []

for required in ("http.request",):
    if required not in names:
        errors.append(f"missing span {required}")
if not any(n.startswith("chain.") for n in names):
    errors.append("missing chain.* span")
if not any(n.startswith("llm.") for n in names):
    errors.append("missing llm.* span")
if not any(n.startswith("tool.") for n in names):
    errors.append("missing tool.* span")

by_name = {}
for s in spans:
    by_name.setdefault(s["name"], s)
http = by_name.get("http.request")
chain = next((s for n, s in by_name.items() if n.startswith("chain.")), None)
if http and chain:
    if chain["trace_id"] != http["trace_id"]:
        errors.append("chain trace_id != http trace_id (bridge broken)")
    if chain["parent_span_id"] != http["span_id"]:
        errors.append("chain parent_span_id != http span_id (bridge broken)")

for s in spans:
    if "session.id" not in s["attributes"]:
        errors.append(f"span {s['name']} missing session.id")
    for key in ("openjiuwen.agent.inputs", "openjiuwen.agent.outputs",
                "gen_ai.prompt", "gen_ai.completion"):
        v = s["attributes"].get(key)
        if isinstance(v, str):
            try:
                json.loads(v)
            except Exception:
                errors.append(f"span {s['name']} attribute {key} is not legal JSON")

if errors:
    for e in errors:
        print("FAIL", e)
    sys.exit(1)
print(f"PASS validated {len(spans)} spans: tree/session.id/JSON all OK")
PYEOF

pass "smoke-otel-trajectory OK"
