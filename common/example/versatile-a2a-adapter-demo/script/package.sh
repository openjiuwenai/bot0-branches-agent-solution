#!/usr/bin/env bash
#
# package.sh — 打生产部署包（tar.gz），复制到生产/测试环境解包即可部署和调测。
#
# 用法:
#   ./package.sh                             # 输出 target/versatile-a2a-adapter-demo-<版本>.tar.gz
#   ./package.sh --jar /path/to/versatile-a2a-adapter-demo-0.1.0.jar
#   ./package.sh --out /tmp
#
# 包内容（解包后即扁平部署布局，script/ 下脚本无需改动即可运行）:
#   versatile-a2a-adapter-demo-<版本>/
#   ├── versatile-a2a-adapter-demo-<版本>.jar   Spring Boot 可执行 jar
#   ├── config/application.yml                  外部配置，覆盖 jar 内置配置（生产按环境改）
#   ├── script/start.sh                         启动/停止服务
#   ├── script/send-requests.sh                 调测：curl 发三轮请求
#   ├── a2a-requests/*.json                     三轮请求体 JSON（send-requests.sh 读取）
#   └── DEPLOY.md                               部署说明
#
# 部署:
#   tar -xzf target/versatile-a2a-adapter-demo-0.1.0.tar.gz
#   cd versatile-a2a-adapter-demo-0.1.0
#   vi config/application.yml          # 改 VERSATILE_URL / 端口等
#   ./script/start.sh && ./script/send-requests.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd)"

OUT_DIR="$DEMO_DIR/target"
JAR="${JAR:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '1,40p' "$0"; exit 0 ;;
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

# jar 文件名即包名: versatile-a2a-adapter-demo-0.1.0
PKG_NAME="$(basename "$JAR" .jar)"
OUT_TGZ="$OUT_DIR/$PKG_NAME.tar.gz"
mkdir -p "$OUT_DIR"

# ---------- 2. 组装临时目录 ----------
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PKG_DIR="$STAGE/$PKG_NAME"
mkdir -p "$PKG_DIR/config" "$PKG_DIR/script" "$PKG_DIR/a2a-requests"

cp "$JAR" "$PKG_DIR/"
cp "$DEMO_DIR/src/main/resources/application.yml" "$PKG_DIR/config/application.yml"
cp "$SCRIPT_DIR/start.sh" "$SCRIPT_DIR/send-requests.sh" "$PKG_DIR/script/"
cp "$DEMO_DIR/src/main/resources/a2a-requests/"*.json "$PKG_DIR/a2a-requests/"
cp "$SCRIPT_DIR/DEPLOY.md" "$PKG_DIR/DEPLOY.md"
chmod +x "$PKG_DIR/script/"*.sh

# ---------- 3. 打包 ----------
if [[ -f "$OUT_TGZ" ]]; then
  rm -f "$OUT_TGZ"
fi
tar -C "$STAGE" -czf "$OUT_TGZ" "$PKG_NAME"

echo
echo "Package OK: $OUT_TGZ"
echo
echo "Deploy:"
echo "  tar -xzf $OUT_TGZ && cd $PKG_NAME"
echo "  vi config/application.yml"
echo "  ./script/start.sh && ./script/send-requests.sh"