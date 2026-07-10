# agent-rdc — Agent Registry & Discovery Center

agent-rdc 是 Agent 解决方案的 registry/discovery 子平面，从 spring-ai-ascend/agent-bus stage 4 抽取。模块定位为可独立启动的 Spring Boot 应用，提供 agent 注册、发现、健康探活能力，基于 PostgreSQL 持久化 + Row-Level Security 实现多租户隔离。

架构权威：[ADR-0160](docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml)（stage4 registry SPI/runtime promotion，7 条决策）。

## 项目概述

| 维度 | 说明 |
|---|---|
| 职责 | agent registry（注册/注销）+ discovery（按 agentId / serviceId / capability 三维度列举实例）+ health probe（定时探活 + 状态降级） |
| 边界 | 仅 shipping registry/discovery runtime。forwarding / ingress / s2c / federation / engine substrates 不在本模块 |
| 主入口 | `com.openjiuwen.rdc.AgentRdcApplication`（`@SpringBootApplication`） |
| 持久化 | PostgreSQL（Flyway V2 建表 + RLS，V3 REQ-2026-001 重构，V4 REQ-2026-004 重构，V5 REQ-2026-006 多实例 PK 重建，V6 FEAT-016 serviceId/instanceId 拆分 + capabilities 重建 + 4-field PK） |
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

启动成功标志：日志含 `Started AgentRdcApplication` + `Successfully applied 5 migrations`（Flyway V2–V6）+ `Tomcat started on port 8092`。

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

