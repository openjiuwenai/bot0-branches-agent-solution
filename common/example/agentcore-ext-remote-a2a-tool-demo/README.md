# AgentCore Ext Remote A2A Tool Demo

这个 example 启动两个 runtime：

- Agent A：DeepAgent + `JiuwenCoreAgentExtHandler`，启动前从 Agent B 的 A2A card 注入远端工具。
- Agent B：`VersatileAgentHandler`，通过 A2A 暴露 card 和 `/a2a/`。

example 拆成两个可执行 jar，避免一个 jar 内两个 `@SpringBootApplication` 同时被扫描，导致两个 `AgentHandler` bean 同时存在：

```text
agentcore-ext-remote-a2a-tool-demo
|-- agent-a-deepagent-runtime
`-- agent-b-versatile-runtime
```

不要把 DeepSeek key 写入仓库文件，只通过环境变量传入。

## 打包

在仓库根目录下执行：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\agent-runtime-ext-java\pom.xml" `
  -pl agent-service-adapters/agent-service-adapters-agentcore-ext -am clean install

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\agentcore-ext-remote-a2a-tool-demo\pom.xml" `
  test
```

## 启动 Agent B

```powershell
Set-Location <repo-root>
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

$env:AGENT_B_PORT = "18091"
$env:VERSATILE_URL = "http://127.0.0.1:31113/v1/0/agents/main_planner/conversations/{conversation_id}"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\agentcore-ext-remote-a2a-tool-demo\agent-b-versatile-runtime\pom.xml" `
  spring-boot:run
```

Agent B card 默认地址：

```text
http://127.0.0.1:18091/.well-known/agent-card.json
```

## 启动 Agent A

```powershell
Set-Location <repo-root>
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

$env:AGENT_A_PORT = "18090"
$env:REMOTE_A2A_CARD_URL = "http://127.0.0.1:18091"
$env:DEEPSEEK_API_KEY = "<runtime-only>"
$env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
$env:DEEPSEEK_MODEL = "deepseek-chat"
$env:DEEP_AGENT_COMPLETION_TIMEOUT = "600s"
$env:DEEP_AGENT_SKILLS_DIR = "common/example/agentcore-ext-remote-a2a-tool-demo/skills"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\agentcore-ext-remote-a2a-tool-demo\agent-a-deepagent-runtime\pom.xml" `
  spring-boot:run
```

Agent A 会在每次 `query()` / `streamQuery()` 前尝试刷新 Agent B card。发现成功后，DeepAgent 内部 ReActAgent 会看到配置名：

```text
versatile-agent
```

AgentCore/OpenAI function calling 会把该名称规范化为可调用函数名 `versatileagent`，日志中的 `tool_call` 也会显示为 `versatileagent`。

DeepAgent 同时加载 example 内置 skill：

```text
common/example/agentcore-ext-remote-a2a-tool-demo/skills/remote-versatile/SKILL.md
```

这个 skill 会要求 DeepAgent 调用注入工具，也就是配置名 `versatile-agent` / 运行时函数名 `versatileagent`，并使用 `{ "remoteInput": "..." }` 作为工具入参。原始用户 message 先进入 Agent A；DeepAgent 需要把原始 `message.parts[0].text` 原样拷贝到 `remoteInput`，随后 `RemoteA2aInterruptRail` 只读取这个 `remoteInput` 字符串并作为 Agent A 发给 Agent B 的 A2A message。

## 调用 Agent A：A2A 三轮请求

这里故意只调用 Agent A 的 `/a2a/`。第一轮进入 DeepAgent，DeepAgent 调用注入工具后触发 remote A2A interrupt；第二轮、第三轮仍然请求 Agent A，但 Agent A 的 orchestrator 应该基于 shadow task 直接转发给 Agent B。三轮请求都完整保留 `params.metadata`，内部转发给 Versatile 时也应该带上这些字段；如果 Agent B 收不到完整 metadata，说明转发逻辑有问题。

下面的请求结构参考 `versatile-a2a-adapter-demo` 第三节。`metadata.body.input.query/intent` 和 `metadata.body.custom_data.inputs.query/intent` 固定为基底值，用来验证 metadata 透传；每轮真正变化的是 `message.parts[0].text` 中的 query/intent。

如果只想直接发送三轮报文，可以运行 example 内置的发送器；它和 `versatile-a2a-adapter-demo` 的 `VersatileA2AClientMain` 一样，只负责向 Agent A `/a2a/` 发送 JSON-RPC 报文，不实现两个 runtime 之间的 A2A client 逻辑：

```powershell
Set-Location <repo-root>
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

$env:A2A_ENDPOINT_URL = "http://127.0.0.1:18090/a2a/"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\agentcore-ext-remote-a2a-tool-demo\agent-a-deepagent-runtime\pom.xml" `
  -DskipTests compile exec:java `
  "-Dexec.mainClass=com.openjiuwen.example.agentcoreext.agent_a.a2a.AgentCoreExtA2AClientMain"
```

先定义公共函数：

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$a2aUrl = "http://127.0.0.1:18090/a2a/"

