#!/usr/bin/env bash
# 验证 EDP 进程、Agent Card、Redis；adapter 不可达仅警示。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker

IMAGE="$(config_value EDP_IMAGE edp-agent-java:0.1.0)"
EDP_CONTAINER="$(config_value EDP_CONTAINER edp-agent)"
HOST_PORT="$(config_value EDP_HOST_PORT 8190)"
DEPLOYMENT="$(config_value EDP_REDIS_DEPLOYMENT local)"
REDIS_CONTAINER="$(config_value EDP_REDIS_CONTAINER edp-redis)"
ADAPTER_A2A_URL="$(config_value EDP_AGENT_VERSATILE_A2A_URL http://adapter-versatile:8191/a2a)"

validate_resource_name "EDP 容器" "${EDP_CONTAINER}"
validate_port EDP_HOST_PORT "${HOST_PORT}"
validate_http_url EDP_AGENT_VERSATILE_A2A_URL "${ADAPTER_A2A_URL}"

container_exists "${EDP_CONTAINER}" || die "EDP 容器不存在：${EDP_CONTAINER}"
require_owned_container "${EDP_CONTAINER}" edp-agent
container_running "${EDP_CONTAINER}" || {
    show_recent_logs "${EDP_CONTAINER}"
    die "EDP 容器没有运行。"
}
if ! wait_for_health "${EDP_CONTAINER}" 20; then
    show_recent_logs "${EDP_CONTAINER}"
    die "EDP 容器健康检查未通过。"
fi

docker exec "${EDP_CONTAINER}" python3 -c \
    "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8190/.well-known/agent-card.json', timeout=4).read(1)" \
    >/dev/null \
    || die "容器内 Agent Card 验证失败。"
log "容器内 Agent Card 验证通过。"

if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 5 "http://127.0.0.1:${HOST_PORT}/.well-known/agent-card.json" >/dev/null \
        || die "宿主机端口 ${HOST_PORT} 无法访问 Agent Card。"
    log "宿主机 Agent Card 验证通过：http://127.0.0.1:${HOST_PORT}/.well-known/agent-card.json"
else
    warn "宿主机未安装 curl，已跳过宿主端口 HTTP 验证；容器内验证已通过。"
fi

if [ "${DEPLOYMENT}" = "local" ]; then
    container_exists "${REDIS_CONTAINER}" || die "local 模式的 Redis 容器不存在：${REDIS_CONTAINER}"
    require_owned_container "${REDIS_CONTAINER}" redis
    container_running "${REDIS_CONTAINER}" || die "local Redis 容器没有运行：${REDIS_CONTAINER}"
    [ "$(docker exec "${REDIS_CONTAINER}" redis-cli ping 2>/dev/null || true)" = "PONG" ] \
        || die "local Redis PING 失败。"
    log "local Redis PING 验证通过。"
else
    log "external Redis 由外部系统负责；EDP 启动健康已证明其启动时 PING/INFO 校验通过。"
fi

ADAPTER_CARD_URL="$(adapter_agent_card_url "${ADAPTER_A2A_URL}")"
if docker exec "${EDP_CONTAINER}" python3 -c \
    'import sys, urllib.request; urllib.request.urlopen(sys.argv[1], timeout=4).read(1)' \
    "${ADAPTER_CARD_URL}" >/dev/null 2>&1; then
    log "adapter 连通性验证通过：${ADAPTER_CARD_URL}"
else
    warn "adapter 连通性验证失败：${ADAPTER_CARD_URL}。EDP 自身可用，但 Versatile 业务暂不可用。"
fi

log "基础部署验证完成。"
