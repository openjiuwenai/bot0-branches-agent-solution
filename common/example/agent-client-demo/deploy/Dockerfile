# agent-client-demo 运行时镜像
#
# 构建前须先在构建机（Linux/Windows 均可）完成 Maven 打包，推荐在 Linux 直接：
#   bash deploy/build-jar.sh
# 或手工执行（父 pom 已把 SDK 纳入同一 reactor）：
#   mvn -f common/example/agent-client-demo/pom.xml clean package -DskipTests
#
# 构建命令（构建上下文必须是 common/ 目录，因为 SDK 与 demo 分属两个子目录，需同时进入）：
#   bash deploy/build-image.sh
# 等价于：
#   docker build -t agent-client-demo:0.1.0 -f deploy/Dockerfile ../../..
#            （../../.. 从 deploy/ 回到 common/）
#
# 镜像组合策略（与 edp-agent-java 保持一致，规避国内 ECS 构建常见的 apt-get GPG/慢速失败）：
#   - 构建阶段用 maven:3.9-eclipse-temurin-17（已内置 Maven + JDK17，离线构建友好）。
#   - 运行阶段用 eclipse-temurin:17-jre（纯 JRE，体积小；本工程零 Web 框架，仅 Jackson 运行时依赖）。
#   - 全程不使用 apt-get，避免国内镜像源 GPG/慢速失败。
#
# 运行模式（由 ACD_RUN_MODE 环境变量在 docker run 时选择，Dockerfile 只负责准备可运行镜像）：
#   EXTERNAL（默认）：AGENT_GATEWAY_URL=... java -cp ... CloudClientVerification —— 连外部 gateway 跑完退出
#   UI              ：java -cp ... CloudClientVerification --ui       —— 常驻看板（绑定 127.0.0.1，需 --network host）
#
# 注意：所有模式都必须通过 AGENT_GATEWAY_URL 指向一个真实可达的外部 gateway，verification-app 不内嵌任何 mock。

# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# 把整个多模块 reactor 拷进容器，保持与本地一致的相对结构：
#   workspace/example/agent-client-demo/...        (父 pom + mock-gateway + verification-app)
#   workspace/agent-client/agent-client-sdk-for-jvm/...   (SDK 本体)
COPY example/agent-client-demo/pom.xml ./example/agent-client-demo/pom.xml
COPY example/agent-client-demo/mock-gateway ./example/agent-client-demo/mock-gateway
COPY example/agent-client-demo/verification-app ./example/agent-client-demo/verification-app
COPY example/agent-client-demo/deploy ./example/agent-client-demo/deploy
COPY agent-client/agent-client-sdk-for-jvm ./agent-client/agent-client-sdk-for-jvm
WORKDIR /workspace/example/agent-client-demo
# install 让 reactor 各模块进入本地仓库，随后把 verification-app 的运行时依赖（含 SDK/jackson）收集到 /app/lib
RUN mvn -q -B -e clean install \
    && mvn -q -B -pl verification-app dependency:copy-dependencies \
       -DincludeScope=runtime -DoutputDirectory=/app/lib \
    && cp verification-app/target/verification-app.jar /app/verification-app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/lib /app/lib
COPY --from=build /app/verification-app.jar /app/verification-app.jar

ENV ACD_RUN_MODE=EXTERNAL \
    JAVA_OPTS="-Dfile.encoding=UTF-8"

# EXTERNAL 模式：跑完即退出，用退出码表达成败，可直接作为 CI / 容器健康门禁。
# UI 模式：常驻看板，由调用方传入 ACD_RUN_MODE=UI。
# 容器内 verification-app 不内嵌任何 mock，运行时必须通过 AGENT_GATEWAY_URL 指向一个真实可达的外部 gateway。
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp \"/app/verification-app.jar:/app/lib/*\" com.openjiuwen.client.verify.CloudClientVerification $([ \"$ACD_RUN_MODE\" = UI ] && echo --ui)"]
