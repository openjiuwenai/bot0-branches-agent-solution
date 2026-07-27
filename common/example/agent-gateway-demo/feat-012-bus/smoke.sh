#!/usr/bin/env bash
# FEAT-012 example smoke:
#   Suite R  — DIRECT regression (reuse feat-011-direct/smoke.sh)
#   Suite B2 — shared-ingress governance (path-agnostic curls)
#   Suite B1 — Fake BUS unit tests via ../validate.sh --bus-unit (not this script)
#
# Usage:
#   GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh
#   GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh --r-only          # suite R only
#   GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh --b2-only         # B2 ingress only
#   GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh --governance-only # R in gov-only mode + B2
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES="$(cd "$ROOT/.." && pwd)"
GW="${GATEWAY_URL:-http://127.0.0.1:8080}"
TOKEN="${GATEWAY_TOKEN:-mock-token}"
R_ONLY=0
B2_ONLY=0
GOV_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --r-only) R_ONLY=1 ;;
    --b2-only) B2_ONLY=1 ;;
    --governance-only) GOV_ONLY=1 ;;
  esac
done

export GATEWAY_URL="$GW"
export GATEWAY_TOKEN="$TOKEN"

run_suite_r() {
  echo ""
  echo "======== Suite R: DIRECT regression (FEAT-011 smoke) ========"
  if [[ "$GOV_ONLY" -eq 1 ]]; then
    "$EXAMPLES/feat-011-direct/smoke.sh" --governance-only
  else
    "$EXAMPLES/feat-011-direct/smoke.sh"
  fi
}

run_suite_b2() {
  echo ""
  echo "======== Suite B2: shared ingress governance (no MQ claims) ========"
  local tmpdir pass fail
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' RETURN
  pass=0
  fail=0

  assert_http() {
    local name="$1" expected="$2" code_file="$3"
    local got; got="$(tr -d '\r' <"$code_file")"
    if [[ "$got" == "$expected" ]]; then
      echo "  PASS  $name (HTTP $got)"; pass=$((pass + 1))
    else
      echo "  FAIL  $name (expected HTTP $expected, got $got)"; fail=$((fail + 1))
    fi
  }
  assert_body() {
    local name="$1" needle="$2" body_file="$3"
    if grep -q "$needle" "$body_file"; then
      echo "  PASS  $name"; pass=$((pass + 1))
    else
      echo "  FAIL  $name (missing $needle)"; fail=$((fail + 1))
    fi
  }
  assert_no_topo() {
    local name="$1" body_file="$2"
    if grep -Eiq 'routeHandle|endpointUrl|http://127\.0\.0\.1:809[0-9]' "$body_file"; then
      echo "  FAIL  $name (topology leak)"; fail=$((fail + 1))
    else
      echo "  PASS  $name"; pass=$((pass + 1))
    fi
  }

  echo "== B2 → $GW (do NOT claim MQ / BUS hop) =="

  curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW/a2a" \
    -H 'Content-Type: application/json' -d '{}' >"$tmpdir/c" || true
  assert_http "B2-G1-01 AUTH_MISSING" "401" "$tmpdir/c"
  assert_body "B2-G1-01 code" "AUTH_MISSING" "$tmpdir/b"
  assert_no_topo "B2-G1-01 topology" "$tmpdir/b"

  curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW/a2a" \
    -H 'Authorization: Token xxx' -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"m","parts":[{"text":"x"}]}}}' \
    >"$tmpdir/c" || true
  assert_http "B2-G1-02 non-Bearer" "401" "$tmpdir/c"
  assert_body "B2-G1-02 code" "AUTH_INVALID" "$tmpdir/b"

  curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW/a2a" \
    -H 'Authorization: Bearer bad-token-xyz' -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"m","parts":[{"text":"x"}]}}}' \
    >"$tmpdir/c" || true
  assert_http "B2-G1-03 bad Bearer" "401" "$tmpdir/c"
  assert_body "B2-G1-03 code" "AUTH_INVALID" "$tmpdir/b"

  curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW/a2a" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{not json' >"$tmpdir/c" || true
  assert_http "B2-G3-02 bad JSON" "400" "$tmpdir/c"
  assert_body "B2-G3-02 code" "VALIDATION_JSONRPC" "$tmpdir/b"
  assert_no_topo "B2-G3-02 topology" "$tmpdir/b"

  curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW/a2a" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data-binary @"$EXAMPLES/feat-011-direct/bodies/bad-method.json" >"$tmpdir/c" || true
  assert_http "B2-G3-03 bad method" "400" "$tmpdir/c"
  assert_body "B2-G3-03 code" "VALIDATION_METHOD" "$tmpdir/b"

  curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW/a2a" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data-binary @"$EXAMPLES/feat-011-direct/bodies/empty-agent.json" >"$tmpdir/c" || true
  assert_http "B2-G3-05 empty agentId" "400" "$tmpdir/c"
  assert_body "B2-G3-05 code" "VALIDATION_AGENT_ID" "$tmpdir/b"

  # Unknown agent — proves governance+route fail surface (may be DIRECT today; still B2-S5)
  curl -sS -o "$tmpdir/b" -w "%{http_code}" -X POST "$GW/a2a" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data-binary @"$EXAMPLES/feat-011-direct/bodies/unknown-agent.json" >"$tmpdir/c" || true
  local got; got="$(tr -d '\r' <"$tmpdir/c")"
  if [[ "$got" == "503" ]] && grep -q "ROUTE_NO_CANDIDATES" "$tmpdir/b"; then
    echo "  PASS  B2-S5-01 unknown agent (503 ROUTE_NO_CANDIDATES)"; pass=$((pass + 1))
    assert_no_topo "B2-S5-01 topology" "$tmpdir/b"
  else
    echo "  FAIL  B2-S5-01 unknown agent (HTTP $got)"; fail=$((fail + 1))
  fi

  echo "-- B2 result: pass=$pass fail=$fail --"
  echo "   NOTE: B3 true BUS hop NOT claimed (needs real MQ + FEAT-017; this suite is R/B2 only)."
  [[ "$fail" -eq 0 ]]
}

echo "== FEAT-012 example smoke =="
echo "   B1 (Fake BUS units): run: ../validate.sh --bus-unit"
echo "   B3 (true BUS E2E): deferred — facade path-mode ready; needs real MQ + FEAT-017"

if [[ "$B2_ONLY" -eq 1 ]]; then
  run_suite_b2
elif [[ "$R_ONLY" -eq 1 ]]; then
  run_suite_r
else
  run_suite_r
  run_suite_b2
fi

echo ""
echo "== FEAT-012 smoke ALL CHECKS PASSED =="
