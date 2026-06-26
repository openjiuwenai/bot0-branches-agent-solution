# Versatile Adapter 实现设计

## 1. 文档定位

本文档描述 `agent-solution` 中 Versatile adapter 在当前 `agent-runtime-java` 框架下的落地方案，目标目录为：

```text
common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile
```

目录命名和包分层对齐 vendor 中 `agent-service-adapters-agentcore` 的风格：Maven module 目录使用 `agent-service-adapters-versatile` 前缀名；Java package 根保留 `com.openjiuwen.service.adapters.versatile`；业务实现类放在 `agentfw` 下；自动装配和配置绑定类放在 `autoconfigure` 下。已有实现类的目录不再使用 `package-info.java` 作为占位文件。

设计主线不是搬运旧实现，而是回答四个实现问题：

1. 在 `agent-runtime-java` 的新入口链路下，Versatile 应该接在哪一层。
2. Versatile 需要哪些入参、配置、事件语义和远端调用能力。
3. 当前框架已有能力能覆盖哪些部分，还缺哪些公共 DTO / ingress 能力。
4. 当前目录下一次性实现需要新增哪些文件、配置和测试。

参考来源共有四处，优先级按下列顺序理解：

| 来源 | 路径 | 用途 |
| --- | --- | --- |
| 当前 Java 框架代码和设计文档 | `D:\Code\openJiuwen\agent-solution\vendor\agent-runtime-java`、`D:\Code\openJiuwen\agent-solution\vendor\agent-runtime-java\service\agent-service-app\src\main\java\com\openjiuwen\service\app\controller\a2a\DESIGN.md` | 作为本方案的主约束，决定入口链路、DTO、metadata、A2A/Query 统一方式、A2A Client/TaskStore 边界 |
| openJiuwen Java 参考实现 | `D:\Code\openJiuwen\temp参考\agent-service-adapters-versatile` | 作为最小 Spring Boot adapter/module 骨架参考 |
| Python Versatile 版本 | `D:\Code\openJiuwen\agent-runtime\applications\versatile_adapter` | 作为功能完整度、动态路由、事件语义、配置模型参考 |
| spring-ai-ascend Java 版本 | `D:\Code\spring-ai-ascend\agent-runtime\src\main\java\com\huawei\ascend\runtime\engine\versatile` | 作为 HTTP/SSE 客户端、SSE 解析、结果抽取、中断语义参考 |

实现以当前 `agent-runtime-java` 的 `ServeRequest -> ServeOrchestrator -> AgentHandler` 链路为准；其他来源只用于补齐 Versatile 的业务能力。

## 2. 当前框架入口

当前 `agent-runtime-java` 的查询入口链路为：

```text
HTTP /v1/query
  -> QueryRequest
  -> QueryIngressSupport.validateAndBuild()
  -> QueryMvcController.validateAndBuildMetadata()
     / QueryWebFluxController.buildMetadata()
  -> ServeRequest
  -> DefaultServeOrchestrator
  -> AgentHandler
```

其中 `QueryIngressSupport.validateAndBuild()` 只负责基础校验、租户 header 归一化和 `QueryRequest -> ServeRequest` 转换；`metadata.headers/query/path/body` 由 MVC/WebFlux controller 在进入 orchestrator 前补充。

最新 `DESIGN.md` 中新增 A2A 后的目标链路为：

```text
Query REST      -> ServeRequest -> ServeOrchestrator -> AgentHandler
A2A JSON-RPC   -> ServeRequest -> ServeOrchestrator -> AgentHandler
```

因此 Versatile 在新框架下应该是一个 `AgentHandler` 实现，负责把统一的 `ServeRequest` 转成远端 Versatile HTTP/SSE workflow 请求，再把远端事件转回 `QueryResponse` / `QueryChunk`。

目标数据流：

```text
Query REST / A2A
  -> ServeRequest
  -> VersatileAgentHandler
  -> VersatileRequestExtractor
  -> VersatileHttpClient
  -> VersatileResponseExtractor
  -> QueryResponse / QueryChunk
```

