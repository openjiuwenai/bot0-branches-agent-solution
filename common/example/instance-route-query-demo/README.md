# instance-route-query-demo

FEAT-016 运行时实例路由查询 — AgentDemo

## 概述

本 Demo 基于 [FEAT-016 特性文档](../../../Feat-Func-016-runtime-instance-route-query.md) 与
[设计文档](../../../FEAT-016-runtime-instance-route-query.md)，
对 `registry-discovery-center` 模块中运行时实例路由查询功能进行测试，
**发现并输出其中存在的 Bug**。

本 Demo 是一个**真正的 AgentDemo**：单进程内同时驻留「目标 Agent」与「注册中心」——
`Fe016StubAgentHandler`（`AgentHandler` SPI 的无大模型确定性 stub）被 `agent-service-app`
检测后自动在 `/a2a` 暴露 A2A JSON-RPC 端点；注册中心 seed 数据中实例 #1 的 `endpointUrl`
指向本进程。客户端链路：**查注册中心 → 解析 opaque 句柄 → 用 A2A 协议回环调用目标 Agent**，
整条链路可被端到端验证（mock 回显 `[mock-llm] echo: ...`）。

测试覆盖范围：
- 异常处理器 HTTP 状态码映射（设计文档 §7）
- 错误码命名一致性（设计文档 §7）
- 路由句柄解析（service 层：合法 / 跨租户 / 畸形 / entry 不存在 / 旧版格式）
- 实例路由查询（controller 层：查询 / 反枚举 / 端到端 resolve）
- AgentCardDto 不透明性（HD3-006）
- RouteResolution 转发层完整性（FEAT-016 v2: 6 字段）

## 目录结构

仿照 `versatile-a2a-adapter-demo`，`main` 与 `test` 双目录：

```
src/
├── main/
│   ├── java/com/openjiuwen/example/fe016/
│   │   ├── InstanceRouteQueryDemoApplication.java   # @SpringBootApplication 启动入口 + Bean 装配(含 @Bean AgentHandler)
│   │   ├── InMemoryAgentRegistryRepository.java     # 内存版仓储（多租户/多实例种子数据，实例 #1 指向本进程 A2A）
│   │   ├── agent/
│   │   │   └── Fe016StubAgentHandler.java            # 目标 Agent：AgentHandler SPI stub（mock LLM，无真实大模型）
│   │   └── client/
│   │       └── InstanceRouteQueryClientMain.java    # 独立 CLI（main()，查注册中心→resolve→A2A 调用+[BUG] 输出）
│   └── resources/
│       └── application.yml                          # 端口 18090 + 排除 DataSource 自动配置
└── test/
    └── java/com/openjiuwen/example/fe016/
        ├── InstanceRouteQueryDemoApplicationTest.java  # @SpringBootTest 上下文加载冒烟
        └── InstanceRouteQueryDemoTest.java             # 18 个 Bug 发现测试（6 个 @Nested 组）
```

装配策略：`@SpringBootApplication` 只扫描 demo 自身包，手动用 `@Bean` 装配 registry 模块的
`InstanceRouteController` / `RegistryApiExceptionHandler` / `PgMvpDiscoveryServiceImpl` /
`ThreadLocalTenantContext` / `RegistryObservabilityConfig` 与内存版 `AgentRegistryRepository`，
并装配 `@Bean AgentHandler fe016AgentHandler()`（`agent-service-app` 据此自动暴露 `/a2a`）。
registry 模块里依赖 `DataSource` 的 `RegistryRuntimeBeanConfig` / `JdbcAgentRegistryRepository` /
探活调度器均不装配，故 demo 无需 PostgreSQL / Flyway。

## 运行方式

### 前提

以下 jar 已安装到本地 .m2（离线构建所需）：

- `registry-discovery-center` 0.1.0 lib jar（被测对象，含 PostgreSQL/Flyway 排除项）
- `agent-service-app` 0.1.1.post1（提供 A2A `/a2a` 端点 + AgentHandler 自动装配）