注册或更新一个 agent 实例（upsert by 4-field PK `(tenant_id, agent_id, service_id, instance_id)`）。请求体为 `AgentRegistryEntry` JSON：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tenantId` | String | 是 | 租户 ID（registry key 维度） |
| `agentId` | String | 是 | agent ID（registry key 维度） |
| `serviceId` | String | 否 | 逻辑服务标识（host only），可跨 `agentId` 共享，组成逻辑服务组；省略时由服务端从 `endpointUrl` host 派生（`ServiceIdCodec`） |
| `instanceId` | String | — | server-derived，**caller 不可提供**：服务端从 `endpointUrl` host-port 派生（`InstanceIdCodec`）；setter 为 package-private，请求体中传该字段会被忽略 |
| `agentName` | String | 是 | agent 显示名 |
| `frameworkType` | String | 是 | 框架类型（`JIUWEN`/`AGENTSCOPE`/`VERSATILE`/`PROXY_SERVICE`） |
| `routeKey` | String | 是 | 路由 key |
| `contractVersion` | String | 是 | 契约版本 |
| `capabilityVersion` | String | 是 | capability 版本 |
| `endpointUrl` | String | 是 | agent HTTP 端点；**派生源**：host → `serviceId`，host-port → `instanceId` |
| `capabilities` | String[] | 否 | 能力数组（重建 REQ-2026-004 移除的字段，现为多值）；省略时存空数组 `{}`
| `maxConcurrency` | int | 否 | 最大并发（默认 10，用于 caller 加权负载均衡） |
| `weight` | int | 否 | 权重（默认 100） |
| `region` | String | 否 | 区域 |
| `a2aAgentCard` | AgentCard (A2A SDK) | 否 | A2A 标准 AgentCard，序列化为 jsonb 持久化 |

> **FEAT-016**：`serviceId` 从 REQ-2026-006 的 "host-port" 语义拆分为 "host only" 的逻辑服务标识（caller 可显式提供以覆盖默认派生），与新增的 `instanceId`（host-port，server-derived）共同构成 4-field PK。同一 `agentId` + `serviceId` 下可挂多个 `instanceId`（水平扩展 / 蓝绿部署）。`capabilities` 重建为 `VARCHAR(64)[]` 多值列，支撑 `by-capability` 查询。

请求头（可选）：
- `traceparent`（W3C）：跨分布式链路追踪 ID
- `X-Trace-Id`：自定义 trace ID

响应：`200 OK`（无 body）。失败抛异常由 Spring 默认错误处理。

### GET `/api/registry/instances/{tenantId}/{agentId}`

列出某 `agentId` 下所有 ONLINE/DEGRADED/DRAINING 实例（REQ-2026-006 新增；FEAT-016 起新增 DRAINING 可见 + 可选 `?contractVersion=` 过滤）。响应为 JSON 数组，每个元素是一个 `AgentCardDto`，携带 opaque `routeHandle`（不暴露 endpointUrl / routeKey）。caller 拿到列表后自行选择实例（如 round-robin / 按 `maxConcurrency` 加权），再调 `POST /route-handle/resolve` 解析 endpoint。

可选 query：`?contractVersion=1.0` —— 出现时 SQL 追加 `AND contract_version = :contractVersion`；省略时不加过滤。

响应示例：
```json
[
  {
    "serviceId": "math-svc",
    "routeHandle": "v2:eyJ0ZW5hbnRJZCI6...",
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

- `routeHandle` 前缀 `v2:`（6-field，含 `instanceId`）；旧 `v1:` / 无前缀 handle 在 FEAT-016 后被 `resolve` 拒绝
- `health` 可为 `ONLINE` / `DEGRADED` / `DRAINING`；`DRAINING` 表示有限可用（caller 可酌情绕开）

空列表返 `200 []`（agent_not_found 语义）。

### GET `/api/registry/instances/by-service/{tenantId}/{serviceId}`

按逻辑服务标识查询（FEAT-016 新增）。返回所有 ONLINE/DEGRADED/DRAINING 实例中 `service_id = {serviceId}` 的行。可选 `?contractVersion=` 过滤。响应结构与 `GET /instances/{tenantId}/{agentId}` 相同（`AgentCardDto[]`）。

### GET `/api/registry/instances/by-capability/{tenantId}/{capability}`

按能力数组包含查询（FEAT-016 新增，替代 REQ-2026-004 移除的 free-text 搜索）。SQL 使用 `capabilities @> ARRAY[::capability]` 精确匹配。可选 `?contractVersion=` 过滤。响应结构同上。

### POST `/api/registry/route-handle/resolve`

解析 opaque `routeHandle` 为物理 endpoint（REQ-2026-006 新增；FEAT-016 起 handle 为 `v2:` 6-field，响应新增 `instanceId`）。请求体：

```json
{ "routeHandle": "v2:eyJ0ZW5hbnRJZCI6...", "tenantId": "tenant-A" }
```

响应：`RouteResolution { instanceId, endpointUrl, routeKey, contractVersion }`（`instanceId` 为首个字段，forwarding-layer only，由 handle 解码而来，不读 DB）。

错误码：
- `400 malformed_handle` — handle 格式错误、缺少 `v2:` 前缀，或为旧 `v1:` 5-field / 无前缀 4-field 格式（FEAT-016 baseline-breaking：不再兼容旧 handle）
- `400 tenant_isolation_violation` — handle 中的 tenantId 与请求体 tenantId 不匹配
- `404 entry_not_found` — handle 指向的实例不存在（已注销或已 OFFLINE）

### DELETE `/api/registry/deregister/{tenantId}/{agentId}`

注销某 `agentId` 的**所有实例**（REQ-2026-006 语义泛化：previously 删 1 行，现在删该 agentId 下全部实例行）。路径变量 `tenantId` + `agentId`。

响应：`204 No Content`（无论条目是否存在）。

### DELETE `/api/registry/deregister/{tenantId}/{agentId}/{serviceId}`

注销 triple `(tenantId, agentId, serviceId)` 下的**所有实例**。FEAT-016 语义泛化：REQ-2026-006 时 `serviceId` 是 host-port 单实例键，此端点删 1 行；FEAT-016 起 `serviceId` 是逻辑标识，此端点删该 triple 下所有 `instanceId`（可能多行）。

响应：`204 No Content`（无论条目是否存在）。

### DELETE `/api/registry/deregister/{tenantId}/{agentId}/{serviceId}/{instanceId}`

注销单个具体实例（FEAT-016 新增，4-field PK）。用于 rolling deploy：某副本下线时只删该 `instanceId`，不影响同 `serviceId` 下其他副本。

响应：`204 No Content`（无论条目是否存在）。

## SPI 迁移指南

### FEAT-016 阶段一（baseline-breaking）

FEAT-016 是继 REQ-2026-006 之后的又一次 **baseline-breaking** SPI 变更。同进程 SPI 消费方（非 HTTP 调用方）需做以下迁移：

#### 1. `AgentDiscoveryService` 新增查询维度 + `contractVersion` 过滤

```java
// 已有方法（REQ-2026-006 引入，FEAT-016 加 contractVersion 参数）
List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId, String contractVersion);

// FEAT-016 新增
List<AgentCardDto> searchByServiceId(String tenantId, String serviceId, String contractVersion);
List<AgentCardDto> searchByCapability(String tenantId, String capability, String contractVersion);
```

- `contractVersion` 可空：`null` = 不加过滤；非空 = SQL `AND contract_version = :contractVersion`
- 三个查询维度均返回 ONLINE/DEGRADED/DRAINING 实例（FEAT-016 起 DRAINING **纳入** discovery 结果，caller 视为有限可用）
- 排序统一为 `weight DESC, last_heartbeat DESC`
- Anti-enumeration：无匹配返空 `List`（不抛、不返 `null`）

#### 2. `AgentCardDto` 新增 `serviceId`

```java
String getServiceId();   // 逻辑服务标识（host only），caller 可用于分组
```

`serviceId` 在 agent/client projection 层可见（L2 §2.3.2）；`instanceId` **不**在 DTO 中暴露（forwarding-layer only，仅由 `RouteResolution` 携带）。

#### 3. `RouteResolution` 新增 `instanceId`（首个字段）

```java
record RouteResolution(String instanceId, String endpointUrl,
                       String routeKey, String contractVersion) {}
```

`instanceId` 由 `routeHandle` 解码而来（不读 DB），forwarding-layer 据此定位同一 `serviceId` 下的具体实例。

#### 4. `RouteHandleCodec` v1: 5-field → v2: 6-field（baseline-breaking）

| 版本 | 前缀 | 字段 | 状态 |
|---|---|---|---|
| pre-REQ-2026-006 | 无 | 4-field (tenantId, agentId, routeKey, contractVersion) | 拒绝 |
| REQ-2026-006 | `v1:` | 5-field (+ serviceId) | **FEAT-016 起拒绝** |
| FEAT-016 | `v2:` | 6-field (+ instanceId) | 当前唯一接受格式 |

旧 `v1:` / 无前缀 handle 调 `resolveRouteHandle` 返 `400 malformed_handle`。routeHandle 生命周期短（一个探活周期），migration 后 caller 重新 `GET /instances` 拿新 `v2:` handle 即可；不保留滚动兼容窗口（H2-4 一次性耦合决策）。

#### 5. `AgentRegistryRepository` persistence port 变更

同进程 SPI 实现方（非 HTTP 调用方）若直接实现此 port，需更新签名：

- **PK 演进**：`(tenant_id, agent_id, service_id)` → `(tenant_id, agent_id, service_id, instance_id)`
- `listByAgentId(tenantId, agentId)` → `listByAgentId(tenantId, agentId, contractVersion)`（加可空过滤；DRAINING 纳入结果）
- 新增 `listByServiceId(tenantId, serviceId, contractVersion)`
- 新增 `listByCapability(tenantId, capability, contractVersion)`
- `delete(tenantId, agentId, serviceId)` 语义泛化：删 triple 下**所有** `instanceId`（可能多行）
- 新增 4-field `delete(tenantId, agentId, serviceId, instanceId)`：删单一实例
- `findEndpoint(tenantId, agentId, serviceId)` → `findEndpoint(tenantId, agentId, serviceId, instanceId)`（4-field PK）
- `updateStatus(tenantId, agentId, serviceId, newStatus, refresh)` → `updateStatus(tenantId, agentId, serviceId, instanceId, newStatus, refresh)`（探活结果按具体实例 scope）
- Upsert `ON CONFLICT` 演进为 `(tenant_id, agent_id, service_id, instance_id)` —— 实例级幂等覆盖
- `RegistryRow` 13 字段：新增 `instanceId`（第 2 字段）+ `capabilities`（`List<String>`，末字段）
- `ProbeTarget` 5 字段：新增 `instanceId`（探活调度器据此调用 4-field `updateStatus`）

#### 6. discovery SQL 变更

- status 过滤从 `ONLINE/DEGRADED` 扩展为 `ONLINE/DEGRADED/DRAINING`（DRAINING = 有限可用）
- `contractVersion` 可空过滤：`null` = 不加 `AND` 子句；非空 = `AND contract_version = ?`

#### 7. PK / 标识语义

- `service_id`：**逻辑服务标识**（host only），caller 可显式提供覆盖默认派生；可跨 `agentId` 共享
- `instance_id`：**具体实例标识**（host-port），server-derived，caller 不可提供；同一 `serviceId` 下可有多 `instanceId`

---

### REQ-2026-006（历史，已被 FEAT-016 进一步演进）

历史背景，仅供理解迁移脉络：

- `searchByAgentId` (Optional) → `searchInstancesByAgentId` (List)
- `AgentCardDto` 新增 `maxConcurrency`
- `routeHandle` 引入 `v1:` 5-field 前缀格式（FEAT-016 起 `v2:` 取代）
- `AgentRegistryRepository` 3-field PK + `delete(tenantId, agentId, serviceId)` 单实例语义（FEAT-016 起改为 4-field PK + triple 删多行）

## Flyway 版本

| 版本 | 文件 | 说明 |
|---|---|---|
| V2 | `V2__create_agent_registry_mvp.sql` | 建表 `agent_registry_mvp` + RLS policy |
| V3 | `V3__refactor_agent_registry_mvp_drop_legacy_fields.sql` | REQ-2026-001 重构：drop 旧字段 |
| V4 | `V4__refactor_agent_registry_drop_capability_search_tsv_rename_framework_type.sql` | REQ-2026-004：drop `capability` / `search_tsv`，rename `framework_type` |
| V5 | `V5__multi_instance_service_id_pk.sql` | REQ-2026-006：多实例 `service_id` PK 重建 |
| V6 | `V6__feat_016_service_instance_capability.sql` | FEAT-016：`serviceId`/`instanceId` 拆分 + `capabilities VARCHAR(64)[]` 重建 + 4-field PK + GIN index on capabilities |

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
