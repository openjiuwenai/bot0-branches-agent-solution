# Versatile Adapter 源码设计说明

## 1. 文档定位

本文档基于当前源码说明 Versatile adapter 的实现边界、数据流和配置模型。目标模块为：

```text
common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile
```

本文只描述当前实现。`agent-runtime-java` 相关内容只保留 adapter 接入边界会用到的 DTO、SPI 和 metadata 约定。

## 2. 模块结构现状

当前源码结构：

```text
agent-service-adapters-versatile
|-- pom.xml
|-- src/main/java/com/openjiuwen/service/adapters/versatile
|   |-- agentfw/
|   |   |-- VersatileAgentHandler.java
|   |   |-- VersatileHttpClient.java
|   |   |-- VersatileRequestExtractor.java
|   |   `-- VersatileResponseExtractor.java
|   `-- autoconfigure/
|       |-- VersatileAutoConfiguration.java
|       `-- VersatileProperties.java
`-- src/main/resources/META-INF/spring/
    `-- org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

文件职责：

| 文件 | 当前职责 |
| --- | --- |
| `pom.xml` | 独立 jar module，声明 adapter 运行和配置绑定所需依赖 |
| `VersatileAgentHandler` | 实现 `AgentHandler`，负责 `query()` / `streamQuery()` 调度、日志、异常转换、取消处理和非流式结果聚合 |
| `VersatileRequestExtractor` | 从 `ServeRequest` 提取 message、metadata、headers、query params，生成远端 `RemoteRequest` |
| `VersatileHttpClient` | 使用 JDK `HttpClient` 发送 HTTP/1.1 POST，拼接 URL query params，按行读取远端响应 |
| `VersatileResponseExtractor` | 解析 SSE/line stream，维护完成、失败、结果和错误状态，输出 `QueryChunk` |
| `VersatileProperties` | 绑定 `openjiuwen.service.versatile` 配置 |
| `VersatileAutoConfiguration` | 只启用 `VersatileProperties` 配置绑定 |
| `AutoConfiguration.imports` | Spring Boot 3 自动装配入口 |

## 3. 接入边界与 DTO 契约

Versatile adapter 接在服务编排后的 `AgentHandler` 层：

```text
ServeRequest
  -> VersatileAgentHandler
  -> remote Versatile HTTP/SSE endpoint
  -> QueryResponse / QueryChunk
```

`ServeRequest` 中当前实现会用到的字段：

| 字段 | Versatile 用途 |
| --- | --- |
| `conversationId` | 替换 URL 模板里的 `{conversation_id}`；为 `null` 时替换为空串 |
| `messages` | 提取最后一条 user message 的 `content`，得到 `query` / `intent` |
| `userId/spaceId/tenantId` | 当前仅用于日志，不写入远端 body/header |
| `stream` | 当前仅用于日志；实际调用方式由上游选择 `query()` 或 `streamQuery()` |
| `metadata.body` | 读取 `custom_data`，作为远端 JSON body 基底 |
| `metadata.headers` | 按 allowlist 过滤后透传到远端 header |
| `metadata.query` | 转成远端 URL query params |

`QueryChunk` 当前类型：

```text
interrupt  # QueryChunk.TYPE_INTERRUPT
chunk      # QueryChunk.TYPE_CHUNK
error      # QueryChunk.TYPE_ERROR
```

语义映射：

| 语义 | 当前 `QueryChunk.type` |
| --- | --- |
| 普通远端事件透传 | `chunk` |
| 等待输入/远端未完成/连接结束但没看到 End | `interrupt` |
| 已完成答案 | `chunk`，`data.type=answer`，真实答案在 `data.output` |
| 远端异常或 adapter 异常 | `error` 或 `observer.onError()` |

`QueryResponse.result` 没有强类型。当前 `VersatileAgentHandler.query()` 源码会构造一个 map，写入：

```text
role = assistant
content = 聚合后的内容字符串
```


## 4. 核心数据流

当前实际数据流：

