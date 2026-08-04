#!/usr/bin/env bash
# FEAT-011 DIRECT — expanded smoke (G1–G5 + S2/S5/idem/sticky), curl as Client stub.
#
# Substitutes:
#   晓娜 verification-app / 翼维 SDK transport → curl POST /a2a
#   国庆 RDC + 下游 Runtime → live 联调栈, OR ./stubs/downstream_stub.py
#
# Usage:
#   GATEWAY_URL=<gateway-url> GATEWAY_TOKEN=<token> ./smoke.sh
#   GATEWAY_URL=<gateway-url> GATEWAY_TOKEN=<token> ./smoke.sh --governance-only
#   GATEWAY_URL=<gateway-url> GATEWAY_TOKEN=<token> ./smoke.sh --skip-forward
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
: "${GATEWAY_URL:?GATEWAY_URL is required}"
: "${GATEWAY_TOKEN:?GATEWAY_TOKEN is required}"
GW="$GATEWAY_URL"
TOKEN="$GATEWAY_TOKEN"
GOV_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --governance-only|--skip-forward) GOV_ONLY=1 ;;
    --with-create) ;; # accepted for back-compat; full suite always includes create when not gov-only
  esac
done

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
pass=0
fail=0
skip=0

post() {
  # post <name> <code_file> <body_file> [curl args...]
  local name="$1" code_file="$2" body_file="$3"
  shift 3
  curl -sS -o "$body_file" -w "%{http_code}" -X POST "$GW/a2a" "$@" >"$code_file" || true
}

http_code() { tr -d '\r' <"$1"; }

assert_http() {
  local name="$1" expected="$2" code_file="$3"
  local got; got="$(http_code "$code_file")"
  if [[ "$got" == "$expected" ]]; then
    echo "  PASS  $name (HTTP $got)"; pass=$((pass + 1))
  else
    echo "  FAIL  $name (expected HTTP $expected, got $got)"; fail=$((fail + 1))
  fi
}

assert_4xx() {
  local name="$1" code_file="$2"
  local got; got="$(http_code "$code_file")"
  if [[ "$got" =~ ^4 ]]; then
    echo "  PASS  $name (HTTP $got)"; pass=$((pass + 1))
  else
    echo "  FAIL  $name (expected 4xx, got $got)"; fail=$((fail + 1))
  fi
}

assert_body() {
  local name="$1" needle="$2" body_file="$3"
  if grep -q "$needle" "$body_file"; then
    echo "  PASS  $name"; pass=$((pass + 1))
  else
    echo "  FAIL  $name (missing $needle) body=$(head -c 180 "$body_file")"; fail=$((fail + 1))
  fi
}

assert_no_topology() {
  local name="$1" body_file="$2"
  if grep -Eiq 'routeHandle|endpointUrl|127\.0\.0\.1:809[0-9]' "$body_file"; then
    echo "  FAIL  $name (topology leak)"; fail=$((fail + 1))
  else
    echo "  PASS  $name (no topology leak)"; pass=$((pass + 1))
  fi
}

uniq_mid() { echo "ex011-$(date +%s)-$$-$RANDOM"; }

write_create() {
  local mid="$1" out="$2" agent="${3:-scripted-verify}"
  python3 - "$ROOT/bodies/create-ok.json" "$mid" "$agent" "$out" <<'PY'
import json, sys
src, mid, agent, dst = sys.argv[1:5]
doc = json.load(open(src, encoding="utf-8"))
doc["params"]["message"]["messageId"] = mid
doc["params"].setdefault("metadata", {})["agentId"] = agent
json.dump(doc, open(dst, "w", encoding="utf-8"))
PY
}

echo "== FEAT-011 DIRECT smoke → $GW =="
echo "   (Client/SDK stubbed by curl; downstream = live or Fake RDC/Runtime)"

# --- G1 ---
echo "-- G1 auth --"
post "g1-01" "$tmpdir/c" "$tmpdir/b" -H 'Content-Type: application/json' -d '{}'
assert_http "G1-01 AUTH_MISSING" "401" "$tmpdir/c"
assert_body "G1-01 code" "AUTH_MISSING" "$tmpdir/b"
assert_no_topology "G1-01 topology" "$tmpdir/b"

post "g1-02" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Token $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"m","parts":[{"text":"x"}]}}}'
assert_http "G1-02 non-Bearer" "401" "$tmpdir/c"
assert_body "G1-02 code" "AUTH_INVALID" "$tmpdir/b"

post "g1-03" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer ${TOKEN}x" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"m","parts":[{"text":"x"}]}}}'
assert_http "G1-03 bad Bearer" "401" "$tmpdir/c"
assert_body "G1-03 code" "AUTH_INVALID" "$tmpdir/b"