## 3. Versatile 需要实现的功能

### 3.1 基础执行能力

Versatile adapter 需要支持两种调用形态：

| 调用形态 | 入口方法 | 期望行为 |
| --- | --- | --- |
| 非流式 | `query(ServeRequest)` | 调用远端 Versatile endpoint，消费完整响应流，聚合最终结果为 `QueryResponse` |
| 流式 | `streamQuery(ServeRequest, QueryStreamObserver)` | 调用远端 Versatile endpoint，逐帧把远端事件映射为 `QueryChunk` 并推送给 observer |

建议 `query()` 和 `streamQuery()` 共用同一条远端 SSE/line-stream 解析链路。非流式只是额外做结果聚合，避免两套解析规则产生语义差异。

`query()` 不依赖流式 `COMPLETED` chunk 才能返回；它应在远端流消费结束后读取 `VersatileResponseExtractor` 的解析状态，并按 `agent-runtime-java` 现有非流式 handler 的契约聚合成普通 assistant message。若出现 `node_type=End` 且已抽取最终结果，则 `content` 使用抽取结果；否则 `content` 使用最后一条远端事件兜底；如果远端完全没有返回事件，则 `content` 为空字符串。最终 `QueryResponse.result` 始终返回 `{role: "assistant", content: "..."}`，不返回 `null`。流式路径仍保持事件语义：流结束时没有 `node_type=End` 则补发 `INPUT_REQUIRED`。

### 3.2 转发模型

Versatile 的主逻辑就是一次配置化远端调用：

```text
ServeRequest
  -> 从 messages[*]["content"] 提取 query / intent
  -> 从 metadata.body / metadata.headers / metadata.query 提取 body / headers / params
  -> 从 body.custom_data 取得远端 body 基底
  -> 用 query、intent 覆盖 custom_data 同名字段
  -> 按配置拼 URL、headers、query params、body
  -> POST 远端 HTTP/SSE endpoint
  -> 按配置抽取最终结果或透传流式片段
```

`intent` 首先作为业务参数写入 `custom_data.intent`。如果配置了多个远端 URL，可以用 `intent` 做一个很薄的 endpoint 选择：命中 `endpoints[].intent` 时使用对应 URL，否则回退到默认 `url-template`。

### 3.3 请求构造能力

`VersatileAgentHandler` 收到的入参是统一后的 `ServeRequest`。当前 vendor 源码中，这个对象至少包含：

| `ServeRequest` 字段 | Versatile 用途 |
| --- | --- |
| `conversationId` | 替换 URL 模板中的 `{conversation_id}` |
| `messages` | 类型是 `List<Map<String,Object>>`；读取最后一条用户消息的 `content` key，值可能是字符串，也可能已经是结构化 Map。`query` / `intent` 只从这里提取 |
| `userId/spaceId/tenantId` | 需要时可补充到 header 或日志，不默认写入远端 body |
| `stream` | 决定调用 `query()` 还是 `streamQuery()` 的上游行为；Versatile 远端是否流式主要由 URL/header/远端协议决定 |
| `metadata.headers` | allowlist 后透传到远端 header |
| `metadata.query` | 合并到远端 URL query params |
| `metadata.body` | 上游入口写入的业务 body；从其中读取 `custom_data` 作为远端 body 基底 |

远端请求按下面顺序构造。

**1. 提取 query / intent**

先从 `messages[*]["content"]` 读取用户语义输入：

```text
latest user message["content"] Map         # content 已经是结构化对象时直接使用
latest user message["content"] JSON string # content 是 JSON 字符串时解析后使用
lastUserQuery                              # content 不是结构化输入时，仅作为 query 兜底
```

从结构化 `content` 提取：

```text
query = content.query
     ?: lastUserQuery

intent = content.intent
      ?: null
```

`query/intent` 不从 `metadata.body` 反向提取，避免业务语义来源不清；`metadata.body` 只作为远端请求 body 的原始来源。

**2. 提取 body / headers / params**

远端请求三类 HTTP 组件从 `metadata` 提取：

