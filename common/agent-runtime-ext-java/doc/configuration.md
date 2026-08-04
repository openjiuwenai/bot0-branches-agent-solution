# 扩展模块配置参考

本文汇总 Custom REST、Versatile Adapter、AgentScope Adapter、AgentCore-ext 和 Client Tools
在当前实现中直接拥有或实际依赖的配置。配置表以配置绑定、条件装配和运行时读取代码为准，
示例文件中出现但没有代码读取的字段不视为有效配置。

## 1. 配置归属

| 能力 | 专属配置前缀 | 装配方式 |
|---|---|---|
| Custom REST | `openjiuwen.service.custom-rest` | 配置 `query-path` 后自动装配 Servlet Controller |
| Versatile Adapter | `openjiuwen.service.versatile` | 自动绑定 `VersatileProperties`，Handler 仍需由宿主注册 |
| AgentScope Adapter | 无 | 宿主构造 AgentScope Agent，并注册 `AgentScopeAgentHandler` Bean |
| AgentCore-ext | 远端 A2A 工具无专属前缀 | 宿主构造 AgentCore Agent，并注册 `JiuwenCoreAgentExtHandler` Bean |
| Client Tools | 无 | 客户端在每次 A2A 请求的 `params.metadata.clientTools` 中声明 |

AgentScope 的模型、Toolkit、Workspace 和状态存储由宿主代码管理。AgentCore-ext 的远端 A2A 工具
和 Client Tools 没有独立 YAML 开关；同一 artifact 中 SkillHub 的配置不属于本轮五项特性。示例中的
模型参数属于示例应用，不属于这些扩展模块的公共配置。

## 2. Custom REST

| 配置项 | 类型 | 默认值 | 必填 | 生效条件 | 功能 |
|---|---|---:|---|---|---|
| `openjiuwen.service.custom-rest.query-path` | String | 无 | 是 | Servlet 应用，且存在 `DispatcherServlet` 和 A2A `RequestHandler` | 注册 Custom REST 的 `POST` 路径；必须是非空、以 `/` 开头的 Spring 路径模式，可包含 `{variable}` |

未配置 `query-path` 时不会创建 Custom REST Bridge 和 Controller。配置后，应用还必须提供
`CustomRestProtocolAdapter`、A2A `RequestHandler`、`TaskStore` 和 Jackson `ObjectMapper`。

```yaml
openjiuwen:
  service:
    custom-rest:
      query-path: /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
```

## 3. Versatile Adapter

配置前缀为 `openjiuwen.service.versatile`。Spring Boot 会完成配置绑定，但不会自动创建
`VersatileAgentHandler`；宿主需要显式注册 Handler。以下表格中的键均相对于该前缀，实际 YAML
完整键需要在前面加上 `openjiuwen.service.versatile.`。

### 3.1 连接与请求

| 配置项 | 类型 | 默认值 | 必填 | 功能 |
|---|---|---:|---|---|
| `url-template` | String | 无 | 是 | 远端 URL 模板；`{conversation_id}` 会替换为当前会话 ID |
| `timeout` | Duration | `600s` | 否 | 单次远端 HTTP 请求超时；显式绑定为 `null` 时运行期仍回退到 `600s` |
| `insecure-skip-verify` | boolean | `false` | 否 | `false` 使用 JVM 默认的 HTTPS 证书链和主机名校验；`true` 同时跳过这两项校验；生产环境建议保持 `false` |
| `headers-template` | Map<String,String> | `{}` | 否 | 固定出站 Header；与透传 Header 同名时覆盖透传值，名称比较不区分大小写 |
| `forward-header-whitelist` | Set<String> | `[]` | 否 | 允许从 `ServeRequest.metadata.headers` 透传的 Header 名称，匹配不区分大小写 |
| `log-mask-sensitive` | boolean | `true` | 否 | `true` 时遮蔽 ServeRequest 和出站请求中的消息、metadata、Header、Query、Body 值；当前不遮蔽远端响应行、聚合结果或非 2xx 响应 Body |

出站请求固定使用 HTTP/1.1 `POST`。模块不自动补充 `Content-Type` 或 `Accept`，需要通过
`headers-template` 配置。`ServeRequest.metadata.query` 中的非空键值会编码到 URL Query。

### 3.2 输入构造

