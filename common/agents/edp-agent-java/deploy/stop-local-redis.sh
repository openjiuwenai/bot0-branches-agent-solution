#!/usr/bin/env bash
# 显式停止/删除 EDP 自管 Redis 容器；保留 named volume 和共享网络。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker

DEPLOYMENT="$(config_value EDP_REDIS_DEPLOYMENT local)"
if [ "${DEPLOYMENT}" != "local" ]; then
    log "EDP_REDIS_DEPLOYMENT=${DEPLOYMENT}；external Redis 不归本脚本管理，未执行任何操作。"
    exit 0
fi

EDP_CONTAINER="$(config_value EDP_CONTAINER edp-agent)"
REDIS_CONTAINER="$(config_value EDP_REDIS_CONTAINER edp-redis)"
REDIS_VOLUME="$(config_value EDP_REDIS_VOLUME edp-redis-data)"
validate_resource_name "EDP 容器" "${EDP_CONTAINER}"
validate_resource_name "Redis 容器" "${REDIS_CONTAINER}"

if container_exists "${EDP_CONTAINER}"; then
    require_owned_container "${EDP_CONTAINER}" edp-agent
    if container_running "${EDP_CONTAINER}"; then
        die "EDP 容器仍在运行。请先执行 stop.sh，再停止 Redis。"
    fi
fi

if ! container_exists "${REDIS_CONTAINER}"; then
    log "本地 Redis 容器不存在，无需停止。volume ${REDIS_VOLUME} 与共享网络均未处理。"
    exit 0
fi

require_owned_container "${REDIS_CONTAINER}" redis
docker rm -f "${REDIS_CONTAINER}" >/dev/null
log "已停止并删除自有 Redis 容器：${REDIS_CONTAINER}"
log "数据 volume ${REDIS_VOLUME} 已保留；共享网络也未删除。"
