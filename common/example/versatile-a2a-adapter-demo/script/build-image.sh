#!/usr/bin/env bash
#
# build-image.sh — 构建 versatile-a2a-adapter-demo 的 Docker 镜像。
#
# 基础镜像: eclipse-temurin:17-jdk-alpine
#
# 用法:
#   ./build-image.sh                          # 默认 tag: versatile-a2a-adapter-demo:latest
#   ./build-image.sh --tag demo:v1
#   ./build-image.sh --jar /path/to/versatile-a2a-adapter-demo-0.1.0.jar
#   ./build-image.sh --gzip                   # 导出压缩镜像包 (.tar.gz)
#   ./build-image.sh --out /tmp               # 指定镜像包输出目录
#
# 说明:
#   - 若 target/ 下没有 jar（或 --jar 指定路径不存在），脚本会先在宿主机
#     用 mvn 构建 demo jar，再 COPY 进镜像（镜像内不跑 maven，避免 vendor
#     依赖与网络带来的不确定性）。
#   - 构建需要网络（拉取 eclipse-temurin:17-jdk-alpine 基础镜像 + apk 装 bash/curl）。
#   - 构建完成后自动 docker save 导出镜像包：
#       target/versatile-a2a-adapter-demo-<版本>.tar
#     复制到生产环境后 docker load -i 导入即可离线部署。
#
# 构建后运行:
#   # 方式一 (Linux 推荐): 容器用宿主机网络, 容器内 127.0.0.1 即宿主机 127.0.0.1,
#   #   远端 Versatile 若跑在宿主机 127.0.0.1:31113 也能直接访问
#   docker run -d --name versatile-demo --network host versatile-a2a-adapter-demo:latest
#   docker exec -it versatile-demo /bin/sh                 # 进入容器（容器不自动启动服务）
#     进入后: /app/script/start.sh                         # 调试本 demo（:18080 对接远端 Versatile）
#             /app/script/send-requests.sh                 # 调测发请求
#             /app/script/start-handoff.sh                 # 调试 controller-handoff demo
#                                                         #   （L1 :18091 / L2 :18092，自包含 mock）
#             SKIP_BUILD=1 /app/controller-handoff-demo/scripts/local-e2e.sh   # 十场景验收
#             /app/script/start.sh --stop                  # 停止服务
#             /app/script/start-handoff.sh --stop
#   docker logs -f versatile-demo                          # 看服务端日志
#
#   # 方式二 (Docker Desktop / 端口映射): 远端 Versatile 地址需指向宿主机入口
#   docker run -d --name versatile-demo -p 18080:18080 -p 18091:18091 -p 18092:18092 \
#     -e VERSATILE_URL=http://host.docker.internal:31113/v1/0/agents/main_planner/conversations/{conversation_id} \
#     versatile-a2a-adapter-demo:latest
#
#   # 或直接交互式:
#   docker run -it --entrypoint /bin/sh versatile-a2a-adapter-demo:latest
#   进入后: /app/script/start.sh && /app/script/send-requests.sh
#
# 容器内脚本与仓库内用法一致:
#   /app/script/send-requests.sh --round 2 --stream
#   /app/script/send-requests.sh --round 3 --non-stream
#   /app/script/send-requests.sh --custom
#   /app/script/send-requests.sh --file /app/a2a-requests/my-request.json

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"

TAG="versatile-a2a-adapter-demo:latest"
JAR="${JAR:-}"
OUT_DIR="$DEMO_DIR/target"
GZIP="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) TAG="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --gzip) GZIP="true"; shift ;;
    -h|--help) sed -n '1,55p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# ---------- 1. 确保 jar 存在 ----------
if [[ -z "$JAR" ]]; then
  JAR="$(ls "$DEMO_DIR"/target/versatile-a2a-adapter-demo-*.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "jar not found, building it on host with mvn ..."
  ( cd "$REPO_ROOT" && mvn -q -f common/example/versatile-a2a-adapter-demo/pom.xml clean package -DskipTests )
  JAR="$(ls "$DEMO_DIR"/target/versatile-a2a-adapter-demo-*.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "ERROR: failed to produce jar under $DEMO_DIR/target/" >&2
  exit 1
fi
echo "Using jar : $JAR"

