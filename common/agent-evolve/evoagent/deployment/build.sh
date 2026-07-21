#!/usr/bin/env bash
#
# build.sh — EvoAgent Docker 镜像构建脚本
#
# 功能：
#   1. 克隆/拉取 agent-solution 仓库最新代码（可跳过）
#   2. 获取 openjiuwen wheel（源码构建 / PyPI 下载 / 本地复用）
#   3. 复制 openjiuwen wheel 到 EvoAgent vendor 目录
#   4. 基于 deployment/Dockerfile 构建 Docker 镜像
#   5. 验证镜像
#
# 使用：
#   ./build.sh                                          # 默认路径，从源码构建 wheel
#   ./build.sh --local                                  # local 模式，从 PyPI 下载 wheel
#   ./build.sh /custom/path/agent-solution              # 指定 agent-solution 路径
#   EVOAGENT_IMAGE_TAG=evoagent:v1.0.0 ./build.sh       # 自定义镜像 tag
#   ./build.sh --skip-pull                              # 跳过代码拉取，使用本地代码构建
#   ./build.sh --local --skip-pull                      # local 模式 + 跳过代码拉取
#
# 模式说明：
#   默认模式：克隆 agent-core 源码 → 构建 wheel → 复制到 vendor
#   --local：  跳过 agent-core 源码克隆和构建，直接从 PyPI 下载指定版本 wheel
#
# 环境变量：
#   EVOAGENT_IMAGE_TAG      镜像标签（默认 evoagent:latest）
#   EVOAGENT_SOLUTION_REPO   agent-solution 仓库地址（默认 https://gitcode.com/openJiuwen/agent-solution.git）
#   EVOAGENT_SOLUTION_BRANCH 仓库分支（默认 common）
#   EVOAGENT_SOLUTION_DIR    agent-solution 本地目录（默认 $HOME/EvoAgent/agent-solution）
#   EVOAGENT_SKIP_PULL      设置为 1 跳过代码拉取（同 --skip-pull）
#   EVOAGENT_CORE_REPO      agent-core 仓库地址（默认 https://gitcode.com/openJiuwen/agent-core.git）
#   EVOAGENT_CORE_BRANCH    agent-core 仓库分支（默认 main）
#   EVOAGENT_CORE_VERSION   local 模式下 openjiuwen 版本号（默认 0.1.13）
#   PIP_INDEX_URL           pip 镜像源（默认华为云）
#   PIP_TRUSTED_HOST        pip 信任主机
#
set -euo pipefail

# 设置 pip 源为华为云
export PIP_INDEX_URL="${PIP_INDEX_URL:-https://mirrors.huaweicloud.com/repository/pypi/simple}"
export PIP_TRUSTED_HOST="${PIP_TRUSTED_HOST:-mirrors.huaweicloud.com}"

# ── 颜色输出 ─────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── 参数解析 ──────────────────────────────────────────────────────────
SKIP_PULL="${EVOAGENT_SKIP_PULL:-0}"
LOCAL_MODE=0
AGENT_SOLUTION_DIR="${EVOAGENT_SOLUTION_DIR:-$HOME/EvoAgent/agent-solution}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --local)
            LOCAL_MODE=1
            shift
            ;;
        --skip-pull|--no-pull)
            SKIP_PULL=1
            shift
            ;;
        *)
            AGENT_SOLUTION_DIR="$1"
            shift
            ;;
    esac
done

# ── 配置参数 ──────────────────────────────────────────────────────────
BUILD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_TAG="${EVOAGENT_IMAGE_TAG:-evoagent:latest}"
SOLUTION_REPO="${EVOAGENT_SOLUTION_REPO:-https://gitcode.com/openJiuwen/agent-solution.git}"
SOLUTION_BRANCH="${EVOAGENT_SOLUTION_BRANCH:-common}"

# EvoAgent 在 agent-solution 中的相对路径
EVOAGENT_REL_PATH="common/agent-evolve/evoagent"

# agent-core 是独立仓库，分支不跟随 agent-solution/common。
CORE_REPO="${EVOAGENT_CORE_REPO:-https://gitcode.com/openJiuwen/agent-core.git}"
CORE_BRANCH="${EVOAGENT_CORE_BRANCH:-main}"
AGENT_CORE_DIR="$HOME/EvoAgent/agent-core"

# local 模式下 openjiuwen 版本号
CORE_VERSION="${EVOAGENT_CORE_VERSION:-0.1.13}"

