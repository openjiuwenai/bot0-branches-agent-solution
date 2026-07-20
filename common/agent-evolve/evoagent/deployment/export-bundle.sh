#!/usr/bin/env bash
#
# export-bundle.sh — 导出 EvoAgent Docker 镜像为离线 zip 包
#
# 使用：
#   ./export-bundle.sh                                   # 默认导出 evoagent:latest
#   ./export-bundle.sh evoagent:v1.0.0                   # 指定镜像 tag
#   ./export-bundle.sh evoagent:latest -o ./my-bundle     # 指定输出目录
#
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }

BUILD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="${1:-evoagent:latest}"
OUTPUT_DIR="${3:-$BUILD_DIR/bundle}"

# 解析可选参数
if [ "${2:-}" = "-o" ] && [ -n "${3:-}" ]; then
    OUTPUT_DIR="$3"
elif [ "${2:-}" = "-o" ]; then
    # -o 后面没有跟路径的情况
    OUTPUT_DIR="$BUILD_DIR/bundle"
fi

# 校验镜像存在
docker image inspect "$IMAGE" >/dev/null 2>&1 || {
    echo "❌ 镜像 $IMAGE 不存在，请先运行 ./build.sh"
    exit 1
}

# 镜像 tag 中的 / 替换为 -（用于文件名）
SAFE_TAG=$(echo "$IMAGE" | tr '/' '-')
DATE_TAG=$(date +%Y%m%d)
IMAGE_TAR="evoagent.${SAFE_TAG}.${DATE_TAG}.tar"
ARCHIVE_NAME="evoagent-offline-${DATE_TAG}"

# 创建打包根（带顶层目录 ARCHIVE_NAME/，unzip 后可直接 cd ${ARCHIVE_NAME}）
STAGE_DIR="$OUTPUT_DIR/${ARCHIVE_NAME}"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

# docker save
info "导出镜像 $IMAGE → $STAGE_DIR/$IMAGE_TAR"
docker save -o "$STAGE_DIR/$IMAGE_TAR" "$IMAGE"

# 复制部署脚本和配置
info "打包部署脚本和配置..."
cp "$BUILD_DIR/import-bundle.sh" "$STAGE_DIR/"
cp "$BUILD_DIR/run.sh" "$STAGE_DIR/"
cp "$BUILD_DIR/stop.sh" "$STAGE_DIR/"
cp -r "$BUILD_DIR/config" "$STAGE_DIR/"
cp "$BUILD_DIR/../README.md" "$STAGE_DIR/" 2>/dev/null || true

# 创建 README 打包说明
cat > "$STAGE_DIR/README.md" << 'PACKAGE_README'
# EvoAgent 离线部署包

## 部署步骤

```bash
# 1. 解压
unzip evoagent-offline-xxxxxx.zip
cd evoagent-offline-xxxxxx

# 2. 导入镜像
./import-bundle.sh evoagent.xxx.xxxxxx.tar

# 3. 配置环境变量
vim config/.env

# 4. 启动
./run.sh

# 5. 验证
curl http://localhost:8000/openapi.json

# 6. 停止
./stop.sh
```
PACKAGE_README

# 打包（zip，带顶层目录 ARCHIVE_NAME/，用 python 标准库 zipfile 免装 zip 命令）
cd "$OUTPUT_DIR"
python3 -m zipfile -c "../${ARCHIVE_NAME}.zip" "${ARCHIVE_NAME}"

# 清理临时打包根
rm -rf "$STAGE_DIR"

info "============================================"
info "离线包已生成："
info "  $OUTPUT_DIR/../${ARCHIVE_NAME}.zip"
info ""
info "传输到离线服务器后执行："
info "  unzip ${ARCHIVE_NAME}.zip"
info "  cd ${ARCHIVE_NAME}"
info "  ./import-bundle.sh <镜像tar文件>"
info "  # 编辑 config/.env"
info "  ./run.sh"
info "============================================"