```text
sourceBody    = metadata.body ?: {}
sourceHeaders = metadata.headers ?: {}
sourceParams  = metadata.query ?: {}
```

**3. 构造远端 body**

远端 body 固定采用 Python 对齐的 `body.custom_data` 模式，不再提供可切换 body 模式配置：

```text
custom_data = sourceBody.custom_data ?: {}
remoteBody = copy(custom_data)
if query  非空: remoteBody.query  = query
if intent 非空: remoteBody.intent = intent
```

也就是最终发送给远端 HTTP 的 JSON body 直接是更新后的 `custom_data`。

例如候选输入为：

```json
{
  "query": "用户问题",
  "intent": "knowledge_qa",
  "metadata": {
    "body": {
      "custom_data": {
        "query": "旧问题",
        "intent": "old_intent",
        "city": "深圳"
      }
    }
  }
}
```

则远端 body 是：

```json
{
  "query": "用户问题",
  "intent": "knowledge_qa",
  "city": "深圳"
}
```

这和 Python `VersatileProxy._build_request_body(body)` 的最终发送形态一致：HTTP 调用时执行 `json=request_body`，其中 `request_body = body.get("custom_data", {})`。差异是 Java 需要在 `VersatileRequestExtractor` 阶段从 `metadata.body` 取 `custom_data`，再把 `messages[*]["content"]` 里的 `query`、`intent` 写回 `custom_data`。

**4. 选择远端 URL**

```text
endpoint = endpoints[].intent 精确匹配 intent 时的 endpoint
        ?: 默认 versatile.url-template
url = endpoint.url-template 替换 {conversation_id}
```

**5. 构造远端 headers**

```text
remoteHeaders =
  allowlist(sourceHeaders)
  + versatile.headers-template
```

默认不全量透传业务请求头；来自 `metadata.headers` 的 header 必须经过 `forward-header-whitelist`。如果透传 header 与 `headers-template` 同名，`headers-template` 优先级最高，最后覆盖同名值；同名判断按 HTTP header 语义做大小写不敏感处理。

**6. 构造远端 query params**

```text
remoteParams = sourceParams
```

最终请求对象：

```text
method  = POST
url     = resolvedUrl
headers = remoteHeaders
params  = remoteParams
body    = remoteBody
```

### 3.4 SSE / 事件映射能力

Versatile adapter 应支持真正的 SSE 或 line stream，不应只按一次性 JSON 响应处理。

Python 版本里的 `AdapterEvent.data_proxy / execution_input_required / execution_completed` 是它自己的 A2A facade 输出模型。Java 版本不需要照搬这个字段名，但应该保留同等语义。建议 Java 内部事件模型使用更贴近 Java/Query 语义的名字：

```text
VersatileEvent
  - PASSTHROUGH
  - INPUT_REQUIRED
  - COMPLETED
  - FAILED
```

该内部事件模型可以作为 `VersatileResponseExtractor` 的内部 enum 或私有状态对象存在，本次不需要新增独立 `VersatileEvent` 文件。

映射到 `QueryChunk`：

| 内部事件 | Python 对应语义 | `QueryChunk.type` | `data` 建议 |
| --- | --- | --- |
| `PASSTHROUGH` | `data_proxy` | `chunk` | 远端原始事件/原始 data |
| `INPUT_REQUIRED` | `execution_input_required` | `input_required` | 远端原始事件；没有原始值时可为空 |
| `COMPLETED` | `execution_completed` | `completed` | 抽取出的最终结果字符串；未抽取到结果时可为 `null` |
| `FAILED` | `execution_completed.is_failed=true` | `error` | 远端错误原始值或错误消息 |

`QueryChunk.type` 已经表达事件类型，因此 `data` 不再额外包一层 `{type: ...}`；优先保留远端 Versatile 返回的原始值，只有非流式聚合或 completed 事件需要抽取最终结果。

远端信号到内部状态的最小判定规则：

