#!/usr/bin/env bash
#
# export-bundle.sh — 导出 EvoAgentAdapter Docker 镜像为离线 tar.gz 包
#
# 适配跨机器独立部署: adapter 与 EvoAgent 可分别打包含独立部署到不同主机,
# EvoAgent 侧通过 EVO_ADAPTER_URL 指向 adapter 机器 IP:8900 访问。
#
# 使用：
#   ./export-bundle.sh                                       # 默认导出 agent-adapter:latest
#   ./export-bundle.sh agent-adapter:v1.0.0                   # 指定镜像 tag
#   ./export-bundle.sh agent-adapter:latest -o ./my-bundle     # 指定输出目录
#
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

BUILD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="${1:-agent-adapter:latest}"
OUTPUT_DIR="${3:-$BUILD_DIR/bundle}"

# 解析可选参数
if [ "${2:-}" = "-o" ] && [ -n "${3:-}" ]; then
    OUTPUT_DIR="$3"
elif [ "${2:-}" = "-o" ]; then
    OUTPUT_DIR="$BUILD_DIR/bundle"
fi

# 校验镜像存在
docker image inspect "$IMAGE" >/dev/null 2>&1 || {
    echo "❌ 镜像 $IMAGE 不存在，请先运行: docker build -t $IMAGE ."
    exit 1
}

# 镜像 tag 中的 / 替换为 -（用于文件名）
SAFE_TAG=$(echo "$IMAGE" | tr '/' '-')
DATE_TAG=$(date +%Y%m%d)
IMAGE_TAR="agent-adapter.${SAFE_TAG}.${DATE_TAG}.tar"
ARCHIVE_NAME="agent-adapter-offline-${DATE_TAG}"

# 创建打包根（带顶层目录 ARCHIVE_NAME/，unzip 后可直接 cd ${ARCHIVE_NAME}）
STAGE_DIR="$OUTPUT_DIR/${ARCHIVE_NAME}"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/config"

# docker save
info "导出镜像 $IMAGE → $STAGE_DIR/$IMAGE_TAR"
docker save -o "$STAGE_DIR/$IMAGE_TAR" "$IMAGE"

# 复制部署脚本和配置
info "打包部署脚本和配置..."
cp "$BUILD_DIR/import-bundle.sh" "$STAGE_DIR/"
cp "$BUILD_DIR/start.sh" "$STAGE_DIR/"
cp "$BUILD_DIR/stop.sh" "$STAGE_DIR/"
cp "$BUILD_DIR/config/.env.example" "$STAGE_DIR/config/"
cp "$BUILD_DIR/config/agent_adapter_config.yaml" "$STAGE_DIR/config/"
cp "$BUILD_DIR/operations-guide.md" "$STAGE_DIR/" 2>/dev/null || true

# 创建 README 打包说明
cat > "$STAGE_DIR/README.md" << 'PACKAGE_README'
# EvoAgentAdapter 离线部署包

EvoAgentAdapter 是 EvoAgent 的 sidecar（日志采集 + skill 同步），独立部署，
可与 EvoAgent 部署在不同机器。EvoAgent 侧 `.env` 的 `EVO_ADAPTER_URL` 填本机 IP:8900。

## 部署步骤

```bash
# 1. 解压
unzip agent-adapter-offline-xxxxxx.zip
cd agent-adapter-offline-xxxxxx

# 2. 导入镜像
./import-bundle.sh agent-adapter.xxx.xxxxxx.tar

# 3. 配置环境变量（填写 *_ROOT，使其指向业务 Agent 的共享父目录）
cp config/.env.example config/.env
vim config/.env

# 4. 启动
./start.sh

# 5. 验证
curl http://localhost:8900/api/v1/status

# 6. 停止
./stop.sh
```

## AgentRule managed-doc（按需启用）

1. 在 `config/.env` 设置真实 `HOST_AGENTS_ROOT`，并设置
   `ADAPTER_ENABLE_DOCKER_RESTART=true`。
2. 首次 `./start.sh` 会 seed `HOST_CONFIG_FILE`；编辑该宿主文件，取消模板中
   `managed_docs` / `managed_doc_defaults` 注释并填写真实 AgentRule 路径、业务 Agent
   容器名、`agent_url`、`health_url`。
3. 再次执行 `./start.sh`，然后确认：

```bash
docker exec agent-adapter test -r /data/agents/edp_agent/AgentRule.md
docker exec agent-adapter test -w /data/agents/edp_agent/AgentRule.md
docker exec agent-adapter docker inspect edp_agent --format '{{.State.Status}}'
docker exec agent-adapter curl -fsS http://host.docker.internal:8090/health
```

全新 AgentRule 还需调用一次 `POST /api/v1/managed-docs`（`action=update`，内容为当前
文件全文）建立 applied revision，并轮询任务到 `SUCCEEDED`。随后复查
`pending_apply=false` 且 `file_revision == applied_revision`，再启动 EvoAgent 优化。

## 前置条件

- Docker 20.10+
- 业务 Agent（EDPAgent）已部署，其日志、skills 与 AgentRule 等 managed-doc 目录在本机可访问
- 使用 managed-doc Docker restart 时，宿主 Docker socket 可用且已了解其权限风险
- `HOST_LOG_ROOT` 只读挂载；`HOST_SKILLS_ROOT`、`HOST_AGENTS_ROOT` 读写挂载
- 使用 `docker restart` 时设置 `ADAPTER_ENABLE_DOCKER_RESTART=true`，并确认
  `HOST_DOCKER_SOCKET` 指向实际 Docker socket

## 与 EvoAgent 联动

在 EvoAgent 机器的 `.env` 中设置：
```
EVO_ADAPTER_URL=http://<本机IP>:8900
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
info "传输到目标服务器后执行："
info "  unzip ${ARCHIVE_NAME}.zip"
info "  cd ${ARCHIVE_NAME}"
info "  ./import-bundle.sh <镜像tar文件>"
info "  # 编辑 config/.env (HOST_LOG_ROOT / HOST_SKILLS_ROOT / HOST_AGENTS_ROOT)"
info "  ./start.sh"
info ""
info "EvoAgent 侧 .env 设 EVO_ADAPTER_URL=http://<本机IP>:8900"
info "============================================"
