# agent-rdc — Agent Registry & Discovery Center

agent-rdc 是 Agent 解决方案的 registry/discovery 子平面，从 spring-ai-ascend/agent-bus stage 4 抽取。模块定位为可独立启动的 Spring Boot 应用，提供 agent 注册、发现、健康探活能力，基于 PostgreSQL 持久化 + Row-Level Security 实现多租户隔离。

架构权威：[ADR-0160](docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml)（stage4 registry SPI/runtime promotion，7 条决策）。

## 项目概述

| 维度 | 说明 |
|---|---|
| 职责 | agent registry（注册/注销）+ discovery（按 capability/Intent 查询）+ health probe（定时探活 + 状态降级） |
| 边界 | 仅 shipping registry/discovery runtime。forwarding / ingress / s2c / federation / engine substrates 不在本模块 |
| 主入口 | `com.openjiuwen.rdc.AgentRdcApplication`（`@SpringBootApplication`） |
| 持久化 | PostgreSQL（Flyway V2 建表 + RLS，V3 REQ-2026-001 重构） |
| SPI 包 | `com.openjiuwen.rdc.spi.registry`（纯 Java，无 Spring/Jackson 依赖） |
| Runtime 包 | `com.openjiuwen.rdc.registry.runtime.{api,discovery,health,persistence.jdbc,tenant}` |
| Java 版本 | 21 |
| Spring Boot | 4.0.5 |

## 快速启动

### 前置：本地 PostgreSQL

dev profile 默认连 `localhost:5432/agent_rdc`，账密 `agent_rdc/agent_rdc`。起一个 PG 容器（需为 `agent_rdc` 库 owner 以创建 RLS policy）：

```bash
docker run -d --name agent-rdc-pg \
  -p 5432:5432 \
  -e POSTGRES_DB=agent_rdc \
  -e POSTGRES_USER=agent_rdc \
  -e POSTGRES_PASSWORD=agent_rdc \
  postgres:16
```

或使用本机既有 PG，确保 `agent_rdc` 库存在且连接账号是 owner。

### 构建

```bash
mvn -pl agent-rdc -am compile
```

### 运行

```bash
# 方式 1：spring-boot:run
mvn -pl agent-rdc spring-boot:run

# 方式 2：打包后 java -jar
mvn -pl agent-rdc -am package
java -jar target/agent-rdc-0.1.0.jar
```

启动成功标志：日志含 `Started AgentRdcApplication` + `Successfully applied 2 migrations`（Flyway V2/V3）+ `Tomcat started on port 8092`。

进程监听 `http://localhost:8092`。端口冲突时用 `--server.port=NNNN` 覆盖。

### 测试

```bash
mvn -pl agent-rdc test
```

测试用 Zonky embedded-postgres（真实 PG binary in-process）+ MockWebServer，不依赖外部 PG，不走 Spring Boot 自动配置。

### 部署侧覆盖

prod 环境通过环境变量覆盖 dev 默认值，无需独立 `application-prod.yml`：

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://prod-pg:5432/agent_rdc
export SPRING_DATASOURCE_USERNAME=prod_user
export SPRING_DATASOURCE_PASSWORD=********
java -jar agent-rdc-0.1.0.jar
```

## API 端点速查

### POST `/api/registry/register`

注册或更新一个 agent 条目（upsert by `(tenant_id, agent_id)`）。请求体为 `AgentRegistryEntry` JSON：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tenantId` | String | 是 | 租户 ID（registry key 维度） |
| `agentId` | String | 是 | agent ID（registry key 维度） |
| `capability` | String | 是 | capability 标签（discovery 按此过滤） |
| `agentName` | String | 是 | agent 显示名 |
| `agentType` | String | 是 | agent 类型 |
| `routeKey` | String | 是 | 路由 key |
| `contractVersion` | String | 是 | 契约版本 |
| `capabilityVersion` | String | 是 | capability 版本 |
| `endpointUrl` | String | 是 | agent HTTP 端点 |
| `maxConcurrency` | int | 否 | 最大并发（默认 10） |
| `weight` | int | 否 | 权重（默认 100） |
| `region` | String | 否 | 区域 |
| `a2aAgentCard` | AgentCard (A2A SDK) | 否 | A2A 标准 AgentCard，序列化为 jsonb 持久化 |

请求头（可选）：
- `traceparent`（W3C）：跨分布式链路追踪 ID
- `X-Trace-Id`：自定义 trace ID

响应：`200 OK`（无 body）。失败抛异常由 Spring 默认错误处理。

### DELETE `/api/registry/deregister/{tenantId}/{agentId}`

注销一个 agent 条目。路径变量 `tenantId` + `agentId`（不再用 query param，避免泄漏到 access log）。

响应：`204 No Content`（无论条目是否存在）。

## 架构/文档链接

| 文档 | 路径 | 说明 |
|---|---|---|
| ADR-0160 | `docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml` | 模块架构权威（7 条决策：SPI 纯度 / JDBC 隔离 / tenant 三层隔离 / runnable 定位等） |
| L2 设计 | `architecture/L2-Low-Level-Design/agent-bus/registry-discovery-runtime-design.cn.md` | 详细设计文档（§3.2 schema / §3.3 discovery SQL） |
| L0/L1 | `architecture/L0-Top-Level-Design/` / `architecture/L1-High-Level-Design/` | 上层架构文档 |
| 4+1 baseline | `.opencode/skills/architecture-models-4plus1/examples/agent-bus-forwarding-baseline.yaml` | L1 4+1 baseline（status: reviewed） |
| 模块治理 baseline | `.harness/baselines/baseline.yaml` | L2 治理 baseline（writable 边界 frozen） |
| 项目术语 | `CONTEXT.md` | 项目术语表（AgentRegistryEntry / AgentCard / AgentCardDto 等） |

`architecture/` 与 `docs/` 是 symlink，指向 spring-ai-ascend 仓库的同名目录。