```text
ServeRequest
  -> VersatileAgentHandler.query()/streamQuery()
  -> VersatileRequestExtractor.extract()
  -> VersatileHttpClient.postStream()
  -> VersatileResponseExtractor.consumeLine()
  -> VersatileResponseExtractor.finish()
  -> QueryResponse 或 QueryStreamObserver.onNext(QueryChunk)
```

`query()` 和 `streamQuery()` 共用远端 HTTP/SSE 消费链路：

| 入口方法 | 行为 |
| --- | --- |
| `query(ServeRequest)` | 消费完整远端响应流，收集所有 `QueryChunk`；优先使用 `answer` chunk 的 data；遇到 `error` chunk 抛 `IllegalStateException`；没有 answer 时使用最后一条远端事件字符串兜底；远端无事件时 content 为空字符串 |
| `streamQuery(ServeRequest, QueryStreamObserver)` | 边读取远端行边 emit chunk；读取结束后 emit `finish()` 产生的终态 chunk；未取消时调用 `observer.onComplete()` |

当前取消语义：

```text
1. 每次消费远端行前后检查 observer.isCancelled()
2. 取消时抛 CancellationException 中断当前调用
3. 捕获取消后不调用 onError，也不调用 onComplete
```

## 5. 请求构造

### 5.1 语义输入提取

`VersatileRequestExtractor.extractSemanticInput()` 的规则：

```text
1. 从 messages 末尾向前找 role=user 的消息，取 content
2. 如果没有 user 消息，则取最后一条 message 的 content
3. 如果 content 是 Map，直接作为结构化输入
4. 如果 content 是以 { 开头、以 } 结尾的 JSON 字符串，则尝试解析为 Map
5. query = structuredContent.query
6. query 为空时，回退到 ServeRequest.lastUserQuery()
7. intent = structuredContent.intent
```

`query/intent` 不从 `metadata.body.custom_data` 反向读取。`metadata.body.custom_data` 只作为远端 body 的基底。

### 5.2 metadata 输入

当前读取三类 metadata：

```text
metadata.body
metadata.headers
metadata.query
```

取值规则：

| 来源 | 规则 |
| --- | --- |
| `metadata.body` | 必须是 Map，否则视为空 |
| `metadata.body.custom_data` | 必须是 Map，否则视为空；复制后作为远端 body 基底 |
| `metadata.headers` | 必须是 Map，否则视为空；按 whitelist 过滤后转成远端 headers |
| `metadata.query` | 必须是 Map，否则视为空；非空 value 转字符串后作为远端 query params |

当前实现假设 `ServeRequest.metadata` 非空。

### 5.3 远端 body 构造

当前远端 body 以 `metadata.body.custom_data` 为基底，并把 message 中提取出的 `query` / `intent` 写入 `inputs` 子对象。

源码当前流程可概括为：

```text
sourceBody = map(metadata.body)
remoteBody = copy(map(sourceBody.custom_data))
inputs = copy(map(remoteBody.inputs))
if query  非空: inputs.query  = query
if intent 非空: inputs.intent = intent
if inputs 非空: remoteBody.inputs = inputs
```

### 5.4 URL 选择

URL 选择规则：

```text
if intent 非空，并且 endpoints[].intent 精确等于 intent，且 endpoint.url-template 非空:
    使用 endpoint.url-template
else:
    使用 openjiuwen.service.versatile.url-template
```

默认 `url-template` 为空时，`extract()` 抛出：

```text
IllegalArgumentException("openjiuwen.service.versatile.url-template must not be blank")
```

模板替换只支持：

```text
{conversation_id}
```

### 5.5 header 合并

远端 header 来源：

```text
1. metadata.headers 中命中 forward-header-whitelist 的 header
2. openjiuwen.service.versatile.headers-template
```

规则：

```text
1. whitelist 和 header 名按大小写不敏感比较
2. 透传 header value 用 String.valueOf(value)
3. headers-template 后写入
4. 同名 header 按大小写不敏感删除旧值，再写入新值
5. headers-template 优先级高于透传 header
```

### 5.6 query params

