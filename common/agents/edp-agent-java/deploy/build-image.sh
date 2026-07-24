#!/usr/bin/env bash
# 仅构建 EDP Agent 镜像；不创建网络、volume 或容器。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker

IMAGE="$(config_value EDP_IMAGE edp-agent-java:0.1.0)"
validate_image_ref "${IMAGE}"

shopt -s nullglob
CANDIDATES=("${SERVICE_DIR}"/engine/target/edp-agent-engine-*.jar)
shopt -u nullglob
JARS=()
for jar in "${CANDIDATES[@]}"; do
    case "${jar}" in
        *-sources.jar|*-javadoc.jar|*-tests.jar|*-plain.jar) ;;
        *) JARS+=("${jar}") ;;
    esac
done
if [ "${#JARS[@]}" -eq 0 ]; then
    die "未找到 engine/target/edp-agent-engine-*.jar。请先执行 Maven 打包，或使用 deploy/pack-for-linux.ps1 生成完整部署包。"
fi
if [ "${#JARS[@]}" -ne 1 ]; then
    die "engine/target 下匹配到 ${#JARS[@]} 个 EDP jar。请先 mvn clean，确保只保留一个 edp-agent-engine-*.jar。"
fi

REQUIRED_PATHS=(
    "${SERVICE_DIR}/deploy/requirements-mcp.txt"
    "${SERVICE_DIR}/deploy/config/edp-config.yaml"
    "${SERVICE_DIR}/engine/src/main/resources/governance"
    "${SERVICE_DIR}/scenarios/wealth-demo"
)
for required in "${REQUIRED_PATHS[@]}"; do
    [ -e "${required}" ] || die "Docker 构建上下文缺少：${required#${SERVICE_DIR}/}"
done

log "开始构建镜像：${IMAGE}"
docker build \
    -t "${IMAGE}" \
    -f "${SCRIPT_DIR}/Dockerfile" \
    "${SERVICE_DIR}"

log "镜像构建完成：${IMAGE}"
docker image inspect --format 'ID={{.Id}} Created={{.Created}}' "${IMAGE}"
