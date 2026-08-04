# Versatile Adapter 特性

## 1. 定位

Versatile Adapter 将 Runtime `ServeRequest` 转换为 Versatile 兼容的 HTTP/SSE 工作流调用，并把远端
逐行事件转换为 `QueryChunk`、最终回答或 Runtime 可识别的中断。它作为 `AgentHandler` 执行后端，
不新增公网 Controller。

Maven 模块：

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-adapters-versatile</artifactId>
  <version>0.1.0</version>
</dependency>
```

Spring Boot 自动装配只绑定 `VersatileProperties`，不会自动注册 `VersatileAgentHandler`。宿主必须提供：

```java
@Bean
AgentHandler versatileAgentHandler(VersatileProperties properties) {
    return new VersatileAgentHandler(properties);
}
```

## 2. 模块结构

| 组件 | 职责 |
|---|---|
| `VersatileAgentHandler` | 实现 query/streamQuery，聚合结果并控制 Observer 终态 |
| `VersatileRequestExtractor` | 从 ServeRequest 构造 URL、Header、Query 和 Body |
| `VersatileHttpClient` | 使用 JDK HTTP/1.1 Client 发送 POST 并逐行读取 UTF-8 响应 |
| `VersatileResponseExtractor` | 解析 SSE data、结果节点、原生中断和三字段路由结果 |
| `IntentAgentResolver` | 按工作流 agent_id 或静态 intent 映射选择目标 Agent |
| `VersatileProperties` | 绑定 `openjiuwen.service.versatile` 完整配置树 |

## 3. 请求构造

### 3.1 语义输入

Adapter 从最后一条有效 user message 的 `content` 提取输入：

- content 是 Map：读取 `query` 和 `intent`。
- content 是以 `{` 和 `}` 包围的合法 JSON Object 字符串：解析后读取 `query` 和 `intent`。
- 未得到 `query`：回退到 `ServeRequest.lastUserQuery()`。
- 未得到 `intent`：不写入远端 inputs.intent。

### 3.2 Body

```text
sourceBody = map(metadata.body)
remoteBody = copy(sourceBody.custom_data)
inputs = copy(remoteBody.inputs)
inputs.query / inputs.intent = 当前语义输入
inputs.intents = 配置候选意图的 JSON 字符串
inputs.messages = 有效消息列表的 JSON 字符串
remoteBody.inputs = inputs
```

因此 `metadata.body.custom_data` 是远端 Body 基底；`metadata.body.input` 不会直接成为远端 inputs。
配置或当前消息生成的字段覆盖基底中的同名值。

恢复模板会在上述构造之后深度处理 Map，把模板字符串中的 `{key}` 替换为原始 `metadata.body` 的
顶层值，再合并到远端 Body。List 内部不进行递归占位符处理，找不到来源值的占位符保持原样。

当前 Adapter 不识别本次调用是否为恢复请求：配置模板后，每次请求都会执行合并；模板与普通 Body
使用相同顶层键时，模板值覆盖普通值，`inputs` 也会整体覆盖而不是逐字段合并。通用 Handler 不应配置
只适用于恢复轮次的模板，除非调用方保证每次请求的 metadata 都满足该模板。

### 3.3 URL、Header 和 Query

URL 使用顶层 `url-template`，其中的 `{conversation_id}` 替换为当前会话 ID；模块不处理其他 URL 占位符。

Header 先从 `metadata.headers` 按白名单选择，再应用 `headers-template`。固定模板优先，名称比较不区分
大小写。`metadata.query` 中键和值均非 null 的条目会转为字符串并 URL 编码后追加到 URL；空字符串会保留。

## 4. HTTP 调用

调用固定为 HTTP/1.1 POST，Body 使用 Jackson 序列化为 UTF-8 JSON。模块不自动添加 Content-Type、
Accept、认证或 Cookie，必须通过配置提供。

2xx 响应按 UTF-8 行读取；非 2xx 会完整读取响应 Body 并抛出 IOException。query 和 streamQuery
最终都把 I/O、线程中断或解析运行时异常包装为 `IllegalStateException("Versatile invocation failed")`。

`insecure-skip-verify=false` 时，HTTPS 使用 JVM 默认的证书链和主机名校验；设置为 `true` 时，
同时跳过证书链和主机名校验。HTTP URL 不受该参数影响。`true` 会失去服务端身份认证，生产环境
建议保持 `false`。

## 5. 响应状态机

每个非空响应行按以下顺序处理：

1. 去掉 `data:` 前缀；普通 SSE 控制行忽略。
2. 若匹配配置的原生中断 event，抽取 prompt、input requirement 和 resume token。
3. 若命中 `result-node-name`，按 Legacy 或三字段规则抽取结果。
4. `event=exception` 标记失败。
5. 任意嵌套位置出现 `node_type=End` 标记完成。
6. 其他行作为原始字符串 `TYPE_CHUNK` 输出。

流关闭时按优先级收口：完整原生中断、原生中断缺字段错误、远端异常、完成后的抽取结果、正常完成、
未完成时的通用输入中断。

## 6. 结果模式

### 6.1 Legacy 结果

没有配置 `result-extractions` 时，仅从目标结果节点的 `/custom_rsp_data/data` 或 `/data` 中识别
`node_type=QA` 且 text 非空的内容。完成后生成：

```json
{"type": "answer", "output": "result text"}
```

blocking Handler 聚合为 `{role: assistant, content: result text}`。若没有 answer，则根据中断或已观察到
的远端事件构造兜底 content；不会返回 `null` result。

### 6.2 三字段结果和远端委派

配置 `result-extractions` 后，`response_content`、`intent_id` 和 `agent_id` 必须是字符串。
`intent_id` 必须非空。目标 Agent 按以下顺序解析：

1. 工作流返回的非空 `agent_id`。
2. `intent-agent-mapping[intent_id]` 和选择策略。

解析成功后不是直接输出最终 answer，而是生成带 `_interrupt_kind=a2a_delegate` 的内部中断。Handler
补充当前 user message 和 stream mode，Runtime 再根据远端 Agent 注册表执行下一层 A2A 调用。
该中断设置 `resume=false`，表示远端结果直接成为本层答案，不再次恢复当前 Versatile Handler。

当 `intent_id` 等于 `ambiguous-intent-id`：

- 配置 `default-workflow.agent-card`：委派默认目标。
- 未配置：返回包含 `intent_id`、`response_content` 和 `ambiguous=true` 的普通 chunk。

## 7. 原生中断

当远端行包含配置的 `event=signal-match` 时，Adapter 按 JSON Pointer 抽取：

```json
{
  "message": "prompt",
  "input_requirement": "schema or description",
  "resume_token": "token"
}
```

三个值必须全部非空。恢复请求可使用 `interrupt.resume-request-template.body`，把当前请求 Body 中的
恢复字段填入远端协议；但模板配置后会应用于首次调用和恢复调用，Adapter 不做轮次判断。原生中断和
三字段 A2A 委派是两个不同分支，不应混用字段。

## 8. 流式与非流式差异

- `streamQuery` 实时转发 extractor 产生的 chunk，并在 `finish()` 后检查 terminal error。
- terminal error 调用 `observer.onError`，不会再调用 `onComplete`。
- Observer 取消会停止继续发出 chunk，并在下次逐行处理边界抛出取消。
- `query` 消费完整远端流后聚合为一个 `QueryResponse`；遇到任何 `TYPE_ERROR` 直接失败。
- A2A delegate 和业务 interrupt 在 blocking 结果中都保存在 result 的 `_interrupt` 字段。

## 9. 错误和日志

三字段流程会产生稳定错误前缀，包括：

- `VERSATILE_INTENT_CONFIG_MISSING`
- `VERSATILE_INTENT_INPUT_MISSING`
- `VERSATILE_INTENT_INTERRUPT_INCOMPLETE`
- `VERSATILE_INTENT_RESULT_CONTRACT`
- `VERSATILE_INTENT_RESULT_TYPE`
- `VERSATILE_INTENT_AGENT_ID_NOT_UNIQUE`
- `VERSATILE_INTENT_AGENT_ID_UNMAPPED`

`log-mask-sensitive=true` 时，DEBUG 日志隐藏请求消息 content、metadata 值以及出站 Header、Query、Body
值。该开关当前不遮蔽逐行读取的远端响应、blocking 聚合结果或 WARN 级别记录的非 2xx 响应 Body；
响应中可能包含敏感数据时，需要同时控制日志级别和远端错误报文。只有在受控的本地环境中才能关闭
请求掩码。

## 10. 限制

- 自动装配不创建 Handler。
- HTTP Client 没有连接池、代理、mTLS 或认证的专属配置抽象；TLS 仅提供默认严格校验和
  `insecure-skip-verify` 跳过校验模式。
- 远端响应按行解析，只支持单行 JSON data；多行 SSE data 不会自动拼接。
- `result-node-name` 使用原始行中的精确 JSON 片段判断，远端字段格式变化可能导致不抽取。
- 原生中断同样使用原始行中的精确 `event` JSON 片段判断；字段两侧增加空白也可能导致不匹配。
- Round-robin 状态只存在当前进程，重启后重置，多副本之间不共享。
- `PRIORITY` 选择数值最小的候选，配置方不能按“数值越大优先”理解。
- `resume-request-template` 对所有请求生效，且按顶层键覆盖普通 Body；不能依赖 Adapter 自动识别恢复轮次。
- 取消只能在逐行读取边界被观察，不能保证立即中止阻塞中的 HTTP send/read。

## 11. 相关文档

- [配置参考](../configuration.md)
- [入口与数据契约](../entrypoints-and-contracts.md)
- [外部 Agent Runtime 接入指南](../guides/external-agent-runtime-integration.md)