`metadata.query` 中 value 非空的条目会转成字符串，追加到 URL 上。

`VersatileHttpClient.withQueryParams()` 使用 `URLEncoder.encode(..., UTF_8)` 编码 key/value，并根据 URL 是否已有 `?` 决定追加 `?` 或 `&`。

## 6. HTTP/SSE 调用

`VersatileHttpClient` 当前实现：

```text
JDK HttpClient
HTTP/1.1
POST
BodyPublishers.ofByteArray(JSON UTF-8)
BodyHandlers.ofInputStream()
BufferedReader 按行读取
忽略空行
非 2xx 时读取完整响应体并抛 IOException
```

重要行为：

| 项 | 当前行为 |
| --- | --- |
| HTTP method | 固定 POST |
| Content-Type | 不默认设置；只有 `headers-template` 或透传 header 中配置才会发出 |
| Accept | 不默认设置；需要配置 |
| timeout | `properties.timeout`，默认 600 秒 |
| SSE 支持 | 按行读取 `InputStream`，支持 `data:...` 行和普通 JSON 行 |
| HTTP error | `IOException("Versatile HTTP <status>: <body>")` |
| 日志 | info 记录 URL、header 数、param 数、body keys；debug 输出完整 request |

## 7. 响应解析与事件映射

### 7.1 行解析

远端返回是按行消费的 SSE/line stream。日志中的原始远端行格式示例：

```text
data:{"event":"message","data":{"text":"...","index":"0","node_id":"node_123","node_type":"QA","node_name":"ABCDEResponseNode","workflow_id":"..."}}
data:{"event":"message","data":{"text":"","summary":"...","node_type":"QA","node_name":"ABCDEResponseNode","is_finished":true,"workflow_id":"..."}}
data:{"event":"message","data":{"text":"","summary":"","node_id":"node_end","node_type":"End","node_name":"结束","is_finished":true,"workflow_id":"..."}}
data:{"event":"end","data":{}}
```

`VersatileResponseExtractor.consumeLine()` 会先调用 `stripSsePrefix()`，把输入行规整成内部 data 字符串：

| 解析前 line | 解析后 data |
| --- | --- |
| `data:{"event":"message","data":{"node_type":"QA"}}` | `{"event":"message","data":{"node_type":"QA"}}` |
| `data: {"event":"message"}` | `{"event":"message"}` |
| `{"event":"message"}` | `{"event":"message"}` |
| `event: message` | `null`，忽略 |
| `id: 1` | `null`，忽略 |
| `retry: 3000` | `null`，忽略 |

解析后的 data 会继续进入 JSON 解析、结果抽取和终态判断；没有被结果抽取吃掉的 data 会作为 `QueryChunk(TYPE_CHUNK, data)` 输出。

### 7.2 状态机

当前状态字段：

```text
completed: boolean
failed: boolean
result: String
error: String
```

消费规则：

| 远端行 | 状态变化 | 即时输出 |
| --- | --- | --- |
| 空行 | 无 | 无 |
| SSE 非 data 字段 | 无 | 无 |
| 普通 JSON/文本行 | 无 | `chunk(data)` |
| 命中结果节点且抽取成功 | `result = text` | 无；该帧不透传 |
| JSON 顶层 `event == "exception"` | `completed=true, failed=true, error=data` | `chunk(data)` |
| JSON 任意层级存在 `node_type == "End"` | `completed=true` | `chunk(data)` |

`finish()` 规则：

```text
if failed:
    return error(error)
if completed:
    return answer(result)   # result 可能为 null
return interrupt(null)
```

日志中前两轮响应最后只有：

```text
data:{"event":"end","data":{}}
```

没有出现 `node_type=End`，因此当前 extractor 会在 `finish()` 阶段返回 `interrupt(null)`。第三轮响应出现：

```text
data:{"event":"message","data":{"node_type":"End","node_name":"结束",...}}
```

因此会标记 `completed=true`，随后 `finish()` 返回 `answer(result)`。

### 7.3 结果抽取

