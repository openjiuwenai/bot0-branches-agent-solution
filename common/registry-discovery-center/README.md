# registry-discovery-center — Agent Registry & Discovery Center

registry-discovery-center 是 Agent 解决方案的 registry/discovery 子平面，从 spring-ai-ascend/agent-bus stage 4 抽取。模块定位为可独立启动的 Spring Boot 应用，提供 agent 注册、发现、健康探活能力，基于 PostgreSQL 持久化 + Row-Level Security 实现多租户隔离。

> 模块目录与 Maven artifactId 为 `registry-discovery-center`（曾用名 `agent-rdc`）。为部署兼容，Java 包名仍为 `com.openjiuwen.rdc`、入口类仍为 `AgentRdcApplication`、DB 名/账号仍为 `agent_rdc`、打包 jar 仍为 `agent-rdc-0.1.0.jar`（`finalName`）。对人展示的配置/OpenAPI 文案统一用模块现名。

架构权威：[ADR-0160](docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml)（stage4 registry SPI/runtime promotion，7 条决策）。

## 项目概述

| 维度 | 说明 |
|---|---|
| 职责 | **逻辑 Agent Card 注册与发现**（Feat-015：provider 对账 + 主动抓 Card + `POST /discover`）+ **运行时实例路由**（FEAT-016：按 agentId / serviceId / capability 列实例 + `route-handle/resolve`）+ health probe |
| 边界 | 仅 shipping registry/discovery runtime。forwarding / ingress / s2c / federation / engine substrates 不在本模块 |
| 主入口 | `com.openjiuwen.rdc.AgentRdcApplication`（`@SpringBootApplication`） |
| 持久化 | PostgreSQL（Flyway V2–V6 实例表演进；**V7–V11 Feat-015**：治理字段、对账来源、source state、fingerprint、逻辑 `agent_card_registration`） |
| 契约/模型 | `com.openjiuwen.rdc.model` + `model.deployment`（纯 Java，无 Spring/Jackson） |
| MVC 运行时 | `controller` / `service` / `repository` / `config` + Feat-015：`card` / `deployment` / `reconcile` / `security`；另有 `health` / `pull` / `tenant` |
| Java 版本 | 21 |
| Spring Boot | 4.0.5 |

### 两平面一张速览

| 平面 | 需求 | HTTP | 目录数据 |
|---|---|---|---|
| 逻辑 Agent Card | Feat-015 | `POST /api/registry/discover` | `agent_card_registration`（按 Card 身份/版本去重；**无** `routeHandle` / `endpointUrl`） |
| 运行时实例路由 | FEAT-016 | `GET /instances...`、`POST /route-handle/resolve` | `agent_registry_mvp`（带 opaque `routeHandle`） |
| 正式注册 | Feat-015 | **非 push**：可插拔 `DeploymentDiscoveryProvider` Bean + 可选 yml 静态实例；缺 yml 绑定时用 `binding-defaults`；抓取 `/.well-known/agent-card.json` | 开启 `rdc.deployment-discovery.enabled=true` 时 `POST /register` 返回 **410** |

## 快速启动

### 前置：本地 PostgreSQL

dev profile 默认连 `localhost:5432/agent_rdc`，账密 `agent_rdc/agent_rdc`。起一个 PG 容器（需为 `agent_rdc` 库 owner 以创建 RLS policy）：

```bash
docker run -d --name registry-discovery-center-pg \
  -p 5432:5432 \
  -e POSTGRES_DB=agent_rdc \
  -e POSTGRES_USER=agent_rdc \
  -e POSTGRES_PASSWORD=agent_rdc \
  postgres:16
```

或使用本机既有 PG，确保 `agent_rdc` 库存在且连接账号是 owner。

### 构建

本模块为独立 Maven 工程（无父 reactor），在模块目录执行：

```bash
cd agent-solution/common/registry-discovery-center
mvn compile
```

### 运行

```bash
# 方式 1：spring-boot:run
mvn spring-boot:run

# 方式 2：打包后 java -jar
mvn package
java -jar target/agent-rdc-0.1.0.jar
```

启动成功标志：日志含 `Started AgentRdcApplication` + Flyway 应用迁移（干净库为 **V2–V12**）+ `Tomcat started on port 8092`。开启 deployment-discovery 时另有 reconcile 日志（抓 Card / 跳过旧 snapshot 等）。

