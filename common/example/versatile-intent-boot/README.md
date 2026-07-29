# versatile-intent-boot

Versatile 意图识别工作流的部署示例模块。演示 L2 设计文档（`Feat-Func-002-versatile-intent-workflow-adapter-compatibility.md` §5.5.3 / §6.2）中描述的 **三层意图识别 + 下游业务** 链路在 Spring Boot 上的落地方式：L1（粗分类）→ L2（细分类）→ downstream（业务执行），层间通过 A2A 协议转发。

## 架构

```
Client
  │  POST /v1/query  {messages:[{user,"订酒店"}]}
  ▼
┌─────────┐  Versatile SSE   ┌──────────────┐
│  L1     │ ───────────────▶ │ Mock Versatile│  → {response_content, agent_id, intent_id}
│ (8081)  │                  └──────────────┘
│ layer1  │  three-field result
│         │ ───────────────────────────────────┐
└─────────┘                                    │
                                               ▼
                                     ┌─────────────────┐
                                     │  转发通道（二选一）│
                                     │  A. Local HTTP  │
                                     │  B. A2A Gateway │
                                     └─────────────────┘
                                               ▼
┌─────────┐  Versatile SSE   ┌──────────────┐
│  L2     │ ───────────────▶ │ Mock Versatile│  → {response_content, agent_id, intent_id}
│ (8082)  │                  └──────────────┘
│ layer2  │  three-field result → 同样转发
└─────────┘
                                               ▼
┌─────────────┐  Versatile SSE   ┌──────────────┐
│ downstream  │ ───────────────▶ │ Mock Versatile│  → {text: "酒店预订成功：..."}
│ (8083)      │                  └──────────────┘
│ 终端节点    │  无 intent-agent-mapping，直接返回业务输出
└─────────────┘
```

每个进程都内置一个 `MockVersatileController`，按 `(agentId, query)` 返回 canned SSE，因此整条链路可以在本地单机跑通，不依赖真实的 Versatile 服务。

## 两种转发模式

| 模式 | Caller 实现 | 路由依据 | 启用方式 |
|------|------------|---------|---------|
| **Local HTTP** | `DefaultRemoteAgentCaller` | `card-resolver.local-mapping`（agentCard → `http://localhost:port`） | 默认（`a2a-gateway.enabled=false`） |
| **A2A Gateway** | `A2AGatewayRemoteAgentCaller` | `a2a-gateway.base-url` + `/a2a/{agentCard}` | `a2a-gateway.enabled=true` |

Local HTTP 模式直接 POST 到目标 runtime 的 `/a2a/{agentId}`，适合本地联调；A2A Gateway 模式走外部网关，适合部署形态验证（含 token / B3 trace / X-Biz-Tag 等 header 透传）。

## Profile

| Profile | 作用 |
|---------|------|
| `layer1` | L1 配置：intents（酒店/机票/其他）+ intent-agent-mapping → L2，端口 8081 |
| `layer2` | L2 配置：intents（国内/国际酒店、国内机票）+ mapping → downstream，端口 8082 |
| `downstream` | 下游业务配置：无 intents / mapping（终端节点，可触发重新分类），端口 8083 |
| `dev` | 把 `versatile.url-template` 指向本地 mock 端点 |
| `mock-versatile` | 激活 `MockVersatileController` + 共享的 `card-resolver.local-mapping` |
| `mock-a2a-gateway` | 激活 `MockA2AGatewayController`（转发代理，端口 8084） |
| `a2a-gateway-test` | 覆盖 `a2a-gateway.enabled=true` 并指向本地 mock gateway（8084） |

## 前置依赖

- Java 17+（运行时需 17+，构建用 Maven）
- `com.openjiuwen:agent-service-app` 0.1.0
- `com.openjiuwen:agent-service-adapters-versatile` 0.1.0

## 本地联调脚本

`scripts/` 下提供两个端到端联调脚本，覆盖 L2 设计文档的全部场景。两个脚本都会自动启动所需进程、等待健康检查、发请求并断言响应/日志，最后清理进程。脚本会在 `target/` 下生成各进程的日志文件（`layer1.log`、`layer2.log`、`downstream.log`、`gateway.log`、`default-wf.log`），断言失败时可直接查看定位。

