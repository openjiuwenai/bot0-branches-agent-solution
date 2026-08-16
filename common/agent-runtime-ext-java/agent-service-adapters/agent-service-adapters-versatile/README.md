# agent-service-adapters-versatile

Agent service adapter that forwards A2A/JSON-RPC service requests to a remote
**Versatile**-compatible HTTP/SSE workflow endpoint, and maps the streamed
response back into the runtime's `QueryChunk` / `QueryResponse` SPI.

这是一个可被 `agent-runtime-java` 加载的 `AgentHandler` 实现：上层 runtime（A2A 入口、
编排器等）按 `AgentHandler` SPI 调用本模块，本模块负责「把请求翻译成远端 Versatile
HTTP 调用 + 把 SSE 流翻译回 QueryChunk」。它本身不启动 HTTP 服务，端到端跑通的示例见
[`common/example/versatile-a2a-adapter-demo`](../../../example/versatile-a2a-adapter-demo)。

- **artifactId**: `agent-service-adapters-versatile`
- **Spring Boot 自动装配**: `VersatileAutoConfiguration`（通过 `AutoConfiguration.imports` 注册）
- **配置前缀**: `openjiuwen.service.versatile.*`（见 `VersatileProperties`）
- **依赖**: `agent-service-spec`（SPI）、`jackson-databind`、`slf4j-api`；Spring Boot 为 `optional`

## 架构

```
runtime (A2A entry / orchestrator)
        │  ServeRequest (AgentHandler SPI)
        ▼
VersatileAgentHandler
 ├─ VersatileRequestExtractor   ServeRequest → RemoteRequest(url, headers, params, body)
 ├─ VersatileHttpClient         POST 远端，逐行消费 SSE
 ├─ VersatileResponseExtractor  SSE 行 → QueryChunk（流）/ 聚合结果（非流）
 └─ IntentAgentResolver         intent_id → agentCard（三字段结果模式下路由下一层）
        │  QueryResponse / QueryChunk 流
        ▼
runtime
```

| 类 | 职责 |
|---|---|
| `VersatileAgentHandler` | `AgentHandler` 实现。`query()` 聚合非流结果，`streamQuery()` 透传流式 chunk；处理 `a2a_delegate` 中断、歧义意图、敏感字段掩码 |
| `VersatileRequestExtractor` | 从 `ServeRequest` 组装远端请求：body 基底取 `metadata.body.custom_data`，query/intent 从 `message.text` 提取并覆盖 `inputs`，header 按白名单透传 + 模板覆盖，URL 占位符替换 |
| `VersatileHttpClient` | `java.net.http.HttpClient`（HTTP/1.1）发 POST、逐行读 SSE；支持自签证书跳过校验；敏感日志掩码 |
| `VersatileResponseExtractor` | 解析 SSE 行：识别 `node_type=End` 终止、`event=exception` 失败、中断信号；按 `result-extractions` 规则抽取结果字段 |
| `IntentAgentResolver` | 把 `intent_id` 解析为下一层 agentCard（A2A Gateway 路径段），策略 `FIRST`/`PRIORITY`/`ROUND_ROBIN` |
| `VersatileProperties` | `@ConfigurationProperties("openjiuwen.service.versatile")` 配置项 |
| `VersatileAutoConfiguration` | Spring Boot 自动装配，注册 `VersatileProperties` |

## 两种工作模式

### 1. Legacy 模式（默认，未配 `intents`）

远端 Versatile 工作流用 `query`/`intent` 文本输入，返回单条答案文本。

- 请求 body：`custom_data.inputs` 作为基底，`query`/`intent` 被 `message.text` 覆盖；未配 `intents` 时不写 `intents`/`messages` 字段。
- 响应：命中 `result-node-name` 节点（且流中出现 `node_type=End`）时，从 `custom_rsp_data.data`（或 `data`）抽 `node_type=QA` 的 `text` 作为 `response_content`；非流聚合为 `{role:"assistant", content:...}`。

### 2. Intent 路由模式（配了 `intents`）