进程监听 `http://localhost:8092`。端口冲突时用 `--server.port=NNNN` 覆盖。

### 测试

```bash
# 推荐：关闭 JUnit 类并行，减轻 macOS kern.sysv.shmmni 压力
mvn -DjunitParallel=false test
```

测试用 Zonky embedded-postgres（真实 PG binary in-process，经 `EmbeddedPostgresTestSupport` **单 JVM 共享一份**）+ MockWebServer。若 `ipcs -m` 段数接近 `kern.sysv.shmmni`（默认常为 32），先清理孤儿共享内存或提高系统 `shmmni` 后再跑。

### 部署侧覆盖

prod 环境通过环境变量覆盖 dev 默认值，无需独立 `application-prod.yml`：

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://prod-pg:5432/agent_rdc
export SPRING_DATASOURCE_USERNAME=prod_user
export SPRING_DATASOURCE_PASSWORD=********
java -jar agent-rdc-0.1.0.jar
```

## API 端点速查

### POST `/api/registry/discover`（Feat-015）

结构化逻辑 Agent Card 发现（`DiscoverAgentCards`）。读 **`agent_card_registration`**，返回按逻辑 Card 去重的候选；**不**展开运行实例，响应中**没有** `routeHandle` / `instanceId` / `endpointUrl`（实例选路走下方 FEAT-016 端点）。

请求头：
- `X-Caller-Ref`（可选）：调用方标识；缺省时用 body `context.callerRef`，再缺省为 `http-client`
- `traceparent` / `X-Trace-Id`（可选）

请求体示例：

```json
{
  "context": {
    "tenantId": "tenant-A",
    "callerRef": "gateway",
    "requestId": "req-1"
  },
  "agentId": "billing-svc",
  "limit": 5,
  "constraints": {
    "contractVersion": "1.0.0",
    "requiredSkillTags": ["commerce"]
  }
}
```

- `context.tenantId` 必填；`agentId` / `serviceId` / `a2aSkillId` 至少一项（`agentId` = 部署 `service-id` 派生，**不是** Card JSON 的 `name`）
- `constraints`、`limit`、`continuationToken` 可选

响应示例：

```json
{
  "outcome": "SUCCESS",
  "candidates": [
    {
      "agentId": "billing-svc",
      "serviceId": "billing-svc",
      "agentCardJson": "{...}",
      "contractVersion": "1.0.0",
      "capabilityVersion": "1.0.0",
      "registrationStatus": "REGISTERED",
      "freshness": "FRESH",
      "lastValidatedAt": "2026-07-14T02:50:26Z",
      "matchedA2aSkillId": null
    }
  ],
  "nextToken": null,
  "traceId": "..."
}
```

| `outcome` | 含义 |
|---|---|
| `SUCCESS` | 至少一个逻辑候选 |
| `NO_MATCH` | 无匹配（含未知 agent、约束不满足、仅 `PENDING` 等） |

新鲜度（Feat-015 §5.1.4）：`FRESH` / `STALE_CARD` / `STALE_SOURCE`。`STALE_*` 在受控有效期内**仍可出现在发现结果中**（携带 `freshness` + `lastValidatedAt`）。

结构化失败（`RegistryFailureException`，经 `RegistryApiExceptionHandler` 映射）：如 `CALLER_NOT_AUTHORIZED`（403）、`INVALID_QUERY`（400）、`TENANT_SCOPE_DENIED`（403）等。

相关配置（`application.yml`）：

```yaml
rdc:
  # 正式路径（Feat-015）。本仓库 application.yml 为上线模板：enabled=true、instances=[]。
  # 本地 E2E 取消注释 yml 内 8090/8091 example，或注入 DeploymentDiscoveryProvider。
  # 与 rdc.pull-registration.enabled 互斥（同时为 true 启动失败）。
  deployment-discovery:
    enabled: true              # true 时 push /register → 410；测 push 时改为 false
    reconcile-interval: 30s
    instances: []              # 生产填真实 URL 或注入 Provider；本地见注释 example
  # 遗留路径（REQ-2026-004）— 已 @Deprecated；勿与 deployment-discovery 同时开启。
  # agentId 为 yml 手填；deployment-discovery 则用 AgentIdCodec.derive(tenantId, serviceId)。
  pull-registration:
    enabled: false
  registry:
    security:
      caller-allowlist: {}     # 非空则按 tenant → callerRef 白名单校验 discover/resolve
    card-fetch:
      mutual-tls: false
      verify-signatures: false
      # 空 = 不限制（启动打 WARN）。生产建议：127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
      target-cidrs: []
