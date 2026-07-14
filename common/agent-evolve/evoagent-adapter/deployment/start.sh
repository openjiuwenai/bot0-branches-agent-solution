#!/usr/bin/env bash
#
# start.sh — EvoAgentAdapter 容器启动脚本（docker run 方式）
#
# 使用：
#   ./start.sh                                # 默认启动
#   ./start.sh --build                        # 先构建镜像再启动
#   ./start.sh --image agent-adapter:v1.0.0   # 指定镜像 tag
#   ./start.sh --port 8901                    # 自定义端口
#   ./start.sh --name my-adapter              # 自定义容器名
#
set -euo pipefail

# ── 颜色输出 ─────────────────────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── 默认值 ────────────────────────────────────────────────────────────
BUILD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$BUILD_DIR"

NAME="agent-adapter"
IMAGE="agent-adapter:latest"
PORT=8900
DO_BUILD=0

# ── 解析参数 ─────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
    case "$1" in
        --build)   DO_BUILD=1; shift ;;
        --image)   IMAGE="$2"; shift 2 ;;
        --port)    PORT="$2"; shift 2 ;;
        --name)    NAME="$2"; shift 2 ;;
        -h|--help)
            echo "用法: $0 [--build] [--image IMAGE] [--port PORT] [--name NAME]"
            echo ""
            echo "参数:"
            echo "  --build       启动前先构建镜像"
            echo "  --image IMG   镜像 tag（默认: agent-adapter:latest）"
            echo "  --port PORT   对外端口（默认: 8900）"
            echo "  --name NAME   容器名（默认: agent-adapter）"
            exit 0
            ;;
        *)  error "未知参数: $1（使用 --help 查看帮助）" ;;
    esac
done

# ── 加载 .env ────────────────────────────────────────────────────────
ENV_FILE="$BUILD_DIR/config/.env"

if [ ! -f "$ENV_FILE" ]; then
    warn "未找到 $ENV_FILE"
    warn "从模板复制: cp config/.env.example config/.env"
    if [ -f "$BUILD_DIR/config/.env.example" ]; then
        cp "$BUILD_DIR/config/.env.example" "$ENV_FILE"
        info "已从模板创建 $ENV_FILE，请编辑后重新运行: vim $ENV_FILE"
    fi
    error "请先配置 .env"
fi

info "加载配置: $ENV_FILE"

# 读取 .env 并 export
set -a
while IFS='=' read -r key value; do
    # 兼容 CRLF 行尾：剥离 key/value 末尾的 CR，避免空行/注释行残留 \r 导致 export 报错
    key="${key%$'\r'}"
    value="${value%$'\r'}"
    case "$key" in
        ''|\#*) continue ;;
    esac
    export "$key=$value"
done < "$ENV_FILE"
set +a

# 应用默认值（.env 未设置时）
: "${HOST_LOG_ROOT:?HOST_LOG_ROOT 未设置，请检查 .env}"
: "${HOST_SKILLS_ROOT:?HOST_SKILLS_ROOT 未设置，请检查 .env}"
: "${HOST_OUTPUT_DIR:=/opt/agent-adapter/data}"
: "${HOST_CONFIG_FILE:=/opt/agent-adapter/agent_adapter_config.yaml}"
: "${ADAPTER_POLL_INTERVAL:=60}"
: "${ADAPTER_START_FROM:=tail}"
: "${ADAPTER_OUTPUT_RETENTION_DAYS:=30}"
: "${ADAPTER_OUTPUT_MAX_FILES:=2000}"
: "${ADAPTER_OUTPUT_MAX_FILE_SIZE:=20MB}"

info "配置已加载:"
info "  HOST_LOG_ROOT    = $HOST_LOG_ROOT"
info "  HOST_SKILLS_ROOT = $HOST_SKILLS_ROOT"
info "  HOST_OUTPUT_DIR  = $HOST_OUTPUT_DIR"
info "  HOST_CONFIG_FILE = $HOST_CONFIG_FILE"
info "  ADAPTER_PORT     = $PORT"
echo ""

# ── 构建镜像 ─────────────────────────────────────────────────────────
if [ "$DO_BUILD" -eq 1 ]; then
    info "构建镜像 $IMAGE ..."
    docker build -t "$IMAGE" ..
    info "镜像构建完成"
fi

# 检查镜像是否存在
docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || error "镜像 $IMAGE 不存在！请先运行: ./start.sh --build"

# ── 确保主机目录存在 ─────────────────────────────────────────────────
mkdir -p "$HOST_LOG_ROOT" 2>/dev/null || warn "无法创建 HOST_LOG_ROOT: $HOST_LOG_ROOT"
mkdir -p "$HOST_SKILLS_ROOT" 2>/dev/null || warn "无法创建 HOST_SKILLS_ROOT: $HOST_SKILLS_ROOT"
mkdir -p "$HOST_OUTPUT_DIR" 2>/dev/null || warn "无法创建 HOST_OUTPUT_DIR: $HOST_OUTPUT_DIR"
mkdir -p "$(dirname "$HOST_CONFIG_FILE")" 2>/dev/null || warn "无法创建 HOST_CONFIG_FILE 父目录: $(dirname "$HOST_CONFIG_FILE")"

# ── 配置文件持久化：首次启动从镜像内模板 seed，已存在则保留 ──────────
# agent_adapter_config.yaml 承载 agents 列表热更新，必须持久化在宿主卷上，
# 否则容器重建后 CRUD 写入丢失。start.sh 负责首次 seed。
if [ -f "$HOST_CONFIG_FILE" ]; then
    info "配置文件已存在，保留现有配置: $HOST_CONFIG_FILE"
