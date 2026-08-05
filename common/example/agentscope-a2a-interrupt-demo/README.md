# AgentScope A2A 中断恢复示例

本示例用两个独立服务验证 AgentScope adapter 的两种中断恢复链路：

```text
ReActAgent
  -> execute_transfer 触发 RequireUserConfirmEvent
  -> adapter 输出 confirmation 中断
  -> 客户端提交 APPROVE/REJECT
  -> adapter 构造 ConfirmResult 恢复 AgentScope

HarnessAgent
  -> external_lookup 产生 TOOL_SUSPENDED
  -> adapter 输出 tool_result 中断
  -> 客户端读取 arguments 并在外部执行工具
  -> 客户端提交外部工具结果文本
  -> adapter 构造 ToolResultBlock 恢复 AgentScope
```

示例包含两个独立服务：

| 服务 | AgentScope 智能体 | 中断 kind | 续轮输入 | 默认端口 |
|---|---|---|---|---:|
| `react-runtime` | `ReActAgent` | `confirmation` | `APPROVE`/`REJECT` | `18110` |
| `harness-runtime` | `HarnessAgent` | `tool_result` | 外部工具结果文本 | `18111` |

两个服务都通过 AgentScope 的 OpenAI 兼容模型接入 DeepSeek。ReAct 注册带 `ASK` 权限规则的本地 `execute_transfer` 工具；Harness 注册没有本地实现的 schema-only `external_lookup` 工具。后者由 AgentScope 标记为 external tool，模型调用时暂停，等待调用方在续轮中提供执行结果。

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
Registered tool 'external_lookup'
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

## 6. 验证 ReActAgent confirmation 中断恢复

本章只验证 ReActAgent 的人工确认中断。请在同一个 PowerShell 窗口中依次执行第一轮请求和第二轮恢复请求。

### 6.1 发送第一轮请求

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$reactUrl = "http://127.0.0.1:18110/a2a/"
$reactContextId = "agentscope-react-" + [Guid]::NewGuid().ToString("N")

$reactRequest1 = [ordered]@{
  jsonrpc = "2.0"
  id = "react-request-1"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      contextId = $reactContextId
      parts = @(
        [ordered]@{
          text = "请调用 execute_transfer 工具，向 Li Ming 转账 5 元。"
        }
      )
    }
  }
}

$reactRequestJson1 = $reactRequest1 | ConvertTo-Json -Depth 100
$reactResponse1 = Invoke-RestMethod `
  -Uri $reactUrl `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($reactRequestJson1))

$reactResponse1 | ConvertTo-Json -Depth 100
```

### 6.2 第一轮预期返回

第一轮 Task 必须进入 `TASK_STATE_INPUT_REQUIRED`，关键结构如下：

```json
{
  "jsonrpc": "2.0",
  "id": "react-request-1",
  "result": {
    "task": {
      "id": "runtime-generated-task-id",
      "contextId": "agentscope-react-runtime-generated-id",
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

- `_interrupt.type` 是 `__interaction__`。
- `_interrupt.payload.kind` 是 `confirmation`。
- `_interrupt.payload.items[0].name` 是 `execute_transfer`。
- confirmation item 不包含 `arguments`，也不包含 AgentScope 内部 tool-call ID。

### 6.3 发送第二轮批准请求

从第一轮实际响应中提取 `taskId` 和 `contextId`，不要手工编造：

```powershell
$reactTask = $reactResponse1.result.task
$reactTaskId = $reactTask.id
$reactReturnedContextId = $reactTask.contextId
$reactInteraction = $reactTask.status.message.metadata._interrupt

$reactTaskId
$reactReturnedContextId
$reactInteraction | ConvertTo-Json -Depth 100

$reactResumeText = "APPROVE"

$reactRequest2 = [ordered]@{
  jsonrpc = "2.0"
  id = "react-request-2"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      taskId = $reactTaskId
      contextId = $reactReturnedContextId
      parts = @(
        [ordered]@{
          text = $reactResumeText
        }
      )
    }
  }
}

$reactRequestJson2 = $reactRequest2 | ConvertTo-Json -Depth 100
$reactResponse2 = Invoke-RestMethod `
  -Uri $reactUrl `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($reactRequestJson2))

$reactResponse2 | ConvertTo-Json -Depth 100
```

adapter 只接受精确的 `APPROVE` 或 `REJECT`（忽略大小写和首尾空白），并将动作转换为：

```text
Msg.METADATA_CONFIRM_RESULTS -> List<ConfirmResult>
```

`APPROVE` 不会作为普通用户文本传给 AgentScope。

### 6.4 批准后的预期返回

```powershell
$reactResponse2.result.task.status.state
$reactResponse2.result.task.artifacts | ConvertTo-Json -Depth 100
```

预期结果：

- Task 不再是 `TASK_STATE_INPUT_REQUIRED`，最终通常为 `TASK_STATE_COMPLETED`。
- AgentScope 使用原 `(userId, sessionId)` 中的 pending tool state 恢复。
- `execute_transfer` 实际执行一次。
- 最终回答表达转账已经执行，并包含工具结果，例如 `Transfer executed: CNY 5.0 to Li Ming`。

模型生成的自然语言可能不同，验收以 Task 完成、工具执行一次、回答语义包含工具结果为准。

### 6.5 验证拒绝恢复

重新执行第 6.1 节，使用新的 `contextId` 获得新的 `INPUT_REQUIRED` Task。然后执行第 6.3 节时，把恢复动作变量改为：

```powershell
$reactResumeText = "REJECT"
```

预期 `execute_transfer` 不执行，Task 离开 `INPUT_REQUIRED`，最终回答说明操作未执行或被拒绝。

## 7. 验证 HarnessAgent tool_result 中断恢复

本章只验证 HarnessAgent 的 schema-only external tool 中断。请另开一个 PowerShell 窗口，在同一窗口中依次执行两轮请求。

### 7.1 发送第一轮请求

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$harnessUrl = "http://127.0.0.1:18111/a2a/"
$harnessContextId = "agentscope-harness-" + [Guid]::NewGuid().ToString("N")

$harnessRequest1 = [ordered]@{
  jsonrpc = "2.0"
  id = "harness-request-1"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      contextId = $harnessContextId
      parts = @(
        [ordered]@{
          text = "请查询客户 C001 的账户等级。"
        }
      )
    }
  }
}

$harnessRequestJson1 = $harnessRequest1 | ConvertTo-Json -Depth 100
$harnessResponse1 = Invoke-RestMethod `
  -Uri $harnessUrl `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($harnessRequestJson1))

