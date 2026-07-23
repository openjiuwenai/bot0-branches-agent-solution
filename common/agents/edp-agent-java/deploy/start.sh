#!/usr/bin/env bash
# 启动 EDP Agent。local 模式自动确保 Redis；external 模式只连接、不管理外部 Redis。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker
ensure_env_file

IMAGE="$(config_value EDP_IMAGE edp-agent-java:0.1.0)"
EDP_CONTAINER="$(config_value EDP_CONTAINER edp-agent)"
HOST_PORT="$(config_value EDP_HOST_PORT 8190)"
NETWORK="$(config_value AGENT_NETWORK agent-net)"
DEPLOYMENT="$(config_value EDP_REDIS_DEPLOYMENT local)"
API_KEY="$(config_value EDP_AGENT_MODEL_API_KEY '')"
MODEL_BASE_URL="$(config_value EDP_AGENT_MODEL_BASE_URL https://api.deepseek.com/v1)"
ADAPTER_A2A_URL="$(config_value EDP_AGENT_VERSATILE_A2A_URL http://adapter-versatile:8191/a2a)"
DIRECT_VERSATILE_URL="$(config_value EDP_AGENT_VERSATILE_URL http://adapter-versatile:8191/a2a)"

validate_image_ref "${IMAGE}"
validate_resource_name "EDP 容器" "${EDP_CONTAINER}"
validate_port EDP_HOST_PORT "${HOST_PORT}"
case "${DEPLOYMENT}" in
    local|external) ;;
    *) die "EDP_REDIS_DEPLOYMENT 只能是 local 或 external，当前值：${DEPLOYMENT}" ;;
esac
[ -n "${API_KEY}" ] || die "EDP_AGENT_MODEL_API_KEY 为空。请填写 ${ENV_FILE} 后重试。"
validate_http_url EDP_AGENT_MODEL_BASE_URL "${MODEL_BASE_URL}"
validate_http_url EDP_AGENT_VERSATILE_A2A_URL "${ADAPTER_A2A_URL}"
validate_http_url EDP_AGENT_VERSATILE_URL "${DIRECT_VERSATILE_URL}"

docker image inspect "${IMAGE}" >/dev/null 2>&1 \
    || die "未找到镜像 ${IMAGE}。请先执行 build-image.sh，或从镜像仓库拉取同名版本。"

# 在任何有副作用的替换操作之前先检查同名资源归属。
if container_exists "${EDP_CONTAINER}"; then
    require_owned_container "${EDP_CONTAINER}" edp-agent
fi

ensure_network "${NETWORK}"

if [ "${DEPLOYMENT}" = "local" ]; then
    LOCAL_PASSWORD="$(config_value EDPA_REDIS_PASSWORD '')"
    [ -z "${LOCAL_PASSWORD}" ] \
        || die "local Redis 模式要求 EDPA_REDIS_PASSWORD 留空；生产环境请使用受控 external Redis。"
    EDP_ENV_FILE="${ENV_FILE}" bash "${SCRIPT_DIR}/start-local-redis.sh"
else
    REDIS_HOST="$(config_value EDPA_REDIS_HOST '')"
    REDIS_PORT="$(config_value EDPA_REDIS_PORT 6379)"
    [ -n "${REDIS_HOST}" ] || die "external Redis 模式必须填写 EDPA_REDIS_HOST。"
    validate_port EDPA_REDIS_PORT "${REDIS_PORT}"
    case "${REDIS_HOST}" in
        localhost|127.0.0.1|::1)
            die "EDPA_REDIS_HOST=${REDIS_HOST} 指向 EDP 容器自身。请填写容器内可路由的外部 Redis DNS/IP。"
            ;;
    esac
    log "external 模式：仅探测 ${REDIS_HOST}:${REDIS_PORT}，不会创建、停止或删除 Redis。"
    probe_tcp_from_network "${IMAGE}" "${NETWORK}" "${REDIS_HOST}" "${REDIS_PORT}" \
        || die "从共享网络无法连接 external Redis ${REDIS_HOST}:${REDIS_PORT}；旧 EDP 容器尚未被替换。"
fi

# adapter 是业务链路依赖，但不是 EDP HTTP 进程的启动依赖；不可达只警示。
ADAPTER_CARD_URL="$(adapter_agent_card_url "${ADAPTER_A2A_URL}")"
if probe_http_from_network "${IMAGE}" "${NETWORK}" "${ADAPTER_CARD_URL}"; then
    log "adapter Agent Card 可达：${ADAPTER_CARD_URL}"