### 测试上手步骤

1. 确认环境：Java 17+ 在 `PATH` 上（`java -version` 检查），Maven 可用。
2. 在模块根目录执行任一脚本即可。首次运行若 `target/versatile-intent-boot-0.1.0.jar` 不存在，脚本会自动 `mvn -q package -DskipTests` 打包；已存在则直接复用。
3. 重复运行时建议加 `SKIP_BUILD=1` 跳过打包检查，加快启动。
4. 脚本以 `set -euo pipefail` 运行，任一断言失败即立即退出并打印失败原因与响应体；通过则结尾输出 `==> All scenarios passed.`。
5. 可通过环境变量覆盖端口与超时：`L1_PORT` / `L2_PORT` / `DOWNSTREAM_PORT` / `GATEWAY_PORT` / `DEFAULT_WF_PORT` / `HEALTH_TIMEOUT_SECONDS`。

### `scripts/local-e2e.sh` — Local HTTP 模式

跑通 L2 §6.2 的四个场景（Local HTTP 转发，`a2a-gateway.enabled=false`）：

- §6.2.1 两层识别 + 下游业务：`curl L1 "订酒店"` → `"酒店预订成功"`
- §6.2.3 显式中断：`curl L1 "中断"` → `_interrupt` payload（并断言包含 resume token `tok-123`）
- §6.2.2 重新分类：`curl downstream "重分类"` → `"重新分类：国内酒店"`
- §6.2.4 意图不明自消：`curl L1 "意图不明"` → L2 检测到 ambiguous intent（`intent_id=1`）后通过 `a2a_delegate` 自消到 default-wf，最终响应包含 `"默认工作流兜底"`

分三轮启动，每轮结束后停止全部进程再启动下一轮：

- **Round 1**：L1/L2 三字段模式（`layer1`/`layer2` profile）+ downstream 终端模式（无 `result-extractions`，直接返回最终答案）。跑场景 1（L1→L2→downstream）与场景 3（interrupt）。
- **Round 2**：downstream 三字段模式（`downstream` profile，返回指向 L1 的 `agent_id`）+ L1 终端模式。跑场景 2（downstream→L1 重新分类）。
- **Round 3**：L1/L2 三字段模式 + default-wf 终端节点（hosting `agent_L2_default`，返回兜底业务输出）。跑场景 4（L1→L2→default-wf 意图不明自消）。依赖 `application-mock-versatile.yml` 中 `agent_card_L2_default → http://localhost:8085` 的 local-mapping。

```bash
./scripts/local-e2e.sh              # 首次运行会自动 mvn package
SKIP_BUILD=1 ./scripts/local-e2e.sh # 复用已有 jar
```

### `scripts/local-e2e-a2a-gateway.sh` — A2A Gateway 模式

跑通 A2A Gateway 转发模式下的完整链路与多场景验证。启动 5 个进程：mock gateway（8084）+ L1（8081）+ L2（8082）+ downstream（8083）+ default-wf（8085），L1/L2 激活 `a2a-gateway-test` profile 走网关转发。分三轮：

```
Round 1: gateway + L1 + L2 + downstream + default-wf
         Client → L1 → gateway → L2 → gateway → downstream / default-wf
Round 2: 重启 L2（移除 default-workflow 配置）后跑 §6.2.2
Round 3: 重启 L1 + L2（开启 direct-chain），downstream 作 versatile mock 宿主 → 直链 SSE 透传
```

**Round 1** 覆盖三类场景：

- **§6.2.1 两层识别 + 下游业务**：`curl L1 "订酒店"` → 最终响应包含 `"酒店预订成功"`，并断言 gateway 日志记录两次转发 hop（`agent_card_L2_hotel`、`agent_card_biz_hotel_domestic`）、L1/L2 日志出现 `A2AGateway call agent=...`。
- **§6.2.4 意图不明自消**：`curl L1 "意图不明"` → L2 检测到 ambiguous intent（`intent_id=1`）后通过 `a2a_delegate` 自消到 default-wf，最终响应包含 `"默认工作流兜底"`，并断言 gateway 日志记录 `agent_card_L2_default` hop。
- **多轮路由缓存**：同一 `conversationId=c4-multi-turn` 连发两轮（`"订酒店"` / `"再订一晚"`），两轮响应都包含 `"酒店预订成功"`，并断言 L1 Versatile 仅被调用一次（第二轮命中缓存）、L2 调用两次、gateway 两轮各触发一次 L1→L2 与 L2→downstream hop。

