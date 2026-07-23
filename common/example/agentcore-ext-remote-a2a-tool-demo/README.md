# AgentCore Ext Remote A2A Tool Demo

这个 example 启动两个 runtime：

- Agent A：DeepAgent + `JiuwenCoreAgentExtHandler`，复用 runtime 的远端 A2A card 发现结果注入远端工具。
- Agent B：`VersatileAgentHandler`，通过 runtime 默认 A2A card 和 `/a2a/` 暴露服务。

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

Agent A 使用 `openjiuwen.service.a2a.remote-agents` 触发 runtime 内置远端 card 发现；`agentcore-ext` 在每次 `query()` / `streamQuery()` 前读取已发现的 registry，并把新增远端 agent 注入为工具。`remote-agents.name` 是 runtime 发现与路由键，不写入 Agent A 给模型的业务指令。

DeepAgent 同时加载 example 内置 skill：

```text
common/example/agentcore-ext-remote-a2a-tool-demo/skills/remote-versatile/SKILL.md
```

这个 skill 会要求 DeepAgent 按工具描述选择可处理银行业务或远端业务流程的注入工具，并使用 `{ "remoteInput": "..." }` 作为工具入参。原始用户 message 先进入 Agent A；DeepAgent 需要把原始 `message.parts[0].text` 原样拷贝到 `remoteInput`，随后 `RemoteA2aInterruptRail` 只读取这个 `remoteInput` 字符串并作为 Agent A 发给 Agent B 的 A2A message。

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
  "-Dexec.mainClass=com.openjiuwen.example.agentcoreext.agenta.a2a.AgentCoreExtA2AClientMain"
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

第一轮进入 Agent A DeepAgent，并要求它使用可处理银行业务流程的远端能力：

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
  -Id "agentcore-ext-remote-a2a-tool-demo-1" `
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
  -Id "agentcore-ext-remote-a2a-tool-demo-1" `
  -Query $round3Query `
  -Intent "LATEST"

Send-AgentARequestJson $requestJson3
```

预期链路：

```text
Agent A DeepAgent
  -> LLM calls the matching injected remote A2A tool
  -> RemoteA2aInterruptRail throws a2a_delegate interrupt
  -> A2AEnabledServeOrchestrator calls Agent B in the configured mode (non-streaming by default)
  -> Agent B VersatileAgentHandler calls VERSATILE_URL
  -> remote result resumes Agent A as tool result
```

## 调用 Agent A：REST 三轮请求

REST 入口调用 Agent A 的 `/v1/query`。runtime 会把 REST 原始请求体完整放进 `ServeRequest.metadata.body`，所以这里把 `custom_data` 放在 REST body 顶层；URL query string 会进入 `ServeRequest.metadata.query`，并由 Agent B 的 `VersatileAgentHandler` 透传给 `VERSATILE_URL`。Agent A 委托到 Agent B 后，Agent B 会从 `metadata.body.custom_data` 构造最终发给 `VERSATILE_URL` 的 HTTP body。

三轮请求必须使用同一个 `conversation_id`，这样 Agent A 的 orchestrator 才能基于 shadow task 继续转发后续轮次。

先定义公共函数：

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$restUrl = "http://127.0.0.1:18090/v1/query?workspace_id=11&type=controller"
$conversationId = "agentcore-ext-remote-a2a-tool-demo-rest-1"

