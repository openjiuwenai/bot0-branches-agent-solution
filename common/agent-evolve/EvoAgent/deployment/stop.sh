#!/usr/bin/env bash
#
# stop.sh — 停止并删除 EvoAgent Docker 容器
#
# 使用：
#   ./stop.sh                         # 停止默认容器（evoagent）
#   ./stop.sh my-evoagent             # 停止指定容器
#   ./stop.sh --all                   # 停止所有 evoagent* 容器
#
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

NAME="${1:-evoagent}"

if [ "$NAME" = "--all" ]; then
    echo ""
    info "停止所有 evoagent 容器..."
    RUNNING=$(docker ps -a --filter "name=evoagent" --format '{{.Names}}')
    if [ -z "$RUNNING" ]; then
        info "没有找到 evoagent 容器"
        exit 0
    fi
    for container in $RUNNING; do
        info "停止并删除容器: $container"
        docker stop "$container" 2>/dev/null || true
        docker rm "$container" 2>/dev/null || true
    done
    info "全部已停止"
    exit 0
fi

if docker ps -a --format '{{.Names}}' | grep -q "^${NAME}$"; then
    info "停止并删除容器: $NAME"
    docker stop "$NAME" 2>/dev/null || warn "停止失败（可能已停止）"
    docker rm "$NAME" 2>/dev/null || warn "删除失败"
    info "容器 $NAME 已删除"
else
    warn "容器 $NAME 不存在"
fi