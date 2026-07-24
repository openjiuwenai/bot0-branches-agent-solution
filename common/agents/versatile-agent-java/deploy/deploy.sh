#!/usr/bin/env bash
# 新手一键入口（Linux）：校验配置 ->（构建 jar -> 构建镜像 ->）启动并验证 adapter。
# 默认从 git 代码仓直接构建，无需任何 Windows / PowerShell 步骤。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

SKIP_BUILD=false
case "${1-}" in
    '') ;;
    --skip-build) SKIP_BUILD=true ;;
    *) die "未知参数：$1。仅支持 --skip-build（复用已构建/已拉取的镜像，跳过 jar 与镜像构建）。" ;;
esac

# 在耗时构建前先检查 .env，避免最后才发现 Versatile 地址未填写。
load_runtime_config
export ADAPTER_ENV_FILE="${ENV_FILE}"

if [ "${SKIP_BUILD}" = false ]; then
    bash "${DEPLOY_DIR}/build-jar.sh"
    bash "${DEPLOY_DIR}/build-image.sh"
else
    info "已按 --skip-build 跳过 jar 与镜像构建，复用现有镜像。"
fi
bash "${DEPLOY_DIR}/start.sh"
