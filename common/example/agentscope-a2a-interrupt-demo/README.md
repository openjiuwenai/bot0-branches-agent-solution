# AgentScope A2A 中断恢复示例

本示例用于手工验证以下链路：

```text
A2A JSON-RPC 请求
  -> OpenJiuwen runtime
  -> AgentScope adapter
  -> ReActAgent / HarnessAgent
  -> execute_transfer 工具触发人工确认
  -> A2A Task 进入 INPUT_REQUIRED
  -> 客户端在同一 Task 中提交 APPROVE/REJECT 消息
  -> adapter 转换为 AgentScope ConfirmResult
  -> AgentScope 从原会话恢复并完成执行
```

示例包含两个独立服务：

| 服务 | AgentScope 智能体 | 默认端口 | A2A 地址 |
|---|---|---:|---|
| `react-runtime` | `ReActAgent` | `18110` | `http://127.0.0.1:18110/a2a/` |
| `harness-runtime` | `HarnessAgent` | `18111` | `http://127.0.0.1:18111/a2a/` |

两个服务都通过 AgentScope 的 OpenAI 兼容模型接入 DeepSeek，并注册 `execute_transfer` 工具。该工具配置了 `ASK` 权限规则，因此模型发起工具调用后不会立即执行，而是先触发人工确认中断。

API key 只能通过进程环境变量传入，不要写入代码、YAML、README 或请求报文。

## 1. 前置条件

- JDK 17 或更高版本。
- Maven 3.9 或更高版本。
- 本地可以访问 `https://api.deepseek.com`。
- 已获得可用的 DeepSeek API key。
- 第 2 节从同时包含 `agent-runtime-java` 和 `agent-solution` 的父目录执行；第 2 节结束后会位于 `agent-solution` 根目录，后续命令均在该目录执行。

建议先设置 PowerShell UTF-8 输出：

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"
```

## 2. 安装本地 runtime 和 adapter

adapter 会输出裸 interrupt data；runtime 复用已有 `_interrupt` 约定，把该数据放入 A2A status `Message.metadata._interrupt`。当前 A2A SDK 在第二轮进入 executor 前会把上一轮 status message 移入 history，再追加本轮 user message；runtime 从 status message 或 history 中最近一条 agent message 读取 `_interrupt` 并带给 adapter。因此先安装本地修改后的 `agent-runtime-java`：

```powershell
Set-Location "agent-runtime-java"

mvn -f "pom.xml" `
  -pl service/agent-service-app -am `
  "-DskipTests" `
  install

Set-Location "..\agent-solution"
```

安装 AgentScope adapter：

```powershell
mvn -f "common\agent-runtime-ext-java\pom.xml" `
  -pl agent-service-adapters/agent-service-adapters-agentscope -am `
  clean install
```

构建两个示例：

```powershell
mvn -f "common\example\agentscope-a2a-interrupt-demo\pom.xml" `
  clean package
```

预期 Maven 最终输出：

```text
[INFO] react-runtime ................................ SUCCESS
[INFO] harness-runtime .............................. SUCCESS
[INFO] BUILD SUCCESS
```

## 3. 设置真实模型环境变量

在每个启动服务的 PowerShell 窗口中设置：

```powershell
$env:DEEPSEEK_API_KEY = "<你的 DeepSeek API key>"
$env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
$env:DEEPSEEK_MODEL = "deepseek-chat"
```

## 4. 启动 ReActAgent 服务

新开一个 PowerShell 窗口，进入仓库根目录，设置第 3 节的模型环境变量，然后执行：

```powershell
$env:REACT_AGENT_PORT = "18110"

mvn -f "common\example\agentscope-a2a-interrupt-demo\react-runtime\pom.xml" `
  spring-boot:run
```

看到以下关键信息表示启动成功：

```text
Registered tool 'execute_transfer'
Tomcat started on port 18110
agent_loaded=true
```

可检查 Agent Card：

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:18110/.well-known/agent-card.json" `
  -Method Get | ConvertTo-Json -Depth 20
```

## 5. 启动 HarnessAgent 服务

Harness 与 ReAct 是两个独立应用。新开另一个 PowerShell 窗口，进入仓库根目录，设置第 3 节的模型环境变量，然后执行：

```powershell
$env:HARNESS_AGENT_PORT = "18111"
$env:AGENTSCOPE_HARNESS_WORKSPACE = "target/agentscope-harness-workspace"

mvn -f "common\example\agentscope-a2a-interrupt-demo\harness-runtime\pom.xml" `
  spring-boot:run
```

