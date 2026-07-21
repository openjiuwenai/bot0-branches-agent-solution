#!/usr/bin/env bash
#
# import-bundle.sh — 导入 EvoAgentAdapter Docker 离线镜像包
#
# 使用：
#   ./import-bundle.sh agent-adapter.latest.20260615.tar
#
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }

if [ $# -lt 1 ]; then
    echo "用法: $0 <镜像tar文件>"
    echo "示例: $0 agent-adapter.latest.20260615.tar"
    exit 1
fi

TAR_FILE="$1"

[ -f "$TAR_FILE" ] || error "文件不存在: $TAR_FILE"

info "导入 Docker 镜像: $TAR_FILE"
docker load -i "$TAR_FILE"

info "导入完成！可用的镜像:"
docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}" | head -5

echo ""
info "下一步："
info "  1. cp .env.example .env 并编辑（填 HOST_LOG_DIR / HOST_SKILLS_DIR）"
info "  2. ./start.sh 启动容器"
info "  3. curl http://localhost:8900/api/v1/status 验证"
