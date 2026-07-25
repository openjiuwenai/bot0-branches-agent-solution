#!/usr/bin/env bash
# Build only the mock image. Does not create networks or containers.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

load_management_config
check_docker
require_sources

info "Building image ${MOCK_IMAGE} from ${PROJECT_DIR}"
docker build \
    --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}" \
    --label "${COMPONENT_LABEL_KEY}=${COMPONENT_LABEL_VALUE}" \
    --build-arg "PIP_INDEX_URL=${MOCK_PIP_INDEX_URL}" \
    -t "${MOCK_IMAGE}" \
    -f "${DEPLOY_DIR}/Dockerfile" \
    "${PROJECT_DIR}"

info "Image built: ${MOCK_IMAGE}"