| 远端信号 | 状态更新 | 流式输出 |
| --- | --- | --- |
| 任意普通 `data:` 行 / JSON 行 | 不改变终态 | 产生 `PASSTHROUGH`，`data` 为远端原始值 |
| JSON 中 `node_type == "End"` | `completed = true` | 产生 `PASSTHROUGH`，保留 End 原始帧 |
| JSON 中 `event == "exception"` | `completed = true`、`failed = true`、记录错误原始帧 | 产生 `PASSTHROUGH`，流结束后再产生 `FAILED` |
| 流结束且 `failed == true` | 失败终态 | 产生 `FAILED` |
| 流结束且 `completed == true` | 成功终态 | 产生 `COMPLETED`，`data` 为缓存的最终结果字符串；未抽取到结果时可为 `null` |
| 流结束且 `completed == false` | 视为等待外部输入或远端中断；即使已缓存结果也不算成功 | 产生 `INPUT_REQUIRED` |

### 3.5 结果抽取能力

远端 Versatile 事件里常见两类结果：

1. 中间帧原样透传给前端。
2. 某些节点的最终结果需要抽取后作为 `QueryResponse.result` 或 completed chunk。

本次实现建议只支持 `result-node-name`，用于从 SSE/JSON 行中按节点名抽取最终答案。命中与抽取规则固定如下：

```text
1. 只处理包含 `"node_name":"<result-node-name>"` 的原始 data 行；匹配范围是整行，而不是限定在某个 JSON 子路径。
2. 命中后再解析为 JSON object；解析失败则按普通 PASSTHROUGH 处理。
3. 先取 resultData = json.custom_rsp_data.data ?: json.data ?: {}。
4. 当 resultData.node_type == "QA" 时抽取 resultData.text 作为最终结果；text 为空则不产生最终结果。
5. 被命中的结果帧不再作为 PASSTHROUGH 输出，避免前端收到重复内容。
```

`node_name` 的判断以原始行子串命中为准，不要求先落到固定 JSON 路径。`resultData` 的取值顺序只保留 `custom_rsp_data.data` 和 `data` 两级，不再额外引入更深层包装。抽取到的结果只作为缓存值保存，不能单独决定成功终态；最终是否 `COMPLETED` 只由 `node_type=End` 决定。

其他抽取能力可以作为可选增强：

| 配置 | 作用 |
| --- | --- |
| `result-node-name` | 本次建议支持；原始行命中指定 `node_name`，且解析后 `node_type=QA` 时抽取 `data.text` |
| `result-node-type` | 可选增强；来自 spring-ai-ascend 旧 Java，用于按 `node_type` 聚合最终结果 |
| `result-extractions` | 可选增强；来自 spring-ai-ascend 旧 Java，用于从未知事件中按规则抽取结果 |

## 4. 配置设计

建议配置前缀：

```yaml
openjiuwen:
  service:
    versatile:
      url-template: "https://versatile.example.com/api/conversations/{conversation_id}"
      timeout: 600s
      headers-template:
        Accept: "application/json, text/event-stream"
        stream: "true"
      forward-header-whitelist:
        - x-user-id
        - x-project-id
        - X-Example-Token
        - cust-userid
      result-node-name: "WorkflowQAResponseNode"
      endpoints:
        - intent: "knowledge_qa"
          url-template: "https://versatile.example.com/api/knowledge/{conversation_id}"
        - intent: "booking"
          url-template: "https://versatile.example.com/api/booking/{conversation_id}"
```

### 4.1 最小必需参数

本次实现最少只需要这些参数：

| 字段 | 层级 | 作用 |
| --- | --- | --- |
| `url-template` | versatile | 默认远端 Versatile 地址，支持 `{conversation_id}` 和静态配置变量 |

必要性说明：

```text
没有 url-template -> 不知道调哪个远端 URL
没有 conversation_id -> URL 中 {conversation_id} 无法替换时应降级为空串或拒绝请求，取决于远端协议要求
```

### 4.2 建议预留参数

这些不是启动必需，但实际联调大概率需要，建议在配置类中预留：

