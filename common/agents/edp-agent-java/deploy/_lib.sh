#!/usr/bin/env bash
# EDP Agent 独立部署脚本公共函数。只 source 本文件，不要单独执行。

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_EXAMPLE="${SCRIPT_DIR}/.env.example"
ENV_FILE="${EDP_ENV_FILE:-${SCRIPT_DIR}/.env}"

OWNER_LABEL_KEY="com.huawei.edpa.owner"
OWNER_LABEL_VALUE="edp-agent-java"
COMPONENT_LABEL_KEY="com.huawei.edpa.component"

log() {
    printf '[EDP部署] %s\n' "$*"
}

warn() {
    printf '[EDP部署][警告] %s\n' "$*" >&2
}

die() {
    printf '[EDP部署][失败] %s\n' "$*" >&2
    exit 1
}

check_docker() {
    command -v docker >/dev/null 2>&1 || die "未找到 docker，请先安装 Docker Engine。"
    docker info >/dev/null 2>&1 || die "Docker daemon 不可用；请启动 Docker 并确认当前用户有权限访问。"
}

ensure_env_file() {
    if [ -f "${ENV_FILE}" ]; then
        return
    fi
    cp "${ENV_EXAMPLE}" "${ENV_FILE}"
    chmod 600 "${ENV_FILE}" 2>/dev/null || true
    die "已创建 ${ENV_FILE}。请先填写 EDP_AGENT_MODEL_API_KEY，并核对 Redis、模型和 adapter 地址后重试。"
}

# 读取严格的 KEY=value 文件。不会 source/eval，密钥内容不会作为 shell 代码执行。
get_file_value() {
    local key="$1" value
    [ -f "${ENV_FILE}" ] || return 0
    value="$(awk -v key="${key}" '
        BEGIN { prefix = key "=" }
        index($0, prefix) == 1 {
            value = substr($0, length(prefix) + 1)
            found = 1
        }
        END { if (found) print value }
    ' "${ENV_FILE}")"
    value="${value%$'\r'}"
    # 只裁掉首尾空白，不解释引号、反斜杠、$ 或命令替换。
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "${value}"
}

# 优先级：当前进程环境变量 > deploy/.env > 默认值。
config_value() {
    local key="$1" default_value="${2-}" value
    if [[ -v "${key}" ]]; then
        printf '%s' "${!key}"
        return
    fi
    value="$(get_file_value "${key}")"
    if [ -n "${value}" ]; then
        printf '%s' "${value}"
    else
        printf '%s' "${default_value}"
    fi
}

