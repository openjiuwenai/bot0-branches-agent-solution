#!/usr/bin/env bash
#
# run.sh — EvoAgent Docker 容器启动脚本
#
# 功能：
#   从 config/*.env 读取配置并启动 EvoAgent 容器
#
# 使用：
#   ./run.sh                                          # 默认启动
#   ./run.sh --image evoagent:v1.0.0 --port 8000      # 自定义镜像和端口
#   ./run.sh --name my-evoagent                        # 自定义容器名
#
# 参数：
#   --name NAME     容器名（默认 evoagent）
#   --image IMAGE   镜像 tag（默认 evoagent:latest）
#   --port PORT     对外映射端口（默认 8000）
#   -h, --help      显示帮助
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
NAME="evoagent"
IMAGE="${EVOAGENT_IMAGE_TAG:-evoagent:latest}"
PORT=8000

# ── 解析参数 ─────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
    case "$1" in
        --name)     NAME="$2"; shift 2 ;;
        --image)    IMAGE="$2"; shift 2 ;;
        --port)     PORT="$2"; shift 2 ;;
        -h|--help)
            echo "用法: $0 [--name NAME] [--image IMAGE] [--port PORT]"
            echo ""
            echo "参数:"
            echo "  --name NAME     容器名（默认: evoagent）"
            echo "  --image IMAGE   镜像 tag（默认: evoagent:latest）"
            echo "  --port PORT     对外映射端口（默认: 8000）"
            exit 0
            ;;
        *)  error "未知参数: $1（使用 --help 查看帮助）" ;;
    esac
done

# ── 前置校验 ─────────────────────────────────────────────────────────
ENV_FILE="$BUILD_DIR/config/.env"

# 检查镜像
docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || error "镜像 $IMAGE 不存在！请先运行 ./build.sh"

# 检查配置文件
if [ ! -f "$ENV_FILE" ]; then
    warn "未找到 $ENV_FILE"
    warn "从模板复制: cp config/.env.example config/.env"
    warn "然后编辑 config/.env 填入配置"
    if [ -f "$BUILD_DIR/config/.env.example" ]; then
        cp "$BUILD_DIR/config/.env.example" "$ENV_FILE"
        info "已从模板创建 $ENV_FILE，请编辑后重新运行"
    fi
    error "请先配置 config/.env"
fi

# ── 清理旧容器 ─────────────────────────────────────────────────────
if docker ps -a --format '{{.Names}}' | grep -q "^${NAME}$"; then
    warn "发现同名容器 $NAME，正在删除..."
    docker rm -f "$NAME" >/dev/null
fi

# ── 创建工作区目录 ───────────────────────────────────────────────────
mkdir -p "$BUILD_DIR/workspace"
if [ ! -d /home/evolution/data ]; then
    mkdir -p /home/evolution/data
fi

# ── 启动容器 ────────────────────────────────────────────────────────
info "启动容器 $NAME（镜像: $IMAGE, 端口: $PORT）"

docker run -d \
    --name "$NAME" \
    --init \
    --add-host "host.docker.internal:host-gateway" \
    -p "${PORT}:8000" \
    --env-file "$ENV_FILE" \
    -v "$BUILD_DIR/workspace:/app/workspace" \
    -v /home/evolution/data:/data \
    --restart unless-stopped \
    --health-cmd="python -c \"import urllib.request; urllib.request.urlopen('http://localhost:8000/openapi.json')\"" \
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
info "EvoAgent 启动完成！"
info "容器名:   $NAME"
info "镜像:     $IMAGE"
info "端口映射: ${PORT}:8000"
info "工作区:   $BUILD_DIR/workspace"
info "数据挂载: /home/evolution/data → /data"
info ""
info "API 文档:  http://localhost:${PORT}/docs"
info "健康检查:  curl http://localhost:${PORT}/openapi.json"
info ""
info "查看日志:  docker logs -f $NAME"
info "停止容器:  ./stop.sh"
info "==================================="