**Round 2** 覆盖：

- **§6.2.2 意图不明回退 L1 重识别**：重启 L2 覆盖 `default-workflow.agent-card` 为空后，`curl L1 "意图不明"` → L2 返回 ambiguous envelope，L1 `ReclassifyServeOrchestrator` 检测后第二次调用 Versatile 直接路由到 downstream，最终响应包含 `"酒店预订成功"`，并断言 L1 至少调用 Versatile 两次、gateway 记录 `agent_card_biz_hotel_domestic` hop。

**Round 3** 覆盖 versatile **直链 SSE 透传**：

- 重启 L1/L2（`direct-chain.enabled=true`，`DirectChainVersatileAgentHandler` 截胡 `a2a_delegate` 走 gateway 隧道）；downstream 仅作 versatile mock server 宿主（`dev,mock-versatile`，不开 direct-chain）。gateway 隧道对**末端业务卡**（如 `agent_card_biz_hotel_domestic`）直接转发到 downstream 的 versatile 端点 `/v1/proj/agents/agent_biz/conversations/{cid}`（mock 内硬编码 `versatileAgentId=agent_biz`），并把 serve 协议 body 翻译成 versatile `{inputs:{query,messages}}`——业务原始 versatile SSE **不经任何业务终端 handler** 直接透传给 client；对**中间跳卡**（如 `agent_card_L2_hotel`）仍是哑隧道，原样转发到 `/v1/query`。
- `curl L1 stream=true "订酒店"` → 客户端直接收到业务原始 versatile SSE 事件，响应体断言包含 `custom_rsp_data` 与 `"酒店预订成功"`，且**不含** a2a JSON-RPC 折叠痕迹（无 `TASK_STATE_COMPLETED`）。
- 断言 gateway 日志记录两跳直链隧道：`TUNNEL agentId=agent_card_L2_hotel -> http://localhost:8082/v1/query`（中间跳，哑隧道）与 `TUNNEL agentId=agent_card_biz_hotel_domestic -> http://localhost:8083/v1/proj/agents/agent_biz/conversations/c5-direct-chain`（末端跳，versatile 直连）。
- `DirectChainAutoConfiguration` 在 `AutoConfiguration.imports` 中先于 `RouteCacheAutoConfiguration`，故 `direct-chain.enabled=true` 时 `DirectChainVersatileAgentHandler` 经 `@ConditionalOnMissingBean(AgentHandler.class)` 抢得 AgentHandler 槽位，route-cache 自动让位——L1 保留其默认 `route-cache.enabled=true`（`application-layer1.yml`）即可，直链仍生效（无需显式关闭 route-cache）。

Round 1 还统一验证 **header 透传**（gateway 日志以 INFO 记录全部入站 header）：

- `token`（出于安全仅记录 `tokenPresent=true`）/ `userId` / `versionNode`
- B3 链路：`X-B3-TraceId` / `X-B3-ParentSpanId` / `X-B3-Sampled`
- 业务标签：`X-Biz-Tag`

```bash
./scripts/local-e2e-a2a-gateway.sh              # 首次运行会自动 mvn package
SKIP_BUILD=1 ./scripts/local-e2e-a2a-gateway.sh # 复用已有 jar
```

### 选择哪个脚本

| 需求 | 脚本 |
|------|------|
| 验证 Local HTTP 转发 + 中断 + 重新分类基础链路 | `local-e2e.sh` |
| 验证 A2A Gateway 转发、header 透传、自消/重识别、多轮路由缓存、直链 SSE 透传 | `local-e2e-a2a-gateway.sh` |
| 两者都想覆盖 | 先跑 `local-e2e.sh` 再跑 `local-e2e-a2a-gateway.sh` |

## 配置

主要配置项（`openjiuwen.service` 前缀）：

