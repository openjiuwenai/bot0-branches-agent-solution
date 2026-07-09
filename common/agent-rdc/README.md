# agent-rdc — Agent Registry & Discovery Center

agent-rdc 是 Agent 解决方案的 registry/discovery 子平面，从 spring-ai-ascend/agent-bus stage 4 抽取。模块定位为可独立启动的 Spring Boot 应用，提供 agent 注册、发现、健康探活能力，基于 PostgreSQL 持久化 + Row-Level Security 实现多租户隔离。

架构权威：[ADR-0160](docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml)（stage4 registry SPI/runtime promotion，7 条决策）。

## 项目概述

| 维度 | 说明 |
|---|---|
| 职责 | agent registry（注册/注销）+ discovery（按 agentId 列举实例）+ health probe（定时探活 + 状态降级） |
| 边界 | 仅 shipping registry/discovery runtime。forwarding / ingress / s2c / federation / engine substrates 不在本模块 |
| 主入口 | `com.openjiuwen.rdc.AgentRdcApplication`（`@SpringBootApplication`） |
| 持久化 | PostgreSQL（Flyway V2 建表 + RLS，V3 REQ-2026-001 重构，V4 REQ-2026-004 重构，V5 REQ-2026-006 多实例 PK 重建） |
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

注册或更新一个 agent 实例（upsert by `(tenant_id, agent_id, service_id)`）。请求体为 `AgentRegistryEntry` JSON：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tenantId` | String | 是 | 租户 ID（registry key 维度） |
| `agentId` | String | 是 | agent ID（registry key 维度） |
| `agentName` | String | 是 | agent 显示名 |
| `frameworkType` | String | 是 | 框架类型（`JIUWEN`/`AGENTSCOPE`/`VERSATILE`/`PROXY_SERVICE`） |
| `routeKey` | String | 是 | 路由 key |
| `contractVersion` | String | 是 | 契约版本 |
| `capabilityVersion` | String | 是 | capability 版本 |
| `endpointUrl` | String | 是 | agent HTTP 端点（服务端据此派生 `serviceId`，caller 不可提供） |
| `maxConcurrency` | int | 否 | 最大并发（默认 10，用于 caller 加权负载均衡） |
| `weight` | int | 否 | 权重（默认 100） |
| `region` | String | 否 | 区域 |
| `a2aAgentCard` | AgentCard (A2A SDK) | 否 | A2A 标准 AgentCard，序列化为 jsonb 持久化 |

> **REQ-2026-006**：`serviceId` 由服务端从 `endpointUrl` 派生（`host-port`），caller 不可提供。同一 `agentId` 部署多副本时，不同 `endpointUrl` 产生不同 `serviceId`，各占一行。

请求头（可选）：
- `traceparent`（W3C）：跨分布式链路追踪 ID
- `X-Trace-Id`：自定义 trace ID

响应：`200 OK`（无 body）。失败抛异常由 Spring 默认错误处理。

### GET `/api/registry/instances/{tenantId}/{agentId}`

列出某 `agentId` 下所有 ONLINE/DEGRADED 实例（REQ-2026-006 新增）。响应为 JSON 数组，每个元素是一个 `AgentCardDto`，携带 opaque `routeHandle`（不暴露 endpointUrl / routeKey / serviceId）。caller 拿到列表后自行选择实例（如 round-robin / 按 `maxConcurrency` 加权），再调 `POST /route-handle/resolve` 解析 endpoint。

响应示例：
```json
[
  {
    "routeHandle": "v1:eyJ0ZW5hbnRJZCI6...",
    "health": "ONLINE",
    "contractVersion": "1.0",
    "capabilityVersion": "1.0",
    "weight": 100,
    "region": "cn-east-1",
    "maxConcurrency": 10,
    "agentName": "math-agent",
    "frameworkType": "JIUWEN"
  }
]
```

空列表返 `200 []`（agent_not_found 语义）。

### POST `/api/registry/route-handle/resolve`

解析 opaque `routeHandle` 为物理 endpoint（REQ-2026-006 新增）。请求体：

```json
{ "routeHandle": "v1:eyJ0ZW5hbnRJZCI6...", "tenantId": "tenant-A" }
```

响应：`RouteResolution { endpointUrl, routeKey, contractVersion }`。

错误码：
- `400 malformed_handle` — handle 格式错误或缺少 `v1:` 前缀（含 REQ-2026-006 前的旧 4-field handle）
- `400 tenant_isolation_violation` — handle 中的 tenantId 与请求体 tenantId 不匹配
- `404 entry_not_found` — handle 指向的实例不存在（已注销或已 OFFLINE）

### DELETE `/api/registry/deregister/{tenantId}/{agentId}`

注销某 `agentId` 的**所有实例**（REQ-2026-006 语义泛化：previously 删 1 行，现在删该 agentId 下全部实例行）。路径变量 `tenantId` + `agentId`。

响应：`204 No Content`（无论条目是否存在）。

### DELETE `/api/registry/deregister/{tenantId}/{agentId}/{serviceId}`

注销单个实例（REQ-2026-006 新增）。用于 rolling deploy：某副本下线时只删该实例，不影响同 `agentId` 其他副本。

响应：`204 No Content`（无论条目是否存在）。

## SPI 迁移指南（REQ-2026-006）

REQ-2026-006 是 **baseline-breaking** SPI 变更。同进程 SPI 消费方（非 HTTP 调用方）需做以下迁移：

### 1. `AgentDiscoveryService` 方法变更

```java
// 旧（REQ-2026-004，已删除）
Optional<AgentCardDto> searchByAgentId(String tenantId, String agentId);

// 新（REQ-2026-006）
List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId);
```

- 返回类型从 `Optional` 改为 `List`：空列表 = agent_not_found（原先 `Optional.empty()`）
- 每个实例独立 `routeHandle`（编码 `serviceId`），caller 自行选择
- 排序：`weight DESC, last_heartbeat DESC`

### 2. `AgentCardDto` 新增 `maxConcurrency`

```java
// 新字段（第 9 个 routing field）
int getMaxConcurrency();
```

caller 可据此做加权负载均衡。

### 3. `routeHandle` 格式破坏

旧 4-field 无前缀 handle 不再被 `resolveRouteHandle` 接受。`v1:` 前缀 5-field 格式（加 `serviceId`）是新唯一格式。migration 部署后，旧 handle 调 resolve 返 `400 malformed_handle`，caller 需重新 `GET /instances` 拿新 handle。

### 4. `AgentRegistryRepository` persistence port 变更

同进程 SPI 实现方（非 HTTP 调用方）若直接实现此 port，需更新签名：
- `searchByAgentId` → `listByAgentId`（返 `List<RegistryRow>`）
- `findEndpoint(tenantId, agentId)` → `findEndpoint(tenantId, agentId, serviceId)`
- `updateStatus` 加 `serviceId` 参数
- 新增 `delete(tenantId, agentId, serviceId)` 重载

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
