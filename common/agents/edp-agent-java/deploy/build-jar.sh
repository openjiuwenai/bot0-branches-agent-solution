#!/usr/bin/env bash
# 在 Linux（x86）上直接从 git 代码仓构建 EDP Agent 运行 jar。
# 等价于 pack-for-linux.ps1 的构建部分，但不打 tar：在 Linux 上就地构建，
# 随后用 build-image.sh 以 edp-agent-java 为上下文构建镜像，无需任何 Windows 步骤。
#
# 步骤：
#   1) mvn install 共享依赖 agent-runtime-ext-java；
#   2) mvn package edp-agent-java，生成 engine/target/edp-agent-engine-*.jar。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

RUNTIME_EXT_POM="$(cd -- "${SERVICE_DIR}/../.." && pwd)/agent-runtime-ext-java/pom.xml"
EDP_POM="${SERVICE_DIR}/pom.xml"

command -v mvn >/dev/null 2>&1 || die "未找到 mvn；请安装 Maven 3.8+ 并加入 PATH。"
command -v java >/dev/null 2>&1 || die "未找到 java；请安装 JDK 17 并加入 PATH。"
[ -f "${RUNTIME_EXT_POM}" ] || die "未找到共享依赖 pom：${RUNTIME_EXT_POM}"
[ -f "${EDP_POM}" ] || die "未找到 edp-agent-java pom：${EDP_POM}"

log "[1/2] 安装共享依赖 agent-runtime-ext-java（install 到本机 ~/.m2）..."
mvn -f "${RUNTIME_EXT_POM}" clean install -DskipTests

log "[2/2] 构建 edp-agent-java 运行 jar..."
mvn -f "${EDP_POM}" clean package -DskipTests

shopt -s nullglob
CANDIDATES=("${SERVICE_DIR}"/engine/target/edp-agent-engine-*.jar)
shopt -u nullglob
JARS=()
for jar in "${CANDIDATES[@]}"; do
    case "${jar}" in
        *-sources.jar|*-javadoc.jar|*-tests.jar|*-plain.jar) ;;
        *) JARS+=("${jar}") ;;
    esac
done
[ "${#JARS[@]}" -ge 1 ] || die "构建后仍未找到 engine/target/edp-agent-engine-*.jar，请检查 Maven 输出。"
log "构建完成：${JARS[0]}"
log "下一步：bash ${SCRIPT_DIR}/build-image.sh 构建镜像。"
