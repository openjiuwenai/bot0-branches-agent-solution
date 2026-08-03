#!/usr/bin/env bash
# 启动 agent-client-demo 容器并运行验证。
#
# 三种运行模式（由 .env 的 ACD_RUN_MODE 选择）：
#   CLI（默认）      ：运行 CLI 自检，跑完即退出，退出码 0=全部断言通过、非 0=失败。
#                     适合 CI/容器门禁；脚本会等待容器退出并解析退出码。
#   UI              ：打开浏览器验证控制台（看板），常驻不退出。
#                     注意：VerificationUiServer 硬编码绑定 127.0.0.1，容器内 UI 无法通过
#                     -p 端口映射从宿主机访问，脚本会自动改用 --network host 启动。
#   EXTERNAL        ：连接外部 gateway（AGENT_GATEWAY_URL）做联调验证，跑完即退出。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker
ensure_env_file

IMAGE="$(config_value ACD_IMAGE agent-client-demo:0.1.0)"
CONTAINER="$(config_value ACD_CONTAINER agent-client-demo)"
HOST_PORT="$(config_value ACD_HOST_PORT 8080)"
RUN_MODE="$(config_value ACD_RUN_MODE CLI)"
UI_PORT="$(config_value UI_PORT 9090)"
GATEWAY_URL="$(config_value AGENT_GATEWAY_URL '')"

validate_image_ref "${IMAGE}"
validate_resource_name "容器" "${CONTAINER}"
validate_port ACD_HOST_PORT "${HOST_PORT}"
case "${RUN_MODE}" in
    CLI|UI|EXTERNAL) ;;
    *) die "ACD_RUN_MODE 只能是 CLI、UI 或 EXTERNAL，当前值：${RUN_MODE}" ;;
esac

docker image inspect "${IMAGE}" >/dev/null 2>&1 \
    || die "未找到镜像 ${IMAGE}。请先执行 build-image.sh，或从镜像仓库拉取同名版本。"

# 在任何有副作用的替换操作之前先检查同名资源归属。
if container_exists "${CONTAINER}"; then
    require_owned_container "${CONTAINER}" agent-client-demo
fi

# EXTERNAL 模式必须填写 gateway URL。
if [ "${RUN_MODE}" = "EXTERNAL" ]; then
    [ -n "${GATEWAY_URL}" ] \
        || die "EXTERNAL 模式必须填写 AGENT_GATEWAY_URL（外部 gateway 的 baseUrl，如 http://10.0.0.5:8080）。"
    validate_http_url AGENT_GATEWAY_URL "${GATEWAY_URL}"
fi

# 构建运行参数（不把密钥值拼进 shell 命令，也不 source/eval env 文件）。
RUN_ARGS=(
    docker run -d
    --name "${CONTAINER}"
    --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}"
    --label "${COMPONENT_LABEL_KEY}=agent-client-demo"
    --env ACD_RUN_MODE="${RUN_MODE}"
)

if [ "${RUN_MODE}" = "EXTERNAL" ]; then
    # EXTERNAL 模式：把外部 gateway URL 传入容器。容器用默认 bridge 网络即可访问外部 gateway。
    RUN_ARGS+=(--env "AGENT_GATEWAY_URL=${GATEWAY_URL}")
elif [ "${RUN_MODE}" = "UI" ]; then
    # UI 模式：VerificationUiServer 硬编码绑定 127.0.0.1，-p 端口映射无法从宿主机访问。
    # 改用 --network host，让容器直接复用宿主网络栈，宿主机浏览器可访问 http://127.0.0.1:UI_PORT/。
    # --network host 与 -p 互斥，因此 UI 模式不映射端口。
    validate_port UI_PORT "${UI_PORT}"
    RUN_ARGS+=(--network host --env "UI_PORT=${UI_PORT}")
    log "UI 模式：使用 --network host。容器启动后请在宿主机浏览器打开 http://127.0.0.1:${UI_PORT}/"
else
    # CLI 模式：内嵌启动 mock-gateway 做端到端自检，全部在本容器内完成，无需任何外部网络。
    # 不映射端口（CLI 模式无需宿主机访问）。
    :
fi

# remove 旧容器（已校验归属）后启动。
remove_owned_container "${CONTAINER}" agent-client-demo

RUN_ARGS+=("${IMAGE}")

log "启动 agent-client-demo：image=${IMAGE}, container=${CONTAINER}, mode=${RUN_MODE}"
"${RUN_ARGS[@]}" >/dev/null

case "${RUN_MODE}" in
    CLI|EXTERNAL)
        # 跑完即退出，等待容器到达终态并解析退出码。
        log "等待验证运行完成（容器退出码 0=全部断言通过，非 0=失败）..."
        if ! wait_for_container_exit "${CONTAINER}" 300; then
            show_recent_logs "${CONTAINER}"
            die "验证容器 300s 内未退出。容器已保留，便于使用 docker logs ${CONTAINER} 排查。"
        fi
        EXIT_CODE="$(container_exit_code "${CONTAINER}")"
        show_recent_logs "${CONTAINER}"
        if [ "${EXIT_CODE}" = "0" ]; then
            log "验证通过：全部断言 PASSED（exit=0）。"
            # 验证通过后清理容器（保留可加 --keep 参数，当前保持简单）。
            docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
            log "容器已清理。"
        else
            warn "验证失败：exit=${EXIT_CODE}。容器已保留，便于使用 docker logs ${CONTAINER} 排查。"
            exit "${EXIT_CODE}"
        fi
        ;;
    UI)
        # 常驻看板，不等待退出。
        if container_running "${CONTAINER}"; then
            log "验证控制台已启动：http://127.0.0.1:${UI_PORT}/"
            log "查看日志：docker logs -f ${CONTAINER}"
            log "停止：bash ${SCRIPT_DIR}/stop.sh"
        else
            show_recent_logs "${CONTAINER}"
            die "UI 容器启动后立即退出，请检查日志。"
        fi
        ;;
esac
