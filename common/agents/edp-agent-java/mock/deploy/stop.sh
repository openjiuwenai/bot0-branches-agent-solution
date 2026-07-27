#!/usr/bin/env bash
# Stop/remove only the owned mock container. Preserve image and shared network.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

load_management_config
check_docker

if [ -z "$(container_id_if_exists "${MOCK_CONTAINER_NAME}")" ]; then
    info "Container ${MOCK_CONTAINER_NAME} does not exist."
    exit 0
fi

remove_owned_container_if_exists "${MOCK_CONTAINER_NAME}"
info "Mock stopped. Image ${MOCK_IMAGE} and shared network ${AGENT_NETWORK} are preserved."