validate_resource_name() {
    local kind="$1" value="$2"
    [[ "${value}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] \
        || die "${kind} 名称不合法：${value}"
}

validate_port() {
    local name="$1" value="$2"
    [[ "${value}" =~ ^[0-9]+$ ]] && [ "${value}" -ge 1 ] && [ "${value}" -le 65535 ] \
        || die "${name} 必须是 1-65535 的端口号，当前值：${value}"
}

validate_image_ref() {
    local value="$1"
    [ -n "${value}" ] || die "镜像名不能为空。"
    [[ "${value}" != -* ]] || die "镜像名不能以 - 开头：${value}"
    [[ "${value}" != *[[:space:]]* ]] || die "镜像名不能包含空白：${value}"
}

validate_http_url() {
    local name="$1" value="$2"
    [[ "${value}" =~ ^https?://[^[:space:]]+$ ]] \
        || die "${name} 必须是非空的 http:// 或 https:// URL，当前值：${value}"
}

container_exists() {
    docker container inspect "$1" >/dev/null 2>&1
}

container_running() {
    [ "$(docker container inspect --format '{{.State.Running}}' "$1" 2>/dev/null || true)" = "true" ]
}

# 固定名称可能已被其它团队或旧编排占用。没有正确 ownership label 时绝不接管或删除。
require_owned_container() {
    local name="$1" expected_component="$2" owner component
    owner="$(docker container inspect --format '{{ index .Config.Labels "com.huawei.edpa.owner" }}' "${name}" 2>/dev/null || true)"
    component="$(docker container inspect --format '{{ index .Config.Labels "com.huawei.edpa.component" }}' "${name}" 2>/dev/null || true)"
    if [ "${owner}" != "${OWNER_LABEL_VALUE}" ] || [ "${component}" != "${expected_component}" ]; then
        die "同名容器 ${name} 不属于本部署脚本（owner=${owner:-无}, component=${component:-无}），为避免误删已拒绝操作。请人工确认并迁移/改名。"
    fi
}

remove_owned_container() {
    local name="$1" component="$2"
    if ! container_exists "${name}"; then
        return
    fi
    require_owned_container "${name}" "${component}"
    docker rm -f "${name}" >/dev/null
    log "已删除旧的自有容器：${name}"
}

# 共享网络属于两个服务的运行契约：双方均可幂等 ensure，任何一方都不能在 stop 中删除。
ensure_network() {
    local network="$1" driver
    validate_resource_name "Docker 网络" "${network}"

    if docker network inspect "${network}" >/dev/null 2>&1; then
        driver="$(docker network inspect --format '{{.Driver}}' "${network}")"
        [ "${driver}" = "bridge" ] || die "网络 ${network} 已存在但 driver=${driver}，要求为 bridge。"
        log "共享网络已存在：${network}"
        return
    fi

    if docker network create --driver bridge \
        --label com.huawei.edpa.scope=shared-agent-network \
        "${network}" >/dev/null 2>&1; then
        log "已创建共享 bridge 网络：${network}"
        return
    fi

    # 两团队可能同时首次部署：create 的失败者重新 inspect，而不是直接判失败。
    if docker network inspect "${network}" >/dev/null 2>&1; then
        driver="$(docker network inspect --format '{{.Driver}}' "${network}")"
        [ "${driver}" = "bridge" ] || die "并发创建后的网络 ${network} 不是 bridge（driver=${driver}）。"
        log "共享网络已由另一个部署进程创建：${network}"
        return
    fi
    die "无法创建或读取共享网络：${network}"
}

ensure_volume() {
    local volume="$1"
    validate_resource_name "Docker volume" "${volume}"
    if docker volume inspect "${volume}" >/dev/null 2>&1; then
        log "Redis named volume 已存在：${volume}"
        return
    fi
    if docker volume create \
        --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}" \
        --label "${COMPONENT_LABEL_KEY}=redis-data" \
        "${volume}" >/dev/null 2>&1; then
        log "已创建 Redis named volume：${volume}"
        return
    fi
    # 与网络相同，处理两个进程同时首次创建 volume 的竞态。
    docker volume inspect "${volume}" >/dev/null 2>&1 \
        || die "无法创建或读取 Redis volume：${volume}"
    log "Redis volume 已由另一个部署进程创建：${volume}"
}

wait_for_health() {
    local name="$1" timeout_seconds="${2:-180}" elapsed=0 state health
    while [ "${elapsed}" -lt "${timeout_seconds}" ]; do
        state="$(docker container inspect --format '{{.State.Status}}' "${name}" 2>/dev/null || true)"
        health="$(docker container inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${name}" 2>/dev/null || true)"
        case "${health}" in
            healthy)
                log "容器健康检查通过：${name}"
                return 0
                ;;
            unhealthy)
                warn "容器健康检查失败：${name}"
                return 1
                ;;
            none)
                if [ "${state}" = "running" ]; then
                    log "容器正在运行（镜像未定义 HEALTHCHECK）：${name}"
                    return 0
                fi
                ;;
        esac
        if [ "${state}" = "exited" ] || [ "${state}" = "dead" ]; then
            warn "容器已退出：${name}（state=${state}）"
            return 1
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    warn "等待 ${name} 健康超时（${timeout_seconds}s）。"
    return 1
}

show_recent_logs() {
    local name="$1"
    printf '\n===== %s 最近日志 =====\n' "${name}" >&2
    docker logs --tail 100 "${name}" >&2 2>/dev/null || true
}

# 使用 EDP 镜像内置 Python 从目标 Docker 网络探测 HTTP；不携带应用密钥。
probe_http_from_network() {
    local image="$1" network="$2" url="$3"
    docker run --rm \
        --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}" \
        --label "${COMPONENT_LABEL_KEY}=connectivity-probe" \
        --network "${network}" \
        --entrypoint python3 \
        "${image}" \
        -c 'import sys, urllib.request; urllib.request.urlopen(sys.argv[1], timeout=4).read(1)' \
        "${url}" >/dev/null 2>&1
}

probe_tcp_from_network() {
    local image="$1" network="$2" host="$3" port="$4"
    docker run --rm \
        --label "${OWNER_LABEL_KEY}=${OWNER_LABEL_VALUE}" \
        --label "${COMPONENT_LABEL_KEY}=connectivity-probe" \
        --network "${network}" \
        --entrypoint python3 \
        "${image}" \
        -c 'import socket, sys; s=socket.create_connection((sys.argv[1], int(sys.argv[2])), 4); s.close()' \
        "${host}" "${port}" >/dev/null 2>&1
}

adapter_agent_card_url() {
    local a2a_url="$1"
    # scheme://authority 后统一替换成 Agent Card 路径。
    printf '%s' "${a2a_url}" | awk -F/ '{ print $1 "//" $3 "/.well-known/agent-card.json" }'
}
