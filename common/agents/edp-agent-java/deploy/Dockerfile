# edp-agent-java 运行时镜像
#
# 构建前须先在构建机（Linux/Windows 均可）完成 Maven 打包，推荐在 Linux 直接：
#   bash deploy/build-jar.sh
# 或手工执行：
#   mvn -f common/agent-runtime-ext-java/pom.xml clean install -DskipTests
#   mvn -f common/agents/edp-agent-java/pom.xml       clean package -DskipTests
#
# 构建命令（在 common/agents/edp-agent-java 目录，即以本项目为构建上下文）：
#   bash deploy/build-image.sh
# 等价于：
#   docker build -t edp-agent-java:latest -f deploy/Dockerfile .
#
# 镜像组合策略（规避国内 ECS 构建常见的 apt-get GPG/慢速失败）：
#   - 以 python:3.11-slim 为基础镜像：Python 原生可用，edp-agent 运行时通过
#     ProcessBuilder("python", ...) 执行场景 skill / 归一化 / MCP 脚本，必须内置 Python。
#   - 从 eclipse-temurin 复制自包含 JDK：Temurin 的 JDK 自带运行库、对宿主 glibc 要求低，
#     复制到 Debian(slim) 基础上可稳定运行；全程不使用 apt-get。

FROM eclipse-temurin:21-jre-jammy AS jdk

FROM python:3.11-slim

WORKDIR /app

# 国内服务器构建时加速 pip（海外环境可 docker build --build-arg PIP_INDEX_URL=... 覆盖）
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
ARG PIP_TRUSTED_HOST=mirrors.aliyun.com
ENV PIP_INDEX_URL=${PIP_INDEX_URL} \
    PIP_TRUSTED_HOST=${PIP_TRUSTED_HOST} \
    PIP_DISABLE_PIP_VERSION_CHECK=1

COPY deploy/requirements-mcp.txt /tmp/requirements-mcp.txt
RUN pip install --no-cache-dir -r /tmp/requirements-mcp.txt

# 自包含 JDK 随镜像携带，无需 apt 安装 java
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY engine/target/edp-agent-engine-*.jar app.jar
# 保留 /app/config/governance 作为部署配置目录；当前 Java 初始化逻辑同时会从
# WORKDIR 下的 src/main/resources/governance 读取框架默认治理配置。
COPY engine/src/main/resources/governance/ /app/config/governance/
COPY engine/src/main/resources/governance/ /app/src/main/resources/governance/
COPY deploy/config/edp-config.yaml /app/config/edp-config.yaml
COPY scenarios/wealth-demo/ /app/scenarios/wealth-demo/

ENV SERVER_PORT=8190
ENV EDPA_AGENT_CONFIG_PATH=/app/config/edp-config.yaml
ENV EDP_AGENT_SCENARIO_HOME=/app/scenarios/wealth-demo
ENV PYTHONIOENCODING=utf-8

EXPOSE 8190

# 健康检查用 Python 内置 urllib，Python 为原生基础，无需 curl
HEALTHCHECK --interval=10s --timeout=5s --retries=8 --start-period=90s \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8190/.well-known/agent-card.json', timeout=3)"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
