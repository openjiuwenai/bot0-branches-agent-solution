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

## 本地联调脚本

### `scripts/local-e2e.sh` — Local HTTP 模式

跑通 L2 §6.2 的三个场景（Local HTTP 转发）：

- §6.2.1 两层识别 + 下游业务：`curl L1 "订酒店"` → `"酒店预订成功"`
- §6.2.3 显式中断：`curl L1 "中断"` → `_interrupt` payload
- §6.2.2 重新分类：`curl downstream "重分类"` → `"重新分类：国内酒店"`

分两轮启动：Round 1 跑场景 1+3（L1/L2 三字段模式 + downstream 终端），Round 2 跑场景 2（downstream 三字段模式 + L1 终端）。

```bash
./scripts/local-e2e.sh              # 首次运行会 mvn package
SKIP_BUILD=1 ./scripts/local-e2e.sh # 复用已有 jar
```

### `scripts/local-e2e-a2a-gateway.sh` — A2A Gateway 模式

跑通 L2 §6.2.1 的完整链路（A2A Gateway 转发）：

```
Client → L1 → gateway → L2 → gateway → downstream
              (8084)        (8084)
```

启动 4 个进程：mock gateway（8084）+ L1（8081）+ L2（8082）+ downstream（8083），L1/L2 激活 `a2a-gateway-test` profile 走网关转发。除了验证最终响应包含 `"酒店预订成功"`，还断言：

- gateway 日志记录两次转发 hop（`agent_card_L2_hotel`、`agent_card_biz_hotel_domestic`）
- header 透传：`token` / `userId` / `versionNode` / `X-B3-TraceId` / `X-B3-ParentSpanId` / `X-B3-Sampled` / `X-Biz-Tag`
- L1/L2 日志出现 `A2AGateway call agent=...`

```bash
./scripts/local-e2e-a2a-gateway.sh
```

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
├── a2a/
│   ├── A2AGatewayAutoConfiguration.java  # A2A Gateway caller 装配（@ConditionalOnProperty enabled=true）
│   ├── A2AGatewayRemoteAgentCaller.java  # Gateway 模式 caller（走 SDK Client → /a2a/{agentId}）
│   ├── A2AGatewayCardResolver.java       # 解析 agentCard → gatewayBaseUrl + "/a2a/" + agentId
│   ├── A2AGatewayProperties.java         # gateway 配置（token/base-url/version-node/...）
│   ├── LocalMappingCardRegistrar.java    # Local HTTP 模式路由注册
│   ├── LocalMappingProperties.java
│   ├── LocalHttpRemoteAgentCaller.java   # Local HTTP caller（@ConditionalOnProperty enabled=false）
│   └── ForwardedServeRequests.java       # 把 response_content 作为 assistant message 追加到转发请求
└── mock/
    ├── MockVersatileController.java      # mock Versatile SSE 端点
    └── MockA2AGatewayController.java     # mock A2A Gateway（转发代理，仅 mock-a2a-gateway profile）
```

## 前置依赖

- Java 17+（运行时需 17+，构建用 Maven）
- `com.openjiuwen:agent-service-app` 0.1.0
- `com.openjiuwen:agent-service-adapters-versatile` 0.1.0