```bash
mvn -f agent-solution/common/agent-bus/registry-discovery-center/pom.xml install -Dmaven.test.skip=true -DskipTests
```

### 运行 Bug 发现测试（18 个测试 + 上下文加载冒烟）

```bash
mvn -o -f agent-solution/common/example/instance-route-query-demo/pom.xml test
```

### 端到端运行（启动应用 + CLI 客户端）

```bash
# 1) 在一个进程启动 demo 应用（Tomcat 监听 18090）
mvn -o -f agent-solution/common/example/instance-route-query-demo/pom.xml spring-boot:run

# 2) 另起一个进程运行 CLI 客户端（默认连 http://127.0.0.1:18090）
#    通过环境变量 RDC_BASE_URL 指定目标地址
$env:RDC_BASE_URL="http://127.0.0.1:18090"
java -cp <demo-classpath> com.openjiuwen.example.fe016.client.InstanceRouteQueryClientMain
```

客户端会依次演示：GET 实例列表（取回 opaque routeHandle）→ 合法句柄 resolve（拿 endpointUrl）
→ **A2A `SendStreamingMessage` 回环调用本进程 stub Agent（验证 `[mock-llm] echo: ...`）** →
畸形句柄 resolve（复现 BUG #1）→ 跨租户 resolve（复现 BUG #2）→ 反枚举查询 →
entry 不存在解析（观察 error code 命名）。

### 端到端 HTTP 冒烟实测结果

启动应用后用 HTTP 请求直连，在真实 HTTP（非单测 mock）下复现 Bug：

| 请求 | HTTP | 设计文档 §7 期望 | 结论 |
|------|------|-----------------|------|
| `GET /api/registry/instances/tenant-A/agent-001` | 200 | 200 + opaque 列表 | OK |
| `POST /route-handle/resolve`（合法句柄） | 200 | 200 + RouteResolution | OK（opaque 句柄往返） |
| `POST /a2a`（A2A SendStreamingMessage） | 200 | 200 + SSE artifactUpdate | OK（mock 回显 `[mock-llm] echo: ...`） |
| `POST /route-handle/resolve`（畸形句柄） | **404** | 400 | **BUG #1 复现** |
| `POST /route-handle/resolve`（跨租户） | **403** | 400 | **BUG #2 复现** |

## 发现的 Bug

### BUG #1: MALFORMED_ROUTE_HANDLE → HTTP 状态码错误（404 应为 400）

| 属性 | 值 |
|------|-----|
| **严重级别** | 中 |
| **设计文档 §7** | route handle 畸形 → HTTP **400** `malformed_handle` |
| **实际行为** | HTTP **404** `MALFORMED_ROUTE_HANDLE` |
| **代码位置** | `RegistryApiExceptionHandler.mapFailureStatus()` 第 86 行 |

**根因**：`MALFORMED_ROUTE_HANDLE` 与 `ENTRY_NOT_FOUND` 被合并在同一个 `case` 分支中，
统一映射到 `HttpStatus.NOT_FOUND`（404）。但设计文档 §7 明确要求畸形 handle 返回 400。

```java
// 当前代码（有 Bug）
case "ENTRY_NOT_FOUND", "MALFORMED_ROUTE_HANDLE" -> HttpStatus.NOT_FOUND;

// 应为
case "ENTRY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
case "MALFORMED_ROUTE_HANDLE" -> HttpStatus.BAD_REQUEST;
```

**影响**：客户端收到 404 可能误认为资源不存在而放弃，而非修正请求格式后重试。

---

### BUG #2: TENANT_SCOPE_DENIED → HTTP 状态码错误（403 应为 400）

| 属性 | 值 |
|------|-----|
| **严重级别** | 中 |
| **设计文档 §7** | 跨 tenant 解析 → HTTP **400** `tenant_isolation_violation` |
| **实际行为** | HTTP **403** `TENANT_SCOPE_DENIED` |
| **代码位置** | `RegistryApiExceptionHandler.mapFailureStatus()` 第 85 行 |

