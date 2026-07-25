#!/usr/bin/env bash
# 启动/更新 adapter 自己的容器；只 ensure 共享网络，不管理其他服务。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

load_runtime_config
check_docker
image_exists || die "未找到镜像 ${ADAPTER_IMAGE}；请先运行 ${DEPLOY_DIR}/build-image.sh，或在 .env 中填写已发布镜像。"

printf '%s\n' "========================================"
printf '%s\n' "  启动 Adapter 容器"
printf '%s\n' "========================================"
info "容器：${ADAPTER_CONTAINER_NAME}"
info "镜像：${ADAPTER_IMAGE}"
info "端口：宿主机 ${ADAPTER_HOST_PORT} -> 容器 ${CONTAINER_PORT}"
info "网络/别名：${AGENT_NETWORK} / ${ADAPTER_NETWORK_ALIAS}"

# 先检查/创建共享网络；构建镜像与该动作没有先后依赖。
ensure_network

# 仅会替换带有本团队 ownership label 的同名容器。
remove_owned_container_if_exists "${ADAPTER_CONTAINER_NAME}"

run_args=(
    docker run -d
    --name "${ADAPTER_CONTAINER_NAME}"
    --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}"
    --network "${AGENT_NETWORK}"
    --network-alias "${ADAPTER_NETWORK_ALIAS}"
    --restart "${RESTART_POLICY}"
    -p "${ADAPTER_HOST_PORT}:${CONTAINER_PORT}"
    -e "VERSATILE_AGENT_PORT=${CONTAINER_PORT}"
    -e "VERSATILE_URL=${VERSATILE_URL}"
    -e "VERSATILE_TIMEOUT=${VERSATILE_TIMEOUT}"
    -e "VERSATILE_RESULT_NODE=${VERSATILE_RESULT_NODE}"
    -e "VERSATILE_AGENT_TENANT_ID=${VERSATILE_AGENT_TENANT_ID}"
)

# Linux 上访问宿主机服务时，为 host.docker.internal 注入稳定网关映射。
if [[ "${VERSATILE_URL}" =~ ^https?://host\.docker\.internal([:/]|$) ]]; then
    run_args+=(--add-host "host.docker.internal:host-gateway")
fi
run_args+=("${ADAPTER_IMAGE}")

if ! "${run_args[@]}" >/dev/null; then
    die "启动 ${ADAPTER_CONTAINER_NAME} 失败；常见原因是宿主机端口 ${ADAPTER_HOST_PORT} 被占用。"
fi

info "容器已创建，等待健康检查（最多约 180 秒）..."
wait_for_healthy 180 || die "Adapter 未达到 healthy，容器保留以便排查。"
verify_agent_card || { print_recent_logs; die "Agent Card 验证失败。"; }

info "Adapter 已可用。"
info "宿主机验证地址：http://127.0.0.1:${ADAPTER_HOST_PORT}/.well-known/agent-card.json"
info "同机 EDP 容器应使用：http://${ADAPTER_NETWORK_ALIAS}:${CONTAINER_PORT}/a2a"
info "注意：本检查不代表真实 Versatile 业务请求一定成功，仍需执行手册中的业务冒烟测试。"