```

手工/自动化验收见仓库根目录 `Feat-015-agent-card-registration-and-discovery0713-test-plan.md`。

---

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

响应：`200 OK`（无 body）。

> **Feat-015：** 当 `rdc.deployment-discovery.enabled=true` 时，本端点返回 **410 Gone**（`push_registration_disabled`）。正式注册走 provider 全量/增量对账 + 主动抓取 Agent Card，不再接受 HTTP push。本仓库 `application.yml` 默认开启 discovery 且 `instances=[]`；本地 E2E 取消注释 8090/8091 example。测 push 时把 `enabled` 改为 `false`。

### 从 `pull-registration` 迁移到 `deployment-discovery`（方案 B）

`rdc.pull-registration.*`（REQ-2026-004）已 `@Deprecated(forRemoval=true)`，保留 1–2 个 release 供迁移：

| 规则 | 行为 |
|------|------|
| 两路同时 `enabled=true` | **启动失败**（`RegistrationPathGuard` → `IllegalStateException`），避免双写与 PK 分裂 |
| 仅 pull `enabled=true` | 允许启动，打 **WARN** 要求迁到 `deployment-discovery` |
| 仅 deployment / 都关 | 正常 |

**为何不能直接删 pull：** `agentId` / `serviceId` / `instanceId` 身份模型不同，硬切会产生孤儿行（resolve 仍命中旧 agentId，discover 走新逻辑键）。

#### 字段映射

| pull-registration | deployment-discovery | 差异 |
|---|---|---|
| `base-url` | `base-url` | 同 |
| `tenant-id` | `tenant-id` | 同 |
| `agent-id`（必填，手填） | ❌ | **改为** `AgentIdCodec.derive(tenantId, serviceId)`（当前实现等于 `service-id`） |
| ❌ | `service-id`（必填） | **新增** |
| ❌ | `instance-id`（必填） | **新增**（pull 侧由 endpointUrl 经 `InstanceIdCodec` 派生） |
| ❌ | `deployment-version` / `readiness` | 新增 |
| `framework-type` 等 | 同名或 `binding-defaults` | 放宽 |

#### 迁移步骤（建议）

1. 把每个 `runtimes[]` 改写成 `deployment-discovery.instances[]`：补 `service-id` / `instance-id`；确认派生后的 `agentId` 是否等于你原先手填的 `agent-id`。
2. 设 `pull-registration.enabled=false`，`deployment-discovery.enabled=true`（勿同时为 true）。
3. 若旧 `agentId` ≠ 新派生值，清理或迁移存量行后再切流量，例如：

```sql
-- 审慎执行：仅示例。先备份。确认无 caller 再 pin 旧 agentId 后再删。
-- SELECT tenant_id, agent_id, service_id, instance_id FROM agent_registry_mvp WHERE ...;
-- DELETE FROM agent_registry_mvp WHERE agent_id = '<legacy-hand-pinned-id>';
```

4. 大版本再移除 `pull` 包（方案 A）。

**安全差异（迁移收益）：** deployment-discovery 走 `AgentCardFetcher`（schema / 签名 / CIDR / mTLS / 大小限制）；pull 路径仍为遗留旁路，仅在互斥下短暂保留。

---

### GET `/api/registry/instances/{tenantId}/{agentId}`

列出某 `agentId` 下所有 ONLINE/DEGRADED/DRAINING 实例（REQ-2026-006 新增；FEAT-016 起新增 DRAINING 可见 + 可选 `?contractVersion=` 过滤）。**实例路由平面**，与 Feat-015 逻辑 `POST /discover` 不同。响应为 JSON 数组，每个元素是一个 `AgentCardDto`，携带 opaque `routeHandle`（不暴露 endpointUrl / routeKey）。caller 拿到列表后自行选择实例（如 round-robin / 按 `maxConcurrency` 加权），再调 `POST /route-handle/resolve` 解析 endpoint。

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

### Feat-015（逻辑 Agent Card 注册与发现）

同进程消费方若使用逻辑发现 / 部署事实接入，关注：

#### 1. `AgentDiscoveryService` 逻辑发现

```java
DiscoveryResult discover(DiscoveryQuery query);