else
    info "配置文件不存在，从镜像内模板 seed: $HOST_CONFIG_FILE"
    seed_tmp="$(mktemp)"
    if docker run --rm "$IMAGE" cat /app/agent_adapter_config.yaml > "$seed_tmp" 2>/dev/null; then
        mv "$seed_tmp" "$HOST_CONFIG_FILE"
        info "配置文件 seed 完成"
    else
        rm -f "$seed_tmp"
        error "从镜像 seed 配置文件失败，请确认镜像 $IMAGE 含 /app/agent_adapter_config.yaml"
    fi
fi

# ── 清理旧容器 ─────────────────────────────────────────────────────
if docker ps -a --format '{{.Names}}' | grep -q "^${NAME}$"; then
    warn "发现同名容器 $NAME，正在删除..."
    docker rm -f "$NAME" >/dev/null
fi

# ── Docker 版本探测：旧版 (< 20) 需要 seccomp=unconfined 才能创建线程 ──
# host-gateway 关键字需 Docker 20.10+（20 系列首个版本即 20.10，故 DOCKER_MAJOR>=20 即满足）。
SECURITY_OPTS=()
ADD_HOST_OPTS=()
DOCKER_MAJOR=$(docker version --format '{{.Server.Version}}' 2>/dev/null | cut -d. -f1 || echo "0")
if [ -z "$DOCKER_MAJOR" ] || [ "$DOCKER_MAJOR" -lt 20 ] 2>/dev/null; then
    warn "Docker 版本 < 20（检测到: ${DOCKER_MAJOR:-未知}），注入 --security-opt seccomp=unconfined"
    warn "Docker 版本 < 20.10，跳过 host.docker.internal:host-gateway 映射（回调宿主端点需改用宿主 IP）"
    SECURITY_OPTS=(--security-opt seccomp=unconfined)
else
    info "Docker 版本: $DOCKER_MAJOR（≥ 20），使用默认 seccomp 策略"
    # 映射 host.docker.internal → 宿主机网关，使容器内可回调宿主/其他容器发布的业务 Agent 端点
    ADD_HOST_OPTS=(--add-host host.docker.internal:host-gateway)
fi

# ── 启动容器 ────────────────────────────────────────────────────────
info "启动容器 $NAME（镜像: $IMAGE, 端口: $PORT）"

docker run -d \
    --name "$NAME" \
    --init \
    "${SECURITY_OPTS[@]}" \
    "${ADD_HOST_OPTS[@]}" \
    -p "${PORT}:8900" \
    -e ADAPTER_HOST="0.0.0.0" \
    -e ADAPTER_PORT=8900 \
    -e ADAPTER_LOG_DIR=/data/logs \
    -e ADAPTER_SKILLS_ROOT=/data/skills \
    -e ADAPTER_POLL_INTERVAL="$ADAPTER_POLL_INTERVAL" \
    -e ADAPTER_START_FROM="$ADAPTER_START_FROM" \
    -e ADAPTER_OUTPUT_RETENTION_DAYS="$ADAPTER_OUTPUT_RETENTION_DAYS" \
    -e ADAPTER_OUTPUT_MAX_FILES="$ADAPTER_OUTPUT_MAX_FILES" \
    -e ADAPTER_OUTPUT_MAX_FILE_SIZE="$ADAPTER_OUTPUT_MAX_FILE_SIZE" \
    -e ADAPTER_LOG_LEVEL="${ADAPTER_LOG_LEVEL:-INFO}" \
    -v "$HOST_OUTPUT_DIR:/app/data" \
    -v "$HOST_CONFIG_FILE:/app/agent_adapter_config.yaml" \
    -v "$HOST_LOG_ROOT:/data/logs:ro" \
    -v "$HOST_SKILLS_ROOT:/data/skills" \
    --restart always \
    --health-cmd="curl -f http://localhost:8900/api/v1/status || exit 1" \
    --health-interval=30s \
    --health-timeout=5s \
    --health-retries=3 \
    --health-start-period=15s \
    "$IMAGE" >/dev/null

# ── 等待就绪 ────────────────────────────────────────────────────────
info -n "等待容器就绪"
for i in $(seq 1 30); do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$NAME" 2>/dev/null || echo "starting")
    if [ "$status" = "healthy" ]; then
        echo " ✓"
        break
    fi
    echo -n "."
    sleep 1
done
echo ""

# 检查最终状态
final_status=$(docker inspect -f '{{.State.Health.Status}}' "$NAME" 2>/dev/null || echo "unknown")
if [ "$final_status" != "healthy" ]; then
    warn "容器状态: $final_status（而非 healthy）"
    warn "查看日志: docker logs $NAME"
fi

# ── 输出信息 ─────────────────────────────────────────────────────────
echo ""
info "==================================="
info "EvoAgentAdapter 启动完成！"
info "容器名:   $NAME"
info "镜像:     $IMAGE"
info "端口映射: ${PORT}:8900"
info "日志目录: $HOST_LOG_ROOT → /data/logs (ro, per-agent 子目录 /data/logs/{name})"
info "Skills:   $HOST_SKILLS_ROOT → /data/skills (rw, per-agent 子目录 /data/skills/{name})"
info "输出目录: $HOST_OUTPUT_DIR → /app/data (rw)"
info "配置文件: $HOST_CONFIG_FILE → /app/agent_adapter_config.yaml (rw)"
info ""
info "健康检查:  curl http://localhost:${PORT}/api/v1/status"
info ""
info "查看日志:  docker logs -f $NAME"
info "停止容器:  ./stop.sh"
info "==================================="
