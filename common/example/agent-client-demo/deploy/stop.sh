#!/usr/bin/env bash
# 停止/删除 agent-client-demo 容器。
# 本 demo 为单容器自检，没有 Redis、volume、共享网络等需要保留或清理的资源。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker

CONTAINER="$(config_value ACD_CONTAINER agent-client-demo)"
validate_resource_name "容器" "${CONTAINER}"

if ! container_exists "${CONTAINER}"; then
    log "容器不存在，无需停止。"
    exit 0
fi

require_owned_container "${CONTAINER}" agent-client-demo
docker rm -f "${CONTAINER}" >/dev/null
log "已停止并删除自有容器：${CONTAINER}"