看到以下关键信息表示启动成功：

```text
Registered tool 'execute_transfer'
HarnessAgent 'runtime-harness-agent' built
Tomcat started on port 18111
agent_loaded=true
```

可检查 Harness Agent Card：

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:18111/.well-known/agent-card.json" `
  -Method Get | ConvertTo-Json -Depth 20
```

## 6. 手工发送第一轮中断请求

新开一个 PowerShell 窗口。下面默认请求 ReAct 服务；验证 Harness 时，只需将 `$a2aUrl` 改成 `http://127.0.0.1:18111/a2a/`。

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$a2aUrl = "http://127.0.0.1:18110/a2a/"
$contextId = "agentscope-manual-" + [Guid]::NewGuid().ToString("N")

$request1 = [ordered]@{
  jsonrpc = "2.0"
  id = "request-1"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      contextId = $contextId
      parts = @(
        [ordered]@{
          text = "请调用 execute_transfer 工具，向 Li Ming 转账 5 元。"
        }
      )
    }
  }
}

$requestJson1 = $request1 | ConvertTo-Json -Depth 100

$response1 = Invoke-RestMethod `
  -Uri $a2aUrl `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($requestJson1))

$response1 | ConvertTo-Json -Depth 100
```

## 7. 第一轮预期返回

第一轮响应中的关键结构应类似：

```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "result": {
    "task": {
      "id": "runtime-generated-task-id",
      "contextId": "agentscope-manual-runtime-generated-id",
      "status": {
        "state": "TASK_STATE_INPUT_REQUIRED",
        "message": {
          "role": "ROLE_AGENT",
          "parts": [
            {
              "text": "The following operation requires confirmation."
            }
          ],
          "metadata": {
            "_interrupt": {
              "type": "__interaction__",
              "index": 0,
              "payload": {
                "kind": "confirmation",
                "items": [
                  {
                    "type": "tool_call",
                    "name": "execute_transfer"
                  }
                ]
              },
              "message": "The following operation requires confirmation.",
              "context": {
                "_interrupt_kind": "ask_user"
              }
            }
          }
        }
      }
    }
  }
}
```

必须检查：

- `result.task.status.state` 是 `TASK_STATE_INPUT_REQUIRED`。
- `result.task.status.message.metadata._interrupt.type` 是 `__interaction__`。
- `interaction.index` 是 `0`，外层结构与九问 Core interaction 协议一致。
- `interaction.payload.kind` 是 `confirmation`。
- `interaction.payload.items[0].name` 是 `execute_transfer`。
- `interaction.payload.items[0]` 只展示待确认工具的名称，不输出工具参数，也不暴露 AgentScope 内部 tool-call ID。
- 返回内容中没有 AgentScope Java 类名或序列化对象。

在 PowerShell 中提取第二轮所需字段：

```powershell
$task = $response1.result.task
$taskId = $task.id
$returnedContextId = $task.contextId
$interaction = $task.status.message.metadata._interrupt

$taskId
$returnedContextId
$interaction | ConvertTo-Json -Depth 100
```

`taskId` 和 `contextId` 都是运行时动态生成的。第二轮必须使用本次第一轮实际返回的值，不能手工编造，也不能复用旧 Task 的值。

## 8. 手工发送第二轮批准恢复请求

在发送第一轮请求的同一个 PowerShell 窗口继续执行：

```powershell
$request2 = [ordered]@{
  jsonrpc = "2.0"
  id = "request-2"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      taskId = $taskId
      contextId = $returnedContextId
      parts = @(
        [ordered]@{
          text = "APPROVE"
        }
      )
    }
  }
}

$requestJson2 = $request2 | ConvertTo-Json -Depth 100

$response2 = Invoke-RestMethod `
  -Uri $a2aUrl `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($requestJson2))