else
    warn "adapter 当前不可达：${ADAPTER_CARD_URL}。EDP 仍会部署，但 Versatile 业务在 adapter 恢复前不可用。"
fi

remove_owned_container "${EDP_CONTAINER}" edp-agent

RUN_ARGS=(
    docker run -d
    --name "${EDP_CONTAINER}"
    --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}"
    --label "${COMPONENT_LABEL_KEY}=edp-agent"
    --restart unless-stopped
    --network "${NETWORK}"
    -p "${HOST_PORT}:8190"
    --env-file "${ENV_FILE}"
)

# 若调用者显式 export 了应用变量，则用 Docker 的 `--env KEY` 安全传递，覆盖 env-file；
# 不把密钥值拼进 shell 命令，也不 source/eval env 文件。
APP_ENV_KEYS=(
    EDP_AGENT_MODEL_PROVIDER
    EDP_AGENT_MODEL_NAME
    EDP_AGENT_MODEL_BASE_URL
    EDP_AGENT_MODEL_API_KEY
    EDPA_REDIS_MODE
    EDPA_REDIS_HOST
    EDPA_REDIS_PORT
    EDPA_REDIS_PASSWORD
    EDPA_REDIS_DB
    EDPA_REDIS_CONNECT_TIMEOUT
    EDPA_REDIS_SOCKET_TIMEOUT
    EDPA_REDIS_CHECKPOINTER_TTL
    EDPA_REDIS_KEY_PREFIX
    EDPA_REDIS_TODO_TTL
    EDPA_REDIS_REFRESH_ON_READ
    EDP_AGENT_VERSATILE_A2A_URL
    EDP_AGENT_VERSATILE_URL
    EDP_AGENT_VERSATILE_TIMEOUT
    EDP_MCP_MASTER_URL
    EDP_MCP_STANDBY_URL
    EDP_MCP_ACCESS_TOKEN
    EDP_MCP_APP_NAME
    # 沙箱环境变量
    EDPA_SANDBOX_GOVERNED_ENABLED
    EDPA_SANDBOX_ENABLED
    EDPA_SANDBOX_SERVICE_URL
    EDPA_SANDBOX_ID_PREFIX
    EDPA_SANDBOX_EXEC_TIMEOUT
    EDPA_SANDBOX_SKILL_DEPLOY_PATH
    EDPA_SANDBOX_AUTO_CREATE
    EDPA_SANDBOX_ON_STOP
    EDPA_SANDBOX_FALLBACK_ON_FAILURE
    EDPA_SANDBOX_EXCLUDED_COMMANDS
    EDPA_SANDBOX_CONTAINER_SCOPE
    EDPA_SANDBOX_CREATE_TIMEOUT
)
for key in "${APP_ENV_KEYS[@]}"; do
    if [[ -v "${key}" ]]; then
        RUN_ARGS+=(--env "${key}")
    fi
done

# 固定容器内服务端口，并把经过校验/补默认值的 URL 明确传入。
RUN_ARGS+=(
    --env SERVER_PORT=8190
    --env "EDP_AGENT_VERSATILE_A2A_URL=${ADAPTER_A2A_URL}"
    --env "EDP_AGENT_VERSATILE_URL=${DIRECT_VERSATILE_URL}"
)
if [ "${DEPLOYMENT}" = "local" ]; then
    # local Redis 的 DNS 契约固定为 edp-redis:6379，不接受 .env 中的意外覆盖。
    RUN_ARGS+=(
        --env EDPA_REDIS_MODE=single
        --env EDPA_REDIS_HOST=edp-redis
        --env EDPA_REDIS_PORT=6379
        --env EDPA_REDIS_PASSWORD=
    )
fi
RUN_ARGS+=("${IMAGE}")

log "启动 EDP Agent：image=${IMAGE}, container=${EDP_CONTAINER}, host-port=${HOST_PORT}"
"${RUN_ARGS[@]}" >/dev/null

if ! wait_for_health "${EDP_CONTAINER}" 180; then
    show_recent_logs "${EDP_CONTAINER}"
    die "EDP Agent 未通过健康检查。容器已保留，便于使用 docker logs ${EDP_CONTAINER} 排查。"
fi

log "EDP Agent 已启动：http://127.0.0.1:${HOST_PORT}/.well-known/agent-card.json"
log "查看日志：docker logs -f ${EDP_CONTAINER}"