| 字段 | 层级 | 必要性 |
| --- | --- | --- |
| `timeout` | versatile | SSE/HTTP 单次请求超时，默认 600s |
| `headers-template` | versatile | 远端常要求 `Accept`、`stream`、租户/应用静态头 |
| `forward-header-whitelist` | versatile | 安全透传用户态 header，避免 servlet/threadlocal 旁路 |
| `result-node-name` | versatile | 需要从 SSE 节点中抽取最终答案时使用；没有配置或未命中时，非流式使用最后一条远端事件作为 `content` 兜底 |
| `endpoints[]` | versatile | 可选；多个远端 URL 时按 `intent` 选择，只覆盖 URL，不改变请求/响应处理逻辑 |
| `endpoints[].intent` | endpoint | 命中入口 `intent` 时使用该 endpoint；`custom_data.intent` 只保留到远端 body |
| `endpoints[].url-template` | endpoint | endpoint 专属 URL；未命中时使用默认 `url-template` |

### 4.3 暂不引入的参数

这些来自旧 Java 或 Python 版本，但不一定适合当前 Java 实现：

| 字段 | 来源 | 处理建议 |
| --- | --- | --- |
| `adapters[]` | Python | 不建议照搬；当前用轻量 `endpoints[]` 表达多个 URL，不引入 controller/workflow adapter |
| `workflow-defaults` | Python | 依赖 `adapters[]`，本次不需要 |
| `workflow-id` | Python | 当前 Java 不按 workflow 路由，不需要 |
| `terminal-node-type` | 旧 Java 推广项 | 先按实际远端返回写最小终态判断，确有多类终态后再配置化 |
| `result-node-type` | spring-ai-ascend | 旧 Java 用 node_type 聚合最终结果；本次如需要可只保留 `result-node-name` |
| `result-extractions` | spring-ai-ascend | 适合复杂未知事件抽取，本次不是必需 |
| `url-variables` | spring-ai-ascend | 多占位符时有用；若只有 `{conversation_id}`，暂不需要 |
| `input-metadata-keys` | spring-ai-ascend | 当前按固定规则从 `metadata.body.custom_data` 取远端 body 基底，先不需要配置化 |

合并规则：

```text
本次实现: `url-template` 是默认 endpoint；如需要多个 URL，仅加轻量 `endpoints[]`
endpoints[]: 只做 intent -> url-template 选择，不覆盖 headers/body/result 处理
headers-template: 可浅合并
headers-template: 优先级最高，最后覆盖 allowlist 透传的同名 header
forward-header-whitelist: 只使用全局配置，endpoints[] 不覆盖
query/intent: 只从 messages[*].content 提取；custom_data 只作为远端 body 基底
```

## 5. 当前模块目录和文件

当前模块结构：

```text
common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile
|-- pom.xml
|-- src/main/java/com/openjiuwen/service/adapters/versatile
|   |-- agentfw/
|   |   |-- VersatileAgentHandler.java
|   |   |-- VersatileHttpClient.java
|   |   |-- VersatileRequestExtractor.java
|   |   `-- VersatileResponseExtractor.java
|   |-- autoconfigure/
|   |   |-- VersatileAutoConfiguration.java
|   |   `-- VersatileProperties.java
|-- src/main/resources/META-INF/spring/
|   `-- org.springframework.boot.autoconfigure.AutoConfiguration.imports
`-- src/test/java/com/openjiuwen/service/adapters/versatile
    |-- agentfw/
    `-- autoconfigure/