# ── 前置依赖检查 ────────────────────────────────────────────────────
require_cmd() {
    command -v "$1" >/dev/null 2>&1 || error "缺少依赖命令: $1（请先安装）"
}
require_cmd git
require_cmd python3
require_cmd docker

# ── 模式提示 ──────────────────────────────────────────────────────────
if [ "$LOCAL_MODE" -eq 1 ]; then
    info "模式: local（从 PyPI 下载 openjiuwen==${CORE_VERSION}）"
else
    info "模式: 源码构建（克隆 agent-core → 构建 wheel）"
fi

# ── 1. 同步最新代码（可跳过）───────────────────────────────────────────
info "=== 步骤 1/5: 同步 agent-solution 代码 ==="

if [ "$SKIP_PULL" -eq 1 ]; then
    info "跳过代码拉取（--skip-pull 指定），使用本地代码"
    [ -d "$AGENT_SOLUTION_DIR" ] || error "agent-solution 目录不存在: $AGENT_SOLUTION_DIR"
else
    if [ -d "$AGENT_SOLUTION_DIR/.git" ]; then
        info "agent-solution 已存在，拉取最新代码（分支: $SOLUTION_BRANCH）"
        cd "$AGENT_SOLUTION_DIR"
        git fetch origin
        git checkout "$SOLUTION_BRANCH"
        git pull origin "$SOLUTION_BRANCH"
        info "当前 commit: $(git log --oneline -1)"
    else
        info "agent-solution 不存在，克隆仓库（分支: $SOLUTION_BRANCH）"
        mkdir -p "$(dirname "$AGENT_SOLUTION_DIR")"
        git clone --branch "$SOLUTION_BRANCH" "$SOLUTION_REPO" "$AGENT_SOLUTION_DIR"
        info "克隆完成，commit: $(cd "$AGENT_SOLUTION_DIR" && git log --oneline -1)"
    fi
fi

# ── 2. 获取 openjiuwen wheel ──────────────────────────────────────────
if [ "$LOCAL_MODE" -eq 1 ]; then
    info "=== 步骤 2/5: 从 PyPI 下载 openjiuwen==${CORE_VERSION} ==="

    # 创建临时下载目录
    CORE_DOWNLOAD_DIR=$(mktemp -d)
    trap 'rm -rf "$CORE_DOWNLOAD_DIR"' EXIT

    info "pip 源: $PIP_INDEX_URL"
    info "下载 openjiuwen==${CORE_VERSION} ..."

    python3 -m pip download \
        --no-deps \
        --no-cache-dir \
        -d "$CORE_DOWNLOAD_DIR" \
        "openjiuwen==${CORE_VERSION}" \
    || error "下载 openjiuwen==${CORE_VERSION} 失败，请检查版本号或 pip 源配置"

    WHEEL_FILE=$(find "$CORE_DOWNLOAD_DIR" -name "openjiuwen-*.whl" -type f -print -quit 2>/dev/null)
    if [ -z "$WHEEL_FILE" ]; then
        WHEEL_FILE=$(find "$CORE_DOWNLOAD_DIR" -name "openjiuwen-*.tar.gz" -type f -print -quit 2>/dev/null)
        if [ -z "$WHEEL_FILE" ]; then
            error "未找到下载的 openjiuwen 包文件"
        fi
        warn "下载到源码包而非 wheel: $(basename "$WHEEL_FILE")"
        warn "将尝试在本地构建 wheel ..."

        BUILD_TMP=$(mktemp -d)
        trap 'rm -rf "$CORE_DOWNLOAD_DIR" "$BUILD_TMP"' EXIT
        python3 -m pip install --no-cache-dir build
        python3 -m build --wheel --outdir "$BUILD_TMP" "$WHEEL_FILE"
        WHEEL_FILE=$(find "$BUILD_TMP" -name "openjiuwen-*.whl" -type f -print -quit 2>/dev/null)
        if [ -z "$WHEEL_FILE" ]; then
            error "从源码包构建 wheel 失败"
        fi
    fi

    info "下载成功: $(basename "$WHEEL_FILE")"
elif [ "$SKIP_PULL" -eq 1 ]; then
    info "=== 步骤 2/5: 使用本地 wheel（--skip-pull） ==="
    info "跳过 agent-core 源码拉取和构建"
