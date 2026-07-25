# AgentCore Ext Client Tool DeepAgent Demo

这个 example 启动一个 `agent-runtime-java` 进程，进程内挂载真实 `DeepAgent` 和
`JiuwenCoreAgentExtHandler`。客户端通过 A2A `params.metadata.clientTools` 声明本轮可用工具；
DeepAgent 使用真实大模型决定调用工具后，服务端把调用投影为 `INPUT_REQUIRED`。客户端在本地
模拟执行工具，再使用原 `taskId` 提交结果，DeepAgent 恢复并生成最终回答。

示例覆盖：

- 单工具：`getLocalWeather`，只有一个参数 `city`；恢复时演示单 pending 不携带 `toolCallId`。
- 多工具：同一模型轮同时调用 `getLocalWeather` 和 `createCalendarEvent`；后者包含
  `title`、`date`、`durationMinutes` 三个参数，恢复时每个结果按 `toolCallId` 定向。
- mock 结果会包含第一轮 `_interrupt.context.arguments` 的原值，最终模型回答可以看到真实调用参数。

API Key 只通过环境变量传入，不要写入仓库文件。

## 完整调用流程

```text
A2A SendMessage + params.metadata.clientTools
  -> Runtime 将 metadata 交给 JiuwenCoreAgentExtHandler
  -> ClientToolRail.bind 为当前请求给 DeepAgent 内部 ReActAgent 注册 rail
  -> ClientToolRail.beforeModelCall 将本轮工具定义注入真实模型请求
  -> DeepSeek 返回一个或多个 ToolCall
  -> ClientToolRail.beforeToolCall 生成 client_tool interrupt
  -> Runtime 保存 _interrupt，Task 进入 INPUT_REQUIRED
  -> 客户端读取 toolName、toolCallId、arguments 并在本地执行
  -> A2A continuation 使用原 taskId 提交 TextPart 结果
  -> 单 pending 可省略 toolCallId；多 pending 必须逐项携带 toolCallId
  -> Solution 校验完整目标集合并恢复 Core ToolCall
  -> DeepAgent 使用工具 observation 再次调用真实模型，Task 最终完成
```

工具定义和临时 rail 都只属于当前 Handler 调用，不进入共享 `AbilityManager`。本示例在恢复请求中
刻意不重传 `clientTools`，因此恢复后的模型只消费已完成的结果，不会继续看到上一轮工具目录。

## 1. 打包安装 Core、Runtime、Solution

在 PowerShell 中执行。三个仓库共用同一个隔离 Maven 本地仓库，确保示例使用的是本地最新代码：

```powershell
mvn `
  -f "..\agent-core-java\pom.xml" `
  clean install

mvn `
  -f "..\agent-runtime-java\pom.xml" `
  clean install

mvn `
  -f "common\agent-runtime-ext-java\pom.xml" `
  clean install

mvn `
  -f "common\example\agentcore-ext-client-tool-deepagent-demo\pom.xml" `
  clean package
```

## 2. 启动 Runtime + DeepAgent

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

$env:CLIENT_TOOL_DEMO_PORT = "18210"
$env:DEEPSEEK_API_KEY = "<your-deepseek-api-key>"
$env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
$env:DEEPSEEK_MODEL = "deepseek-chat"

mvn `
  -f "common\example\agentcore-ext-client-tool-deepagent-demo\pom.xml" `
  spring-boot:run
```

A2A endpoint：`http://127.0.0.1:18210/a2a/`。

## 3. PowerShell 公共函数

另开一个 PowerShell 窗口，执行：

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$a2aUrl = "http://127.0.0.1:18210/a2a/"

