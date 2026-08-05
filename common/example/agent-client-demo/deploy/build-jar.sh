#!/usr/bin/env bash
# 在 64 位 Linux（amd64/arm64）上直接从 git 代码仓构建 agent-client-demo 的运行 jar。
# 本工程是多模块 reactor：父 pom 在 common/example/agent-client-demo/pom.xml，
# SDK 在 common/agent-client/agent-client-sdk-for-jvm/ 下（通过相对路径纳入 reactor）。
#
# 产物（瘦 jar，不打 fat-jar，避免联网拉取 assembly/shade 插件依赖）：
#   common/agent-client/agent-client-sdk-for-jvm/target/agent-client-sdk-for-jvm.jar
#   common/example/agent-client-demo/mock-gateway/target/mock-gateway.jar
#   common/example/agent-client-demo/verification-app/target/verification-app.jar
#
# 步骤等价于：
#   mvn -f common/example/agent-client-demo/pom.xml clean package -DskipTests
# 一条命令即可，因为父 pom 已通过 <module>../../agent-client/agent-client-sdk-for-jvm</module>
# 把 SDK 纳入同一 reactor。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

DEMO_POM="${SERVICE_DIR}/pom.xml"

command -v mvn >/dev/null 2>&1 || die "未找到 mvn；请安装 Maven 3.9+ 并加入 PATH。"
command -v java >/dev/null 2>&1 || die "未找到 java；请安装 JDK 17 并加入 PATH。"
[ -f "${DEMO_POM}" ] || die "未找到 agent-client-demo 父 pom：${DEMO_POM}"
# SDK 模块通过相对路径纳入 reactor，确认它存在。
[ -f "${COMMON_DIR}/agent-client/agent-client-sdk-for-jvm/pom.xml" ] \
    || die "未找到 SDK 模块 pom：${COMMON_DIR}/agent-client/agent-client-sdk-for-jvm/pom.xml"

log "构建 agent-client-demo 多模块 reactor（SDK + mock-gateway + verification-app）..."
mvn -f "${DEMO_POM}" clean package -DskipTests

SDK_JAR="${COMMON_DIR}/agent-client/agent-client-sdk-for-jvm/target/agent-client-sdk-for-jvm.jar"
MOCK_JAR="${SERVICE_DIR}/mock-gateway/target/mock-gateway.jar"
VERIFY_JAR="${SERVICE_DIR}/verification-app/target/verification-app.jar"
[ -f "${SDK_JAR}" ]   || die "构建后仍未找到 ${SDK_JAR}，请检查 Maven 输出。"
[ -f "${MOCK_JAR}" ]  || die "构建后仍未找到 ${MOCK_JAR}，请检查 Maven 输出。"
[ -f "${VERIFY_JAR}" ] || die "构建后仍未找到 ${VERIFY_JAR}，请检查 Maven 输出。"

log "构建完成。"
log "  SDK  jar: ${SDK_JAR}"
log "  Mock jar: ${MOCK_JAR}"
log "  Verify jar: ${VERIFY_JAR}"
log "下一步：bash ${SCRIPT_DIR}/build-image.sh 构建镜像。"
