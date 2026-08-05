#!/usr/bin/env bash
# 仅构建 agent-client-demo 镜像；不创建网络或容器。
#
# 关键：docker build 上下文必须是 common/ 目录，因为 SDK 在 common/agent-client/ 下，
# demo 在 common/example/agent-client-demo/ 下，二者需同时进入构建上下文。
# Dockerfile 通过相对路径 COPY example/agent-client-demo/... 与 agent-client/... 引用。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker

IMAGE="$(config_value ACD_IMAGE agent-client-demo:0.1.0)"
validate_image_ref "${IMAGE}"

# 预检查构建上下文必需的目录/文件，避免 docker build 跑到一半才因 COPY 失败报错。
REQUIRED_PATHS=(
    "${SERVICE_DIR}/pom.xml"
    "${SERVICE_DIR}/mock-gateway"
    "${SERVICE_DIR}/verification-app"
    "${SERVICE_DIR}/deploy/Dockerfile"
    "${COMMON_DIR}/agent-client/agent-client-sdk-for-jvm/pom.xml"
    "${COMMON_DIR}/agent-client/agent-client-sdk-for-jvm/src"
)
for required in "${REQUIRED_PATHS[@]}"; do
    [ -e "${required}" ] || die "Docker 构建上下文缺少：${required}"
done

# 校验 Maven 产物是否存在。本镜像采用多阶段构建（Dockerfile 内会 mvn install），
# 因此不强制要求先跑 build-jar.sh；但若已存在本地 jar，会用本地 Maven 缓存加速。
# 若采用 pack-for-linux.ps1 的离线包流程，产物已在上下文中。

log "开始构建镜像：${IMAGE}"
log "构建上下文：${COMMON_DIR}（common/ 目录，SDK 与 demo 需同时进入）"
docker build \
    -t "${IMAGE}" \
    -f "${SCRIPT_DIR}/Dockerfile" \
    "${COMMON_DIR}"

log "镜像构建完成：${IMAGE}"
docker image inspect --format 'ID={{.Id}} Created={{.Created}}' "${IMAGE}"
