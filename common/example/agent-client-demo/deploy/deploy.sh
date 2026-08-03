#!/usr/bin/env bash
# 新手入口（Linux）：按"构建 jar -> 构建镜像 -> 启动 -> 验证"执行。
# 默认从 git 代码仓直接构建，无需任何 Windows / PowerShell 步骤。
#
# 用法：
#   bash deploy/deploy.sh              # 完整流程：构建 jar + 构建镜像 + 启动 + 验证
#   bash deploy/deploy.sh --skip-build # 跳过 jar 与镜像构建（镜像已拉取或已构建时使用）
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

SKIP_BUILD=false
case "${1-}" in
    '') ;;
    --skip-build) SKIP_BUILD=true ;;
    *) die "未知参数：$1。仅支持 --skip-build（使用已拉取/已构建的镜像，跳过 jar 与镜像构建）。" ;;
esac

check_docker
ensure_env_file

if [ "${SKIP_BUILD}" = false ]; then
    bash "${SCRIPT_DIR}/build-jar.sh"
    ACD_ENV_FILE="${ENV_FILE}" bash "${SCRIPT_DIR}/build-image.sh"
else
    log "已按参数跳过 jar 与镜像构建。"
fi

ACD_ENV_FILE="${ENV_FILE}" bash "${SCRIPT_DIR}/start.sh"
ACD_ENV_FILE="${ENV_FILE}" bash "${SCRIPT_DIR}/verify.sh"

log "独立部署流程完成。"