function New-AgentARestRequestJson {
  param(
    [string] $ConversationId,
    [string] $Query,
    [string] $Intent
  )

  $messageText = @{
    query = $Query
    intent = $Intent
  } | ConvertTo-Json -Compress -Depth 100

  $request = [ordered]@{
    conversation_id = $ConversationId
    stream = $true
    user_id = "demo-user"
    space_id = "demo-space"
    messages = @(
      [ordered]@{
        role = "user"
        content = $messageText
      }
    )
    agent_id = "main_planner"
    input = [ordered]@{
      query = "metadata-body-fixed-query"
      intent = "metadata-body-fixed-intent"
      wap_userName = "张三"
    }
    timeout = "300"
    role_id = "1"
    role_name = "手机银行"
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

  return ($request | ConvertTo-Json -Depth 100)
}

function Send-AgentARestRequestJson {
  param([string] $RequestJson)

  Write-Host "Request body:"
  $RequestJson
  Write-Host ""

  $response = Invoke-WebRequest `
    -UseBasicParsing `
    -Uri $restUrl `
    -Method Post `
    -ContentType "application/json; charset=utf-8" `
    -Headers @{
      Accept = "text/event-stream"
      stream = "true"
      "x-invoke-mode" = "DEBUG"
      "x-language" = "zh-cn"
      "x-debug-trace" = "trace-from-agentcore-ext-rest-demo"
    } `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($RequestJson))

  Write-Host "HTTP $($response.StatusCode)"
  $response.RawContentStream.Position = 0
  $reader = New-Object System.IO.StreamReader($response.RawContentStream, [System.Text.Encoding]::UTF8)
  $reader.ReadToEnd()
}
```

第一轮进入 Agent A DeepAgent，并要求它使用可处理银行业务流程的远端能力：

```powershell
$restRequestJson1 = New-AgentARestRequestJson `
  -ConversationId $conversationId `
  -Query "先查询尾号为4241的银行卡余额，再转账5元给李四" `
  -Intent "查询账户余额"

Send-AgentARestRequestJson $restRequestJson1
```

第二轮仍然请求 Agent A；预期由 Agent A 直接转发到 Agent B 的 shadow task：

```powershell
$restRequestJson2 = New-AgentARestRequestJson `
  -ConversationId $conversationId `
  -Query '[{"cardNum":"6222021816044054241","regAcctType":"011","cardAlias":""}]' `
  -Intent "LATEST"

