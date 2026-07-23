# syntax=docker/dockerfile:1
#
# 验证镜像：多阶段构建，最终运行在 JDK 17 JRE 上。
# 证明 SDK 核心能力与设备/OS 无关——在一个普通 Linux 容器里即可完整验收（真实 HTTP + SSE）。
#
#   docker build -t ascend/cloud-client:0.2.0 .
#   docker run --rm ascend/cloud-client:0.2.0   # 退出码 0=全部断言通过，非 0=失败
#
# 说明：verification-app 内嵌启动 mock-gateway，SDK 经真实 HTTP 打到本机回环，容器内即可自检。

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY agent-client-sdk ./agent-client-sdk
COPY mock-gateway ./mock-gateway
COPY verification-app ./verification-app
# install 让 reactor 各模块进入本地仓库，随后把 verification-app 的运行时依赖（含 SDK/mock/jackson）收集到 /app/lib
RUN mvn -q -B -e clean install \
    && mvn -q -B -pl verification-app dependency:copy-dependencies \
       -DincludeScope=runtime -DoutputDirectory=/app/lib

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/lib /app/lib
COPY --from=build /workspace/verification-app/target/verification-app.jar /app/verification-app.jar
# 用退出码表达成败，可直接作为 CI / 容器健康门禁
ENTRYPOINT ["sh", "-c", "java -cp \"/app/verification-app.jar:/app/lib/*\" com.huawei.ascend.client.verify.CloudClientVerification"]
