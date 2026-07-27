#!/usr/bin/env bash
# Validate FEAT-011 DIRECT gateway example (structure always; optional online).
# Usage:
#   ./validate.sh
#   ./validate.sh --online
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ONLINE=0
for arg in "$@"; do
  case "$arg" in
    --online) ONLINE=1 ;;
  esac
done

echo "== 1) structure =="
required=(
  "$ROOT/README.md"
  "$ROOT/application-example.yml"
  "$ROOT/feat-011-direct/README.md"
  "$ROOT/feat-011-direct/smoke.sh"
  "$ROOT/feat-011-direct/bodies/create-ok.json"
  "$ROOT/feat-011-direct/bodies/bad-method.json"
  "$ROOT/feat-011-direct/bodies/empty-agent.json"
  "$ROOT/feat-011-direct/bodies/unknown-agent.json"
  "$ROOT/feat-011-direct/bodies/create-stream.json"
  "$ROOT/feat-011-direct/bodies/create-default-agent.json"
  "$ROOT/feat-011-direct/stubs/downstream_stub.py"
)
for f in "${required[@]}"; do
  [[ -f "$f" ]] || { echo "MISSING $f"; exit 1; }
  echo "  OK  ${f#"$ROOT"/}"
done

echo "== 2) bash -n =="
bash -n "$ROOT/validate.sh"
bash -n "$ROOT/feat-011-direct/smoke.sh"

chmod +x "$ROOT/validate.sh" "$ROOT/feat-011-direct/smoke.sh" \
  "$ROOT/feat-011-direct/stubs/downstream_stub.py" 2>/dev/null || true

if [[ "$ONLINE" -eq 1 ]]; then
  echo "== 3) online smoke (GATEWAY_URL) =="
  GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:8080}" \
    "$ROOT/feat-011-direct/smoke.sh"
else
  echo "== 3) online smoke skipped (pass --online)"
fi

echo "OK validate (FEAT-011 DIRECT example)"