# ---------- 1b. 同镜像附带 versatile-controller-handoff-demo ----------
# 镜像同时可调试两个 demo：本 demo（对接远端 Versatile）+ controller-handoff demo
# （自包含 mock 控制器，L1/L2 双 runtime）。构建上下文是本 demo 目录，兄弟模块的
# 产物无法直接 COPY，先 stage 到 target/image-extra/ 再进镜像（.dockerignore 已放行）。
HANDOFF_DEMO_DIR="$REPO_ROOT/common/example/versatile-controller-handoff-demo"
STAGE_EXTRA="$DEMO_DIR/target/image-extra"
if [[ -d "$HANDOFF_DEMO_DIR" ]]; then
  HANDOFF_JAR="${HANDOFF_JAR:-}"
  if [[ -z "$HANDOFF_JAR" ]]; then
    HANDOFF_JAR="$(ls "$HANDOFF_DEMO_DIR"/target/versatile-controller-handoff-demo-*.jar 2>/dev/null | head -1 || true)"
  fi
  if [[ -z "$HANDOFF_JAR" || ! -f "$HANDOFF_JAR" ]]; then
    echo "controller-handoff demo jar not found, building it on host with mvn ..."
    # demo 依赖的 adapter 需先安装到本地仓库（见 handoff demo README）
    ( cd "$REPO_ROOT/common/agent-runtime-ext-java" \
      && mvn -q -pl agent-service-adapters/agent-service-adapters-versatile-controller-handoff install -DskipTests )
    ( cd "$REPO_ROOT" && mvn -q -f common/example/versatile-controller-handoff-demo/pom.xml clean package -DskipTests )
    HANDOFF_JAR="$(ls "$HANDOFF_DEMO_DIR"/target/versatile-controller-handoff-demo-*.jar 2>/dev/null | head -1 || true)"
  fi
  if [[ -z "$HANDOFF_JAR" || ! -f "$HANDOFF_JAR" ]]; then
    echo "ERROR: failed to produce controller-handoff demo jar under $HANDOFF_DEMO_DIR/target/" >&2
    exit 1
  fi
  echo "Bundling: $HANDOFF_JAR"
  rm -rf "$STAGE_EXTRA/controller-handoff-demo"
  mkdir -p "$STAGE_EXTRA/controller-handoff-demo/target" "$STAGE_EXTRA/controller-handoff-demo/scripts"
  cp "$HANDOFF_JAR" "$STAGE_EXTRA/controller-handoff-demo/target/"
  cp "$HANDOFF_DEMO_DIR/scripts/local-e2e.sh" "$STAGE_EXTRA/controller-handoff-demo/scripts/"
else
  echo "WARN: $HANDOFF_DEMO_DIR not found, image will NOT include the controller-handoff demo" >&2
fi

# ---------- 2. docker build ----------
echo "Building image: $TAG"
docker build -f "$DEMO_DIR/Dockerfile" -t "$TAG" "$DEMO_DIR"

# ---------- 3. 导出镜像包 ----------
# 文件名与 jar 同名，便于与部署 tar.gz 对应: versatile-a2a-adapter-demo-<版本>.tar(.gz)
PKG_BASE="$(basename "$JAR" .jar)"
mkdir -p "$OUT_DIR"
if [[ "$GZIP" == "true" ]]; then
  OUT_IMG="$OUT_DIR/$PKG_BASE.tar.gz"
  echo "Exporting image (gzip) -> $OUT_IMG"
  docker save "$TAG" | gzip > "$OUT_IMG"
else
  OUT_IMG="$OUT_DIR/$PKG_BASE.tar"
  echo "Exporting image -> $OUT_IMG"
  docker save -o "$OUT_IMG" "$TAG"
fi
chmod 644 "$OUT_IMG"   # docker save 默认 600，放宽便于 scp 到生产机
echo "Image tar size: $(du -h "$OUT_IMG" | cut -f1)"

echo
echo "Build OK : $TAG"
echo "Image tar: $OUT_IMG"
echo
echo "本地运行 (调测容器, 不自动启动服务):"
echo "  docker run -d --name versatile-demo --network host $TAG"
echo "  docker exec -it versatile-demo /bin/sh"
echo "    进入后: /app/script/start.sh  &&  /app/script/send-requests.sh   # a2a-adapter demo"
echo "            /app/script/start-handoff.sh                             # controller-handoff demo"
echo "            SKIP_BUILD=1 /app/controller-handoff-demo/scripts/local-e2e.sh"
echo "  docker logs -f versatile-demo"
echo
echo "复制到生产环境离线部署:"
echo "  scp $OUT_IMG <host>:<dir>/"
echo "  生产机: docker load -i $(basename "$OUT_IMG") && docker run -d --name versatile-demo --network host $TAG"