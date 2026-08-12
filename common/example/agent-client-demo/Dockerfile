# syntax=docker/dockerfile:1
#
# 验证镜像：多阶段构建，最终运行在 JDK 17 JRE 上。
# 证明 SDK 核心能力与设备/OS 无关——在一个普通 Linux 容器里即可完整验收（真实 HTTP + SSE）。
#
# 构建上下文需为 common/ 目录（因为 SDK 在 common/agent-client/ 下，demo 在 common/example/agent-client-demo/ 下）：
#   cd common
#   docker build -t openjiuwen/agent-client-demo:0.1.0 -f example/agent-client-demo/Dockerfile .
#   docker run --rm -e AGENT_GATEWAY_URL=http://10.0.0.5:8080 openjiuwen/agent-client-demo:0.1.0   # 退出码 0=全部断言通过，非 0=失败
#
# 说明：verification-app 不内嵌任何 mock，运行时必须通过 AGENT_GATEWAY_URL 指向一个真实可达的外部 gateway。
# mock-gateway 仍随 reactor 构建（可独立起当参考网关），但 verification-app 不依赖它。

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# 把整个多模块 reactor 拷进容器，保持与本地一致的相对结构：
#   workspace/pom.xml                                  (agent-client-demo-parent)
#   workspace/mock-gateway/...
#   workspace/verification-app/...
#   workspace/agent-client/agent-client-sdk-for-jvm/...
COPY example/agent-client-demo/pom.xml ./pom.xml
COPY example/agent-client-demo/mock-gateway ./mock-gateway
COPY example/agent-client-demo/verification-app ./verification-app
COPY agent-client/agent-client-sdk-for-jvm ./agent-client/agent-client-sdk-for-jvm
# install 让 reactor 各模块进入本地仓库，随后把 verification-app 的运行时依赖（含 SDK/mock/jackson）收集到 /app/lib
RUN mvn -q -B -e clean install \
    && mvn -q -B -pl verification-app dependency:copy-dependencies \
       -DincludeScope=runtime -DoutputDirectory=/app/lib

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/lib /app/lib
COPY --from=build /workspace/verification-app/target/verification-app.jar /app/verification-app.jar
# 用退出码表达成败，可直接作为 CI / 容器健康门禁
ENTRYPOINT ["sh", "-c", "java -cp \"/app/verification-app.jar:/app/lib/*\" com.openjiuwen.client.verify.CloudClientVerification"]
