# Versatile Agent Docker 单机部署指南

## 1. 部署架构

adapter-versatile-agent-java 是一个独立的无状态 Spring Boot 服务，作为 A2A（Agent-to-Agent）协议的代理层运行：

```
┌─────────────┐      A2A SSE      ┌──────────────────────────┐     HTTP/SSE      ┌────────────────────┐
│   EDPAgent  │ ────────────────> │ adapter-versatile-agent  │ ────────────────> │  Versatile 工作流API │
│  (上游调用方) │                   │      (本服务, :8191)     │                   │   (下游服务)        │
└─────────────┘                   └──────────────────────────┘                   └────────────────────┘
```

**核心特点**：
- **无状态服务**：不依赖 Redis，不存储会话状态，可直接水平扩展
- Agent ID 固定为 `versatile-agent`
- 默认监听端口 8191
- 通过 A2A 协议接收上游 EDPAgent 的请求，转发到下游 Versatile 工作流 API

代码入口：[VersatileAgentApplication.java](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/src/main/java/com/huawei/ascend/versatile/VersatileAgentApplication.java#L22-L27)

## 2. 前置条件

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| Docker | 20.10+ | 容器运行时 |
| JDK | 17+（构建用） | Maven 打包需要，Docker 镜像内置 JRE 21 |
| Maven | 3.6+ | 构建打包 |
| 网络连通性 | - | 容器到 Versatile 服务网络可达 |

Docker 基础镜像使用 `eclipse-temurin:21-jre-jammy`，参见 [Dockerfile](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/deploy/Dockerfile#L12)。

## 3. 构建镜像

### 3.1 Maven 打包

在项目根目录（`adapter-versatile-agent-java/`）执行：

```bash
mvn clean package -DskipTests
```

打包成功后，`target/` 目录下会生成 `adapter-versatile-agent-java-*.jar` 文件。

### 3.2 Docker 构建镜像

在**仓库根目录**执行（参考 [Dockerfile 注释](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/deploy/Dockerfile#L7-L10)）：

```bash
docker build -t adapter-versatile-agent-java:latest \
  -f adapter-versatile-agent-java/deploy/Dockerfile \
  adapter-versatile-agent-java
```

如果是在当前模块目录（`adapter-versatile-agent-java/`）执行：

```bash
docker build -t adapter-versatile-agent-java:latest \
  -f deploy/Dockerfile \
  .
```

## 4. docker run 启动

### 4.1 基本启动命令

```bash
docker run -d \
  --name versatile-agent \
  -p 8191:8191 \
  -e VERSATILE_URL="http://your-versatile-host:port/v1/0/agent-manager/workflows/your_workflow/conversations/{conversation_id}?type=controller&workspace_id=10" \
  -e VERSATILE_TIMEOUT=600s \
  -e VERSATILE_RESULT_NODE=GXZQAResponseNode \
  adapter-versatile-agent-java:latest
```

### 4.2 网络模式说明

| 场景 | 网络配置 |
|------|----------|
| Versatile 服务在宿主机 | 使用 `host.docker.internal` 访问宿主机，例如 `http://host.docker.internal:30001/...` |
| Versatile 服务在同一 Docker 网络 | 使用容器名或服务名访问 |
| Versatile 服务有独立域名 | 直接使用域名，确保 DNS 解析正常 |

**宿主机访问示例**：

```bash
docker run -d \
  --name versatile-agent \
  -p 8191:8191 \
  -e VERSATILE_URL="http://host.docker.internal:30001/v1/0/agent-manager/workflows/mock_workflow/conversations/{conversation_id}?type=controller&workspace_id=10" \
  adapter-versatile-agent-java:latest
```

### 4.3 挂载日志目录（可选）

```bash
docker run -d \
  --name versatile-agent \
  -p 8191:8191 \
  -v $(pwd)/logs:/app/logs \
  -e VERSATILE_URL="http://host.docker.internal:30001/..." \
  adapter-versatile-agent-java:latest
```

## 5. docker-compose 示例

创建 `docker-compose.yml` 文件：

```yaml
version: '3.8'

services:
  versatile-agent:
    image: adapter-versatile-agent-java:latest
    container_name: versatile-agent
    restart: unless-stopped
    ports:
      - "8191:8191"
    environment:
      - VERSATILE_AGENT_PORT=8191
      - VERSATILE_URL=http://host.docker.internal:30001/v1/0/agent-manager/workflows/mock_workflow/conversations/{conversation_id}?type=controller&workspace_id=10
      - VERSATILE_TIMEOUT=600s
      - VERSATILE_RESULT_NODE=GXZQAResponseNode
      - TZ=Asia/Shanghai
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8191/.well-known/agent-card.json"]
      interval: 10s
      timeout: 5s
      retries: 8
      start_period: 90s
    logging:
      driver: json-file
      options:
        max-size: "100m"
        max-file: "5"
    networks:
      - agent-network

networks:
  agent-network:
    driver: bridge
```

启动服务：

```bash
docker-compose up -d
```

## 6. 容器健康检查

### 6.1 HEALTHCHECK 机制

Dockerfile 内置健康检查（参见 [Dockerfile:24-25](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/deploy/Dockerfile#L24-L25)）：

| 参数 | 值 | 说明 |
|------|-----|------|
| interval | 10s | 每 10 秒检查一次 |
| timeout | 5s | 单次检查超时 5 秒 |
| retries | 8 | 连续失败 8 次标记为 unhealthy |
| start-period | 90s | 启动宽限期 90 秒（Spring Boot 启动较慢） |

检查端点：`/.well-known/agent-card.json`

### 6.2 查看健康状态

```bash
# 查看容器状态
docker ps

# 详细健康检查信息
docker inspect --format='{{.State.Health.Status}}' versatile-agent

# 查看健康检查历史
docker inspect --format='{{json .State.Health}}' versatile-agent | python -m json.tool
```

状态说明：
- `starting`：启动中（90 秒宽限期内）
- `healthy`：健康
- `unhealthy`：不健康（连续 8 次检查失败）

## 7. 验证部署

服务启动后，执行以下命令验证：

### 7.1 验证 Agent Card 端点

```bash
curl http://localhost:8191/.well-known/agent-card.json
```

正常返回包含 Agent 信息的 JSON，示例片段：

```json
{
  "name": "versatile-agent",
  "description": "Remote runtime that processes banking and external business workflows through Versatile HTTP/SSE.",
  ...
}
```

### 7.2 验证 Actuator 健康端点

```bash
curl http://localhost:8191/actuator/health
```

正常返回：

```json
{"status":"UP"}
```

## 8. 日志配置

### 8.1 Docker 日志轮转（推荐）

在 docker-compose.yml 中配置（见上面示例），或在 `/etc/docker/daemon.json` 中全局配置：

```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m",
    "max-file": "5"
  }
}
```

### 8.2 容器内日志文件

服务日志同时输出到控制台和容器内文件：
- 容器内路径：`/app/logs/run/run.log`
- 日志级别：DEBUG（针对 `com.huawei.ascend.versatile`、`com.openjiuwen.service.adapters.versatile`、`com.openjiuwen.service.app` 包）

参见 [application.yml:44-50](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/src/main/resources/application.yml#L44-L50)。

### 8.3 查看日志

```bash
# 查看容器控制台日志
docker logs -f versatile-agent

# 如果挂载了日志目录，直接查看文件
tail -f ./logs/run/run.log
```
