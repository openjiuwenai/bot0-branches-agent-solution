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
| `layer2-flight` | L2 机票配置：intents（国内机票）+ mapping → downstream，端口 8086 |
| `downstream` | 下游业务配置：无 intents / mapping（终端节点），端口 8083 |
| `dev` | 把 `versatile.url-template` 指向本地 mock 端点 |
| `mock-versatile` | 激活 `MockVersatileController` + 共享的 `card-resolver.local-mapping` |
| `mock-a2a-gateway` | 激活 `MockA2AGatewayController`（转发代理，端口 8084） |
| `a2a-gateway-test` | 覆盖 `a2a-gateway.enabled=true` 并指向本地 mock gateway（8084） |
| `llm-intent` | 激活 `LlmIntentAgentHandler`，支持真实 LLM 意图分类（与 `VersatileAgentHandler` 二选一） |

## 前置依赖

- Java 17+（运行时需 17+，构建用 Maven 3.9+）
- `com.openjiuwen:agent-service-app` 0.1.0 — 来自外部 [agent-runtime-java](https://gitcode.com/openJiuwen/agent-runtime-java) 仓库
- `com.openjiuwen:agent-service-adapters-versatile` 0.1.0 — 来自本仓 `common/agent-runtime-ext-java` 模块
- `com.openjiuwen:agent-core-java` — LLM 意图 demo 中 `LlmIntentClient` 通过此模块访问 LLM
- `protobuf-java:4.33.2` — 在 `<dependencyManagement>` 中固定版本，解决 agent-core-java→milvus 与 a2a SDK 的 protobuf 版本冲突

> ⚠️ 这两个制品**必须先 `mvn install` 到本地 Maven 仓库（`~/.m2/repository`）**，否则本模块会编译失败（报大量 `cannot find symbol`，如 `RemoteAgentCaller`、`RemoteAgentCardResolver`、`A2aPushNotificationCallback` 等）。联调脚本只负责打包本模块自身 jar，**不会**构建这些前置依赖。

> ⚠️ 这两个制品**必须先 `mvn install` 到本地 Maven 仓库（`~/.m2/repository`）**，否则本模块会编译失败（报大量 `cannot find symbol`，如 `RemoteAgentCaller`、`RemoteAgentCardResolver`、`A2aPushNotificationCallback` 等）。联调脚本只负责打包本模块自身 jar，**不会**构建这些前置依赖。

### 首次构建（全新环境）

本模块依赖内部制品，**必须**按以下顺序构建并安装到本地仓库（`agent-runtime-java` 依赖 `agent-core-java`，故 core 必须先于 runtime 安装）。完整顺序见仓库根 [CONTRIBUTING.md](../../../../CONTRIBUTING.md)「Development Setup」。

1. **构建外部 agent 执行核心**（提供 `agent-core-java`，被 runtime 依赖）：

   ```bash
   cd <agent-core-java>             # openJiuwen/agent-core-java 仓库
   mvn clean install -DskipTests
   ```

2. **构建外部 runtime 核心**（提供 `agent-service-app`、`agent-runtime-java`）：

   ```bash
   cd <agent-runtime-java>          # openJiuwen/agent-runtime-java 仓库
   mvn clean install -DskipTests
   ```

3. **构建本仓 extension 模块**（提供 `agent-service-adapters-versatile`）：

   ```bash
   mvn -f common/agent-core-ext-java/pom.xml clean install
   mvn -f common/agents/pom.xml clean install
   mvn -f common/agent-runtime-ext-java/pom.xml clean install
   ```

   > 注意：`agent-runtime-ext-java` 聚合构建时，`agent-service-adapters-agentcore-ext` 等无关子模块可能编译失败，**只要 `agent-service-adapters-versatile` 构建成功即可**，本模块所需依赖不受影响。

4. 验证依赖已安装：

   ```bash
   ls ~/.m2/repository/com/openjiuwen/agent-service-app/0.1.0/agent-service-app-0.1.0.jar
   ls ~/.m2/repository/com/openjiuwen/agent-service-adapters-versatile/0.1.0/agent-service-adapters-versatile-0.1.0.jar
   ```

   两个文件都存在即可继续后续步骤；若不存在，回到步骤 1–3 重新构建对应模块。

### 后续运行

- 仅修改本模块代码时，前置依赖已存在于本地仓库，可直接运行联调脚本（脚本会自动 `mvn package` 本模块）。
- 若修改了 `agent-runtime-java`、`agent-core-java` 或本仓 extension 模块，需重新 `mvn install` 对应模块后再运行脚本。

## 本地联调脚本

`scripts/` 下提供两个端到端联调脚本，覆盖 L2 设计文档的全部场景。两个脚本都会自动启动所需进程、等待健康检查、发请求并断言响应/日志，最后清理进程。脚本会在 `target/` 下生成各进程的日志文件（`layer1.log`、`layer2.log`、`downstream.log`、`gateway.log`、`default-wf.log`），断言失败时可直接查看定位。

### 测试上手步骤

1. 确认环境：Java 17+ 在 `PATH` 上（`java -version` 检查），Maven 可用。
2. **完成「首次构建」**：按上文「前置依赖 → 首次构建」安装 `agent-service-app` 与 `agent-service-adapters-versatile` 到本地 Maven 仓库。脚本启动时会先校验这两个制品是否存在，缺失则直接报错并给出构建指引。
3. 在模块根目录执行任一脚本即可。脚本只负责打包本模块自身 jar：首次运行若 `target/versatile-intent-boot-0.1.0.jar` 不存在，会自动 `mvn -q package -DskipTests` 打包；已存在则直接复用。**前置依赖不在脚本构建范围内**。
4. 重复运行时建议加 `SKIP_BUILD=1` 跳过打包检查，加快启动。
5. 脚本以 `set -euo pipefail` 运行，任一断言失败即立即退出并打印失败原因与响应体；通过则结尾输出 `==> All scenarios passed.`。
6. 可通过环境变量覆盖端口与超时：`L1_PORT` / `L2_PORT` / `DOWNSTREAM_PORT` / `GATEWAY_PORT` / `DEFAULT_WF_PORT` / `HEALTH_TIMEOUT_SECONDS`。

### `scripts/local-e2e.sh` — Local HTTP 模式

跑通 L2 §6.2 的三个场景（Local HTTP 转发，`a2a-gateway.enabled=false`）：

- §6.2.1 两层识别 + 下游业务：`curl L1 "订酒店"` → `"酒店预订成功"`
- §6.2.3 显式中断：`curl L1 "中断"` → `_interrupt` payload（并断言包含 resume token `tok-123`）
- §6.2.4 意图不明自消：`curl L1 "意图不明"` → L2 检测到 ambiguous intent（`intent_id=1`）后通过 `a2a_delegate` 自消到 default-wf，最终响应包含 `"默认工作流兜底"`

分两轮启动，每轮结束后停止全部进程再启动下一轮：

- **Round 1**：L1/L2 三字段模式（`layer1`/`layer2` profile）+ downstream 终端模式（无 `result-extractions`，直接返回最终答案）。跑场景 1（L1→L2→downstream）与场景 3（interrupt）。
- **Round 2**：L1/L2 三字段模式 + default-wf 终端节点（hosting `agent_L2_default`，返回兜底业务输出）。跑场景 4（L1→L2→default-wf 意图不明自消）。依赖 `application-mock-versatile.yml` 中 `agent_card_L2_default → http://localhost:8085` 的 local-mapping。

```bash
./scripts/local-e2e.sh              # 首次运行会自动 mvn package
SKIP_BUILD=1 ./scripts/local-e2e.sh # 复用已有 jar
```

### `scripts/local-e2e-a2a-gateway.sh` — A2A Gateway 模式

跑通 A2A Gateway 转发模式下的完整链路与多场景验证。启动 5 个进程：mock gateway（8084）+ L1（8081）+ L2（8082）+ downstream（8083）+ default-wf（8085），L1/L2 激活 `a2a-gateway-test` profile 走网关转发。分两轮：

```
Round 1: gateway + L1 + L2 + downstream + default-wf
         Client → L1 → gateway → L2 → gateway → downstream / default-wf
         跑 §6.2.1 + §6.2.4 自消 + 多轮路由缓存
Round 2: 重启 L1 + L2（开启 direct-chain），downstream 作 versatile mock 宿主 → 直链 SSE 透传
```

### `scripts/local-e2e-llm-intent.sh` — LLM 意图驱动演示

真实 LLM 驱动的意图识别 + 真实 DeepAgent downstream。LLM 参数从 `$MODULE_DIR/.env` 读取（见 `.env.example`，真实环境变量优先；`DEEPSEEK_*` 未设时默认与 `LLM_*` 相同）：`LLM_API_KEY`/`LLM_BASE_URL`/`LLM_MODEL`（L1/L2 分类用，OpenAI 兼容）与 `DEEPSEEK_API_KEY`/`DEEPSEEK_BASE_URL`/`DEEPSEEK_MODEL`（两个 Agent B 业务 agent 用）。启动 6 个进程：gateway + L1 + L2_hotel + L2_flight + Agent B hotel + Agent B flight，演示三场景（单 conversation_id）：

- **场景 A**：订酒店多轮 ask-user——`订酒店` → Agent B hotel 追问"定什么地方" → `上海` → 追问"订哪天"。
- **场景 B**：跨工作流跳转买机票——L1 据会话历史识别出话题切换，直接路由到 L2_flight → Agent B flight 追问"去哪里"。
- **场景 C**：回跳继续订酒店——`继续订酒店` → L2_hotel 恢复 shadow task 续传，Agent B hotel 追问"住几天"。

对话剧本（单 `conversation_id`，真实 LLM 文案非精确）：

```
用户：订酒店        智能体：定什么地方？
用户：上海          智能体：订哪天？
用户：买机票        智能体：去哪里？（切换意图）
用户：继续订酒店    智能体：好的，住几天？
```

跨工作流跳转机制说明：L1 在本演示中**关闭路由缓存**（`route-cache.enabled=false`），改由 `LlmIntentAgentHandler` 按 `conversationId` 在内存中累积用户输入历史，每轮把"历史+当前消息"喂给 LLM 分类。这样 L1 能识别 `上海`（酒店目的地回答）、`继续订酒店`（回跳）等后续消息的意图，并正确把 `买机票` 路由到 L2_flight。L2 对同一会话的 pending 业务任务走 A2A shadow-task 续传（§6.2.4），实现同领域多轮与回跳恢复；新领域（flight）首次到达无 pending 任务，走分类。

> 设计取舍：原 spec §8 设想"路由缓存命中错领域 L2 → L2 返 ambiguous → L1 重识别"实现跨工作流跳转，但运行时 shadow-task 续传会抢占 L2 重分类、使重识别无法触发，实测不可行。故改用"缓存 OFF + handler 端历史"达成同一目标（跨工作流跳转）。（reclassify 特性已移除。）

新增 `llm-intent` profile（激活 `LlmIntentAgentHandler`，与 `VersatileAgentHandler` 二选一，即意图对接 SPI）与 `layer2-flight` profile（机票专属 L2，端口 8086）。mock gateway 对末端业务卡（`agent_card_biz_*`）走 A2A 原生透传（`openjiuwen.example.mock-a2a-gateway.passthrough-cards`），把 JSON-RPC 原样转发到 Agent B 的 `/a2a/`，保留 `INPUT_REQUIRED` 与 shadow-task 恢复。断言为结构/关键字（非精确串），依赖真实 LLM 故不进 CI。

> LLM 配置参考 `apiconfig.json`（智谱 GLM `glm-5.2`，OpenAI 兼容端点）。`LLM_API_KEY` 等敏感信息仅经环境变量传入，绝不提交到代码；`DEEPSEEK_*` 未设时默认与 `LLM_*` 相同。

```bash
cp .env.example .env                            # 填入真实 LLM 密钥（.env 已 gitignore）
./scripts/local-e2e-llm-intent.sh              # 首次运行会自动 mvn package
SKIP_BUILD=1 ./scripts/local-e2e-llm-intent.sh # 复用已有 jar
```

### `scripts/cli-llm-intent.py` — LLM 意图演示 CLI 客户端

纯标准库 Python CLI（无需 `httpx`/`a2a-sdk`，`python3` 直接可跑），参考 a2a-samples 的 `helloworld/test_client.py` 结构：获取并展示 agent card、发送（流式/非流式）消息、重放 `local-e2e-llm-intent.sh` 的三场景、进入交互式会话。`start` 子命令可一键拉起整套演示进程栈（gateway + L1 + L2_hotel + L2_flight + Agent B hotel/flight）并在退出时清理。

**LLM 参数经 `.env` 传入**：复制 `.env.example` 为 `.env` 并填入 `LLM_API_KEY`/`LLM_BASE_URL`/`LLM_MODEL`（`DEEPSEEK_*` 未设时默认与 `LLM_*` 相同）。CLI 与 `local-e2e-llm-intent.sh` 均读取 `.env`；真实环境变量优先于 `.env`。`.env` 已被 `.gitignore` 忽略，真实密钥绝不提交。

```bash
cp .env.example .env                                       # 填入真实 LLM 密钥（.env 已 gitignore）
python3 scripts/cli-llm-intent.py start                    # 读 .env 拉起进程栈 → 交互式会话
python3 scripts/cli-llm-intent.py start --scenario all     # 拉起栈 → 顺序重放 A→B→C → 退出清理
python3 scripts/cli-llm-intent.py start --no-build         # 复用已有 jar，跳过 mvn package
python3 scripts/cli-llm-intent.py card                     # 展示 L1 agent card（兼作连通性自检，需栈已启动）
python3 scripts/cli-llm-intent.py scenario a               # 重放场景 A
python3 scripts/cli-llm-intent.py scenario all             # 顺序重放 A → B → C（单 conversation_id）
python3 scripts/cli-llm-intent.py chat                     # 交互式会话（非流式）
python3 scripts/cli-llm-intent.py chat --stream            # 交互式会话（SSE 流式）
python3 scripts/cli-llm-intent.py --base-url http://host:8081 chat   # 指向远端 L1
# 交互中：`exit` 退出、`card` 显示 agent card、`reset` 切换新 conversation_id
```

`start` 之外的子命令（`card`/`scenario`/`chat`）假定进程栈已运行（可由 `start` 或 shell 脚本启动）。`--conversation-id`（默认 `c-llm-demo`）、`--user-id`（默认 `u-42`）跨轮复用，与 shell 脚本的 `send_q` 请求体一致（`X-Biz-Tag: llm-demo`）。

### 选择哪个脚本

| 需求 | 脚本 |
|------|------|
| 验证 Local HTTP 转发 + 中断 + 意图不明自消基础链路 | `local-e2e.sh` |
| 验证 A2A Gateway 转发、header 透传、自消、多轮路由缓存、直链 SSE 透传 | `local-e2e-a2a-gateway.sh` |
| 验证 LLM 意图驱动 + 真实 DeepAgent downstream | `local-e2e-llm-intent.sh` |
| 用 CLI 交互式/脚本式驱动上述 LLM 演示场景 | `cli-llm-intent.py` |
| 两者都想覆盖 | 先跑 `local-e2e.sh` 再跑 `local-e2e-a2a-gateway.sh` |
| 全部覆盖 | 按顺序运行全部三个脚本 |

**Round 1** 覆盖三类场景：

- **§6.2.1 两层识别 + 下游业务**：`curl L1 "订酒店"` → 最终响应包含 `"酒店预订成功"`，并断言 gateway 日志记录两次转发 hop（`agent_card_L2_hotel`、`agent_card_biz_hotel_domestic`）、L1/L2 日志出现 `A2AGateway call agent=...`。
- **§6.2.4 意图不明自消**：`curl L1 "意图不明"` → L2 检测到 ambiguous intent（`intent_id=1`）后通过 `a2a_delegate` 自消到 default-wf，最终响应包含 `"默认工作流兜底"`，并断言 gateway 日志记录 `agent_card_L2_default` hop。
- **多轮路由缓存**：同一 `conversationId=c4-multi-turn` 连发两轮（`"订酒店"` / `"再订一晚"`），两轮响应都包含 `"酒店预订成功"`，并断言 L1 Versatile 仅被调用一次（第二轮命中缓存）、L2 调用两次、gateway 两轮各触发一次 L1→L2 与 L2→downstream hop。

**Round 2** 覆盖 versatile **直链 SSE 透传**：

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
| 验证 Local HTTP 转发 + 中断 + 意图不明自消基础链路 | `local-e2e.sh` |
| 验证 A2A Gateway 转发、header 透传、自消、多轮路由缓存、直链 SSE 透传 | `local-e2e-a2a-gateway.sh` |
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

  example:
    mock-a2a-gateway:
      passthrough-cards: [...]          # A2A 透传卡列表（Set），默认空
      routing: [...]                    # 路由覆盖配置（Map），默认空
      # llm-intent demo 用途：把 biz 卡 A2A 透传到真实 Agent B（18191/18192）
      # 示例：passthrough-cards: agent_card_biz_hotel_domestic,agent_card_biz_flight_domestic
```

## 构建 & 测试

> 首次构建前，请先按「前置依赖 → 首次构建」安装 `agent-service-app` 与 `agent-service-adapters-versatile`，否则编译会报 `cannot find symbol`。

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

### 注意事项

- 缓存为进程内本地缓存（无 Redis）。缓存丢失会导致下一轮重新跑一次 L1 —— 对这一可容忍有损的优化而言是可接受的。
- 仅缓存 L1 的路由结果，L2 每轮仍然执行。
- 缓存命中时，合成的 `a2a_delegate` payload 使用空的 `responseContent`（L1 的原始输出已在 RemoteAgentCaller 转发的会话历史中）。