# --- G3 validation (no forward needed) ---
echo "-- G3 validate --"
post "g3-02" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{not json'
assert_http "G3-02 bad JSON" "400" "$tmpdir/c"
assert_body "G3-02 code" "VALIDATION_JSONRPC" "$tmpdir/b"
assert_no_topology "G3-02 topology" "$tmpdir/b"

post "g3-03" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$ROOT/bodies/bad-method.json"
assert_http "G3-03 bad method" "400" "$tmpdir/c"
assert_body "G3-03 code" "VALIDATION_METHOD" "$tmpdir/b"

post "g3-05" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$ROOT/bodies/empty-agent.json"
assert_http "G3-05 empty agentId" "400" "$tmpdir/c"
assert_body "G3-05 code" "VALIDATION_AGENT_ID" "$tmpdir/b"

if [[ "$GOV_ONLY" -eq 1 ]]; then
  echo "-- forward scenarios SKIPPED (--governance-only) --"
  skip=$((skip + 1))
  echo "-- result: pass=$pass fail=$fail skip_groups=$skip --"
  [[ "$fail" -eq 0 ]]
  exit 0
fi

# --- forward-dependent ---
echo "-- S2/S5/G2/G4/G5/sticky (need RDC+Runtime) --"

mid="$(uniq_mid)"
write_create "$mid" "$tmpdir/create.json"
post "s2" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$tmpdir/create.json"
assert_http "G3-01/S2-01 create sync" "200" "$tmpdir/c"
assert_body "create has task/result" "result" "$tmpdir/b"
TASK_ID="$(python3 - "$tmpdir/b" <<'PY'
import json,sys,re
t=open(sys.argv[1],encoding="utf-8").read()
try:
    d=json.loads(t)
    r=d.get("result") or {}
    print(r.get("id") or r.get("taskId") or "")
except Exception:
    m=re.search(r'"id"\s*:\s*"([^"]+)"', t)
    print(m.group(1) if m else "")
PY
)"

# G2-03 forged tenant still succeeds
mid2="$(uniq_mid)"
write_create "$mid2" "$tmpdir/create2.json"
post "g2-03" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'X-Tenant-Id: forged-tenant' \
  -H 'Content-Type: application/json' --data-binary @"$tmpdir/create2.json"
assert_http "G2-03 forged X-Tenant-Id still OK" "200" "$tmpdir/c"

# G2-04 same tenant header
mid3="$(uniq_mid)"
write_create "$mid3" "$tmpdir/create3.json"
post "g2-04" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'X-Tenant-Id: tenant-1' \
  -H 'Content-Type: application/json' --data-binary @"$tmpdir/create3.json"
assert_http "G2-04 matching X-Tenant-Id OK" "200" "$tmpdir/c"

# G5-04 traceparent (success path)
mid4="$(uniq_mid)"
write_create "$mid4" "$tmpdir/create4.json"
post "g5-04" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' \
  --data-binary @"$tmpdir/create4.json"
assert_http "G5-04 with traceparent" "200" "$tmpdir/c"

# S5-01 unknown agent
post "s5" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$ROOT/bodies/unknown-agent.json"
# may be 503 ROUTE_NO_CANDIDATES
got="$(http_code "$tmpdir/c")"
if [[ "$got" == "503" ]] && grep -q "ROUTE_NO_CANDIDATES" "$tmpdir/b"; then
  echo "  PASS  S5-01 unknown agent (503 ROUTE_NO_CANDIDATES)"; pass=$((pass + 1))
  assert_no_topology "S5-01 topology" "$tmpdir/b"
else
  echo "  FAIL  S5-01 unknown agent (got HTTP $got body=$(head -c 160 "$tmpdir/b"))"; fail=$((fail + 1))
fi

# G3-04 default agent (no metadata.agentId) — needs gateway.default-agent-id + that agent in RDC
mid5="$(uniq_mid)"
python3 - "$ROOT/bodies/create-default-agent.json" "$mid5" "$tmpdir/def.json" <<'PY'
import json,sys
doc=json.load(open(sys.argv[1],encoding="utf-8"))
doc["params"]["message"]["messageId"]=sys.argv[2]
json.dump(doc, open(sys.argv[3],"w",encoding="utf-8"))
PY
post "g3-04" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$tmpdir/def.json"
got="$(http_code "$tmpdir/c")"
if [[ "$got" == "200" ]]; then
  echo "  PASS  G3-04 default agent (HTTP 200)"; pass=$((pass + 1))
elif [[ "$got" == "503" ]]; then
  echo "  SKIP  G3-04 default agent (503 — default-agent-id unset or not in RDC)"; skip=$((skip + 1))
