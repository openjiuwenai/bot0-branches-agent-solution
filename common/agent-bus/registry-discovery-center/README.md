# registry-discovery-center — Agent Registry & Discovery Center

可独立启动的 Spring Boot 应用，提供 Agent **逻辑 Card 注册/发现**、**运行时实例路由**与健康探活；PostgreSQL + RLS 多租户隔离。

> Maven artifactId：`registry-discovery-center`（曾用名 `agent-rdc`）。兼容保留：包名 `com.openjiuwen.rdc`、入口 `AgentRdcApplication`、库/账号 `agent_rdc`、打包名 `agent-rdc-0.1.0.jar`。

| 项 | 说明 |
|---|---|
| 入口 | `com.openjiuwen.rdc.AgentRdcApplication` |
| 端口 | `8092` |
| Java / Boot | 21 / 4.0.5 |
| 架构 | [ADR-0160](docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml) |

### 两平面

| 平面 | HTTP | 数据 |
|---|---|---|
| 逻辑 Agent Card（Feat-015） | `POST /api/registry/discover` | `agent_card_registration`（无 `routeHandle` / `endpointUrl`） |
| 运行时实例路由（FEAT-016） | `GET /instances...`、`POST /route-handle/resolve` | `agent_registry_mvp`（opaque `routeHandle`） |
| 正式注册 | 非 push：`DeploymentDiscoveryProvider` + 可选 yml 静态实例 | `rdc.deployment-discovery.enabled=true` 时 `POST /register` → **410** |

---

## 快速启动

### 1. PostgreSQL

库 `agent_rdc`，账密 `agent_rdc/agent_rdc`（账号需为库 owner，以便建 RLS）。

> **端口选择**：RDC 应用默认连 `localhost:5432/agent_rdc`。但 event-bus 编排已占用宿主 `5432`（库 `agentbus`）；与 event-bus 同机并跑（Gateway BUS 场景）时，RDC 用宿主 `5433` 并在第 2 步覆盖 JDBC URL，避免两个 PostgreSQL 争抢 `5432`。RDC 单独运行时可用 `5432`（无需覆盖）。

```bash
docker run -d --name registry-discovery-center-pg \
  -p 5433:5432 \
  -e POSTGRES_DB=agent_rdc \
  -e POSTGRES_USER=agent_rdc \
  -e POSTGRES_PASSWORD=agent_rdc \
  postgres:16
```

### 2. 构建与运行

本模块为独立 Maven 工程（上步用宿主 `5433` 时须覆盖 JDBC URL，否则连不上库）：

```bash
cd agent-solution/common/agent-bus/registry-discovery-center
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/agent_rdc \
SPRING_DATASOURCE_USERNAME=agent_rdc SPRING_DATASOURCE_PASSWORD=agent_rdc \
mvn spring-boot:run
# 或
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/agent_rdc \
mvn package && java -jar target/agent-rdc-0.1.0.jar
```

成功标志：`Started AgentRdcApplication` + Flyway（干净库 **V2–V12**）+ `Tomcat started on port 8092`。

### 3. 测试

```bash
mvn -DjunitParallel=false test
```

（macOS 建议关 JUnit 类并行，减轻 `kern.sysv.shmmni` 压力；测试使用 Zonky embedded-postgres。）

联机发现过滤 / 降级冒烟见配套 example（本机 PG，无 Docker）：

```bash
cd ../../example/registry-discovery-center-demo/discovery-degrade
DEGRADE_L1_YES=1 ./run-online.sh   # 过滤 + L1(切断 agent_rdc) + L2(Gateway)
./run-online.sh --filter-only      # 只要过滤
```

### 4. 生产覆盖

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://prod-pg:5432/agent_rdc
export SPRING_DATASOURCE_USERNAME=prod_user
export SPRING_DATASOURCE_PASSWORD=********
java -jar agent-rdc-0.1.0.jar
```

---

## 配置要点

```yaml
rdc:
  deployment-discovery:
    enabled: true          # true → push /register 返回 410
    reconcile-interval: 30s
    instances: []          # 本地 E2E 可取消注释 application.yml 内 example
  pull-registration:
    enabled: false         # 已废弃；勿与 deployment-discovery 同时为 true
  registry:
    security:
      caller-allowlist: {}
    card-fetch:
      mutual-tls: false
      verify-signatures: false
      target-cidrs: []     # 空=不限制（启动 WARN）；生产建议收紧
```

---

## API 速查

Base：`http://localhost:8092`

### 逻辑发现 — `POST /api/registry/discover`

读逻辑目录，返回去重 Card 候选（**无**实例路由字段）。

```json
{
  "context": { "tenantId": "tenant-A", "callerRef": "gateway", "requestId": "req-1" },
  "agentId": "billing-svc",
  "limit": 5,
  "constraints": { "contractVersion": "1.0.0", "requiredSkillTags": ["commerce"] }
}
```

- `context.tenantId` 必填；`agentId` / `serviceId` / `a2aSkillId` 至少一项  
- `outcome`：`SUCCESS` | `NO_MATCH`；新鲜度：`FRESH` / `STALE_CARD` / `STALE_SOURCE`

### 实例列表

| 方法 | 说明 |
|---|---|
| `GET /api/registry/instances/{tenantId}/{agentId}` | 按 agent；可选 `?contractVersion=` |
| `GET /api/registry/instances/by-service/{tenantId}/{serviceId}` | 按逻辑服务 |
| `GET /api/registry/instances/by-capability/{tenantId}/{capability}` | 按 capability |

响应为 `AgentCardDto[]`（含 `routeHandle`，**不**暴露 `endpointUrl`）。`health`：`ONLINE` / `DEGRADED` / `DRAINING`。空列表 → `200 []`。

### 解析路由 — `POST /api/registry/route-handle/resolve`

```json
{ "routeHandle": "v2:...", "tenantId": "tenant-A" }
```

→ `RouteResolution { instanceId, endpointUrl, routeKey, contractVersion }`  
仅接受 **`v2:`** handle；错误：`400 malformed_handle` / `400 tenant_isolation_violation` / `404 entry_not_found`。

### 注册 / 注销

| 方法 | 说明 |
|---|---|
| `POST /api/registry/register` | upsert 实例；**deployment-discovery 开启时返回 410** |
| `DELETE .../deregister/{tenantId}/{agentId}` | 删该 agent 下全部实例 |
| `DELETE .../deregister/{tenantId}/{agentId}/{serviceId}` | 删 triple 下全部实例 |
| `DELETE .../deregister/{tenantId}/{agentId}/{serviceId}/{instanceId}` | 删单一实例 |

`register` 请求体关键字段：`tenantId`、`agentId`、`agentName`、`frameworkType`、`routeKey`、`contractVersion`、`capabilityVersion`、`endpointUrl`；`serviceId` 可选（默认可从 URL host 派生）；`instanceId` 由服务端从 host-port 派生，请求体传入会被忽略。

可选头：`X-Caller-Ref`、`traceparent` / `X-Trace-Id`。

---

## 相关文档

| 文档 | 路径 |
|---|---|
| Feat-015 需求 | `Feat-015-agent-card-registration-and-discovery.md` |
| ADR-0160 | `docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml` |
| L2 设计 | `architecture/L2-Low-Level-Design/agent-bus/registry-discovery-runtime-design.cn.md` |
| L0 / L1 | `architecture/L0-Top-Level-Design/` / `architecture/L1-High-Level-Design/` |
| 术语 | `CONTEXT.md` |
