#!/usr/bin/env bash
# 在 64 位 Linux（amd64/arm64）上直接从 git 代码仓构建 adapter 运行 jar。
# 等价于 pack-for-linux.ps1 的构建部分，但不打 tar：在 Linux 上直接就地构建，
# 随后用 build-image.sh 以本项目为上下文构建镜像即可，无需任何 Windows 步骤。
#
# 步骤：
#   1) mvn install 共享依赖 agent-runtime-ext-java（含 versatile 适配器）；
#   2) mvn package 本 adapter，生成 target/adapter-versatile-agent-java-*.jar。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

RUNTIME_EXT_POM="$(cd "${PROJECT_DIR}/../.." && pwd)/agent-runtime-ext-java/pom.xml"
ADAPTER_POM="${PROJECT_DIR}/pom.xml"

command -v mvn >/dev/null 2>&1 || die "未找到 mvn；请安装 Maven 3.8+ 并加入 PATH。"
command -v java >/dev/null 2>&1 || die "未找到 java；请安装 JDK 17 并加入 PATH。"
[ -f "${RUNTIME_EXT_POM}" ] || die "未找到共享依赖 pom：${RUNTIME_EXT_POM}"
[ -f "${ADAPTER_POM}" ] || die "未找到 adapter pom：${ADAPTER_POM}"

info "[1/2] 安装共享依赖 agent-runtime-ext-java（install 到本机 ~/.m2）..."
mvn -f "${RUNTIME_EXT_POM}" clean install -DskipTests

info "[2/2] 构建 adapter 运行 jar..."
mvn -f "${ADAPTER_POM}" clean package -DskipTests

JAR_PATH="$(require_runtime_jar)"
info "构建完成：${JAR_PATH}"
info "下一步：bash ${DEPLOY_DIR}/build-image.sh 构建镜像。"
