# 接入外部 Agent Runtime

本指南说明如何把 Versatile HTTP/SSE 工作流或本地 AgentScope Java Agent 接入同一个 Runtime SPI。
两者都以 `AgentHandler` Bean 交给 Runtime，但通信方向和装配方式不同。

## 1. 选择 Adapter

| 场景 | Versatile Adapter | AgentScope Adapter |
|---|---|---|
| Agent 所在位置 | 独立 HTTP/SSE 服务 | 当前 JVM 内的 Java 对象 |
| 主要输入 | ServeRequest + metadata 中的宿主协议数据 | 最后一条有效 user 消息 |
| 主要输出 | 远端逐行事件、结果节点和原生中断 | AgentEvent、Msg 和 AgentState |
| 配置方式 | `openjiuwen.service.versatile.*` | 宿主 Java 构造，无专属 YAML |
| 中断恢复 | 远端 token/template 或三字段 A2A 委派 | confirmation、tool_result、message |
| 推荐用途 | 已部署的低码/工作流平台 | 进程内 ReActAgent 或 HarnessAgent |

不要在同一个 Runtime 应用中同时注册两个未限定的 `AgentHandler` Bean。一个进程选择一个主 Handler，
或由宿主自行实现明确的路由 Handler。

## 2. 公共 Runtime 设置

引入基础 Runtime 应用模块，并保持 A2A 入口可用。自定义 Handler Bean 会阻止基础 AgentCore Handler
的缺省装配。若显式配置 `openjiuwen.service.handler`，其作用只是参与基础 Handler 条件，不会创建
Versatile 或 AgentScope Handler。

推荐最小公共配置：

```yaml
spring:
  application:
    name: external-agent-runtime

openjiuwen:
  service:
    query:
      legacy-path-enabled: true
      webflux:
        enabled: false
    a2a:
      streaming: true
      agent-description: External agent adapter
      default-input-modes: [text, text/plain]
      default-output-modes: [text, text/plain]
```

## 3. 接入 Versatile

### 3.1 添加依赖和 Handler

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-adapters-versatile</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
@Bean
AgentHandler versatileAgentHandler(VersatileProperties properties) {
    return new VersatileAgentHandler(properties);
}
```

### 3.2 配置远端协议

```yaml
openjiuwen:
  service:
    versatile:
      url-template: http://127.0.0.1:31113/v1/agents/main/conversations/{conversation_id}
      timeout: 60s
      forward-header-whitelist: [x-user-id, x-language]
      headers-template:
        Content-Type: application/json
        Accept: text/event-stream
      result-node-name: ResultNode
```

远端返回自定义结果节点时，再按实际 JSON Pointer 配置 `result-extractions`。不要照抄示例路径；先用
真实 SSE 样本确认 `node_name`、`response_content`、`intent_id` 和 `agent_id` 所在位置。

需要配置 `interrupt.resume-request-template.body` 时要单独评估部署方式：当前 Adapter 会把模板应用到
每次调用，并按顶层键覆盖普通 Body，不会自动区分首次调用和恢复调用。

### 3.3 发送 A2A 请求

```json
{
  "jsonrpc": "2.0",
  "id": "versatile-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "message-1",
      "contextId": "versatile-conversation-1",
      "parts": [
        {"text": "{\"query\":\"查询余额\",\"intent\":\"查询账户余额\"}"}
      ]
    },
    "metadata": {
      "body": {
        "custom_data": {
          "inputs": {"channel": "mobile"}
        }
      },
      "headers": {"x-user-id": "user-1", "x-debug": "not-forwarded"},
      "query": {"workspace_id": "11"}
    }
  }
}
```

成功判据：远端收到替换后的 conversation ID、白名单 Header、URL Query 和合并后的 inputs；Runtime
流式输出不以 error 结束。Legacy 结果应聚合成 assistant content；三字段结果应进入目标 A2A Agent
委派或歧义分支。

## 4. 接入 AgentScope

### 4.1 添加依赖

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-adapters-agentscope</artifactId>
  <version>0.1.0</version>
</dependency>
```

### 4.2 注册 ReActAgent

宿主先按业务需要构建模型、Toolkit、Permission 和状态能力，然后注册 Handler：

```java
@Bean
AgentHandler agentScopeHandler(ReActAgent reactAgent) {
    return AgentScopeAgentHandler.forReActAgent(reactAgent);
}
```

HarnessAgent 使用：

```java
@Bean
AgentHandler agentScopeHandler(HarnessAgent harnessAgent) {
    return AgentScopeAgentHandler.forHarnessAgent(harnessAgent);
}
```

Adapter 没有 `agentscope.*` 公共配置。模型 API Key 和 Workspace 应由宿主自己的配置类管理。

### 4.3 验证普通调用

向 `/a2a/` 发送 `SendMessage`，message 使用唯一 contextId 和非空 TextPart。成功判据：Task 最终完成，
artifact 包含 AgentScope 最终文本；同一请求不会把 Runtime 历史重复写入 AgentScope。

### 4.4 验证确认恢复

让 ReActAgent 调用权限为 ASK 的工具。第一轮成功判据：Task 为 `INPUT_REQUIRED`，且：

```text
status.message.metadata._interrupt.payload.kind = confirmation
```

使用第一轮实际返回的 taskId/contextId 发送第二轮 `SendMessage`，TextPart 为 `APPROVE` 或 `REJECT`。
不要回传 `_interrupt` 或 AgentScope 内部 ID。成功判据：Adapter 从 AgentState 构造 ConfirmResult，Task
离开 INPUT_REQUIRED；批准时工具执行，拒绝时工具不执行。

### 4.5 验证外部工具结果恢复

让 HarnessAgent 调用 schema-only external tool。第一轮 interrupt 的 `payload.kind` 应为 `tool_result`，
item 包含 `name` 和 `arguments`。客户端按这些参数调用外部系统，再把结果文本作为同一 Task 的第二轮
TextPart。成功判据：Adapter 使用 pending tool 的内部 ID 构造 ToolResultBlock，Agent 继续并完成。

## 5. 排障

| 现象 | Versatile | AgentScope |
|---|---|---|
| 应用没有 Handler | 确认显式注册 `new VersatileAgentHandler(properties)` | 确认显式调用 `forReActAgent` 或 `forHarnessAgent` |
| 请求直接失败 | 检查 URL、Header、messages.required 和远端 HTTP 状态 | 检查 conversationId、有效消息和 Agent 构造 |
| 流提前成功结束 | 确认远端出现合法 End、结果或中断事件 | Flux 必须出现最终 AgentResultEvent 或可识别 interrupt |
| 中断无法恢复 | 检查三个 JSON Pointer 和恢复模板 | 使用原 taskId/contextId，确认 kind 与 AgentState pending 状态一致 |
| 输出目标错误 | 检查 agent_id 和 intent 映射，PRIORITY 为较小值优先 | 确认当前 session 未被清理且请求落到保存状态的实例 |
| 日志泄露参数 | 保持 `log-mask-sensitive=true` 并控制 Adapter 日志级别；该开关不遮蔽远端响应行、聚合结果和非 2xx 响应 Body | 不在生产开启 AgentScope 模型客户端 DEBUG 日志 |

## 6. 相关文档

- [Versatile Adapter 特性](../features/versatile-adapter.md)
- [AgentScope Adapter 特性](../features/agentscope-adapter.md)
- [配置参考](../configuration.md)
- [入口与数据契约](../entrypoints-and-contracts.md)
