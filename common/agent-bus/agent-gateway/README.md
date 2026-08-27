# agent-gateway — A2A 入口治理 + 路由转发网关

agent-gateway 是 Agent 解决方案的 client-facing 入口：接收 A2A JSON-RPC（`POST /a2a`），完成治理（鉴权 / 租户 / 校验 / 幂等 / 审计）后按 `path-mode` 转发——**DIRECT** 直连 runtime（HTTP/SSE），或 **BUS** 经转发总线两跳到 runtime。独立可部署的 Spring Boot 单元；`registry-discovery-center` 与 runtime 经六边形端口（`RdcRouteClient` / `AgentRuntimeClient`）以 HTTP/SSE 触达，**无进程内耦合**。

## 项目概述

| 维度 | 说明 |
|---|---|
| 职责 | A2A 入口治理（auth / tenant / validate / idempotency / audit）+ 路由转发（DIRECT 直连 / BUS 经总线） |
| 主入口 | `com.openjiuwen.gateway.GatewayApplication`（`@SpringBootApplication`） |
| HTTP 入口 | `POST /a2a`（A2A JSON-RPC：`SendMessage` / `SendStreamingMessage` 等） |
| 路由模式 | `direct`（默认）/ `bus`（经 event-bus 两跳） |
| 下游触达 | `registry-discovery-center`（`RdcRouteClient`，HTTP）、runtime（`AgentRuntimeClient`，HTTP/SSE）——端口注入，无 in-process 依赖 |
| 配置命名空间 | `gateway.*`（网关自身）+ `agent-bus.*`（BUS 模式，复用 event-bus SDK） |
| Java 版本 | 21 |
| Spring Boot | 4.0.5 |

## 制品

agent-gateway 产出两个 jar：

| 制品 | 用途 |
|---|---|
| `agent-gateway-0.1.0.jar` | 可执行 fat-jar（`spring-boot:repackage`）。`java -jar` 直跑 DIRECT 模式 |
| `agent-gateway-0.1.0-lib.jar`（`classifier=lib`） | 纯库 jar（排除运行配置）。供 launcher 模块依赖，把 gateway 类放到编译/运行 classpath |

> fat-jar 的 `BOOT-INF/` 会隐藏类，故 BUS 模式（需 gateway + `event-bus-sdk` 同 classpath）由 launcher 模块依赖 `lib` jar 组装。

## 快速启动

### 构建

```bash
mvn -f common/agent-bus/agent-gateway/pom.xml install -Dmaven.test.skip=true
# 产出 target/agent-gateway-0.1.0.jar（exec）+ agent-gateway-0.1.0-lib.jar（lib，入 .m2 供 launcher）
```

### DIRECT 模式运行

注入本地联调凭据（鉴权 fail-closed：未配置时所有 `/a2a` 请求返回 401 `AUTH_MISSING`/`AUTH_INVALID`）：

```bash
java -jar common/agent-bus/agent-gateway/target/agent-gateway-0.1.0.jar \
  --gateway.test-credential.token=mock-token \
  --gateway.test-credential.principalId=test-principal \
  --gateway.test-credential.tenantId=tenant-1
# gateway.path-mode=direct：经 RDC 解析路由后直连 runtime /a2a（HTTP/SSE），不经总线
```

成功标志：`Started GatewayApplication` + `Tomcat started on port 8080`。端口冲突用 `--server.port=NNNN` 覆盖。

> `mock-token` 仅用于本地联调；生产必须接入正式 IdP / `CredentialDirectory`，不得发布真实 token。完整业务响应仍需 RDC(:8092) 与目标 runtime 就绪。

### BUS 模式运行

BUS 模式需 `event-bus-sdk` 在运行时 classpath（提供 caller 角色的 `requestProducer` / `responseConsumer` / `forwardingOutbox`）。由 launcher 模块（`common/example/agent-gateway-demo`）组装 fat-jar：依赖 `agent-gateway`（lib）+ `event-bus-sdk`，内嵌 `application-bus.yml`，`spring.profiles.active=bus`。

