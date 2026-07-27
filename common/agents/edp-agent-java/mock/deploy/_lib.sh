#!/usr/bin/env bash
# Shared helpers for the independent Versatile mock deployment scripts.

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${DEPLOY_DIR}/.." && pwd)"
DEFAULT_ENV_FILE="${DEPLOY_DIR}/.env"

CONTAINER_PORT=30001
OWNER_LABEL_KEY="com.huawei.edpa.owner"
OWNER_LABEL_VALUE="streaming-agent-testing-evaluation-mock"
COMPONENT_LABEL_KEY="com.huawei.edpa.component"
COMPONENT_LABEL_VALUE="versatile-mock"

info() {
    printf '[Mock部署] %s\n' "$*"
}

warn() {
    printf '[Mock部署][警告] %s\n' "$*" >&2
}

die() {
    printf '[Mock部署][失败] %s\n' "$*" >&2
    exit 1
}

trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "${value}"
}

# Parse strict KEY=value configuration without source/eval.
get_file_value() {
    local file="$1" wanted="$2" default_value="${3-}"
    local line key value result="${default_value}" stripped

    [ -f "${file}" ] || { printf '%s' "${result}"; return 0; }
    while IFS= read -r line || [ -n "${line}" ]; do
        line="${line%$'\r'}"
        stripped="$(trim "${line}")"
        [ -z "${stripped}" ] && continue
        case "${stripped}" in
            \#*) continue ;;
        esac
        [[ "${line}" == *"="* ]] || die "${file} contains an invalid line without '=': ${line}"
        key="$(trim "${line%%=*}")"
        value="$(trim "${line#*=}")"
        [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "Invalid configuration key in ${file}: ${key}"
        [ "${key}" = "${wanted}" ] && result="${value}"
    done < "${file}"
    printf '%s' "${result}"
}

# Priority: exported process environment > deploy/.env > default.
config_value() {
    local key="$1" default_value="${2-}"
    if [[ -v "${key}" ]]; then
        printf '%s' "${!key}"
        return
    fi
    get_file_value "${ENV_FILE}" "${key}" "${default_value}"
}

check_docker() {
    command -v docker >/dev/null 2>&1 || die "Docker is not installed."
    docker info >/dev/null 2>&1 || die "Docker daemon is unavailable or the current user lacks permission."
}

ensure_env_file() {
    ENV_FILE="${MOCK_ENV_FILE:-${DEFAULT_ENV_FILE}}"
    if [ -f "${ENV_FILE}" ]; then
        return
    fi
    cp "${DEPLOY_DIR}/.env.example" "${ENV_FILE}"
    chmod 600 "${ENV_FILE}" 2>/dev/null || true
    die "Created ${ENV_FILE}. Review it and run the command again."
}

validate_resource_name() {
    local label="$1" value="$2"
    [[ "${value}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "Invalid ${label}: ${value}"
}

validate_image_ref() {
    local value="$1"
    [ -n "${value}" ] || die "MOCK_IMAGE cannot be empty."
    [[ "${value}" != -* && "${value}" != *[[:space:]]* ]] || die "Invalid MOCK_IMAGE: ${value}"
}

validate_port() {
    local name="$1" value="$2"
    [[ "${value}" =~ ^[0-9]+$ ]] && (( value >= 1 && value <= 65535 )) \
        || die "${name} must be an integer from 1 to 65535."
}

normalize_bool() {
    local name="$1" value
    value="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
    case "${value}" in
        true|false) printf '%s' "${value}" ;;
        *) die "${name} must be true or false." ;;
    esac
}

validate_nonnegative_number() {
    local name="$1" value="$2"
    [[ "${value}" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "${name} must be a non-negative number."
}

load_management_config() {
    ENV_FILE="${MOCK_ENV_FILE:-${DEFAULT_ENV_FILE}}"
    MOCK_IMAGE="$(config_value MOCK_IMAGE versatile-mock:1.0.0)"
    MOCK_CONTAINER_NAME="$(config_value MOCK_CONTAINER_NAME versatile-mock)"
    AGENT_NETWORK="$(config_value AGENT_NETWORK agent-net)"
    MOCK_NETWORK_ALIAS="$(config_value MOCK_NETWORK_ALIAS versatile-mock)"
    RESTART_POLICY="$(config_value RESTART_POLICY unless-stopped)"
    MOCK_PIP_INDEX_URL="$(config_value MOCK_PIP_INDEX_URL https://pypi.org/simple)"
    MOCK_PUBLISH_PORT="$(normalize_bool MOCK_PUBLISH_PORT "$(config_value MOCK_PUBLISH_PORT false)")"
    MOCK_HOST_BIND="$(config_value MOCK_HOST_BIND 127.0.0.1)"
    MOCK_HOST_PORT="$(config_value MOCK_HOST_PORT 30001)"

    validate_image_ref "${MOCK_IMAGE}"
    validate_resource_name "container name" "${MOCK_CONTAINER_NAME}"
    validate_resource_name "network name" "${AGENT_NETWORK}"
    validate_resource_name "network alias" "${MOCK_NETWORK_ALIAS}"
    [[ "${RESTART_POLICY}" =~ ^(no|always|unless-stopped|on-failure(:[0-9]+)?)$ ]] \
        || die "Invalid RESTART_POLICY: ${RESTART_POLICY}"
    [[ "${MOCK_PIP_INDEX_URL}" =~ ^https?://[^[:space:]]+$ ]] \
        || die "MOCK_PIP_INDEX_URL must be an HTTP(S) URL."
    [[ "${MOCK_HOST_BIND}" =~ ^[0-9A-Za-z_.-]+$ ]] || die "Invalid MOCK_HOST_BIND."
    validate_port MOCK_HOST_PORT "${MOCK_HOST_PORT}"
}

load_runtime_config() {
    ensure_env_file
    load_management_config

    MOCK_SKIP_COOKIE_AUTH="$(normalize_bool MOCK_SKIP_COOKIE_AUTH "$(config_value MOCK_SKIP_COOKIE_AUTH true)")"
    MOCK_BALANCE_DELAY_SECONDS="$(config_value MOCK_BALANCE_DELAY_SECONDS 0)"
    MOCK_TRANSFER_AMOUNTS="$(config_value MOCK_TRANSFER_AMOUNTS '')"
    MOCK_TRANSFER_MODE="$(printf '%s' "$(config_value MOCK_TRANSFER_MODE cycle)" | tr '[:upper:]' '[:lower:]')"
    MOCK_LICAI_BALANCE="$(config_value MOCK_LICAI_BALANCE 1000.0)"
    MOCK_CHUXU_BALANCE="$(config_value MOCK_CHUXU_BALANCE 125680.5)"
    MOCK_SAME_CARD_MODE="$(normalize_bool MOCK_SAME_CARD_MODE "$(config_value MOCK_SAME_CARD_MODE false)")"
    MOCK_BALANCE_SAME_CARD="$(normalize_bool MOCK_BALANCE_SAME_CARD "$(config_value MOCK_BALANCE_SAME_CARD false)")"
    MOCK_PRODUCT_BUY_SUCCESS="$(normalize_bool MOCK_PRODUCT_BUY_SUCCESS "$(config_value MOCK_PRODUCT_BUY_SUCCESS true)")"

    [[ "${MOCK_BALANCE_DELAY_SECONDS}" =~ ^[0-9]+$ ]] \
        || die "MOCK_BALANCE_DELAY_SECONDS must be a non-negative integer."
    case "${MOCK_TRANSFER_MODE}" in
        cycle|last|full|fail) ;;
        *) die "MOCK_TRANSFER_MODE must be cycle, last, full, or fail." ;;
    esac
    if [ -n "${MOCK_TRANSFER_AMOUNTS}" ]; then
        [[ "${MOCK_TRANSFER_AMOUNTS}" =~ ^[0-9]+([.][0-9]+)?(,[0-9]+([.][0-9]+)?)*$ ]] \
            || die "MOCK_TRANSFER_AMOUNTS must be a comma-separated numeric list without spaces."
    fi
    validate_nonnegative_number MOCK_LICAI_BALANCE "${MOCK_LICAI_BALANCE}"
    validate_nonnegative_number MOCK_CHUXU_BALANCE "${MOCK_CHUXU_BALANCE}"
}

require_sources() {
    local path
    for path in \
        "${PROJECT_DIR}/versatile_main.py" \
        "${PROJECT_DIR}/engine/__init__.py" \
        "${PROJECT_DIR}/config/server.json" \
        "${PROJECT_DIR}/workflows/default.json" \
        "${DEPLOY_DIR}/requirements.txt" \
        "${DEPLOY_DIR}/Dockerfile"; do
        [ -e "${path}" ] || die "Docker build context is missing: ${path}"
    done
}

validate_network() {
    local driver scope
    driver="$(docker network inspect --format '{{.Driver}}' "${AGENT_NETWORK}" 2>/dev/null)" \
        || die "Cannot inspect Docker network ${AGENT_NETWORK}."
    scope="$(docker network inspect --format '{{.Scope}}' "${AGENT_NETWORK}" 2>/dev/null)" \
        || die "Cannot inspect Docker network ${AGENT_NETWORK}."
    [ "${driver}" = "bridge" ] || die "Network ${AGENT_NETWORK} exists but driver=${driver}; bridge is required."
    [ "${scope}" = "local" ] || die "Network ${AGENT_NETWORK} exists but scope=${scope}; local is required."
}

# Concurrent-safe: if another team creates the network between inspect/create, inspect again.
ensure_network() {
    if ! docker network inspect "${AGENT_NETWORK}" >/dev/null 2>&1; then
        info "Creating shared bridge network: ${AGENT_NETWORK}"
        if ! docker network create --driver bridge "${AGENT_NETWORK}" >/dev/null 2>&1; then
            docker network inspect "${AGENT_NETWORK}" >/dev/null 2>&1 \
                || die "Cannot create shared network ${AGENT_NETWORK}."
        fi
    fi
    validate_network
}

container_id_if_exists() {
    docker container inspect --format '{{.Id}}' "$1" 2>/dev/null || true
}

assert_owned_container() {
    local name="$1" id owner component
    id="$(container_id_if_exists "${name}")"
    [ -n "${id}" ] || die "Container does not exist: ${name}"
    owner="$(docker container inspect --format "{{index .Config.Labels \"${OWNER_LABEL_KEY}\"}}" "${id}" 2>/dev/null || true)"
    component="$(docker container inspect --format "{{index .Config.Labels \"${COMPONENT_LABEL_KEY}\"}}" "${id}" 2>/dev/null || true)"
    [ "${owner}" = "${OWNER_LABEL_VALUE}" ] && [ "${component}" = "${COMPONENT_LABEL_VALUE}" ] \
        || die "Container ${name} is not owned by this deployment (owner=${owner:-missing}, component=${component:-missing})."
    printf '%s' "${id}"
}

remove_owned_container_if_exists() {
    local name="$1" id
    id="$(container_id_if_exists "${name}")"
    [ -n "${id}" ] || return 0
    assert_owned_container "${name}" >/dev/null
    docker rm -f "${id}" >/dev/null
    info "Removed previous owned container: ${name}"
}

image_exists() {
    docker image inspect "${MOCK_IMAGE}" >/dev/null 2>&1
}

show_recent_logs() {
    docker logs --tail 100 "${MOCK_CONTAINER_NAME}" 2>&1 || true
}

wait_for_healthy() {
    local timeout_seconds="${1:-90}" elapsed=0 state health
    while (( elapsed < timeout_seconds )); do
        state="$(docker container inspect --format '{{.State.Status}}' "${MOCK_CONTAINER_NAME}" 2>/dev/null || true)"
        health="$(docker container inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${MOCK_CONTAINER_NAME}" 2>/dev/null || true)"
        if [ "${state}" = "running" ] && [ "${health}" = "healthy" ]; then
            return 0
        fi
        if [ "${state}" = "exited" ] || [ "${state}" = "dead" ] || [ "${health}" = "unhealthy" ]; then
            warn "Container state=${state:-unknown}, health=${health:-unknown}."
            show_recent_logs
            return 1
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    warn "Timed out waiting for mock health."
    show_recent_logs
    return 1
}