$response2 | ConvertTo-Json -Depth 100
```

第二轮必须在第一轮返回 `INPUT_REQUIRED` 后发送；同一 `contextId` 存在在途调用时，adapter 会直接拒绝后到请求，不在内部排队。第二轮不需要 `params.metadata`，也不需要回传 interaction item 或 AgentScope tool-call ID。runtime 根据 `taskId` 在 TaskStore 中命中第一轮 Task 后，只把上一轮 status message（当前 SDK 续轮时已移入 history）的 `_interrupt` 带入 `ServeRequest.metadata`；adapter 自行解析其 `payload.kind`，再用相同 `contextId` 读取 AgentScope 当前 pending state，使用其中真实的 tool-call ID 构造 `ConfirmResult`。这些内部数据都不由客户端填写。

正常恢复无需客户端填写 `params.metadata._interrupt`。同 Task 续轮时，runtime 只使用 TaskStore status/history message 中的 `_interrupt`；新 Task 仍不清理客户端主动提交的该保留字段，因此示例服务仅应向可信 A2A 客户端开放。

当 pending interaction 是确认中断时，adapter 只接受精确的 `APPROVE` 或 `REJECT`（忽略大小写和首尾空白），并把该动作转换为 metadata-only AgentScope `UserMessage`：

```text
Msg.METADATA_CONFIRM_RESULTS -> List<ConfirmResult>
```

`APPROVE`/`REJECT` 不会作为普通文本传给 AgentScope。`"同意"`、`"可以"`、`"confirm"` 等自然语言不会被猜测为批准；如果当前确认中断仍存在，这些输入会被拒绝。

## 9. 批准后的预期返回

检查第二轮 Task 状态：

```powershell
$response2.result.task.status.state
$response2.result.task.artifacts | ConvertTo-Json -Depth 100
```

预期结果：

- Task 不再是 `TASK_STATE_INPUT_REQUIRED`。
- AgentScope 使用原 `(userId, sessionId)` 中保存的 pending tool state 恢复。
- `execute_transfer` 实际执行一次。
- 最终 Task 通常为 `TASK_STATE_COMPLETED`。
- 最终回答应表达转账已经执行，并包含工具返回的事实：

```text
Transfer executed: CNY 5.0 to Li Ming
```

模型生成的自然语言可能不同，因此不要求最终回答逐字一致；验收以 Task 完成、工具执行一次、回答语义包含工具结果为准。

## 10. 手工验证拒绝恢复

重新使用一个新的 `$contextId` 发送第 6 节第一轮请求。收到新的 `INPUT_REQUIRED` 后，第二轮只需把消息正文改成 `REJECT`：

```powershell
$request2.params.message.parts[0].text = "REJECT"
```

其余第二轮报文与第 8 节一致。

预期结果：

- Task 不再是 `TASK_STATE_INPUT_REQUIRED`。
- AgentScope 使用 `ConfirmResult(false, pendingTool)` 恢复。
- `execute_transfer` 不执行。
- Task 进入完成态或明确的拒绝终态。
- 最终回答说明操作未执行或被拒绝。

## 11. 分别验证 ReAct 和 Harness

两套验证报文完全相同，只有 URL 不同：

```powershell
# ReActAgent
$a2aUrl = "http://127.0.0.1:18110/a2a/"

# HarnessAgent
$a2aUrl = "http://127.0.0.1:18111/a2a/"
```

ReAct 与 Harness 的预期 A2A 协议结果相同。Harness 的区别仅在 adapter 内部通过 `HarnessAgent.getDelegate()` 读取 ReAct state 并执行 session 定向中断，上层请求与响应不应出现 Harness 或 ReAct 的框架类型。

## 12. 常见问题

### 第一轮直接完成，没有进入 INPUT_REQUIRED

检查：

- Prompt 是否明确要求调用 `execute_transfer`。
- 模型是否为支持 tool calling 的 `deepseek-chat`。
- 服务日志是否出现 `execute_transfer` tool call。
- AgentScope permission context 是否仍包含 `execute_transfer -> ASK` 规则。

### 第一轮是 INPUT_REQUIRED，但没有 metadata._interrupt

说明服务使用的不是本地修改后的 `agent-service-app:0.1.0`。重新执行第 2 节的 runtime `install`，然后重启示例服务。

### 第二轮提示当前没有待恢复中断

确认第二轮使用的是第一轮实际返回的 `taskId` 和 `contextId`，并且该 Task 尚未被批准、拒绝或取消。adapter 会读取该 AgentScope session 当前唯一的 pending interaction，不要求客户端回传内部 item ID。

### 第二轮把 APPROVE 当成普通用户消息

确认第二轮同时携带第一轮返回的 `taskId` 和 `contextId`。如果遗漏或编造 `taskId`，runtime 会把它作为新 Task，adapter 不会进入恢复分支。第二轮不需要发送 `params.metadata`。

### Harness 提示 workspace 不存在

可在启动前创建目录：

```powershell
New-Item `
  -ItemType Directory `
  -Path "common\example\agentscope-a2a-interrupt-demo\harness-runtime\target\agentscope-harness-workspace" `
  -Force | Out-Null
```

本示例已关闭 Harness 的 workspace context、filesystem、shell、memory、subagent 和 dynamic skill 能力；workspace 只用于满足 HarnessAgent 的基础构造约束。