**根因**：`TENANT_SCOPE_DENIED` 与 `CALLER_NOT_AUTHORIZED` 被合并在同一个 `case` 分支中，
统一映射到 `HttpStatus.FORBIDDEN`（403）。但设计文档 §7 明确要求租户隔离违规返回 400。

```java
// 当前代码（有 Bug）
case "CALLER_NOT_AUTHORIZED", "TENANT_SCOPE_DENIED" -> HttpStatus.FORBIDDEN;

// 应为
case "CALLER_NOT_AUTHORIZED" -> HttpStatus.FORBIDDEN;
case "TENANT_SCOPE_DENIED" -> HttpStatus.BAD_REQUEST;
```

**影响**：403 暗示权限不足（需申请权限），但租户隔离违规的本质是请求参数错误
（请求了错误租户的数据），客户端无法通过申请权限来解决，应返回 400 提示请求本身有问题。

---

### BUG #3: TenantIsolationViolationException 错误码命名不一致

| 属性 | 值 |
|------|-----|
| **严重级别** | 低 |
| **设计文档 §7** | error code: `tenant_isolation_violation` |
| **实际行为** | error code: `TENANT_SCOPE_DENIED` |
| **代码位置** | `TenantIsolationViolationException` 构造函数 第 28 行 |

**根因**：`failureCode` 硬编码为 `TENANT_SCOPE_DENIED`，与设计文档 §7 规定的
`tenant_isolation_violation` 不一致。同时 `PgMvpDiscoveryServiceImpl` 和
`RouteHandleCodec` 的 Javadoc 均写明 `tenant_isolation_violation`，与实际代码矛盾。

---

### BUG #4: MalformedRouteHandleException 错误码命名不一致

| 属性 | 值 |
|------|-----|
| **严重级别** | 低 |
| **设计文档 §7** | error code: `malformed_handle` |
| **实际行为** | error code: `MALFORMED_ROUTE_HANDLE` |
| **代码位置** | `MalformedRouteHandleException` 构造函数 第 15 行 |

**根因**：`failureCode` 硬编码为 `MALFORMED_ROUTE_HANDLE`，与设计文档 §7 规定的
`malformed_handle` 不一致。同时 `PgMvpDiscoveryServiceImpl` 和 `RouteHandleCodec`
的 Javadoc 均写明 `malformed_handle`，与实际代码矛盾。

---

## Bug 汇总表

| # | Bug 描述 | 位置 | 期望 | 实际 | 级别 |
|---|---------|------|------|------|------|
| 1 | MALFORMED_ROUTE_HANDLE 状态码错误 | `RegistryApiExceptionHandler:86` | HTTP 400 | HTTP 404 | 中 |
| 2 | TENANT_SCOPE_DENIED 状态码错误 | `RegistryApiExceptionHandler:85` | HTTP 400 | HTTP 403 | 中 |
| 3 | 租户隔离错误码命名不一致 | `TenantIsolationViolationException:28` | `tenant_isolation_violation` | `TENANT_SCOPE_DENIED` | 低 |
| 4 | 畸形句柄错误码命名不一致 | `MalformedRouteHandleException:15` | `malformed_handle` | `MALFORMED_ROUTE_HANDLE` | 低 |

## 测试输出示例

运行 `mvn test` 后，控制台会输出 `[BUG]` 标记的 Bug 详情和 `[OK]` 标记的通过项：

```
============================================================
[BUG #1] MALFORMED_ROUTE_HANDLE → HTTP 状态码错误
  期望（设计文档§7 + Javadoc）: HTTP 400, error code: malformed_handle
  实际: HTTP 404, error code: MALFORMED_ROUTE_HANDLE
  位置: RegistryApiExceptionHandler.mapFailureStatus() 第86行
  ...
============================================================
```
