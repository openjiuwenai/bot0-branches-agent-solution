#!/usr/bin/env bash
# Validate agent-gateway examples (structure always; optional online / bus unit).
# Usage:
#   ./validate.sh
#   ./validate.sh --online
#   ./validate.sh --bus-unit
#   ./validate.sh --online --bus-unit
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# Demo lives under common/example/; Gateway module is sibling under common/.
MODULE="$(cd "$ROOT/../../agent-gateway" && pwd)"
ONLINE=0
BUS_UNIT=0
for arg in "$@"; do
  case "$arg" in
    --online) ONLINE=1 ;;
    --bus-unit) BUS_UNIT=1 ;;
  esac
done

JAVA_HOME="${JAVA_HOME:-/Users/kevin/jdks/jdk-21.0.11+10/Contents/Home}"
MVN="${MVN:-$HOME/.m2/wrapper/dists/apache-maven-3.9.15/9925cc1d/bin/mvn}"
export JAVA_HOME

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
  "$ROOT/feat-012-bus/README.md"
  "$ROOT/feat-012-bus/smoke.sh"
  "$ROOT/feat-012-bus/bodies/create-ok.json"
)
for f in "${required[@]}"; do
  [[ -f "$f" ]] || { echo "MISSING $f"; exit 1; }
  echo "  OK  ${f#"$ROOT"/}"
done

echo "== 2) bash -n =="
bash -n "$ROOT/validate.sh"
bash -n "$ROOT/feat-011-direct/smoke.sh"
bash -n "$ROOT/feat-012-bus/smoke.sh"
echo "  OK  smoke scripts"

echo "== 3) JSON bodies =="
python3 - <<PY
import json, pathlib, sys
root = pathlib.Path("$ROOT")
paths = list(root.glob("feat-*/bodies/*.json"))
assert paths, "no JSON bodies"
for p in paths:
    json.load(p.open(encoding="utf-8"))
    print(f"  OK  {p.relative_to(root)}")
PY

# YAML: light check (key presence)
python3 - "$ROOT/application-example.yml" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(encoding="utf-8")
for key in ("path-mode", "test-credential", "base-url", "mock-token"):
    assert key in text, f"application-example.yml missing {key}"
print("  OK  application-example.yml keys")
PY

echo "== 4) offline result: PASS =="

if [[ "$BUS_UNIT" -eq 1 ]]; then
  echo "== 5) Suite B1 — BUS unit tests (Fake) =="
  if [[ ! -x "$MVN" && ! -f "$MVN" ]]; then
    echo "  SKIP  mvn not found at $MVN"
  else
    # Keep in sync with feat-012-bus/README.md Suite B1 table.
    B1_TESTS='PathSelectorTest,PathSelectorWiringTest,BusSpiClasspathTest,EnvelopeBuilderTest,PayloadStoreTest,BusControlForwarderTest,BusForwarderTest,BusStreamingAndResumeTest,FiveStateFolderTest,WaitWindowTest,StreamReadyGateTest,SyncDisconnectTest,G4BusWiringTest,ProjectionDedupTest'
    "$MVN" -f "$MODULE/pom.xml" -q test "-Dtest=${B1_TESTS}"
    echo "  OK  Suite B1 unit tests"
  fi
fi

if [[ "$ONLINE" -eq 1 ]]; then
  GW="${GATEWAY_URL:-http://127.0.0.1:8080}"
  echo "== 6) online Suite R + B2 → $GW =="
  if ! curl -sS -o /dev/null -m 2 -w "%{http_code}" "$GW/a2a" -X POST \
      -H 'Content-Type: application/json' -d '{}' | grep -qE '^[0-9]+$'; then
    echo "  FAIL  Gateway not reachable at $GW"
    exit 1
  fi
  # feat-012 smoke already embeds Suite R (011) then B2 — do not double-run 011.
  GATEWAY_URL="$GW" "$ROOT/feat-012-bus/smoke.sh"
  echo "  OK  online Suite R + B2"
fi

echo "== ALL CHECKS PASSED =="