| 配置项 | 类型 | 默认值 | 必填 | 功能 |
|---|---|---:|---|---|
| `intents[].id` | String | 无 | 配置该项时是 | 候选意图 ID；任一条的 `id` 或 `name` 为空都会拒绝请求 |
| `intents[].name` | String | 无 | 配置该项时是 | 候选意图名称 |
| `messages.source` | String | `serve_request_messages` | 否 | 保留字段；当前实现始终读取 `ServeRequest.messages`，该值不切换数据源 |
| `messages.required` | boolean | `true` | 否 | 为 `true` 时要求消息列表非空、每个非 null 消息都有非空 `role` 和 `content`，并且最终至少有一条有效消息；为 `false` 时跳过无效项 |
| `interrupt.resume-request-template.body` | Map<String,Object> | 未配置 | 否 | 配置后对每次出站请求执行占位符替换并合并到 Body；字符串中的 `{key}` 从当前请求的原始 metadata body 顶层取值 |

远端 Body 以 `ServeRequest.metadata.body.custom_data` 的浅拷贝为基础，其 `inputs` 会被当前用户
消息解析出的 `query`、`intent` 以及序列化后的 `intents`、`messages` 覆盖或补充。

### 3.3 结果与中断

| 配置项 | 类型 | 默认值 | 必填 | 功能 |
|---|---|---:|---|---|
| `result-node-name` | String | 无 | 否 | 仅对包含该 `node_name` 的远端事件执行结果抽取；未配置时不抽取结果节点 |
| `result-extractions[].match` | String | 无 | 配置该项时是 | 抽取目标名；三字段流程使用 `response_content`、`intent_id`、`agent_id` |
| `result-extractions[].get` | String | 无 | 配置该项时是 | Jackson JSON Pointer，例如 `/custom_rsp_data/data/intent_id` |
| `interrupt.signal-match` | String | 无 | 否 | 识别远端原生中断事件的 `event` 值 |
| `interrupt.prompt-get` | String | 无 | 原生中断时是 | 从中断事件抽取提示文本的 JSON Pointer |
| `interrupt.input-requirement-get` | String | 无 | 原生中断时是 | 从中断事件抽取输入要求的 JSON Pointer |
| `interrupt.resume-token-get` | String | 无 | 原生中断时是 | 从中断事件抽取恢复令牌的 JSON Pointer |

原生中断的三个抽取结果必须同时非空，否则返回
`VERSATILE_INTENT_INTERRUPT_INCOMPLETE`。配置 `result-extractions` 后，结果必须至少得到
非空 `intent_id`，并能解析目标 `agent_id`，否则返回三字段契约错误。

### 3.4 意图到 Agent 的映射

| 配置项 | 类型 | 默认值 | 必填 | 功能 |
|---|---|---:|---|---|
| `intent-agent-mapping.<intent-id>[].agent-card` | String | 无 | 按需 | 工作流没有返回 `agent_id` 时使用的候选远端 Agent 注册名 |
| `intent-agent-mapping.<intent-id>[].priority` | int | `0` | 否 | `PRIORITY` 策略下按数值从小到大选择 |
| `intent-agent-mapping-strategy` | enum | `FIRST` | 否 | `FIRST`、`PRIORITY` 或 `ROUND_ROBIN`；轮询游标按 intent 保存在当前进程内 |
| `ambiguous-intent-id` | String | `1` | 否 | 将该 `intent_id` 识别为歧义意图 |
| `default-workflow.agent-card` | String | 无 | 否 | 歧义意图的默认目标；为空时不委派，返回带 `ambiguous=true` 的结果 |

工作流直接返回的非空 `agent_id` 优先于静态映射。映射中的 `agent-card` 是 Runtime 远端 Agent
注册键，不是 Agent Card 的展示名称。

### 3.5 普通调用完整示例

```yaml
openjiuwen:
  service:
    versatile:
      url-template: http://127.0.0.1:31113/v1/agents/main/conversations/{conversation_id}
      timeout: 60s
      insecure-skip-verify: false
      headers-template:
        Content-Type: application/json
        Accept: text/event-stream
      forward-header-whitelist: [x-user-id, x-language]
      result-node-name: ResultNode
      intents:
        - id: booking
          name: 酒店预订
      messages:
        source: serve_request_messages
        required: true
      result-extractions:
        - match: response_content
          get: /custom_rsp_data/data/response_content
        - match: intent_id
          get: /custom_rsp_data/data/intent_id
        - match: agent_id
          get: /custom_rsp_data/data/agent_id
      intent-agent-mapping:
        booking:
          - agent-card: booking-agent
            priority: 0
      intent-agent-mapping-strategy: priority
      ambiguous-intent-id: "1"
      default-workflow:
        agent-card: default-agent
      interrupt:
        signal-match: need_user_input
        prompt-get: /data/question
        input-requirement-get: /data/input_schema
        resume-token-get: /data/resume_token
      log-mask-sensitive: true
```

