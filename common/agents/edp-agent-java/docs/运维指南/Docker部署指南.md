# Docker 单机部署指南

本文档详细介绍如何在生产环境中使用 Docker 部署 EDPAgent。

## 前置条件

| 组件 | 要求 |
|------|------|
| 服务器 | Linux x86_64，推荐 2核4GB 以上 |
| Docker | 20.10+ |
| Redis | 6.0+（独立部署，不推荐容器化 Redis 用于生产） |
| 网络 | 能访问大模型 API 端点（出站 HTTPS） |
| 可选 | Versatile 服务、MCP SSE 服务 |

## 部署架构

单机部署架构：

```
┌─────────────────────────────────────────┐
│              服务器 (Linux)              │
│                                         │
│  ┌──────────────┐    ┌───────────────┐  │
│  │  EDPAgent    │    │   Redis       │  │
│  │  (Docker)    │◀──▶│  (独立部署)    │  │
│  │  :8190       │    │   :6379       │  │
│  └──────┬───────┘    └───────────────┘  │
│         │                                │
└─────────┼────────────────────────────────┘
          │
     ┌────┴────┬──────────┐
     ▼         ▼          ▼
  大模型API  Versatile  MCP SSE
 (DeepSeek)  工作流    脚本沙箱
```

## 步骤一：准备 Redis

生产环境建议独立部署 Redis（不使用 Docker 中的 Redis）：

```bash
# Ubuntu/Debian 安装 Redis
sudo apt update
sudo apt install redis-server

# 配置 Redis（编辑 /etc/redis/redis.conf）
# bind 0.0.0.0  # 如果需要远程访问
# requirepass your-redis-password  # 设置密码
# maxmemory 2gb  # 根据内存设置
# maxmemory-policy allkeys-lru  # 过期策略

# 启动 Redis
sudo systemctl start redis-server
sudo systemctl enable redis-server

# 验证连接
redis-cli ping
# 返回 PONG
```

Redis 部署建议：
- 生产环境至少使用主从模式或哨兵模式保证高可用
- 设置密码认证
- 开启持久化（RDB + AOF）
- 配置内存上限和淘汰策略

## 步骤二：获取 EDPAgent 镜像

### 方式一：从镜像仓库拉取

```bash
docker pull <registry>/edp-agent-java:<version>

# 示例
docker pull registry.example.com/edp/edp-agent-java:0.2.0
```

### 方式二：从源码构建

在具备 Maven 和 JDK 21 的构建机上执行：

```bash
# 克隆代码
git clone <repository-url>
cd edp-agent-java

# Maven 打包（在项目根目录）
./mvnw clean install -DskipTests
./mvnw -pl engine -am package -DskipTests

# 构建 Docker 镜像
docker build -t edp-agent-java:0.2.0 \
  -f deploy/Dockerfile \
  .
```

Dockerfile 位于 [deploy/Dockerfile](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/deploy/Dockerfile)。

## 步骤三：准备配置文件

### 环境变量配置文件

创建 `/opt/edp-agent/.env` 文件（或其他位置）：

```bash
# 大模型配置（必填）
EDP_AGENT_MODEL_PROVIDER=OpenAI
EDP_AGENT_MODEL_NAME=deepseek-v4-pro
EDP_AGENT_MODEL_BASE_URL=https://api.deepseek.com/v1
EDP_AGENT_MODEL_API_KEY=sk-your-api-key-here

# Redis 配置
EDPA_REDIS_MODE=single
EDPA_REDIS_HOST=your-redis-host
EDPA_REDIS_PORT=6379
EDPA_REDIS_PASSWORD=your-redis-password
EDPA_REDIS_DB=0
EDPA_REDIS_CONNECT_TIMEOUT=5000
EDPA_REDIS_SOCKET_TIMEOUT=10000

# 场景配置
EDP_AGENT_SCENARIO_HOME=/app/scenarios/wealth-demo

# 服务端口
SERVER_PORT=8190

# 日志级别
EDP_AGENT_LOG_LEVEL=INFO

# Versatile 配置（如使用）
EDP_AGENT_VERSATILE_URL=http://versatile-server:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
EDP_AGENT_VERSATILE_TIMEOUT=60s

# MCP 配置（如使用）
EDP_MCP_MASTER_URL=http://mcp-server:8080/sse
EDP_MCP_STANDBY_URL=http://mcp-standby:8080/sse
EDP_MCP_ACCESS_TOKEN=your-mcp-token
EDP_MCP_APP_NAME=edp-agent
```

