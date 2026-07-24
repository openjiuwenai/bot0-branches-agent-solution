#!/usr/bin/env bash
# 只停止并删除 adapter 自己的容器；保留镜像和共享网络。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

load_management_config
check_docker

if [ -z "$(container_id_if_exists "${ADAPTER_CONTAINER_NAME}")" ]; then
    info "容器 ${ADAPTER_CONTAINER_NAME} 不存在，无需停止。"
    exit 0
fi

remove_owned_container_if_exists "${ADAPTER_CONTAINER_NAME}"
info "已停止 adapter；镜像 ${ADAPTER_IMAGE} 与共享网络 ${AGENT_NETWORK} 均保留。"