结果抽取由 `openjiuwen.service.versatile.result-node-name` 启用。没有配置时不抽取结果节点。

命中条件：

```text
resultNodeName 非空
rawData 包含子串 "\"node_name\":\"<resultNodeName>\""
rawData 能解析成 JSON object
```

抽取规则：

```text
resultData = json.at("/custom_rsp_data/data")
if resultData missing/null:
    resultData = json.get("data")
if resultData.node_type == "QA" 且 resultData.text 非空:
    result = resultData.text
    当前帧不作为 chunk 透传
```

`completed` 不由结果节点决定，只由 `node_type=End` 或 `event=exception` 决定。

### 7.4 非流式聚合

`VersatileAgentHandler.query()` 聚合规则：

```text
1. 调用 postStream() 消费全部远端行
2. 对每行记录 stripSsePrefix 后的 lastEvent
3. consumeLine() 产生的 chunk 收集到列表
4. 远端结束后追加 finish() 的 chunk
5. 如果任一 chunk.type == error，抛 IllegalStateException(String.valueOf(chunk.data))
6. 如果任一 chunk.type == answer 且 data != null，content = data
7. 如果 content 仍为空，content = lastEvent ?: ""
8. 返回 QueryResponse({role:"assistant", content:String.valueOf(content)}, conversationId)
```

当远端只返回 `End` 但没有结果节点时，非流式 content 会变成 End 帧原文；当远端完全没有事件时，content 为空字符串。

## 8. 配置设计

配置前缀：

```yaml
openjiuwen:
  service:
    versatile:
      url-template: "http://xxxxxx/conversations/{conversation_id}"
      timeout: 600s
      headers-template:
        Content-Type: "application/json"
        accept: "*/*"
        stream: "true"
      forward-header-whitelist:
        - x-invoke-mode
        - x-language
      result-node-name: "ResponseNode"
      endpoints:
        - intent: "LATEST"
          url-template: "http://xxxxx/conversations/{conversation_id}"
```

当前配置字段：

| 字段 | 默认值 | 当前用途 |
| --- | --- | --- |
| `url-template` | `null` | 默认远端 URL；没有命中 endpoint 时必需 |
| `timeout` | `600s` | JDK HttpRequest timeout |
| `headers-template` | 空 map | 静态远端 headers；覆盖同名透传 headers |
| `forward-header-whitelist` | 空 set | 允许从 `metadata.headers` 透传到远端的 header 名 |
| `result-node-name` | `null` | 启用结果节点抽取 |
| `endpoints[].intent` | `null` | intent 精确匹配值 |
| `endpoints[].url-template` | `null` | intent 命中后的 URL |

当前 auto-configuration 行为：

```text
VersatileAutoConfiguration 只注册 VersatileProperties。
即使配置 openjiuwen.service.handler=versatile，也不会自动注册 AgentHandler。
```

宿主应用需要显式提供：

```java
@Bean
AgentHandler versatileAgentHandler(VersatileProperties properties) {
    return new VersatileAgentHandler(properties);
}
```

## 9. 当前实现结论

当前 Versatile adapter 已经具备：

```text
1. 独立 Maven module。
2. Spring Boot 3 auto-configuration properties 绑定。
3. 显式 AgentHandler 实现。
4. 从 ServeRequest.messages 提取 query/intent。
5. 从 metadata.body.custom_data 构造远端 body。
6. 从 metadata.headers/query 构造远端 headers/query params。
7. 按 intent 选择 endpoint URL。
8. JDK HttpClient POST + line-stream/SSE 消费。
9. result-node-name 最小结果抽取。
10. node_type=End / event=exception / stream close 终态判断。
11. 非流式返回 `{role:"assistant", content:"..."}`。
```

当前实现边界：

```text
1. 不自动注册 AgentHandler。
2. 不提供独立服务进程。
3. 不支持 result-node-type / result-extractions / url-variables。
4. 不做任意 header 透传。
5. 不从 metadata.body.custom_data 读取 query/intent，只把它作为远端 body 基底。
```