```bash
# 1) 先 install event-bus reactor（落 spi/sdk 到 .m2）
cd common/agent-bus/event-bus && mvn install -DskipTests

# 2) 起 relay + RocketMQ + Postgres（见 event-bus/README「快速启动」）

# 3) 构建 launcher fat-jar
mvn -f common/example/agent-gateway-demo/pom.xml clean package -Dmaven.test.skip=true

# 4) 运行
java -DGATEWAY_TEST_TENANT=tenant-a \
     -DAGENT_BUS_NAMESERVER=localhost:9876 \
     -DSPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agentbus \
     -DSPRING_DATASOURCE_USERNAME=agentbus -DSPRING_DATASOURCE_PASSWORD=agentbus \
     -jar common/example/agent-gateway-demo/target/agent-gateway-demo-0.1.0.jar \
     --spring.profiles.active=bus
```

成功标志：`Started GatewayApplication` + `SUBSCRIBE responseConsumer(filter targetServiceId=gateway-01)`。

> **联合数据库拓扑**：event-bus relay 用 `localhost:5432/agentbus`；RDC 用 `localhost:5433/agent_rdc`（见 RDC README，宿主 `5433` 避免与 event-bus 的 `5432` 冲突）。本 launcher 的 `spring.datasource` 指向 event-bus 库（outbox），RDC 库由 RDC 进程独立连接。

> 改 gateway 代码后：先 `mvn install` 重装 lib jar，**再** `clean package` 重打 launcher fat-jar（no-clean 的 `package` 是 no-op，会跑旧内嵌 lib）。launcher fat-jar 若被运行中的 gateway 进程锁，先停掉再重打。

## 配置

```yaml
gateway:
  path-mode: direct             # direct | bus
  rdc:
    base-url: http://127.0.0.1:8092   # RDC 路由解析
  test-credential:              # 本地联调凭据
    token: mock-token
    principalId: test-principal
    tenantId: tenant-1
  bus:                           # BUS 模式参数
    accept-window-ms: 30000      # accept 超时 → UNKNOWN
    response-window-ms: 60000    # response 超时 → ACCEPTED_WITH_TASK
    stream-first-frame-deadline-ms: 10000   # SSE 首帧超时 → STREAM_DEADLINE_EXCEEDED

agent-bus:                       # BUS 模式复用 event-bus SDK 命名空间（详见 event-bus/README）
  nameserver: localhost:9876
  namespace: ascend-prod
  tenant: tenant-a
  gateway-service-id: gateway-01
  event-bus-service-id: eventbus-01
  role:
    caller:
      enabled: true              # BUS 模式 caller 角色
  reliability:
    enabled: true                # JdbcForwardingOutbox（需 spring.datasource）
```

## A2A 调用

`POST /a2a`，每个请求必须携带 `Authorization: Bearer <token>`（与 DIRECT Quick Start 的 `gateway.test-credential.token` 一致；鉴权在路由/校验之前，fail-closed）。A2A JSON-RPC body：

```json
{
  "jsonrpc": "2.0", "id": "1", "method": "SendMessage",
  "params": {
    "message": { "role": "ROLE_USER", "messageId": "m1", "parts": [{"kind":"text","text":"hi"}] },
    "metadata": { "agentId": "travel-hotel" }
  }
}
```

完整 curl（与上述 `mock-token` 配套）：

```bash
curl -i -H 'Authorization: Bearer mock-token' -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"role":"ROLE_USER","messageId":"m1","parts":[{"kind":"text","text":"hi"}]},"metadata":{"agentId":"travel-hotel"}}}' \
  http://127.0.0.1:8080/a2a
```

预期结果（鉴权在前，不依赖 RDC/runtime 是否就绪）：

- 缺 `Authorization` → `401 AUTH_MISSING`
- 错误 token（与配置不一致）→ `401 AUTH_INVALID`
- 正确 `mock-token` → 不再返回认证错误；此后再排查 RDC/runtime/路由

- `metadata.agentId` 必填（C2 移除默认 Agent 兜底：DIRECT 与 BUS 创建类均按 `ctx.agentId()` 路由，缺/空 → 400 `VALIDATION_AGENT_ID`）
- `message.role` 可省（默认 `ROLE_USER`）；显式给须是 `ROLE_USER` / `ROLE_AGENT` / `ROLE_UNSPECIFIED`
- `parts[].kind` 必须是 `"text"`
- 流式用 `SendStreamingMessage`（DIRECT 经 runtime `/a2a` SSE；BUS 经 `SubscribeToTask` SSE）

响应五态：`COMPLETED_RESPONSE` / `ACCEPTED_WITH_TASK` / `UNKNOWN` / `REJECTED` / `FAILED`（`INPUT_REQUIRED` 带 `taskId`）。