else
    info "=== 步骤 2/5: 同步 agent-core 代码并构建 wheel ==="

    info "agent-core 仓库: $CORE_REPO"
    info "agent-core 路径: $AGENT_CORE_DIR"
    info "使用分支: $CORE_BRANCH"

    if [ -d "$AGENT_CORE_DIR/.git" ]; then
        info "agent-core 已存在，拉取最新代码"
        cd "$AGENT_CORE_DIR"
        git fetch origin
        git checkout "$CORE_BRANCH"
        git pull origin "$CORE_BRANCH"
        info "当前 commit: $(git log --oneline -1)"
    else
        info "agent-core 不存在，克隆仓库"
        mkdir -p "$(dirname "$AGENT_CORE_DIR")"
        git clone --branch "$CORE_BRANCH" "$CORE_REPO" "$AGENT_CORE_DIR"
        info "克隆完成，commit: $(cd "$AGENT_CORE_DIR" && git log --oneline -1)"
    fi

    info "构建 openjiuwen wheel..."
    cd "$AGENT_CORE_DIR"
    python3 -m pip install --no-cache-dir build
    python3 -m build --wheel --outdir dist/

    info "查找生成的 wheel 文件..."
    WHEEL_FILE=$(find "$AGENT_CORE_DIR/dist" -name "openjiuwen-*.whl" -type f -print -quit 2>/dev/null)

    if [ -z "$WHEEL_FILE" ]; then
        error "wheel 构建失败，未找到生成的 wheel 文件"
    fi

    info "wheel 构建成功: $(basename "$WHEEL_FILE")"
    info "wheel 完整路径: $WHEEL_FILE"
fi

# ── 3. 复制 wheel 到 EvoAgent vendor 目录 ────────────────────────────
info "=== 步骤 3/5: 复制 wheel 到 EvoAgent vendor 目录 ==="

EVOAGENT_DIR="$AGENT_SOLUTION_DIR/$EVOAGENT_REL_PATH"
[ -d "$EVOAGENT_DIR" ] || error "EvoAgent 目录不存在: $EVOAGENT_DIR"
[ -f "$EVOAGENT_DIR/pyproject.toml" ] || error "pyproject.toml 不存在"
[ -f "$EVOAGENT_DIR/deployment/Dockerfile" ] || error "Dockerfile 不存在"

# 创建 vendor 目录
mkdir -p "$EVOAGENT_DIR/vendor"

if [ "$SKIP_PULL" -eq 1 ] && [ "$LOCAL_MODE" -eq 0 ]; then
    WHEEL_FILE=$(find "$EVOAGENT_DIR/vendor" -name "openjiuwen-*.whl" -type f -print -quit 2>/dev/null)
    if [ -z "$WHEEL_FILE" ]; then
        error "未找到 vendor wheel 文件，请先构建 agent-core 或使用 --local 模式"
    fi
else
    info "清空 vendor 目录..."
    rm -rf "$EVOAGENT_DIR/vendor"/*

    info "复制 wheel: $WHEEL_FILE -> $EVOAGENT_DIR/vendor/"
    cp "$WHEEL_FILE" "$EVOAGENT_DIR/vendor/"
    info "wheel 复制成功"
fi

info "EvoAgent 目录: $EVOAGENT_DIR"
info "pyproject.toml: 存在"
info "vendor/openjiuwen wheel: $(basename "$WHEEL_FILE")"

# ── 4. 构建 Docker 镜像 ─────────────────────────────────────────────
info "=== 步骤 4/5: 构建 Docker 镜像 ==="

info "构建上下文: $EVOAGENT_DIR"
info "镜像标签: $IMAGE_TAG"

docker build \
    -t "$IMAGE_TAG" \
    -f "$EVOAGENT_DIR/deployment/Dockerfile" \
    "$EVOAGENT_DIR"

# ── 5. 验证 ──────────────────────────────────────────────────────────
info "=== 步骤 5/5: 验证 ==="
info "镜像构建完成:"
docker images "$IMAGE_TAG" --format "   {{.Repository}}:{{.Tag}}   {{.Size}}   {{.CreatedSince}}"

echo ""
info "==================================="
info "构建成功！"
info "镜像: $IMAGE_TAG"
if [ "$LOCAL_MODE" -eq 1 ]; then
info "模式: local（openjiuwen==${CORE_VERSION}）"
else
info "模式: 源码构建"
fi
info ""
info "启动容器:"
info "  ./run.sh"
info "或指定版本:"
info "  ./run.sh --image $IMAGE_TAG"
info "==================================="