// 0713 HTTP / 测试常用别名（映射到同一语义）
default AgentCardDiscoveryResult discoverAgentCards(AgentCardDiscoveryQuery query);
```

- 候选类型：`DiscoveryCandidate` / `AgentCardCandidate`（逻辑字段 + `registrationStatus` / `freshness` / `lastValidatedAt`）
- **不含** 实例路由字段（`routeHandle` 仍仅出现在 FEAT-016 的 `AgentCardDto`）

#### 2. `DeploymentDiscoveryProvider`（部署发现扩展 SPI）

```java
// spi.deployment
ListDeploymentInstancesResult listInstances();
// + watch 增量事件（ADDED / MODIFIED / TERMINATING / DELETED）
```

**可插拔接线：**

1. 实现 `DeploymentDiscoveryProvider` 并注册为 Spring Bean → `ReconciliationScheduler` 自动对账并订阅 `watchInstances`。
2. `rdc.deployment-discovery.instances` 非空时，额外启用内置 **static-config** provider（可与自定义 Bean 并存）。
3. 观测实例若无匹配的 yml `instances[]` 条目，使用 `rdc.deployment-discovery.binding-defaults`（`cardPath` / `routeKey` / `frameworkType` 等），动态源不必为每个 Pod 先写 yml。

本模块**尚未**自带 K8s provider；动态源由集成方按 SPI 提供 Bean 即可接入。

#### 3. 注册中心行为要点

- `agentId` = `AgentIdCodec.derive(tenantId, serviceId)`，等于部署侧 `service-id`
- 抓取失败且**从未**成功注册 → `PENDING`，discover → `NO_MATCH`
- 已有成功快照后刷新失败 → 保留快照，`STALE_CARD`，discover 仍可 `SUCCESS`（「最后有效快照」）
- Card 内容变更：按 digest 更新逻辑目录；旧 digest 失联后标记 `REMOVED`

---

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
| V7 | `V7__feat015_registry_entry_governance.sql` | Feat-015：实例表治理/生命周期字段 |
| V8 | `V8__feat015_deployment_source_reconcile.sql` | Feat-015：部署来源对账相关列 |
| V9 | `V9__feat015_source_state_and_pending.sql` | Feat-015：`registry_source_state` + pending |
| V10 | `V10__feat015_source_snapshot_fingerprint.sql` | Feat-015：source snapshot fingerprint（跳过无变更 snapshot） |
| V11 | `V11__feat015_logical_agent_card_registration.sql` | Feat-015：逻辑目录 `agent_card_registration` + `agent_card_source_ref` |

> 已应用的 Flyway 文件名含 `feat015` / `feat_016` 前缀仅作迁移溯源；**不要随意改名**（checksum）。

## 架构/文档链接

| 文档 | 路径 | 说明 |
|---|---|---|
| Feat-015 需求范围 | `springaiascend/spring-ai-ascend/version-scope/Feat-015-agent-card-registration-and-discovery.md` | 逻辑注册/发现 MUST、最后有效快照、新鲜度语义 |
| Feat-015 0713 测试计划 | 仓库根 `Feat-015-agent-card-registration-and-discovery0713-test-plan.md` | 手工场景 + 自动化索引 |
| ADR-0160 | `docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml` | 模块架构权威（7 条决策：SPI 纯度 / JDBC 隔离 / tenant 三层隔离 / runnable 定位等） |
| L2 设计 | `architecture/L2-Low-Level-Design/agent-bus/registry-discovery-runtime-design.cn.md` | 详细设计文档（§3.2 schema / §3.3 discovery SQL） |
| L0/L1 | `architecture/L0-Top-Level-Design/` / `architecture/L1-High-Level-Design/` | 上层架构文档 |
| 4+1 baseline | `.opencode/skills/architecture-models-4plus1/examples/agent-bus-forwarding-baseline.yaml` | L1 4+1 baseline（status: reviewed） |
| 模块治理 baseline | `.harness/baselines/baseline.yaml` | L2 治理 baseline（writable 边界 frozen） |
| 项目术语 | `CONTEXT.md` | 项目术语表（AgentRegistryEntry / AgentCard / AgentCardDto 等） |

`architecture/` 与 `docs/` 是 symlink，指向 spring-ai-ascend 仓库的同名目录。