```

其中 `agentfw/` 是 Versatile adapter 的运行时实现层，和 vendor 中 agentcore adapter 的 `agentfw` 目录保持一致；`autoconfigure/` 只放 Spring Boot 自动装配与属性绑定，不承载请求转发、SSE 解析或结果抽取逻辑。

文件职责：

| 文件 | 职责 |
| --- | --- |
| `pom.xml` | 声明为独立 Maven module，依赖 `agent-service-spec`、Spring Boot auto-config、Jackson、测试库 |
| `agentfw/VersatileAgentHandler` | `AgentHandler` 门面，协调 query/streamQuery、异常转换、生命周期 |
| `agentfw/VersatileRequestExtractor` | 从 `messages[*]["content"]` 提取 `query/intent`，从 `metadata.body/headers/query` 提取 `customData/headers/params` |
| `agentfw/VersatileHttpClient` | 组装 URL、headers、query、body 并发起 HTTP POST，返回 SSE/line stream，支持取消、超时、HTTP error；需要临时请求对象时可用私有 record/内部类 |
| `agentfw/VersatileResponseExtractor` | 从远端响应/SSE 事件中维护解析状态、映射内部事件、抽取最终结果；内部持有最小事件 enum/状态对象即可 |
| `autoconfigure/VersatileProperties` | 绑定 `openjiuwen.service.versatile` 配置 |
| `autoconfigure/VersatileAutoConfiguration` | 只启用 `VersatileProperties` 配置绑定，不注册 `AgentHandler` |
| `AutoConfiguration.imports` | Spring Boot 3 自动装配入口 |

暂不新增的文件：

| 文件 | 暂不新增原因 |
| --- | --- |
| `VersatileAdapterRunner` | 当前只做轻量 intent -> endpoint URL 选择，放在 `VersatileProperties`/`VersatileAgentHandler` 即可 |
| `VersatileAdapterConfig` | endpoint 配置结构很薄，直接作为 `VersatileProperties.Endpoint` 内部类即可 |
| `VersatileBackendAdapter` | 当前没有多种后端 adapter 抽象需求 |
| `VersatileControllerAdapter` | 当前不区分 controller adapter |
| `VersatileWorkflowAdapter` | 当前不区分 workflow adapter |
| `VersatileHttpRequest` | 请求对象只在 HTTP 调用前后短暂存在，独立文件收益不高；先放在 `VersatileHttpClient` 内部 |
| `VersatileEventMapper` | 本次可由 `VersatileResponseExtractor` 内部完成最小映射 |
| `VersatileResultAggregator` | 非流式聚合可以先放在 `VersatileAgentHandler`，复杂后再拆 |

## 6. 自动装配与显式 Handler

Versatile 模块本身不启动 Spring Boot，只通过 auto-configuration 被宿主应用加载。当前只支持宿主应用显式声明 `AgentHandler`；adapter module 不根据 `openjiuwen.service.handler=versatile` 自动创建 handler。`openjiuwen.service.handler=versatile` 在这里是宿主层约定值，不会单独激活 handler bean。

自动装配只负责让 yaml 中的 `openjiuwen.service.versatile` 绑定到 `VersatileProperties`：

```java
@AutoConfiguration
@EnableConfigurationProperties(VersatileProperties.class)
public class VersatileAutoConfiguration {
}
```

启动方需要显式提供：

```java
@Bean
AgentHandler versatileAgentHandler(VersatileProperties properties) {
    return new VersatileAgentHandler(properties);
}
```

配置文件只保留 Versatile 远端调用参数：

```yaml
openjiuwen:
  service:
    versatile:
      url-template: "https://versatile.example.com/api/conversations/{conversation_id}"
