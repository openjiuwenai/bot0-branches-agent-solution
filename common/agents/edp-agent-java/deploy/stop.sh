#!/usr/bin/env bash
# 只停止/删除 EDP Agent 自身；默认保留本地 Redis、数据 volume 和共享网络。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker

EDP_CONTAINER="$(config_value EDP_CONTAINER edp-agent)"
REDIS_VOLUME="$(config_value EDP_REDIS_VOLUME edp-redis-data)"
validate_resource_name "EDP 容器" "${EDP_CONTAINER}"

if ! container_exists "${EDP_CONTAINER}"; then
    log "EDP 容器不存在，无需停止。Redis、volume 和共享网络均未处理。"
    exit 0
fi

require_owned_container "${EDP_CONTAINER}" edp-agent
docker rm -f "${EDP_CONTAINER}" >/dev/null
log "已停止并删除自有 EDP 容器：${EDP_CONTAINER}"
log "本地 Redis（若有）、数据 volume ${REDIS_VOLUME} 和共享网络均已保留。"
