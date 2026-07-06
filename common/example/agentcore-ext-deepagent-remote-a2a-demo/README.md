# AgentCore Ext DeepAgent 远程 A2A Demo

这个示例会启动两个独立的 Spring Boot 运行时：

- Agent A：`DeepAgent` + `JiuwenCoreAgentExtHandler`。它接收首轮用户请求，通过自动注入的远程 A2A tool 委托给 Agent B；Agent B 最终完成后，它再基于最终结果生成总结。
- Agent B：`DeepAgent` + 上游 `JiuwenCoreAgentHandler`。它使用 core 原生的 `AskUserTool` 和一个继承 `BaseInterruptRail` 的轻量 demo rail，前两轮中断，第三轮返回最终结果。

这个示例不再包含 Java A2A client main class。三轮请求都发送到 Agent A 的 A2A endpoint。首轮会进入 Agent A；后续轮次命中 runtime 保存的 shadow task 后，会先直接续传给 Agent B。Agent B 返回最终结果后，runtime 再恢复 Agent A 生成最终总结。

## 打包

在仓库根目录执行：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\agent-runtime-ext-java\pom.xml" `
  clean install

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\agentcore-ext-deepagent-remote-a2a-demo\pom.xml" `
  clean install
```

## 启动 Agent B

```powershell
Set-Location D:\Code\openJiuwen\agent-solution
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

$env:AGENT_B_PORT = "18191"
$env:DEEPSEEK_API_KEY = "<your-deepseek-api-key>"
$env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
$env:DEEPSEEK_MODEL = "deepseek-chat"
$env:DEEP_AGENT_COMPLETION_TIMEOUT = "600s"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\agentcore-ext-deepagent-remote-a2a-demo\agent-b-deepagent-runtime\pom.xml" `
  spring-boot:run
```

Agent B card 地址：

```text
http://127.0.0.1:18191/.well-known/agent-card.json
```

## 启动 Agent A

```powershell
Set-Location D:\Code\openJiuwen\agent-solution
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

$env:AGENT_A_PORT = "18190"
$env:REMOTE_A2A_CARD_URL = "http://127.0.0.1:18191"
$env:DEEPSEEK_API_KEY = "<your-deepseek-api-key>"
$env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
$env:DEEPSEEK_MODEL = "deepseek-chat"
$env:DEEP_AGENT_COMPLETION_TIMEOUT = "600s"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\agentcore-ext-deepagent-remote-a2a-demo\agent-a-deepagent-runtime\pom.xml" `
  spring-boot:run
```

Agent A 会通过 `openjiuwen.service.a2a.remote-agents` 发现 Agent B。`agentcore-ext` 会在 `query()` / `streamQuery()` 调用前，把发现到的远程 Agent 注入为 AgentCore tool。

## 三轮 A2A 调用

先定义辅助函数：

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$a2aUrl = "http://127.0.0.1:18190/a2a/"

function New-AgentARequestJson {
  param(
    [string] $Id,
    [string] $Text
  )

  $request = [ordered]@{
    jsonrpc = "2.0"
    id = $Id
    method = "SendStreamingMessage"
    params = [ordered]@{
      message = [ordered]@{
        role = "ROLE_USER"
        contextId = $Id
        parts = @(
          [ordered]@{
            text = $Text
          }
        )
      }
    }
  }

  return ($request | ConvertTo-Json -Depth 100)
}

function Send-AgentARequestJson {
  param([string] $RequestJson)

  Write-Host "Request body:"
  $RequestJson
  Write-Host ""

  $response = Invoke-WebRequest `
    -UseBasicParsing `
    -Uri $a2aUrl `
    -Method Post `
    -ContentType "application/json; charset=utf-8" `
    -Headers @{
      Accept = "text/event-stream"
    } `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($RequestJson))

  Write-Host "HTTP $($response.StatusCode)"
  $response.RawContentStream.Position = 0
  $reader = New-Object System.IO.StreamReader($response.RawContentStream, [System.Text.Encoding]::UTF8)
  $reader.ReadToEnd()
}
```

第一轮请求进入 Agent A。Agent A 委托给 Agent B。Agent B 触发第一次 core 原生 `ask_user` 中断，所以响应最终应结束在 `TASK_STATE_INPUT_REQUIRED`。此时 Agent A 侧会保存一条 shadow task，记录远端 Agent B 的 pending task。

```powershell
$conversationId = "agentcore-ext-deepagent-remote-a2a-demo-1"
$requestJson1 = New-AgentARequestJson `
  -Id $conversationId `
  -Text "Start the DeepAgent to DeepAgent three-round workflow."

Send-AgentARequestJson $requestJson1
```

第二轮继续使用相同的 `contextId` 调用 Agent A 的 A2A endpoint。runtime 会先命中 Agent A 侧的 shadow task，并直接把本轮用户输入续传给 Agent B，不先进入 Agent A DeepAgent。Agent B 触发第二次 core 原生 `ask_user` 中断，所以响应最终仍应结束在 `TASK_STATE_INPUT_REQUIRED`。

```powershell
$requestJson2 = New-AgentARequestJson `
  -Id $conversationId `
  -Text "round-2-confirmed: account list has been provided"

Send-AgentARequestJson $requestJson2
```

第三轮继续使用相同的 `contextId` 调用 Agent A 的 A2A endpoint。runtime 仍然先把本轮用户输入续传给 Agent B。Agent B 返回最终结果后，runtime 删除 shadow task，并把 Agent B 的最终结果作为恢复输入交给 Agent A。Agent A 再输出自己的最终回答。

```powershell
$requestJson3 = New-AgentARequestJson `
  -Id $conversationId `
  -Text "round-3-confirmed: transfer confirmation has been provided"

Send-AgentARequestJson $requestJson3
```

预期调用链：

```text
Agent A DeepAgent
  -> 注入的远程 A2A tool
  -> runtime A2A client
  -> Agent B DeepAgent
  -> BaseInterruptRail 第一轮中断
  -> Agent A 侧保存 shadow task
第二轮请求
  -> runtime 命中 shadow task
  -> runtime A2A client 直接续传给 Agent B
  -> BaseInterruptRail 第二轮中断
  -> Agent A 侧刷新 shadow task
第三轮请求
  -> runtime 命中 shadow task
  -> runtime A2A client 直接续传给 Agent B
  -> Agent B 返回最终结果
  -> runtime 恢复 Agent A
  -> Agent A 返回最终总结
```