### 3.6 恢复请求模板

当前 Adapter 不区分首次调用和恢复调用。只要配置了 `resume-request-template.body`，模板就会对每次
出站请求执行；原始 `metadata.body` 中没有对应值的占位符会原样保留。模板在普通 Body 构造完成后按
顶层键合并，同名键会被模板覆盖，包括整个 `inputs` Map。因此，不应把下面配置直接加入同时承载首次
调用和恢复调用的通用实例；只有在调用方保证每次请求都提供模板字段，或该 Handler 实例只承载对应
协议请求时才使用：

```yaml
openjiuwen:
  service:
    versatile:
      interrupt:
        resume-request-template:
          body:
            inputs:
              resume_token: "{resume_token}"
              user_response: "{user_response}"
```

## 4. Runtime 公共配置

以下配置由基础 Runtime 拥有，但会影响本批扩展的接入和对外表现。

### 4.1 服务与 Query

| 配置项 | 类型 | 默认值 | 必填 | 作用 |
|---|---|---:|---|---|
| `openjiuwen.service.agent-id` | String | 无 | 仅基础 Agent ID 模式 | 基础 AgentCore Handler 的资源 ID；使用实例型 `JiuwenCoreAgentExtHandler`、Versatile 或 AgentScope 自定义 Bean 时不负责创建这些 Handler |
| `openjiuwen.service.handler` | String | `agentcore` | 否 | 基础 AgentCore 自动装配条件读取的选择值；非 `agentcore` 值只会阻止缺省 AgentCore Handler 创建，不会创建对应扩展 Handler |
| `openjiuwen.service.version` | String | `0.1.0` | 否 | Runtime 健康信息和本地 Agent Card 的版本 |
| `openjiuwen.service.query.legacy-path-enabled` | boolean | `true` | 否 | 是否保留兼容 Query 路径 `/query` |
| `openjiuwen.service.query.webflux.enabled` | boolean | `false` | 否 | 是否启用 WebFlux Query 入口 `/v1/query/reactive`；不改变 MVC Query、A2A 或 Custom REST 入口 |

提供自定义 `AgentHandler` Bean 会阻止基础 AgentCore Handler 的缺省装配。为 `handler` 设置扩展名称只能
关闭基础条件，不能代替宿主创建扩展 Handler。

### 4.2 A2A 服务与任务

| 配置项 | 类型 | 默认值 | 必填 | 作用 |
|---|---|---:|---|---|
| `openjiuwen.service.a2a.public-url` | String | 自动检测 | 否 | Agent Card 中 JSON-RPC 接口的公开基址；为空时根据当前 HTTP 请求推导 |
| `openjiuwen.service.a2a.agent-description` | String | `OpenJiuwen Agent Runtime Service` | 否 | Agent Card 描述 |
| `openjiuwen.service.a2a.documentation-url` | String | 无 | 否 | Agent Card 文档地址 |
| `openjiuwen.service.a2a.icon-url` | String | 无 | 否 | Agent Card 图标地址 |
| `openjiuwen.service.a2a.streaming` | boolean | `true` | 否 | 声明本地 Agent 是否支持流式调用 |
| `openjiuwen.service.a2a.push-notifications` | boolean | `false` | 否 | 请求声明推送通知能力；最终 Card 值还受 Runtime capability gate 约束 |
| `openjiuwen.service.a2a.extended-agent-card` | boolean | `false` | 否 | 声明是否支持扩展 Agent Card |
| `openjiuwen.service.a2a.default-input-modes` | List<String> | `[text, text/plain]` | 否 | Agent Card 默认输入模式 |
| `openjiuwen.service.a2a.default-output-modes` | List<String> | `[text, text/plain]` | 否 | Agent Card 默认输出模式 |
| `openjiuwen.service.a2a.provider-organization` | String | 空字符串 | 否 | Agent Card Provider 组织 |
| `openjiuwen.service.a2a.provider-url` | String | 空字符串 | 否 | Agent Card Provider URL |
| `openjiuwen.service.a2a.json-rpc-path` | String | `/a2a` | 否 | 拼接到 Agent Card 公布的 JSON-RPC URL；不会改变本地 Controller 固定注册的 `/a2a` 和 `/a2a/` |
| `openjiuwen.service.a2a.task-completion-timeout-seconds` | int | `300` | 否 | Runtime 等待任务完成的超时 |
| `openjiuwen.service.a2a.remote-invocation.max-concurrency` | int | `16` | 否 | 远端 A2A 调用最大并发数 |
| `openjiuwen.service.a2a.remote-invocation.max-queue-size` | int | `256` | 否 | 远端调用等待队列容量 |
| `openjiuwen.service.a2a.remote-invocation.queue-timeout-seconds` | long | `30` | 否 | 远端调用排队超时 |