> ⚠️ **安全提示**：`.env` 文件包含敏感信息（API Key、Redis 密码），请设置文件权限为 600：
> ```bash
> chmod 600 /opt/edp-agent/.env
> ```

### 自定义场景挂载（可选）

如果需要使用自定义场景（非镜像内打包的场景），准备场景目录：

```bash
mkdir -p /opt/edp-agent/scenarios
# 将自定义场景目录复制到此处
cp -r /path/to/your-scenario /opt/edp-agent/scenarios/
```

## 步骤四：启动容器

### 基础启动命令

```bash
docker run -d \
  --name edp-agent \
  --restart=always \
  -p 8190:8190 \
  --env-file /opt/edp-agent/.env \
  edp-agent-java:0.2.0
```

### 带自定义场景挂载

```bash
docker run -d \
  --name edp-agent \
  --restart=always \
  -p 8190:8190 \
  --env-file /opt/edp-agent/.env \
  -e EDP_AGENT_SCENARIO_HOME=/app/scenarios/your-scenario \
  -v /opt/edp-agent/scenarios/your-scenario:/app/scenarios/your-scenario:ro \
  edp-agent-java:0.2.0
```

### 生产环境推荐配置

```bash
docker run -d \
  --name edp-agent \
  --restart=always \
  --network host \
  --env-file /opt/edp-agent/.env \
  --log-driver json-file \
  --log-opt max-size=100m \
  --log-opt max-file=7 \
  -v /opt/edp-agent/logs:/app/logs \
  edp-agent-java:0.2.0
```

参数说明：
- `--restart=always`：容器异常退出时自动重启
- `--network host`：使用主机网络（性能更好，端口直接使用主机8190），如需要端口映射用 `-p`
- `--log-driver json-file`：日志驱动
- `--log-opt max-size=100m/max-file=7`：日志轮转，保留7个文件每个100MB
- `-v ...:/app/logs`：挂载日志目录（如果日志输出到文件）

## 步骤五：验证部署

### 1. 检查容器状态

```bash
docker ps | grep edp-agent
# 状态应为 Up，healthy 表示健康检查通过
```

### 2. 检查健康检查

```bash
# 等待约60秒（启动期），然后检查
docker inspect edp-agent --format '{{.State.Health.Status}}'
# 返回 healthy 表示健康
```

### 3. 访问 Agent Card

```bash
curl http://localhost:8190/.well-known/agent-card.json
```

返回 Agent 信息 JSON 即表示服务正常。

### 4. 发送测试请求

```bash
curl -N -X POST http://localhost:8190/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-001",
    "method": "sendStreamingMessage",
    "params": {
      "message": {
        "messageId": "test-msg",
        "role": "user",
        "parts": [{"kind": "text", "text": "你好"}]
      }
    }
  }'
```

收到 SSE 流响应即为成功。

### 5. 查看启动日志

```bash
docker logs edp-agent --tail 100
```

确认无 ERROR 级别日志，看到 `Started EdpaApplication` 即为启动成功。

## 使用 Docker Compose（推荐）

创建 `docker-compose.yml`：

