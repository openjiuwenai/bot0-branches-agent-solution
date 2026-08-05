#!/usr/bin/env bash
# Verify container ownership/health, shared-network DNS, workflow loading, and a real SSE response.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

load_management_config
check_docker
image_exists || die "Image not found: ${MOCK_IMAGE}"
container_id="$(assert_owned_container "${MOCK_CONTAINER_NAME}")"

state="$(docker container inspect --format '{{.State.Status}}' "${container_id}")"
health="$(docker container inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")"
[ "${state}" = "running" ] || die "Mock is not running: state=${state}"
[ "${health}" = "healthy" ] || die "Mock is not healthy: health=${health}"

networks="$(docker container inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "${container_id}")"
grep -Fxq "${AGENT_NETWORK}" <<< "${networks}" || die "Mock is not attached to ${AGENT_NETWORK}."

health_url="http://${MOCK_NETWORK_ALIAS}:${CONTAINER_PORT}/health"
workflow_url="http://${MOCK_NETWORK_ALIAS}:${CONTAINER_PORT}/v1/0/agent-manager/workflows/wealth-invest/conversations/deploy-smoke"

info "Probing health through Docker DNS: ${health_url}"
docker run --rm \
    --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}" \
    --label "${COMPONENT_LABEL_KEY}=network-probe" \
    --network "${AGENT_NETWORK}" \
    --entrypoint python \
    "${MOCK_IMAGE}" \
    -c '
import json
import sys
import urllib.request

with urllib.request.urlopen(sys.argv[1], timeout=5) as response:
    payload = json.load(response)
assert payload.get("status") == "healthy", payload
workflows = payload.get("workflows_loaded") or []
assert "default" in workflows and len(workflows) > 1, payload
assert not payload.get("load_errors"), payload
print("health payload OK; workflows_loaded=%d" % len(workflows))
' "${health_url}"

info "Sending a real workflow request and checking the SSE result node..."
docker run --rm \
    --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}" \
    --label "${COMPONENT_LABEL_KEY}=sse-probe" \
    --network "${AGENT_NETWORK}" \
    --entrypoint python \
    "${MOCK_IMAGE}" \
    -c '
import json
import sys
import urllib.request

body = json.dumps({"inputs": {"query": "\u67e5\u8be2\u5c3e\u53f7\u4e3a6605\u7684\u5361\u7684\u4f59\u989d"}}).encode("utf-8")
request = urllib.request.Request(
    sys.argv[1],
    data=body,
    headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
    method="POST",
)
with urllib.request.urlopen(request, timeout=15) as response:
    content_type = response.headers.get("Content-Type", "")
    payload = response.read().decode("utf-8")
assert "text/event-stream" in content_type.lower(), content_type
assert "data:" in payload, payload[:500]
assert "GXZQAResponseNode" in payload, payload[:1000]
assert "\"event\":\"end\"" in payload or "\"node_type\":\"End\"" in payload, payload[-1000:]
print("SSE smoke OK; bytes=%d" % len(payload.encode("utf-8")))
' "${workflow_url}"

info "Mock functional verification passed."
info "Adapter should use: VERSATILE_URL=http://${MOCK_NETWORK_ALIAS}:${CONTAINER_PORT}/v1/0/agent-manager/workflows/wealth-invest/conversations/{conversation_id}"

