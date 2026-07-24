#!/usr/bin/env bash
# 只构建 adapter 镜像；不创建网络、不启动或修改任何容器。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

load_management_config
check_docker
JAR_PATH="$(require_runtime_jar)"

printf '%s\n' "========================================"
printf '%s\n' "  构建 Adapter 镜像"
printf '%s\n' "========================================"
info "项目目录：${PROJECT_DIR}"
info "运行 jar：${JAR_PATH}"
info "镜像名称：${ADAPTER_IMAGE}"

docker build \
    --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}" \
    -t "${ADAPTER_IMAGE}" \
    -f "${DEPLOY_DIR}/Dockerfile" \
    "${PROJECT_DIR}"

info "镜像构建完成：${ADAPTER_IMAGE}"
