# 接入 Custom REST

本指南在已有 Servlet Agent Runtime 应用中增加一个宿主自定义的 REST 入口。示例协议接受
`{input, stream}`，路径中的 `conversation_id` 作为 A2A contextId；真实业务可以替换这些字段，
但必须遵守框架的 SPI 和传输约束。

## 1. 前置条件

- 应用使用 Spring MVC，而不是纯 WebFlux。
- 应用已经能创建 A2A `RequestHandler` 和 `TaskStore`。
- 已注册一个可工作的 `AgentHandler`。
- JDK 17 或更高版本。

## 2. 添加依赖

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-app-custom-rest</artifactId>
  <version>0.1.1</version>
</dependency>
```

## 3. 实现协议适配器

下面是最小实现。它把 Body 的 `input` 转成 A2A TextPart，把 Task 或流事件放入统一 response 字段。

```java
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;

import java.util.Map;
import java.util.UUID;

final class DemoRestAdapter implements CustomRestProtocolAdapter {
    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        String conversationId = context.pathVariables().get("conversation_id");
        String input = String.valueOf(context.body().getOrDefault("input", ""));
        Message message = Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId(UUID.randomUUID().toString())
            .contextId(conversationId)
            .parts(new TextPart(input))
            .build();
        MessageSendParams params = MessageSendParams.builder()
            .message(message)
            .metadata(Map.of("body", context.body()))
            .build();
        boolean stream = !Boolean.FALSE.equals(context.body().get("stream"));
        return new A2ASendCommand(params, stream);
    }

    @Override
    public Object fromA2ATask(Task task, Context context) {
        return Map.of("success", true, "response", task);
    }

    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        return new SseEvent("message", Map.of("success", true, "response", event));
    }

    @Override
    public Object fromError(CustomRestError error, Context context) {
        return Map.of("success", false,
            "error", Map.of("code", error.code(), "message", error.message()));
    }

    @Override
    public SseEvent fromStreamError(CustomRestError error, Context context) {
        return new SseEvent("error", fromError(error, context));
    }
}
```

生产实现应定义稳定 DTO，不要直接把完整 A2A Task 暴露成长期业务契约。上例只用于展示转换位置。

## 4. 注册 Bean

```java
@Configuration
class CustomRestConfiguration {
    @Bean
    CustomRestProtocolAdapter customRestProtocolAdapter() {
        return new DemoRestAdapter();
    }
}
```

一个应用只注册一个目标 Adapter。若存在多个同类型 Bean，需要由宿主显式指定唯一实现。

## 5. 配置路径

```yaml
openjiuwen:
  service:
    query:
      webflux:
        enabled: false
    custom-rest:
      query-path: /v1/projects/{project_id}/agents/{agent_id}/conversations/{conversation_id}
```

这里只配置传输路径。Body Schema、stream 字段和 response 信封都由 `DemoRestAdapter` 定义。

## 6. 验证非流式请求

```powershell
$conversationId = "custom-rest-" + [Guid]::NewGuid().ToString("N")
$url = "http://127.0.0.1:8080/v1/projects/demo/agents/demo-agent/conversations/$conversationId"
$body = @{
  input = "介绍一下你自己"
  stream = $false
} | ConvertTo-Json -Depth 20

Invoke-RestMethod `
  -Uri $url `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Headers @{Accept = "application/json"} `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

成功判据：HTTP 200、Content-Type 为 JSON，并且 Body 符合 `fromA2ATask` 定义的信封。

## 7. 验证流式请求

```powershell
$body = @{
  input = "分步骤回答这个问题"
  stream = $true
} | ConvertTo-Json -Depth 20

curl.exe -N `
  -X POST $url `
  -H "Content-Type: application/json" `
  -H "Accept: text/event-stream" `
  --data-binary $body
```

成功判据：HTTP 200、Content-Type 为 `text/event-stream`，收到一个或多个 `message` 事件，并在
A2A Task final 或 interrupted 后结束。若 `A2ASendCommand.stream=true` 但请求不接受 SSE，应得到 406。

## 8. 验证续轮

让 Agent 首轮进入 `INPUT_REQUIRED`，然后使用相同 `conversation_id` 再次调用 Custom REST。
Adapter 不需要自行查询 Task ID；只要新命令没有显式 taskId，Bridge 会查找该会话唯一的
`INPUT_REQUIRED` 正式 Task 并自动续接。

成功判据：第二轮续接原 Task，而不是创建并行正式 Task。同一会话仍在 WORKING 时重复调用应返回
409 `conversation_busy`。

## 9. 排障

| 现象 | 检查项 |
|---|---|
| 路由 404 | `query-path` 是否配置、是否为 Servlet 应用、模块是否在运行时类路径 |
| 应用启动失败缺少 Bean | 是否注册 `CustomRestProtocolAdapter`，Runtime 是否提供 RequestHandler 和 TaskStore |
| 400 `invalid_json` | Body 是否为合法 JSON，编码是否为 UTF-8 |
| 400 `invalid_custom_request` | JSON 根节点是否为对象，Adapter 是否设置非空 contextId |
| 406 `stream_not_acceptable` | Adapter 返回的 stream 与请求 Accept 是否一致 |
| 409 conversation 错误 | 是否复用了仍在执行或存在冲突 Task 的 conversationId |
| 终态 SSE 后同会话持续 409 | 正式父 Task 是否在终态事件到达前已写入 TaskStore；不可观察终态不会释放当前进程 reservation |
| SSE 建立后出现 error | 检查事件投影是否返回非空、可序列化 data，event 名是否含换行 |
| 500 `adapter_execution_failed` | 检查 SPI 是否返回 null、非 Task blocking 结果或不可序列化对象 |

## 10. 相关文档

- [Custom REST 特性](../features/custom-rest.md)
- [配置参考](../configuration.md)
- [入口与数据契约](../entrypoints-and-contracts.md)