else
  echo "  FAIL  G3-04 default agent (HTTP $got)"; fail=$((fail + 1))
fi

# G4-01 REPLAY
mid_idem="$(uniq_mid)"
write_create "$mid_idem" "$tmpdir/idem.json"
post "g4-01a" "$tmpdir/c1" "$tmpdir/b1" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$tmpdir/idem.json"
post "g4-01b" "$tmpdir/c2" "$tmpdir/b2" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$tmpdir/idem.json"
assert_http "G4-01 first" "200" "$tmpdir/c1"
assert_http "G4-01 second REPLAY" "200" "$tmpdir/c2"
if cmp -s "$tmpdir/b1" "$tmpdir/b2"; then
  echo "  PASS  G4-01 bodies identical"; pass=$((pass + 1))
else
  echo "  FAIL  G4-01 bodies differ"; fail=$((fail + 1))
fi

# G4-02 CONFLICT
mid_c="$(uniq_mid)"
write_create "$mid_c" "$tmpdir/cA.json"
python3 - "$tmpdir/cA.json" "$tmpdir/cB.json" <<'PY'
import json,sys
a=json.load(open(sys.argv[1],encoding="utf-8"))
a["params"]["message"]["parts"]=[{"text":"other-payload"}]
json.dump(a, open(sys.argv[2],"w",encoding="utf-8"))
PY
post "g4-02a" "$tmpdir/c1" "$tmpdir/b1" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$tmpdir/cA.json"
post "g4-02b" "$tmpdir/c2" "$tmpdir/b2" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$tmpdir/cB.json"
assert_http "G4-02 first" "200" "$tmpdir/c1"
assert_http "G4-02 CONFLICT" "409" "$tmpdir/c2"
assert_body "G4-02 code" "IDEMPOTENCY_PAYLOAD_MISMATCH" "$tmpdir/b2"

# sticky resume + sticky miss
if [[ -n "${TASK_ID:-}" ]]; then
  resume="$tmpdir/resume.json"
  python3 - "$TASK_ID" "$resume" <<'PY'
import json,sys
tid=sys.argv[1]
doc={
  "jsonrpc":"2.0","id":"r1","method":"SendMessage",
  "params":{"message":{"messageId":"resume-1","taskId":tid,"parts":[{"text":"continue"}]}}
}
json.dump(doc, open(sys.argv[2],"w",encoding="utf-8"))
PY
  post "s3" "$tmpdir/c" "$tmpdir/b" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data-binary @"$resume"
  assert_http "S3 sticky resume" "200" "$tmpdir/c"
else
  echo "  SKIP  S3 sticky resume (no taskId parsed)"; skip=$((skip + 1))
fi

post "s3-miss" "$tmpdir/c" "$tmpdir/b" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"r-miss","taskId":"task-never-bound-xyz","parts":[{"text":"x"}]}}}'
assert_http "S3-03 sticky miss" "404" "$tmpdir/c"
assert_body "S3-03 code" "RESUME_OWNER_UNKNOWN" "$tmpdir/b"
assert_no_topology "S3-03 topology" "$tmpdir/b"

# streaming create (S2 stream)
mid_s="$(uniq_mid)"
python3 - "$ROOT/bodies/create-stream.json" "$mid_s" "$tmpdir/stream.json" <<'PY'
import json,sys
doc=json.load(open(sys.argv[1],encoding="utf-8"))
doc["params"]["message"]["messageId"]=sys.argv[2]
json.dump(doc, open(sys.argv[3],"w",encoding="utf-8"))
PY
# capture headers too
curl -sS -D "$tmpdir/sh" -o "$tmpdir/sb" -X POST "$GW/a2a" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @"$tmpdir/stream.json" || true
if grep -qi 'text/event-stream' "$tmpdir/sh" || grep -q 'data:' "$tmpdir/sb" || grep -q 'result' "$tmpdir/sb"; then
  echo "  PASS  S2 stream create (SSE or result body)"; pass=$((pass + 1))
else
  echo "  FAIL  S2 stream create (unexpected response)"; fail=$((fail + 1))
  head -c 200 "$tmpdir/sh"; head -c 200 "$tmpdir/sb"; echo
fi

# G1-04 / G5 reject already covered; note optional skips
echo "  NOTE  G1-05 AUTH_FORBIDDEN / G2-02 TENANT_UNRESOLVED need extra GW tokens — SKIP by design"
echo "  NOTE  S3 tool loop / S4 continueInput need Client SDK — out of Gateway example scope"

echo "-- result: pass=$pass fail=$fail skip=$skip --"
[[ "$fail" -eq 0 ]]