```yaml
version: '3.8'

services:
  edp-agent:
    image: edp-agent-java:0.2.0
    container_name: edp-agent
    restart: always
    ports:
      - "8190:8190"
    env_file:
      - .env
    volumes:
      - ./scenarios:/app/scenarios:ro
      - ./logs:/app/logs
    logging:
      driver: "json-file"
      options:
        max-size: "100m"
        max-file: "7"
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8190/.well-known/agent-card.json"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 60s
    # 如果需要连外部 Redis 和其他服务，不需要 depends_on
```

启动：

```bash
docker-compose up -d
```

查看状态：

```bash
docker-compose ps
docker-compose logs -f edp-agent
```

## 镜像内容说明

EDPAgent Docker 镜像基于 `eclipse-temurin:21-jre-jammy`，包含：

| 组件 | 路径 | 说明 |
|------|------|------|
| JRE 21 | /usr/lib/jvm/... | Java 运行时 |
| Python 3 | /usr/bin/python3 | Python 运行时（用于MCP脚本） |
| Python 依赖 | /usr/local/lib/python3.x/ | requirements-mcp.txt 中的包 |
| 应用 Jar | /app/app.jar | edp-agent-engine jar |
| 框架 Governance | /app/config/governance/ | 框架级默认配置 |
| 配置锚点 | /app/config/edp-config.yaml | 配置加载入口 |
| 内置场景 | /app/scenarios/wealth-demo/ | 理财演示场景 |

环境变量默认值（Dockerfile中设置）：
- `SERVER_PORT=8190`
- `EDPA_AGENT_CONFIG_PATH=/app/config/edp-config.yaml`
- `EDP_AGENT_SCENARIO_HOME=/app/scenarios/wealth-demo`
- `PYTHONIOENCODING=utf-8`

## 端口与网络

| 端口 | 协议 | 说明 |
|------|------|------|
| 8190 | HTTP | A2A 端点 + Agent Card 发现 |

生产环境建议：
- 使用 Nginx 反向代理，配置 HTTPS
- 配置防火墙只允许信任的 IP 访问 8190 端口
- 如果在内网使用不需要公网暴露

## 资源配置建议

| 并发规模 | CPU | 内存 | 备注 |
|----------|-----|------|------|
| 测试/POC | 1核 | 2GB | 仅用于功能验证 |
| 小规模（<50并发） | 2核 | 4GB | 部门级使用 |
| 中规模（50-200并发） | 4核 | 8GB | 企业级使用 |
| 大规模（>200并发） | 8核+ | 16GB+ | 需要集群化（后续版本支持） |

> 注意：大模型 API 调用是主要延迟来源，EDPAgent 本身内存占用较低（约 512MB-1GB），主要开销在 LLM 推理等待。

## 常见部署问题

### 容器启动后立即退出

```bash
# 查看日志
docker logs edp-agent
```

常见原因：
1. Redis 连接失败：检查 `EDPA_REDIS_HOST` 是否可达
2. 端口被占用：使用 `-p` 映射到其他端口或停止占用端口的服务
3. API Key 无效：虽然不会导致启动退出，但会导致推理失败

### 健康检查一直是 starting

启动期（start_period=60s）后仍为 unhealthy：
1. 检查应用是否启动完成：`docker logs edp-agent`
2. 手动 curl 测试：`docker exec edp-agent curl -sf http://localhost:8190/.well-known/agent-card.json`
3. 检查端口是否监听：`docker exec edp-agent netstat -tlnp`

### 日志中文乱码

容器内已设置 `PYTHONIOENCODING=utf-8`，如仍有乱码检查：
1. 宿主机 LANG 环境变量
2. Docker 日志驱动编码设置
3. 查看日志时使用 `docker logs --tail 100 edp-agent | cat` 避免终端编码问题

## 下一步

- [环境变量配置详解](环境配置指南.md) - 所有配置项的详细说明
- [日常运维操作](daily-ops.md) - 启停、日志、Redis维护
- [健康检查与日志分析](健康检查与日志.md) - 监控与问题定位