function New-AgentARequestJson {
  param(
    [string] $Id,
    [string] $Query,
    [string] $Intent
  )

  $messageText = @{
    query = $Query
    intent = $Intent
  } | ConvertTo-Json -Compress -Depth 100

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
            text = $messageText
          }
        )
      }
      metadata = [ordered]@{
        body = [ordered]@{
          agent_id = "main_planner"
          input = [ordered]@{
            query = "metadata-body-fixed-query"
            intent = "metadata-body-fixed-intent"
            wap_userName = "张三"
          }
          conversation_id = "test-session-001"
          timeout = "300"
          role_id = "1"
          role_name = "手机银行"
          stream = $true
          custom_data = [ordered]@{
            inputs = [ordered]@{
              query = "custom-data-fixed-query"
              intent = "custom-data-fixed-intent"
              wap_userName = "张三"
            }
            memory_inputs = [ordered]@{}
            globals = [ordered]@{}
            plugin_configs = @()
            long_term_memory = [ordered]@{
              enable_retrieve = $true
              enable_extract = $true
            }
          }
        }
        headers = [ordered]@{
          stream = "true"
          "x-invoke-mode" = "DEBUG"
          "x-language" = "zh-cn"
          "x-debug-trace" = "trace-from-agentcore-ext-demo"
        }
        query = [ordered]@{
          workspace_id = "11"
          type = "controller"
        }
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
      stream = "true"
      "x-invoke-mode" = "DEBUG"
      "x-language" = "zh-cn"
      "x-debug-trace" = "trace-from-agentcore-ext-demo"
    } `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($RequestJson))

  Write-Host "HTTP $($response.StatusCode)"
  $response.RawContentStream.Position = 0
  $reader = New-Object System.IO.StreamReader($response.RawContentStream, [System.Text.Encoding]::UTF8)
  $reader.ReadToEnd()
}
```

第一轮进入 Agent A DeepAgent，并要求它调用注入的 `versatile-agent` 工具：

```powershell
$requestJson1 = New-AgentARequestJson `
  -Id "agentcore-ext-remote-a2a-tool-demo-1" `
  -Query "先查询尾号为4241的银行卡余额，再转账5元给李四" `
  -Intent "查询账户余额"

Send-AgentARequestJson $requestJson1
```

第二轮仍然请求 Agent A；预期由 Agent A 直接转发到 Agent B 的 shadow task：

```powershell
$requestJson2 = New-AgentARequestJson `
  -Id "agentcore-ext-remote-a2a-tool-demo-2" `
  -Query '[{"cardNum":"6222021816044054241","regAcctType":"011","cardAlias":""}]' `
  -Intent "LATEST"

Send-AgentARequestJson $requestJson2
```

第三轮继续请求 Agent A；预期 Agent B 返回最终结果后，Agent A 把 remote tool result 注入回 DeepAgent：

```powershell
$round3Query = @'
{"bankCardBalanceList":[{"bankCardNumber":"6222021816044054241","mediumStatus":"0","currencyBalanceList":[{"currencyCode":"001","currencyName":"人民币可用余额","balance":"1500.92"}]}],"responseData":[{"answer":"已为您查询账户余额","readme":"已为您查询账户余额","pageData":"","type":"1"},{"answer":"","readme":"","pageData":{"id":"queryBalance","bankBalanceData":[{"layouttype":"1","actionFun_click":{"menu":{"param":"returnFlag=3","needLogin":"false","menuId":"account_1"}},"actionType_click":"menu","bankIoc":{"titleValueColor":"","titleValue":"","type":"pic","bgColor":"","bgPic":"","actionFun_click":"","actionType_click":""},"areaName":{"titleValueColor":"F4E1B3","titleValue":"广州","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"cardTypeDesc":{"titleValueColor":"F4E1B3","titleValue":"借记卡（I类）","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"alias":{"titleValueColor":"F4E1B3","titleValue":"","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"cardNumLast":{"titleValueColor":"F4E1B3","titleValue":"6222****4241","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balanceList":[{"balanceTitle":{"titleValueColor":"C3B9A1","titleValue":"人民币余额","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balance":{"titleValueColor":"F4E1B3","titleValue":"1970.23","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null}},{"balanceTitle":{"titleValueColor":"C3B9A1","titleValue":"人民币可用余额","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balance":{"titleValueColor":"F4E1B3","titleValue":"1500.92","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null}}],"showCardNumBtn":{"type":"button","btnId":"tttt","btnName":"查看","bgColor":null,"actionFun_click":"abc","actionType_click":"4"},"showAccountBalanceBtn":{"type":"button","btnId":"qqqq","btnName":"点击查询","bgColor":null,"actionFun_click":"def","actionType_click":"4"}},"queryStatus":"成功","failCause":"","type":"7"}]}
'@

$requestJson3 = New-AgentARequestJson `
  -Id "agentcore-ext-remote-a2a-tool-demo-3" `
  -Query $round3Query `
  -Intent "LATEST"

Send-AgentARequestJson $requestJson3
```

预期链路：

```text
Agent A DeepAgent
  -> LLM calls versatile-agent
  -> RemoteA2aInterruptRail throws a2a_delegate interrupt
  -> A2AEnabledServeOrchestrator calls Agent B with streaming A2A client
  -> Agent B VersatileAgentHandler calls VERSATILE_URL
  -> remote result resumes Agent A as tool result
```
