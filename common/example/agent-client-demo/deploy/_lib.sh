#!/usr/bin/env bash
# agent-client-demo 独立部署脚本公共函数。只 source 本文件，不要单独执行。

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_EXAMPLE="${SCRIPT_DIR}/.env.example"
ENV_FILE="${ACD_ENV_FILE:-${SCRIPT_DIR}/.env}"

# demo 的父 pom 在 common/example/agent-client-demo/，SDK 在 common/agent-client/ 下，
# 因此 docker build 上下文需要是 common/ 目录（demo 与 SDK 分属两个子目录，需同时进入容器）。
COMMON_DIR="$(cd -- "${SERVICE_DIR}/../../.." && pwd)"

# ownership label：固定名称容器可能被其他团队或旧编排占用，没有正确 label 时绝不接管或删除。
OWNER_LABEL_KEY="com.huawei.edpa.owner"
OWNER_LABEL_VALUE="agent-client-demo"
COMPONENT_LABEL_KEY="com.huawei.edpa.component"

log() {
    printf '[agent-client-demo] %s\n' "$*"
}

warn() {
    printf '[agent-client-demo][警告] %s\n' "$*" >&2
}

die() {
    printf '[agent-client-demo][失败] %s\n' "$*" >&2
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
    die "已创建 ${ENV_FILE}。请先核对镜像名、容器名、宿主机端口与运行模式后重试。"
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

wait_for_container_exit() {
    local name="$1" timeout_seconds="${2:-60}" elapsed=0 state
    # 本 demo 容器跑完自检即退出，用退出码表达成败。等待它到达终态。
    while [ "${elapsed}" -lt "${timeout_seconds}" ]; do
        state="$(docker container inspect --format '{{.State.Status}}' "${name}" 2>/dev/null || true)"
        case "${state}" in
            exited|dead)
                return 0
                ;;
        esac
        sleep 2
        elapsed=$((elapsed + 2))
    done
    warn "等待 ${name} 退出超时（${timeout_seconds}s），可能仍在运行。"
    return 1
}

container_exit_code() {
    docker container inspect --format '{{.State.ExitCode}}' "$1" 2>/dev/null || true
}

show_recent_logs() {
    local name="$1"
    printf '\n===== %s 最近日志 =====\n' "${name}" >&2
    docker logs --tail 100 "${name}" >&2 2>/dev/null || true
}