### 4.3 本地 Agent Card Skills

| 配置项 | 类型 | 默认值 | 必填 | 作用 |
|---|---|---:|---|---|
| `openjiuwen.service.a2a.skills[].id` | String | 无 | 配置 Skill 时应提供 | 写入 Agent Card 的 Skill ID；Runtime 不额外修剪或校验 |
| `openjiuwen.service.a2a.skills[].name` | String | 无 | 配置 Skill 时应提供 | 写入 Agent Card 的 Skill 名称；Runtime 不额外修剪或校验 |
| `openjiuwen.service.a2a.skills[].description` | String | 无 | 否 | Skill 描述；其他 Runtime 把本服务发现为远端工具时优先使用该字段 |
| `openjiuwen.service.a2a.skills[].tags` | List<String> | `[]` | 否 | Skill 标签 |
| `openjiuwen.service.a2a.skills[].examples` | List<String> | `[]` | 否 | Skill 示例 |
| `openjiuwen.service.a2a.skills[].input-modes` | List<String> | `[text]` | 否 | Skill 输入模式 |
| `openjiuwen.service.a2a.skills[].output-modes` | List<String> | `[text]` | 否 | Skill 输出模式 |

AgentCore-ext 注入远端工具时要求发现到的远端 Card 至少声明一个 Skill，并优先合并所有非空 Skill
描述作为工具描述。

### 4.4 远端 Agents

`openjiuwen.service.a2a.remote-agents[]` 支持以下字段：

| 配置项 | 类型 | 默认值 | 必填 | 作用 |
|---|---|---:|---|---|
| `openjiuwen.service.a2a.remote-agents[].name` | String | 无 | 是 | 本地注册键，同时成为 AgentCore-ext 注入的工具名和委派目标名 |
| `openjiuwen.service.a2a.remote-agents[].url` | String | 无 | 是 | 远端服务基址；Runtime 在去除末尾 `/` 后追加 `/.well-known/agent-card.json` 完成发现 |
| `openjiuwen.service.a2a.remote-agents[].timeout-seconds` | int | `300` | 否 | 该远端调用的超时 |
| `openjiuwen.service.a2a.remote-agents[].streaming` | boolean | `false` | 否 | 是否优先使用流式远端调用 |

只要配置了任一 `remote-agents[]` 条目，Runtime 会在应用 ready 后校验每一项的 `name` 和 `url` 非空；
发现失败时每 30 秒重试，发现成功后将 Card、超时和 streaming 偏好写入进程内 Registry。

示例中的 `openjiuwen.service.a2a.agent-name` 当前没有对应配置字段。Agent Card 名称来自 Runtime
服务身份实现，常见宿主使用 `spring.application.name`，不要依赖不存在的 `agent-name` 字段。

## 5. 不属于公共配置的内容

- `server.*`、`spring.*` 和 `logging.*` 是 Spring Boot 配置，不属于扩展模块。
- 示例使用的 `openjiuwen.demo.*`、`agentscope.demo.*`、模型 API Key 和 Workspace 参数只服务于示例应用。
- Client Tools 的工具清单、参数 Schema 和结果不是 YAML，应随每次请求传输。
- AgentScope Handler 的 5 分钟同步超时和 30 分钟流式超时是当前内部常量，不是可配置契约。
- SkillHub 虽与 AgentCore-ext 位于同一 Maven 模块，但不在本文覆盖的五个特性范围内。
