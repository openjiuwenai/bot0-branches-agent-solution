#!/usr/bin/env bash
# Start/update only the mock container. Ensures but never removes the shared network.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

load_runtime_config
check_docker
image_exists || die "Image not found: ${MOCK_IMAGE}. Run build-image.sh or pull the configured image first."

ensure_network
remove_owned_container_if_exists "${MOCK_CONTAINER_NAME}"

run_args=(
    docker run -d
    --name "${MOCK_CONTAINER_NAME}"
    --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}"
    --label "${COMPONENT_LABEL_KEY}=${COMPONENT_LABEL_VALUE}"
    --network "${AGENT_NETWORK}"
    --network-alias "${MOCK_NETWORK_ALIAS}"
    --restart "${RESTART_POLICY}"
    --read-only
    --tmpfs /tmp:rw,noexec,nosuid,size=16m
    --cap-drop ALL
    --security-opt no-new-privileges:true
    --env MOCK_SERVER_HOST=0.0.0.0
    --env MOCK_SERVER_PORT=30001
    --env "MOCK_SKIP_COOKIE_AUTH=${MOCK_SKIP_COOKIE_AUTH}"
    --env "MOCK_BALANCE_DELAY_SECONDS=${MOCK_BALANCE_DELAY_SECONDS}"
    --env "MOCK_TRANSFER_AMOUNTS=${MOCK_TRANSFER_AMOUNTS}"
    --env "MOCK_TRANSFER_MODE=${MOCK_TRANSFER_MODE}"
    --env "MOCK_LICAI_BALANCE=${MOCK_LICAI_BALANCE}"
    --env "MOCK_CHUXU_BALANCE=${MOCK_CHUXU_BALANCE}"
    --env "MOCK_SAME_CARD_MODE=${MOCK_SAME_CARD_MODE}"
    --env "MOCK_BALANCE_SAME_CARD=${MOCK_BALANCE_SAME_CARD}"
    --env "MOCK_PRODUCT_BUY_SUCCESS=${MOCK_PRODUCT_BUY_SUCCESS}"
)

if [ "${MOCK_PUBLISH_PORT}" = "true" ]; then
    run_args+=(-p "${MOCK_HOST_BIND}:${MOCK_HOST_PORT}:${CONTAINER_PORT}")
fi
run_args+=("${MOCK_IMAGE}")

info "Starting mock: image=${MOCK_IMAGE}, container=${MOCK_CONTAINER_NAME}, network=${AGENT_NETWORK}, alias=${MOCK_NETWORK_ALIAS}"
"${run_args[@]}" >/dev/null

info "Waiting for workflow-aware Docker health check..."
wait_for_healthy 90 || die "Mock did not become healthy; the container is retained for docker logs."

info "Mock is healthy at http://${MOCK_NETWORK_ALIAS}:${CONTAINER_PORT} inside ${AGENT_NETWORK}."
if [ "${MOCK_PUBLISH_PORT}" = "true" ]; then
    info "Host debug endpoint: http://${MOCK_HOST_BIND}:${MOCK_HOST_PORT}/health"
else
    info "Host port is not published; adapter access uses the shared Docker network."
fi