function Invoke-ClientToolA2A {
  param([System.Collections.IDictionary] $Request)

  $json = $Request | ConvertTo-Json -Depth 100
  Write-Host "Request:"
  Write-Host $json
  $response = Invoke-RestMethod `
    -Uri $a2aUrl `
    -Method Post `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
  Write-Host "Response:"
  Write-Host ($response | ConvertTo-Json -Depth 100)
  return $response
}

function New-ClientToolView {
  return @(
    [ordered]@{
      name = "getLocalWeather"
      description = "读取指定城市的本地实时天气；只有客户端可以执行。"
      inputSchema = [ordered]@{
        type = "object"
        properties = [ordered]@{
          city = [ordered]@{ type = "string"; description = "城市名称" }
        }
        required = @("city")
        additionalProperties = $false
      }
    },
    [ordered]@{
      name = "createCalendarEvent"
      description = "在客户端本地日历创建事件；只有客户端可以执行。"
      inputSchema = [ordered]@{
        type = "object"
        properties = [ordered]@{
          title = [ordered]@{ type = "string"; description = "事件标题" }
          date = [ordered]@{ type = "string"; description = "日期，YYYY-MM-DD" }
          durationMinutes = [ordered]@{ type = "integer"; description = "持续分钟数" }
        }
        required = @("title", "date", "durationMinutes")
        additionalProperties = $false
      }
    }
  )
}
```

## 4. 单工具注入与恢复

第一轮把完整 ToolView 放在 `params.metadata.clientTools`：

```powershell
$singleContextId = "client-tool-single-" + [Guid]::NewGuid().ToString("N")
$singleRequest1 = [ordered]@{
  jsonrpc = "2.0"
  id = "single-round-1"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      contextId = $singleContextId
      parts = @([ordered]@{
        text = "SINGLE_CLIENT_TOOL_DEMO：查询深圳天气，并在拿到客户端结果后回显参数和结果。"
      })
    }
    metadata = [ordered]@{ clientTools = (New-ClientToolView) }
  }
}

$singleResponse1 = Invoke-ClientToolA2A $singleRequest1
$singleTask = $singleResponse1.result.task
$singleInterrupt = $singleTask.status.message.metadata._interrupt

if ($singleTask.status.state -ne "TASK_STATE_INPUT_REQUIRED") {
  throw "Expected INPUT_REQUIRED, actual=$($singleTask.status.state)"
}
if ($singleInterrupt.toolName -ne "getLocalWeather") {
  throw "Expected getLocalWeather, actual=$($singleInterrupt.toolName)"
}

$singleInterrupt | ConvertTo-Json -Depth 100
```

本地 mock 执行直接复用模型产生的真实参数。单 pending 恢复允许不带 `metadata.toolCallId`：

```powershell
$singleMockResult = [ordered]@{
  executedBy = "mock-client"
  toolName = $singleInterrupt.toolName
  receivedArguments = $singleInterrupt.context.arguments
  result = [ordered]@{ temperatureC = 31; condition = "晴" }
} | ConvertTo-Json -Compress -Depth 100

$singleRequest2 = [ordered]@{
  jsonrpc = "2.0"
  id = "single-round-2"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      taskId = $singleTask.id
      contextId = $singleTask.contextId
      parts = @([ordered]@{ text = $singleMockResult })
    }
  }
}

$singleResponse2 = Invoke-ClientToolA2A $singleRequest2
$singleFinalTask = $singleResponse2.result.task

if ($singleFinalTask.status.state -ne "TASK_STATE_COMPLETED") {
  throw "Expected COMPLETED, actual=$($singleFinalTask.status.state)"
}

Write-Host "Final response:"
Write-Host ($singleResponse2 | ConvertTo-Json -Depth 100)
```

最终回答应包含 `getLocalWeather`、模型实际生成的 `receivedArguments`（预期 `city=深圳`）以及
mock 的 `temperatureC=31`、`condition=晴`。

## 5. 多工具注入与恢复

第一轮要求真实模型在同一个 assistant turn 调用单参数工具和多参数工具：

```powershell
$multiContextId = "client-tool-multi-" + [Guid]::NewGuid().ToString("N")
$multiRequest1 = [ordered]@{
  jsonrpc = "2.0"
  id = "multi-round-1"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      contextId = $multiContextId
      parts = @([ordered]@{
        text = "MULTI_CLIENT_TOOL_DEMO：同时查询深圳天气并创建日历事件，拿到两个客户端结果后统一回显。"
      })
    }
    metadata = [ordered]@{ clientTools = (New-ClientToolView) }
  }
}

$multiResponse1 = Invoke-ClientToolA2A $multiRequest1
$multiTask = $multiResponse1.result.task
$multiInterrupt = $multiTask.status.message.metadata._interrupt
$multiItems = @($multiInterrupt.items)

if ($multiTask.status.state -ne "TASK_STATE_INPUT_REQUIRED") {
  throw "Expected INPUT_REQUIRED, actual=$($multiTask.status.state)"
}
if ($multiItems.Count -ne 2) {
  throw "Expected two pending client tools, actual=$($multiItems.Count)"
}

$multiItems | Select-Object toolCallId, toolName, @{n="arguments";e={$_.context.arguments}} |
  Format-List
```

客户端分别执行两个工具，并给每个结果带第一轮返回的真实 `toolCallId`。下面的 mock observation
同时回显 `toolName` 和 `receivedArguments`：

```powershell
$multiResultParts = @(
  foreach ($item in $multiItems) {
    $businessResult = if ($item.toolName -eq "getLocalWeather") {
      [ordered]@{ temperatureC = 30; condition = "多云" }
    } else {
      [ordered]@{ eventId = "local-event-20260725"; created = $true }
    }
    $observation = [ordered]@{
      executedBy = "mock-client"
      toolName = $item.toolName
      receivedArguments = $item.context.arguments
      result = $businessResult
    } | ConvertTo-Json -Compress -Depth 100

    [ordered]@{
      text = $observation
      metadata = [ordered]@{ toolCallId = $item.toolCallId }
    }
  }
)

$multiRequest2 = [ordered]@{
  jsonrpc = "2.0"
  id = "multi-round-2"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      taskId = $multiTask.id
      contextId = $multiTask.contextId
      parts = $multiResultParts
    }
  }
}

$multiResponse2 = Invoke-ClientToolA2A $multiRequest2
$multiFinalTask = $multiResponse2.result.task

if ($multiFinalTask.status.state -ne "TASK_STATE_COMPLETED") {
  throw "Expected COMPLETED, actual=$($multiFinalTask.status.state)"
}

Write-Host "Final response:"
Write-Host ($multiResponse2 | ConvertTo-Json -Depth 100)
```

最终回答应同时包含：

- `getLocalWeather` 和实际 `city` 参数，以及天气 mock 结果。
- `createCalendarEvent` 和实际 `title/date/durationMinutes` 参数，以及 `eventId`。

如果只提交一个结果、使用未知 `toolCallId`，或不给多 pending 结果定向，Solution 会在恢复 Core 前
拒绝整次 continuation，不会把一条文本广播给两个 ToolCall。

## 6. 同会话无工具请求隔离

多工具任务完成后，复用同一个 `contextId` 发送一个新请求，但不携带原任务的 `taskId`，也不提供
`params.metadata.clientTools`。该请求用于确认前两轮动态注入的工具不会泄漏到同一会话的后续任务：

```powershell
$noToolRequest = [ordered]@{
  jsonrpc = "2.0"
  id = "no-tool-round-1"
  method = "SendMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      messageId = [Guid]::NewGuid().ToString("N")
      contextId = $multiTask.contextId
      parts = @([ordered]@{
        text = "NO_CLIENT_TOOL_DEMO：如果当前请求实际提供了 getLocalWeather 工具就调用它；否则只回复 NO_CLIENT_TOOL_AVAILABLE。"
      })
    }
  }
}

$noToolResponse = Invoke-ClientToolA2A $noToolRequest
$noToolTask = $noToolResponse.result.task
$noToolInterrupt = $noToolTask.status.message.metadata._interrupt
$noToolFinalText = @(
  foreach ($artifact in @($noToolTask.artifacts)) {
    foreach ($part in @($artifact.parts)) {
      if ($part.text) {
        $part.text
      }
    }
  }
) -join "`n"

if ($noToolTask.status.state -ne "TASK_STATE_COMPLETED") {
  throw "Expected COMPLETED, actual=$($noToolTask.status.state)"
}
if ($null -ne $noToolInterrupt) {
  throw "Expected no client-tool interrupt, actual=$($noToolInterrupt | ConvertTo-Json -Compress -Depth 100)"
}
if ($noToolFinalText -notlike "*NO_CLIENT_TOOL_AVAILABLE*") {
  throw "Expected NO_CLIENT_TOOL_AVAILABLE, actual=$noToolFinalText"
}

Write-Host "No-tool final response:"
Write-Host ($noToolResponse | ConvertTo-Json -Depth 100)
```

该轮预期为 `TASK_STATE_COMPLETED`，且响应中没有 `_interrupt`。同时检查服务端日志中的本轮
`Before request chat model`：`tools` 应为 `[]` 或 `null`，不得再出现 `getLocalWeather`、
`createCalendarEvent`。日志中的模型入参是确认旧工具未泄漏的最终依据。