Send-AgentARestRequestJson $restRequestJson2
```

第三轮继续请求 Agent A；预期 Agent B 返回最终结果后，Agent A 把 remote tool result 注入回 DeepAgent：

```powershell
$restRound3Query = @'
{"bankCardBalanceList":[{"bankCardNumber":"6222021816044054241","mediumStatus":"0","currencyBalanceList":[{"currencyCode":"001","currencyName":"人民币可用余额","balance":"1500.92"}]}],"responseData":[{"answer":"已为您查询账户余额","readme":"已为您查询账户余额","pageData":"","type":"1"},{"answer":"","readme":"","pageData":{"id":"queryBalance","bankBalanceData":[{"layouttype":"1","actionFun_click":{"menu":{"param":"returnFlag=3","needLogin":"false","menuId":"account_1"}},"actionType_click":"menu","bankIoc":{"titleValueColor":"","titleValue":"","type":"pic","bgColor":"","bgPic":"","actionFun_click":"","actionType_click":""},"areaName":{"titleValueColor":"F4E1B3","titleValue":"广州","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"cardTypeDesc":{"titleValueColor":"F4E1B3","titleValue":"借记卡（I类）","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"alias":{"titleValueColor":"F4E1B3","titleValue":"","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"cardNumLast":{"titleValueColor":"F4E1B3","titleValue":"6222****4241","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balanceList":[{"balanceTitle":{"titleValueColor":"C3B9A1","titleValue":"人民币余额","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balance":{"titleValueColor":"F4E1B3","titleValue":"1970.23","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null}},{"balanceTitle":{"titleValueColor":"C3B9A1","titleValue":"人民币可用余额","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balance":{"titleValueColor":"F4E1B3","titleValue":"1500.92","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null}}],"showCardNumBtn":{"type":"button","btnId":"tttt","btnName":"查看","bgColor":null,"actionFun_click":"abc","actionType_click":"4"},"showAccountBalanceBtn":{"type":"button","btnId":"qqqq","btnName":"点击查询","bgColor":null,"actionFun_click":"def","actionType_click":"4"}},"queryStatus":"成功","failCause":"","type":"7"}]}
'@

$restRequestJson3 = New-AgentARestRequestJson `
  -ConversationId $conversationId `
  -Query $restRound3Query `
  -Intent "LATEST"

Send-AgentARestRequestJson $restRequestJson3
```

REST 三轮链路：

```text
Agent A /v1/query
  -> QueryMvcController stores raw REST body as ServeRequest.metadata.body
  -> Agent A DeepAgent calls the injected remote A2A tool
  -> A2AEnabledServeOrchestrator calls Agent B through A2A
  -> Agent B VersatileAgentHandler reads metadata.body.custom_data
  -> Agent B calls VERSATILE_URL
  -> remote result resumes Agent A as tool result
```

## 验证 Agent A 自定义 REST 入口

这个入口复用 Agent A，不新增 Agent C，也不新增监听端口。Agent A 仍监听 `18090`，原有
`/a2a/` 和 `/v1/query` 保持不变；新增入口为：

```text
POST /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
```

自定义 adapter 将 `input` 序列化成 Agent A 的 user message，并保留以下数据：

| 自定义请求数据 | `ServeRequest.metadata` | Agent B 用途 |
| --- | --- | --- |
| `custom_data` | `body.custom_data` | 构造发往 `VERSATILE_URL` 的 body |
| HTTP headers | `headers` | 完整保留；白名单字段继续转发 |
| URL query params | `query` | 作为 query string 转发到 `VERSATILE_URL` |
| URL path variables | `path_variables` | 保留项目、Agent 和会话标识 |

### 1. 构建并安装扩展

```powershell
mvn -f common\agent-runtime-ext-java\pom.xml `
  -pl agent-service-app/agent-service-app-custom-rest -am clean install

mvn -f common\agent-runtime-ext-java\pom.xml `
  -pl agent-service-adapters/agent-service-adapters-agentcore-ext install -DskipTests

mvn -f common\example\agentcore-ext-remote-a2a-tool-demo\pom.xml clean package
```

### 2. 启动 Agent B

在第一个 PowerShell 中进入仓库根目录。`VERSATILE_URL` 应指向实际可用的下游工作流服务：

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

$env:AGENT_B_PORT = "18091"
$env:VERSATILE_URL = "http://127.0.0.1:31113/v1/0/agents/main_planner/conversations/{conversation_id}"

java -jar "common\example\agentcore-ext-remote-a2a-tool-demo\agent-b-versatile-runtime\target\agent-b-versatile-runtime-0.1.0.jar"
```

确认 Agent B card 可访问：

```powershell
Invoke-RestMethod http://127.0.0.1:18091/.well-known/agent-card.json
```

### 3. 启动 Agent A

在第二个 PowerShell 中配置模型。API Key 只通过环境变量传入，不要写入配置或日志：

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

$env:AGENT_A_PORT = "18090"
$env:REMOTE_A2A_CARD_URL = "http://127.0.0.1:18091"
$env:DEEPSEEK_API_KEY = "替换为真实 API Key"
$env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
$env:DEEPSEEK_MODEL = "deepseek-chat"
$env:DEEP_AGENT_COMPLETION_TIMEOUT = "600s"
$env:DEEP_AGENT_SKILLS_DIR = "common/example/agentcore-ext-remote-a2a-tool-demo/skills"

java -jar "common\example\agentcore-ext-remote-a2a-tool-demo\agent-a-deepagent-runtime\target\agent-a-deepagent-runtime-0.1.0.jar"
```

启动日志中不应出现 mapping 冲突。以下三个入口共同使用 `18090`：

```text
http://127.0.0.1:18090/a2a/
http://127.0.0.1:18090/v1/query
http://127.0.0.1:18090/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
```

### 4. 定义自定义请求函数

在第三个 PowerShell 中执行：

```powershell
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$projectId = "0"
$agentId = "main_planner"
$conversationId = "test-session-001"
$customRestUrl = "http://127.0.0.1:18090/v1/${projectId}/agents/${agentId}/conversations/${conversationId}?workspace_id=11&type=controller"

function New-CustomRestRequestJson {
  param(
    [Parameter(Mandatory = $true)] [string] $Query,
    [Parameter(Mandatory = $true)] [string] $Intent,
    [bool] $Stream = $true
  )

  $body = [ordered]@{
    agent_id = $agentId
    input = [ordered]@{
      query = $Query
      intent = $Intent
      wap_userName = "张三"
    }
    conversation_id = $conversationId
    timeout = "300"
    role_id = "1"
    role_name = "手机银行"
    stream = $Stream
    custom_data = [ordered]@{
      inputs = [ordered]@{
        query = $Query
        intent = $Intent
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

  return ($body | ConvertTo-Json -Depth 100)
}

function Send-CustomRestRequestJson {
  param([Parameter(Mandatory = $true)] [string] $RequestJson)

  $requestBody = $RequestJson | ConvertFrom-Json
  $accept = if ([bool] $requestBody.stream) { "text/event-stream" } else { "application/json" }
  try {
    $response = Invoke-WebRequest `
      -UseBasicParsing `
      -Uri $customRestUrl `
      -Method Post `
      -ContentType "application/json; charset=utf-8" `
      -Headers @{
        Accept = $accept
        stream = ([bool] $requestBody.stream).ToString().ToLowerInvariant()
        "x-invoke-mode" = "DEBUG"
        "x-language" = "zh-cn"
      } `
      -Body ([System.Text.Encoding]::UTF8.GetBytes($RequestJson))
    $stream = $response.RawContentStream
  } catch [System.Net.WebException] {
    $response = $_.Exception.Response
    if ($null -eq $response) {
      throw
    }
    $stream = $response.GetResponseStream()
  }

  Write-Host "HTTP $([int]$response.StatusCode)"
  if ($stream.CanSeek) {
    $stream.Position = 0
  }
  $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
  try {
    $reader.ReadToEnd()
  } finally {
    $reader.Dispose()
    $stream.Dispose()
  }
}
```

### 5. 发送三轮流式报文

第一轮进入 Agent A DeepAgent，再由远端工具进入 Agent B：

```powershell
$requestJson1 = New-CustomRestRequestJson `
  -Query "先查询尾号为4241的银行卡余额，再转账5元给李四" `
  -Intent "查询账户余额"
Send-CustomRestRequestJson $requestJson1
```

第二轮命中 pending shadow task，跳过 DeepAgent，直接转发给 Agent B：

```powershell
$requestJson2 = New-CustomRestRequestJson `
  -Query '[{"cardNum":"6222021816044054241","regAcctType":"011","cardAlias":""}]' `
  -Intent "LATEST"
Send-CustomRestRequestJson $requestJson2
```

第三轮由 Agent B 返回最终结果，Agent A 把结果恢复到 DeepAgent 并结束：

```powershell
$round3Query = @'
{"bankCardBalanceList":[{"bankCardNumber":"6222021816044054241","mediumStatus":"0","currencyBalanceList":[{"currencyCode":"001","currencyName":"人民币可用余额","balance":"1500.92"}]}],"queryStatus":"成功","failCause":""}
'@
$requestJson3 = New-CustomRestRequestJson -Query $round3Query -Intent "LATEST"
Send-CustomRestRequestJson $requestJson3
```

预期路由：

```text
第一轮：Custom REST -> Agent A DeepAgent -> Agent B -> input required
第二轮：Custom REST -> Agent A orchestrator -> Agent B -> input required
第三轮：Custom REST -> Agent A orchestrator -> Agent B -> Agent A DeepAgent -> completed
```

Agent B 日志应显示 `workspace_id=11`、`type=controller`，白名单 headers 为
`stream=true`、`x-invoke-mode=DEBUG`、`x-language=zh-cn`，远端 body 来自当前轮
`custom_data`。

### 6. 验证同步分支和原入口回归

同步分支使用新的 conversation id，避免续接上面的流式会话：

```powershell
$conversationId = "sync-test-session-001"
$customRestUrl = "http://127.0.0.1:18090/v1/${projectId}/agents/${agentId}/conversations/${conversationId}?workspace_id=11&type=controller"
$syncRequestJson = New-CustomRestRequestJson `
  -Query "请介绍一下你自己" `
  -Intent "普通问答" `
  -Stream $false
Send-CustomRestRequestJson $syncRequestJson
```

同步响应应为普通 JSON；流式响应的 event 名称由 adapter 给出，data 信封包含：

```json
{
  "success": true,
  "agent_id": "main_planner",
  "conversation_id": "test-session-001",
  "output": "",
  "error": "",
  "execution_time": "",
  "custom_rsp_data": {
    "type": "chunk",
    "data": {}
  }
}
```

最后继续执行本文前面的 A2A 三轮请求和 `/v1/query` REST 三轮请求。三组入口应同时可用，
Agent A/Agent B 端口仍分别是 `18090`、`18091`。

## 并发查询三个人的余额：A2A 三轮示例

下面的请求验证同一轮三个独立 remote ToolCall。Agent A 必须在同一个 assistant turn 中调用三次 `versatile-agent`，不能把三个人合并成一次远端调用。三个 Agent B Task 各自多轮推进，Runtime 使用 `toolCallId` 关联结果，不能依赖完成顺序。

每次独立测试请使用新的 `contextId`/`conversation_id`，避免 Versatile mock 中已有会话状态影响结果；同一次测试的三轮请求必须复用该值，第二、三轮同时复用第一轮返回的父 `taskId`。三轮都必须保留 `params.metadata`，尤其是 Versatile endpoint 必需的 `query.workspace_id` 和 `query.type`。

以下三段代码按顺序在同一个 PowerShell 中执行。第一段同时定义本示例使用的发送函数，不依赖前文的公共函数。

第一轮请求：

```powershell
$a2aUrl = "http://127.0.0.1:18090/a2a/"
$parallelConversationId = "parallel-balance-conversation-$(Get-Date -Format 'yyyyMMddHHmmss')"

function Send-ParallelBalanceRequestJson {
  param([Parameter(Mandatory = $true)] [string] $RequestJson)

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
    } `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($RequestJson))

  Write-Host "HTTP $($response.StatusCode)"
  $response.RawContentStream.Position = 0
  $reader = New-Object System.IO.StreamReader(
    $response.RawContentStream,
    [System.Text.Encoding]::UTF8
  )
  $reader.ReadToEnd()
}

function New-ParallelBalanceMetadata {
  [ordered]@{
    body = [ordered]@{
      agent_id = "main_planner"
      conversation_id = $parallelConversationId
      stream = $true
    }
    headers = [ordered]@{
      stream = "true"
      "x-invoke-mode" = "DEBUG"
      "x-language" = "zh-cn"
    }
    query = [ordered]@{
      workspace_id = "11"
      type = "controller"
    }
  }
}

$parallelBalanceRequest1 = [ordered]@{
  jsonrpc = "2.0"
  id = "parallel-balance-1"
  method = "SendStreamingMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      contextId = $parallelConversationId
      parts = @(
        [ordered]@{
          text = "请并行查询三个人的银行卡余额：张三尾号4241、李四尾号7816、王五尾号3058。三个人互不依赖，请为每个人分别调用一次远端银行能力。"
        }
      )
    }
    metadata = (New-ParallelBalanceMetadata)
  }
}

$parallelBalanceRequestJson1 = $parallelBalanceRequest1 | ConvertTo-Json -Depth 100
Send-ParallelBalanceRequestJson $parallelBalanceRequestJson1
```

第一轮预期返回三个独立成员进度，并以一个父 Task `INPUT_REQUIRED` 收口。记录响应中的父 `taskId` 和三个 `toolCallId`；下面使用示例值：

```json
{
  "status": {
    "state": "TASK_STATE_INPUT_REQUIRED",
    "message": {
      "metadata": {
        "_interrupt": {
          "message": "Multiple remote agents require input",
          "items": [
            {"toolCallId": "call-zhang", "message": "请选择张三的银行卡"},
            {"toolCallId": "call-li", "message": "请选择李四的银行卡"},
            {"toolCallId": "call-wang", "message": "请选择王五的银行卡"}
          ]
        }
      }
    }
  },
  "id": "parent-task-from-round-1"
}
```

第二轮在一条标准 A2A Message 中放三个 TextPart。每个 Part 的 `metadata.toolCallId` 指向一个 pending member；客户端不传 `batchId`：

```powershell
# 使用第一轮实际响应中的父 taskId 和三个 toolCallId 替换下面的示例值。
$parentTaskId = "parent-task-from-round-1"
$zhangToolCallId = "call-zhang"
$liToolCallId = "call-li"
$wangToolCallId = "call-wang"

$zhangCardQuery = ConvertTo-Json -InputObject @(
  [ordered]@{ cardNum = "6222021816044054241"; regAcctType = "011" }
) -Compress -Depth 100
$liCardQuery = ConvertTo-Json -InputObject @(
  [ordered]@{ cardNum = "6222021816044057816"; regAcctType = "011" }
) -Compress -Depth 100
$wangCardQuery = ConvertTo-Json -InputObject @(
  [ordered]@{ cardNum = "6222021816044053058"; regAcctType = "011" }
) -Compress -Depth 100

$parallelBalanceRequest2 = [ordered]@{
  jsonrpc = "2.0"
  id = "parallel-balance-2"
  method = "SendStreamingMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      taskId = $parentTaskId
      contextId = $parallelConversationId
      parts = @(
        [ordered]@{
          text = ([ordered]@{ query = $zhangCardQuery; intent = "LATEST" } | ConvertTo-Json -Compress -Depth 100)
          metadata = [ordered]@{ toolCallId = $zhangToolCallId }
        }
        [ordered]@{
          text = ([ordered]@{ query = $liCardQuery; intent = "LATEST" } | ConvertTo-Json -Compress -Depth 100)
          metadata = [ordered]@{ toolCallId = $liToolCallId }
        }
        [ordered]@{
          text = ([ordered]@{ query = $wangCardQuery; intent = "LATEST" } | ConvertTo-Json -Compress -Depth 100)
          metadata = [ordered]@{ toolCallId = $wangToolCallId }
        }
      )
    }
    metadata = (New-ParallelBalanceMetadata)
  }
}

$parallelBalanceRequestJson2 = $parallelBalanceRequest2 | ConvertTo-Json -Depth 100
Send-ParallelBalanceRequestJson $parallelBalanceRequestJson2
```

如果三个远端流程都还需要余额查询结果，第二轮再次返回同一父 Task 的三个 `INPUT_REQUIRED` item。若只回答其中一个，另外两个必须继续保持 pending；不允许默认广播。

第三轮仍按相同方式携带三个定向 Part。这里省略每个人完整的 `responseData.pageData`，实际验证时替换为 Versatile 返回的完整 JSON：

```powershell
# 实际验证时，把三个对象替换为 Versatile 返回的完整 JSON 数据。
$zhangBalanceResult = [ordered]@{
  bankCardBalanceList = @(
    [ordered]@{
      bankCardNumber = "6222021816044054241"
      currencyBalanceList = @([ordered]@{ balance = "1500.92" })
    }
  )
}
$liBalanceResult = [ordered]@{
  bankCardBalanceList = @(
    [ordered]@{
      bankCardNumber = "6222021816044057816"
      currencyBalanceList = @([ordered]@{ balance = "2088.10" })
    }
  )
}
$wangBalanceResult = [ordered]@{
  bankCardBalanceList = @(
    [ordered]@{
      bankCardNumber = "6222021816044053058"
      currencyBalanceList = @([ordered]@{ balance = "936.44" })
    }
  )
}

$parallelBalanceRequest3 = [ordered]@{
  jsonrpc = "2.0"
  id = "parallel-balance-3"
  method = "SendStreamingMessage"
  params = [ordered]@{
    message = [ordered]@{
      role = "ROLE_USER"
      taskId = $parentTaskId
      contextId = $parallelConversationId
      parts = @(
        [ordered]@{
          text = ([ordered]@{
            query = ($zhangBalanceResult | ConvertTo-Json -Compress -Depth 100)
            intent = "LATEST"
          } | ConvertTo-Json -Compress -Depth 100)
          metadata = [ordered]@{ toolCallId = $zhangToolCallId }
        }
        [ordered]@{
          text = ([ordered]@{
            query = ($liBalanceResult | ConvertTo-Json -Compress -Depth 100)
            intent = "LATEST"
          } | ConvertTo-Json -Compress -Depth 100)
          metadata = [ordered]@{ toolCallId = $liToolCallId }
        }
        [ordered]@{
          text = ([ordered]@{
            query = ($wangBalanceResult | ConvertTo-Json -Compress -Depth 100)
            intent = "LATEST"
          } | ConvertTo-Json -Compress -Depth 100)
          metadata = [ordered]@{ toolCallId = $wangToolCallId }
        }
      )
    }
    metadata = (New-ParallelBalanceMetadata)
  }
}

$parallelBalanceRequestJson3 = $parallelBalanceRequest3 | ConvertTo-Json -Depth 100
Send-ParallelBalanceRequestJson $parallelBalanceRequestJson3
```

最终响应应在三个成员都完成后才恢复 Agent A。即使远端完成顺序是王五、张三、李四，最终语义仍必须按各自的 `toolCallId` 归位，例如：

```json
{
  "status": {"state": "TASK_STATE_COMPLETED"},
  "artifacts": [
    {
      "parts": [
        {
          "text": "张三可用余额1500.92元；李四可用余额2088.10元；王五可用余额936.44元。"
        }
      ]
    }
  ]
}
```
