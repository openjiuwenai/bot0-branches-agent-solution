#!/usr/bin/env bash
# 创建/启动由 EDP 团队自管的本机 Redis。数据使用 named volume + AOF 持久化。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker
ensure_env_file

DEPLOYMENT="$(config_value EDP_REDIS_DEPLOYMENT local)"
[ "${DEPLOYMENT}" = "local" ] \
    || die "EDP_REDIS_DEPLOYMENT=${DEPLOYMENT}；external 模式禁止本脚本创建或管理 Redis。"

NETWORK="$(config_value AGENT_NETWORK agent-net)"
REDIS_IMAGE="$(config_value EDP_REDIS_IMAGE redis:7-alpine)"
REDIS_CONTAINER="$(config_value EDP_REDIS_CONTAINER edp-redis)"
REDIS_VOLUME="$(config_value EDP_REDIS_VOLUME edp-redis-data)"
REDIS_PASSWORD="$(config_value EDPA_REDIS_PASSWORD '')"

validate_image_ref "${REDIS_IMAGE}"
validate_resource_name "Redis 容器" "${REDIS_CONTAINER}"
[ -z "${REDIS_PASSWORD}" ] \
    || die "local helper 默认运行不暴露宿主端口的无密码 Redis；请把 EDPA_REDIS_PASSWORD 留空，生产环境请使用受控 external Redis。"

ensure_network "${NETWORK}"
ensure_volume "${REDIS_VOLUME}"

if container_exists "${REDIS_CONTAINER}"; then
    require_owned_container "${REDIS_CONTAINER}" redis
    MOUNT_SOURCE="$(docker container inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "${REDIS_CONTAINER}")"
    [ "${MOUNT_SOURCE}" = "${REDIS_VOLUME}" ] \
        || die "已有 Redis 容器的 /data 未挂载预期 volume（实际=${MOUNT_SOURCE:-无}, 预期=${REDIS_VOLUME}），拒绝自动接管。"

    if ! docker network inspect --format '{{range .Containers}}{{println .Name}}{{end}}' "${NETWORK}" | grep -Fxq "${REDIS_CONTAINER}"; then
        docker network connect --alias edp-redis "${NETWORK}" "${REDIS_CONTAINER}"
        log "已把自有 Redis 接入共享网络并设置 alias：edp-redis"
    fi
    if ! container_running "${REDIS_CONTAINER}"; then
        docker start "${REDIS_CONTAINER}" >/dev/null
        log "已启动现有 Redis 容器：${REDIS_CONTAINER}"
    else
        log "Redis 容器已在运行：${REDIS_CONTAINER}"
    fi
else
    log "创建本地 Redis：container=${REDIS_CONTAINER}, volume=${REDIS_VOLUME}, alias=edp-redis"
    docker run -d \
        --name "${REDIS_CONTAINER}" \
        --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}" \
        --label "${COMPONENT_LABEL_KEY}=redis" \
        --restart unless-stopped \
        --network "${NETWORK}" \
        --network-alias edp-redis \
        --mount "type=volume,source=${REDIS_VOLUME},target=/data" \
        --health-cmd 'redis-cli ping | grep -q PONG' \
        --health-interval 5s \
        --health-timeout 3s \
        --health-retries 10 \
        --health-start-period 5s \
        "${REDIS_IMAGE}" \
        redis-server --appendonly yes >/dev/null
fi

if ! wait_for_health "${REDIS_CONTAINER}" 60; then
    show_recent_logs "${REDIS_CONTAINER}"
    exit 1
fi

PONG="$(docker exec "${REDIS_CONTAINER}" redis-cli ping 2>/dev/null || true)"
[ "${PONG}" = "PONG" ] || die "Redis PING 未返回 PONG。"
log "本地 Redis 可用；AOF 已启用，数据保存在 named volume ${REDIS_VOLUME}。"