远端工作流返回三字段结果（`response_content` / `intent_id` / `agent_id`），本模块据此把请求路由到下一层 agent。

- 请求 body：额外注入 `inputs.intents`（配置的候选意图 JSON 数组）和 `inputs.messages`（`serve_request_messages`）。
- 响应：按 `result-extractions` 规则抽取三字段。命中后：
  - `intent_id` 为歧义值（等于 `ambiguous-intent-id`）→ 走 `default-workflow.agent-card` 自愈，否则回退 L1 抛 `VERSATILE_INTENT_AMBIGUOUS`（标记 `ambiguous:true`）。
  - 正常 → 用 `IntentAgentResolver` 解析最终 `agent_id`，产出 `a2a_delegate` 中断，交给 runtime 编排器的 `RemoteInvocationBatchCoordinator` 把请求转发到该 agentCard，远端返回即为本层终态答案（`resume=false`）。
  - `intent_id` 缺失或 `agent_id` 无法解析 → `VERSATILE_INTENT_RESULT_CONTRACT` / `VERSATILE_INTENT_AGENT_ID_UNMAPPED`。

三字段 SSE 示例（见 `src/test/resources/versatile-sse/`）：

```
data: {"custom_rsp_data":{"node_name":"AnswerNode","data":{"node_type":"QA","response_content":"__L1_OUTPUT__","intent_id":"intent_L1_hotel","agent_id":"agent_card_L2_hotel"}}}
data: {"data":{"node_type":"End"}}
```

## 中断（用户交互打断）

配置 `interrupt.*` 后，远端可在流中发中断信号（`event=<signal-match>`），本模块抽取
`prompt` / `input_requirement` / `resume_token` 三字段产出 `TYPE_INTERRUPT` chunk，
交给 runtime 向用户追问后用 resume 机制续接。三者任一缺失 → `VERSATILE_INTENT_INTERRUPT_INCOMPLETE`。
`interrupt.resume-request-template.body` 支持 `{字段名}` 占位符引用 `metadata.body` 顶层字段构造 resume 请求体。

## 错误码

| code | 触发条件 |
|---|---|
| `VERSATILE_INTENT_CONFIG_MISSING` | `intents[i]` 的 `id`/`name` 为空，或序列化失败 |
| `VERSATILE_INTENT_INPUT_MISSING` | `messages.required=true` 但 messages 为空/无效 |
| `VERSATILE_INTENT_RESULT_CONTRACT` | 三字段结果缺失或无效 |
| `VERSATILE_INTENT_RESULT_TYPE` | 三字段中某字段非字符串 |
| `VERSATILE_INTENT_AGENT_ID_NOT_UNIQUE` | `agent_id` 抽到的是数组而非单个字符串 |
| `VERSATILE_INTENT_AGENT_ID_UNMAPPED` | `intent_id` 为空或配置中无对应 agentCard |
| `VERSATILE_INTENT_INTERRUPT_INCOMPLETE` | 中断信号出现但三字段未齐 |
| `VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL` | 流关闭前未出现任何识别的终止信号 |

## 配置参考

`openjiuwen.service.versatile.*`（`VersatileProperties`，demo 的 `application.yml` 只用其中一部分）：