$harnessResponse1 | ConvertTo-Json -Depth 100
```

### 7.2 第一轮预期返回

第一轮 Task 必须进入 `TASK_STATE_INPUT_REQUIRED`，关键结构如下：

```json
{
  "jsonrpc": "2.0",
  "id": "harness-request-1",
  "result": {
    "task": {
      "id": "runtime-generated-task-id",
      "contextId": "agentscope-harness-runtime-generated-id",
      "status": {
        "state": "TASK_STATE_INPUT_REQUIRED",
        "message": {
          "role": "ROLE_AGENT",
          "parts": [
            {
              "text": "The following tool call requires an external result."
            }
          ],
          "metadata": {
            "_interrupt": {
              "type": "__interaction__",
              "index": 0,
              "payload": {
                "kind": "tool_result",
                "items": [
                  {
                    "type": "tool_call",
                    "name": "external_lookup",
                    "arguments": {
                      "customer_id": "C001",
                      "attribute": "account_tier"
                    }
                  }
                ]
              },
              "message": "The following tool call requires an external result.",
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

- `_interrupt.payload.kind` 是 `tool_result`。
- `_interrupt.payload.items[0].name` 是 `external_lookup`。
- `arguments.customer_id` 是 `C001`。
- `arguments.attribute` 表达待查询的账户等级，预期为 `account_tier`。
- item 不包含 AgentScope 内部 tool-call ID。
- `external_lookup` 没有在 Harness 进程内执行，Task 正在等待外部结果。

`arguments` 由模型根据工具 schema 生成，字段名固定，具体字符串值可能受模型输出影响。外部执行方应按 `name + arguments` 调用真实外部系统。

### 7.3 发送第二轮工具结果

先提取并检查本次中断中的两个工具参数，再模拟外部系统完成查询：

```powershell
$harnessTask = $harnessResponse1.result.task
$harnessTaskId = $harnessTask.id
$harnessReturnedContextId = $harnessTask.contextId
$harnessInteraction = $harnessTask.status.message.metadata._interrupt
$harnessArguments = $harnessInteraction.payload.items[0].arguments

$harnessTaskId
$harnessReturnedContextId
$harnessArguments | ConvertTo-Json -Depth 20

# 实际系统应使用 $harnessArguments.customer_id 和
# $harnessArguments.attribute 调用外部服务，这里直接填写模拟结果。
$externalResult = "客户 C001 的账户等级为 PREMIUM。"

$harnessRequest2 = [ordered]@{
  jsonrpc = "2.0"
  id = "harness-request-2"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      taskId = $harnessTaskId
      contextId = $harnessReturnedContextId
      parts = @(
        [ordered]@{
          text = $externalResult
        }
      )
    }
  }
}

$harnessRequestJson2 = $harnessRequest2 | ConvertTo-Json -Depth 100
$harnessResponse2 = Invoke-RestMethod `
  -Uri $harnessUrl `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($harnessRequestJson2))

$harnessResponse2 | ConvertTo-Json -Depth 100
```

adapter 接受第二轮正文作为外部工具结果，结合 AgentScope pending state 中的真实 tool-call ID 和工具名构造：

```text
Msg(role=TOOL, content=ToolResultBlock)
```

客户端不需要回传 `arguments` 或 AgentScope tool-call ID。第二轮正文会成为 `ToolResultBlock.output`，而不是新的用户问题。

### 7.4 恢复后的预期返回

```powershell
$harnessResponse2.result.task.status.state
$harnessResponse2.result.task.artifacts | ConvertTo-Json -Depth 100
```

预期结果：

- Task 不再是 `TASK_STATE_INPUT_REQUIRED`，最终通常为 `TASK_STATE_COMPLETED`。
- adapter 使用 AgentScope pending state 中的真实 ID 构造 `ToolResultBlock`。
- 外部执行方使用第一轮的 `customer_id + attribute` 执行查询。
- `external_lookup` 没有本地方法体，也不会在 Harness 进程中执行。
- Harness 使用第二轮工具结果继续推理，最终回答包含客户 `C001` 的账户等级 `PREMIUM`。

## 8. 恢复协议公共约束

- 第二轮必须在第一轮返回 `INPUT_REQUIRED` 后发送，并使用第一轮实际返回的 `taskId` 和 `contextId`。
- 第二轮不需要 `params.metadata`，也不需要主动回传 `_interrupt`、interaction item 或 AgentScope tool-call ID。
- runtime 只在原 Task 仍为 `INPUT_REQUIRED` 时，从 TaskStore 当前 status 或 history 中最近的有效 agent message 读取 `_interrupt` 并带给 adapter。
- runtime 会清理客户端主动提交的 `params.metadata._interrupt`。新 Task、非 `INPUT_REQUIRED` Task 或找不到存储值时都不会进入恢复分支。
- adapter 使用相同 `contextId` 读取 AgentScope 当前 pending state；当前只支持 session 中恰好一个待恢复的 external tool call。
- 同一 `contextId` 已有在途调用时，adapter 拒绝后到请求，不在内部排队。

## 9. 常见问题

### 第一轮直接完成，没有进入 INPUT_REQUIRED

验证 ReAct 时检查：

- Prompt 是否明确要求调用 `execute_transfer`。
- 模型是否为支持 tool calling 的 `deepseek-chat`。
- 服务日志是否出现 `execute_transfer` tool call。
- AgentScope permission context 是否仍包含 `execute_transfer -> ASK` 规则。

验证 Harness 时检查：

- 请求是否明确要求查询外部信息。
- 服务日志是否显示模型调用 `external_lookup`。
- `external_lookup` 是否仍通过 `Toolkit.registerSchema(...)` 注册；不要替换为本地 `registerTool(...)`。

### 第一轮是 INPUT_REQUIRED，但没有 metadata._interrupt

说明服务使用的不是本地修改后的 `agent-service-app:0.1.0`。重新执行第 2 节的 runtime `install`，然后重启示例服务。

### 第二轮提示当前没有待恢复中断

确认第二轮使用的是第一轮实际返回的 `taskId` 和 `contextId`，并且该 Task 尚未被批准、拒绝或取消。adapter 会读取该 AgentScope session 当前唯一的 pending interaction，不要求客户端回传内部 item ID。

### ReAct 第二轮把 APPROVE 当成普通用户消息

确认第二轮同时携带第一轮返回的 `taskId` 和 `contextId`。如果遗漏或编造 `taskId`，runtime 会把它作为新 Task，adapter 不会进入恢复分支。第二轮不需要发送 `params.metadata`。

### Harness 第二轮没有把正文作为工具结果

确认第一轮 `_interrupt.payload.kind` 是 `tool_result`，第二轮携带第一轮实际返回的 `taskId` 和 `contextId`。如果第一轮 kind 是 `confirmation`，说明运行的仍是旧版 Harness 示例。

### Harness 第一轮没有 arguments

确认使用的是最新 AgentScope adapter，并重新执行第 2 节的 adapter `clean install`。`tool_result` item 必须包含 `arguments`；只有 `confirmation` item 会刻意省略参数。

### Harness 提示 workspace 不存在

可在启动前创建目录：

```powershell
New-Item `
  -ItemType Directory `
  -Path "common\example\agentscope-a2a-interrupt-demo\harness-runtime\target\agentscope-harness-workspace" `
  -Force | Out-Null
```

本示例已关闭 Harness 的 workspace context、filesystem、shell、memory、subagent 和 dynamic skill 能力；workspace 只用于满足 HarnessAgent 的基础构造约束。