```yaml
openjiuwen:
  service:
    versatile:
      url-template: http://versatile-host:3001/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
      result-node-name: AnswerNode
      intents: [...]                    # 该层识别的意图列表
      intent-agent-mapping:             # 意图 → 下游 agentCard
        intent_L1_hotel:
          - agent-card: agent_card_L2_hotel
      intent-agent-mapping-strategy: first
      result-extractions:               # 从 Versatile SSE 提取三字段
        - match: response_content
          get: /custom_rsp_data/data/response_content
        - match: agent_id
          get: /custom_rsp_data/data/agent_id
      interrupt:                        # 显式中断配置
        signal-match: need_user_input
        prompt-get: /data/question

    a2a-gateway:
      enabled: false                    # true 切换到 A2A Gateway 模式
      base-url: https://gateway.example.com
      token: ${A2A_GATEWAY_TOKEN}
      version-node: ${A2A_GATEWAY_VERSION_NODE}
      json-rpc-path: /a2a/{agentCard}
      streaming: false
      call-timeout-seconds: 60

    card-resolver:
      local-mapping:                    # Local HTTP 模式的路由表
        agent_card_L2_hotel: http://localhost:8082
        agent_card_L2_default: http://localhost:8085   # L2 ambiguous 自消到 default-wf
```

## 构建 & 测试

```bash
mvn package               # 编译 + 跑单元/集成测试
mvn -DskipTests package   # 仅打包（联调脚本会用这个 jar）
```

测试覆盖：

- `ProfileLayer1LoadTest` / `ProfileLayer2LoadTest` / `ProfileDownstreamLoadTest` — 各 profile 配置加载断言
- `A2AGatewayRemoteAgentCallerTest` / `A2AGatewayCardResolverTest` — Gateway 模式 caller 单元测试

## 模块结构

```
src/main/java/com/openjiuwen/example/versatile/intent/
├── VersatileIntentApplication.java       # Spring Boot 入口
├── VersatileIntentAutoConfiguration.java # 自动装配
├── routecache/                          # 多跳路由缓存特性
│   ├── RouteCache.java                  # 缓存 SPI 接口：get、put、invalidate
│   ├── CachedRoute.java                 # 缓存值对象：agentName、responseContent、expiresAt
│   ├── InProcessRouteCache.java         # 进程内实现：ConcurrentHashMap + TTL 惰性过期
│   ├── RouteCacheProperties.java        # @ConfigurationProperties("openjiuwen.service.versatile.route-cache")：enabled、ttl
│   ├── CachedVersatileAgentHandler.java # AgentHandler 装饰器：拦截 query/streamQuery/clearSession
│   ├── A2aDelegatePayload.java          # 工具类：提取/合成 a2a_delegate payload
│   └── RouteCacheAutoConfiguration.java # Spring Boot 自动装配：注册 RouteCache Bean 与 CachedVersatileAgentHandler Bean
└── mock/
    ├── MockVersatileController.java      # mock Versatile SSE 端点
    └── MockA2AGatewayController.java     # mock A2A Gateway（转发代理，仅 mock-a2a-gateway profile）
```

## 多轮路由缓存（Multi-Turn Route Cache）

在 L1 profile 上启用后，路由缓存会为每个 `conversationId` 记录 L1 Versatile 工作流解析出的下一跳 `agent_id`。同一会话的后续轮次将跳过 L1，直接向已缓存的 L2 agent 发出一个合成的 `a2a_delegate` 中断。

### 配置

```yaml
openjiuwen:
  service:
    versatile:
      route-cache:
        enabled: true      # 默认 false；layer1 profile 下默认开启
        ttl: 30m           # 默认 30 分钟
```

### 失效条件

缓存会在以下情况下失效：
- TTL 到期（读取时惰性淘汰）。
- 调用 `clearSession(conversationId)`（经 `A2AEnabledServeOrchestrator.resetConversation` 传递触发）。
- `ReclassifyServeOrchestrator` 检测到重新分类信号。

### 注意事项

- 缓存为进程内本地缓存（无 Redis）。缓存丢失会导致下一轮重新跑一次 L1 —— 对这一可容忍有损的优化而言是可接受的。
- 仅缓存 L1 的路由结果，L2 每轮仍然执行。
- 缓存命中时，合成的 `a2a_delegate` payload 使用空的 `responseContent`（L1 的原始输出已在 RemoteAgentCaller 转发的会话历史中）。
