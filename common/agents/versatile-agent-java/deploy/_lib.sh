#!/usr/bin/env bash
# Adapter 独立部署脚本公共函数。请由同目录其他脚本 source，不要直接执行。

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${DEPLOY_DIR}/.." && pwd)"
DEFAULT_ENV_FILE="${DEPLOY_DIR}/.env"

CONTAINER_PORT=8191
OWNER_LABEL_KEY="com.huawei.edpa.owner"
OWNER_LABEL_VALUE="adapter-versatile-agent-java"

info() {
    printf '  %s\n' "$*"
}

warn() {
    printf '  [警告] %s\n' "$*" >&2
}

die() {
    printf '  [失败] %s\n' "$*" >&2
    exit 1
}

trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "${value}"
}

# 按 Docker env-file 的 KEY=VALUE 思路读取配置，但绝不 eval/source 用户输入。
# 同一个 key 出现多次时取最后一个，与常见 env-file 行为一致。
get_env_value() {
    local file="$1" wanted="$2" default_value="${3-}"
    local line key value result="${default_value}"

    [ -f "${file}" ] || { printf '%s' "${result}"; return 0; }
    while IFS= read -r line || [ -n "${line}" ]; do
        line="${line%$'\r'}"
        [ -z "$(trim "${line}")" ] && continue
        case "$(trim "${line}")" in
            \#*) continue ;;
        esac
        [[ "${line}" == *"="* ]] || die "${file} 存在非法行（缺少 =）：${line}"
        key="$(trim "${line%%=*}")"
        value="$(trim "${line#*=}")"
        [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "${file} 存在非法配置名：${key}"
        [ "${key}" = "${wanted}" ] && result="${value}"
    done < "${file}"
    printf '%s' "${result}"
}

check_docker() {
    command -v docker >/dev/null 2>&1 || die "未找到 docker，请先安装 Docker。"
    docker info >/dev/null 2>&1 || die "Docker 服务不可用，或当前用户没有访问 Docker 的权限。"
}

load_management_config() {
    ENV_FILE="${ADAPTER_ENV_FILE:-${DEFAULT_ENV_FILE}}"

    local file_image file_container file_port file_network file_alias file_restart
    file_image="$(get_env_value "${ENV_FILE}" ADAPTER_IMAGE "adapter-versatile-agent-java:latest")"
    file_container="$(get_env_value "${ENV_FILE}" ADAPTER_CONTAINER_NAME "adapter-versatile")"
    file_port="$(get_env_value "${ENV_FILE}" ADAPTER_HOST_PORT "8191")"
    file_network="$(get_env_value "${ENV_FILE}" AGENT_NETWORK "agent-net")"
    file_alias="$(get_env_value "${ENV_FILE}" ADAPTER_NETWORK_ALIAS "adapter-versatile")"
    file_restart="$(get_env_value "${ENV_FILE}" RESTART_POLICY "unless-stopped")"

    ADAPTER_IMAGE="${ADAPTER_IMAGE:-${file_image}}"
    ADAPTER_CONTAINER_NAME="${ADAPTER_CONTAINER_NAME:-${file_container}}"
    ADAPTER_HOST_PORT="${ADAPTER_HOST_PORT:-${file_port}}"
    AGENT_NETWORK="${AGENT_NETWORK:-${file_network}}"
    ADAPTER_NETWORK_ALIAS="${ADAPTER_NETWORK_ALIAS:-${file_alias}}"
    RESTART_POLICY="${RESTART_POLICY:-${file_restart}}"

    [ -n "${ADAPTER_IMAGE}" ] || die "ADAPTER_IMAGE 不能为空。"
    [[ "${ADAPTER_IMAGE}" != *[[:space:]]* ]] || die "ADAPTER_IMAGE 不能包含空白字符。"
    [[ "${ADAPTER_CONTAINER_NAME}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "ADAPTER_CONTAINER_NAME 格式非法。"
    [[ "${AGENT_NETWORK}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "AGENT_NETWORK 格式非法。"
    [[ "${ADAPTER_NETWORK_ALIAS}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "ADAPTER_NETWORK_ALIAS 格式非法。"
    [[ "${ADAPTER_HOST_PORT}" =~ ^[0-9]+$ ]] || die "ADAPTER_HOST_PORT 必须是端口数字。"
    (( ADAPTER_HOST_PORT >= 1 && ADAPTER_HOST_PORT <= 65535 )) || die "ADAPTER_HOST_PORT 必须在 1 到 65535 之间。"
    [[ "${RESTART_POLICY}" =~ ^(no|always|unless-stopped|on-failure(:[0-9]+)?)$ ]] || die "RESTART_POLICY 格式非法。"
}

load_runtime_config() {
    ENV_FILE="${ADAPTER_ENV_FILE:-${DEFAULT_ENV_FILE}}"
    [ -f "${ENV_FILE}" ] || die "未找到 ${ENV_FILE}。请先执行：cp ${DEPLOY_DIR}/.env.example ${DEFAULT_ENV_FILE}"
    load_management_config

    VERSATILE_URL="$(get_env_value "${ENV_FILE}" VERSATILE_URL "")"
    VERSATILE_TIMEOUT="$(get_env_value "${ENV_FILE}" VERSATILE_TIMEOUT "600s")"
    VERSATILE_RESULT_NODE="$(get_env_value "${ENV_FILE}" VERSATILE_RESULT_NODE "GXZQAResponseNode")"
    VERSATILE_AGENT_TENANT_ID="$(get_env_value "${ENV_FILE}" VERSATILE_AGENT_TENANT_ID "edp-tenant")"

    [ -n "${VERSATILE_URL}" ] || die "${ENV_FILE} 中 VERSATILE_URL 必须填写。"
    [[ "${VERSATILE_URL}" =~ ^https?://[^[:space:]]+$ ]] || die "VERSATILE_URL 必须是以 http:// 或 https:// 开头且不含空格的 URL。"
    [[ "${VERSATILE_URL}" == *"{conversation_id}"* ]] || die "VERSATILE_URL 必须保留 {conversation_id} 占位符。"
    if [[ "${VERSATILE_URL}" =~ ^https?://(localhost|127\.0\.0\.1|\[::1\])([:/]|$) ]]; then
        die "容器内 localhost/127.0.0.1 指向 adapter 自己，不能作为 Versatile 地址；请使用可路由 DNS/IP 或 host.docker.internal。"
    fi
    [[ "${VERSATILE_TIMEOUT}" =~ ^[0-9]+(ms|s|m|h|d)$ ]] || die "VERSATILE_TIMEOUT 格式非法，例如应写 600s、10m 或 30000ms。"
    [ -n "${VERSATILE_RESULT_NODE}" ] || die "VERSATILE_RESULT_NODE 不能为空。"
}

require_runtime_jar() {
    local candidates=() filtered=() jar
    shopt -s nullglob
    candidates=("${PROJECT_DIR}"/target/adapter-versatile-agent-java-*.jar)
    shopt -u nullglob
    for jar in "${candidates[@]}"; do
        case "${jar}" in
            *-sources.jar|*-javadoc.jar|*-tests.jar|*-plain.jar) ;;
            *) filtered+=("${jar}") ;;
        esac
    done
    [ "${#filtered[@]}" -eq 1 ] || die "期望 target/ 下恰好有一个 adapter 运行 jar，实际找到 ${#filtered[@]} 个；请先完成 Maven package 并清理旧产物。"
    printf '%s' "${filtered[0]}"
}

image_exists() {
    docker image inspect "${ADAPTER_IMAGE}" >/dev/null 2>&1
}

validate_network() {
    local driver scope
    driver="$(docker network inspect --format '{{.Driver}}' "${AGENT_NETWORK}" 2>/dev/null)" || die "无法读取 Docker 网络 ${AGENT_NETWORK}。"
    scope="$(docker network inspect --format '{{.Scope}}' "${AGENT_NETWORK}" 2>/dev/null)" || die "无法读取 Docker 网络 ${AGENT_NETWORK}。"
    [ "${driver}" = "bridge" ] || die "同名网络 ${AGENT_NETWORK} 已存在，但 Driver=${driver}；要求为 bridge，脚本不会擅自重建共享网络。"
    [ "${scope}" = "local" ] || die "同名网络 ${AGENT_NETWORK} 已存在，但 Scope=${scope}；要求为 local。"
}

# 两个团队可同时调用：create 因竞态失败后重新 inspect；谁先创建成功都可以。
# 该脚本只 ensure，任何停止/清理流程都不会删除共享网络。
ensure_network() {
    if ! docker network inspect "${AGENT_NETWORK}" >/dev/null 2>&1; then
        info "共享网络 ${AGENT_NETWORK} 不存在，尝试创建 user-defined bridge..."
        if ! docker network create --driver bridge "${AGENT_NETWORK}" >/dev/null 2>&1; then
            # 可能是另一团队刚好并发创建；此时以重新 inspect 的结果为准。
            docker network inspect "${AGENT_NETWORK}" >/dev/null 2>&1 || die "创建共享网络 ${AGENT_NETWORK} 失败。"
        fi
    fi
    validate_network
    info "共享网络已就绪：${AGENT_NETWORK}（bridge/local）"
}

container_id_if_exists() {
    docker container inspect --format '{{.Id}}' "$1" 2>/dev/null || true
}

assert_owned_container() {
    local container="$1" id owner
    id="$(container_id_if_exists "${container}")"
    [ -n "${id}" ] || die "容器 ${container} 不存在。"
    owner="$(docker container inspect --format "{{index .Config.Labels \"${OWNER_LABEL_KEY}\"}}" "${id}" 2>/dev/null || true)"
    [ "${owner}" = "${OWNER_LABEL_VALUE}" ] || die "容器 ${container} 不属于本部署脚本（${OWNER_LABEL_KEY}=${owner:-<缺失>}），拒绝操作。"
    printf '%s' "${id}"
}

remove_owned_container_if_exists() {
    local container="$1" id owner
    id="$(container_id_if_exists "${container}")"
    [ -n "${id}" ] || return 0
    owner="$(docker container inspect --format "{{index .Config.Labels \"${OWNER_LABEL_KEY}\"}}" "${id}" 2>/dev/null || true)"
    [ "${owner}" = "${OWNER_LABEL_VALUE}" ] || die "同名容器 ${container} 已存在但不属于本团队（${OWNER_LABEL_KEY}=${owner:-<缺失>}），拒绝替换/删除。请更换 ADAPTER_CONTAINER_NAME 或联系其所有者。"
    docker rm -f "${id}" >/dev/null
    info "已移除本团队旧容器：${container}"
}

print_recent_logs() {
    docker logs --tail 80 "${ADAPTER_CONTAINER_NAME}" 2>&1 || true
}

wait_for_healthy() {
    local timeout_seconds="${1:-180}" deadline state health
    deadline=$((SECONDS + timeout_seconds))
    while (( SECONDS < deadline )); do
        state="$(docker container inspect --format '{{.State.Status}}' "${ADAPTER_CONTAINER_NAME}" 2>/dev/null || true)"
        health="$(docker container inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${ADAPTER_CONTAINER_NAME}" 2>/dev/null || true)"
        if [ "${state}" = "running" ] && [ "${health}" = "healthy" ]; then
            return 0
        fi
        if [ "${state}" = "exited" ] || [ "${state}" = "dead" ] || [ "${health}" = "unhealthy" ]; then
            warn "容器状态为 state=${state:-unknown}, health=${health:-unknown}。最近日志："
            print_recent_logs
            return 1
        fi
        sleep 3
    done
    warn "等待容器健康超时。最近日志："
    print_recent_logs
    return 1
}

# 镜像没有 curl，直接使用镜像自带 bash 的 /dev/tcp 请求 Agent Card。
verify_agent_card() {
    docker exec "${ADAPTER_CONTAINER_NAME}" bash -c '
        set -e
        exec 3<>/dev/tcp/127.0.0.1/8191
        printf "GET /.well-known/agent-card.json HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3
        IFS= read -r status <&3
        case "$status" in
            *" 200 "*) exit 0 ;;
            *) printf "Agent Card 返回异常状态行：%s\n" "$status" >&2; exit 1 ;;
        esac
    '
}