```

并确保该 module jar 在 classpath 中。


## 7. 测试建议

| 测试 | 覆盖点 |
| --- | --- |
| `VersatileRequestExtractorTest` | `messages[*]["content"]` 提取 `query/intent`、`metadata.body.custom_data` 构造远端 body、`metadata.headers/query` 透传 |
| `VersatileHttpClientTest` | URL 模板、query、headers allowlist、custom_data body、HTTP error 和取消/超时 |
| `VersatileResponseExtractorTest` | End、exception、connection close、result node |
| `VersatileAgentHandlerTest` | query 聚合和 streamQuery 推送 |
| `VersatileAutoConfigurationTest` | 只验证 `VersatileProperties` 配置绑定，不自动注册 `AgentHandler` |

## 8. 附录：四个参考来源的功能对比

### 8.1 当前 agent-runtime-java

已具备：

```text
Query REST -> QueryRequest -> ServeRequest -> ServeOrchestrator -> AgentHandler
QueryChunk / QueryResponse
ActiveStreamRegistry
Spring Boot auto-configuration 基础
ServeRequest.metadata
Query REST headers/query/path/body -> metadata
A2A params.metadata -> metadata
A2A JSON-RPC -> ServeRequest
```

设计文档已规划但仍需按具体启用场景确认：

```text
A2A Client / remote AgentCard registry
InMemoryTaskStore / RedisTaskStore
A2AEnabledServeOrchestrator
a2a_interrupt -> 远端 A2A 调用 -> 影子 Task / INPUT_REQUIRED
```

Versatile 应依赖这些统一入口，而不是自己复制一套协议入口。

### 8.2 openJiuwen Java 参考实现

可复用：

```text
独立 Maven module 结构
Spring Boot AutoConfiguration
VersatileProperties 基础形态
VersatileHttpClient 基础远端调用
AgentHandler 最小实现
```

不足：

```text
偏单 endpoint
流式能力更像一次性 JSON 响应
缺少 body/custom_data/headers/params 提取模型
没有把 controller/workflow 动态路由作为当前 Java 目标
缺少完整 SSE 事件语义和结果抽取
```

### 8.3 Python Versatile 版本

最值得对齐的部分：

```text
headers_template + forward_header_whitelist
body.custom_data 入参约定
AdapterEvent:
  - data_proxy
  - execution_input_required
  - execution_completed
workflow_result_node 抽取
```

不建议迁入当前 Java adapter 的部分：

```text
VersatileAdapterRunner
target.workflow_id / target.intent 路由
controller/workflow 两类 adapter
workflow_defaults + adapters[] 配置模型
FastAPI app/main
A2A facade executor
Agent Card
Redis TaskStore
独立服务进程模型
```

这些属于 Python 独立服务外壳，而当前 Java 方案运行在 `agent-runtime-java` 的 Spring Boot 服务内。

### 8.4 spring-ai-ascend Java 版本

可复用思想：

```text
HTTP POST 后按 SSE/line stream 消费
连接关闭时注入 connection_closed
message/workflow_finished/exception/end/connection_closed 映射
node_type=End 终态判断
未见 End 即断流 -> input_required / interrupted
result-node-type
result-extractions
```

需要改写的部分：

```text
输入从 AgentExecutionContext 改为 ServeRequest
输出从 AgentExecutionResult 改为 QueryChunk / QueryResponse
配置从旧 versatile.* 改为 openjiuwen.service.versatile.*
A2A 相关外壳交给 agent-runtime-java 统一协议层
```

## 9. 最小结论

本次实现不再拆“阶段”。前文列为必要能力的部分，都应作为一次性实现范围交付：

```text
1. Maven module 与 pom.xml。
2. Spring Boot auto-configuration 与 VersatileProperties。
3. VersatileAgentHandler，覆盖 query() 与 streamQuery()。
4. VersatileRequestExtractor，从 `messages[*]["content"]` 提取 `query/intent`，从 `metadata` 提取 `custom_data/headers/params`。
5. VersatileHttpClient，负责 URL、header、query param、body 构造和远端 HTTP/SSE 调用。
6. VersatileResponseExtractor，负责 SSE/line-stream 事件映射、最终结果抽取和错误转换。
7. endpoints[] intent -> url-template 选择逻辑，只选择 URL，不分叉 headers/body/result 处理。
8. 单元测试与 auto-configuration 测试。
```

当前实现假设 `ServeRequest.metadata.body` 已由入口层保留完整原始请求体。Versatile adapter 的关键边界是稳定组合两类来源：从 `messages[*]["content"]` 取得 `query/intent`，从 `metadata.body.custom_data` 取得远端 body 基底，并从 `metadata.headers/query` 取得远端 header 和 query params。实现时应兼容结构化 `content` 和 JSON 字符串形式的 `content`。当前实现也假设 `ServeRequest.metadata` 已由入口层初始化为非空 `new LinkedHashMap<>()`，因此 adapter 内部不需要再做 metadata 空指针防御。