| 配置项 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `url-template` | `String` | — | 远端 HTTP 地址模板，**必填**。支持 `{conversation_id}`、`{agent_id}` 占位符 |
| `timeout` | `Duration` | `600s` | 远端 HTTP 调用超时 |
| `insecure-skip-verify` | `boolean` | `false` | 跳过 HTTPS 证书/主机名校验（仅本地调测） |
| `headers-template` | `Map<String,String>` | `{}` | 固定写入远端请求头，**同名覆盖**被透传的请求头（优先级最高） |
| `forward-header-whitelist` | `Set<String>` | `{}` | 允许从 `metadata.headers` 透传到远端的请求头白名单（大小写不敏感） |
| `result-node-name` | `String` | `null` | 命中该 `node_name` 且 `node_type=End` 才抽 result；否则 result 为 null |
| `intents` | `List<{id,name}>` | `[]` | 注入 `inputs.intents` 的候选意图数组（JSON 字符串）；未配走 legacy 模式 |
| `messages.source` | `String` | `serve_request_messages` | `inputs.messages` 来源 |
| `messages.required` | `boolean` | `true` | messages 为空/无效时是否抛 `VERSATILE_INTENT_INPUT_MISSING` |
| `intent-agent-mapping` | `Map<String, List<{agent-card,priority}>>` | `{}` | intent_id → 候选 agentCard 映射 |
| `intent-agent-mapping-strategy` | `enum` | `FIRST` | 多候选选择策略：`FIRST`/`PRIORITY`/`ROUND_ROBIN` |
| `result-extractions` | `List<{match,get}>` | `[]` | 按 JSON path（`get`）抽取字段并存到 `match` key |
| `interrupt.signal-match` | `String` | `null` | 中断信号匹配的 `event` 值 |
| `interrupt.prompt-get` | `String` | `null` | 抽中断提示文案的 path |
| `interrupt.input-requirement-get` | `String` | `null` | 抽中断所需输入的 path |
| `interrupt.resume-token-get` | `String` | `null` | 抽 resume token 的 path |
| `interrupt.resume-request-template.body` | `Map<String,Object>` | `{}` | resume 请求体模板，支持 `{字段名}` 引用 `metadata.body` 顶层字段 |
| `default-workflow.agent-card` | `String` | `null` | L2 自愈默认工作流 agentCard；未配时歧义意图回退 L1 |
| `log-mask-sensitive` | `boolean` | `true` | DEBUG 日志掩码 `messages[].content`、`response_content`、metadata 值 |
| `ambiguous-intent-id` | `String` | `"1"` | 歧义意图回退时使用的 intent_id |

### url-template 占位符

`VersatileRequestExtractor.extract()` 每次请求实时替换，顺序无关：

| 占位符 | 取值 | 缺失时 |
|---|---|---|
| `{conversation_id}` | `ServeRequest.conversationId`（即 A2A `contextId`） | 替换为空串 |
| `{agent_id}` | `ServeRequest.metadata.agent_id`（**metadata 顶层字段**，非 `metadata.body.*`） | 替换为空串 |

模板不含某占位符时，对应字段被忽略、不报错。

> 通过环境变量 `VERSATILE_URL` 覆盖时可直接写裸占位符；
> 写在 `application.yml` 的 `${VERSATILE_URL:...}` 默认值里时，裸 `}` 会被 Spring
> 误判为占位符结束符，需用嵌套占位符引用（demo 的 `application.yml` 即如此处理）。

### metadata 字段流向（请求侧）

```
params.message.parts[0].text      → 解析 query/intent，覆盖远端 body.inputs.query/.intent
params.metadata.body.custom_data  → 原样成为远端 body 顶层字段（body 基底）
params.metadata.body 其它顶层字段  → 默认不进远端请求，除非 resume-request-template 用 {字段名} 引用
params.metadata.headers           → 按 forward-header-whitelist 过滤透传，再叠加 headers-template（同名覆盖）
params.metadata.query             → 拼到远端 URL 上的 query 参数
params.metadata.agent_id          → 替换 url-template 的 {agent_id}
```

## 构建

```bash
# 仓库根目录，使用本地 Maven 仓库避免写入全局 .m2
mvn "-Dmaven.repo.local=.m2/repository" \
  -f "common/agent-runtime-ext-java/pom.xml" \
  -pl agent-service-adapters/agent-service-adapters-versatile -am clean install -DskipTests
```

## 测试

```bash
mvn -f "common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile/pom.xml" test
```

关键测试：

- `VersatileRequestExtractorTest` — 请求组装规则、占位符替换、resume 模板填充
- `VersatileResponseExtractorTest` / `VersatileSseContractTest` — SSE 解析、三字段结果、中断、错误码
- `IntentAgentResolverTest` — 三种选择策略、未映射报错
- `VersatileHttpClientTest` — query 参数拼接、自签证书路径（含 `src/test/resources/tls/`）
- `VersatileAutoConfigurationTest` / `VersatilePropertiesTest` — 装配与配置绑定
