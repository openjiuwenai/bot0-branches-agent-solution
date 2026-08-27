# Versatile A2A Adapter Demo

这个 example 用 `agent-runtime-java` 的 A2A 入口启动一个只挂载 `VersatileAgentHandler` 的 runtime，然后通过 A2A `SendStreamingMessage` 调用远端 Versatile HTTP 服务。

> 另提供 Linux shell 脚本 + Docker 镜像方式（`script/start.sh`、`script/send-requests.sh`、`script/build-image.sh`），见 [`script/README.md`](script/README.md)。

默认本地 runtime 地址：

```text
http://127.0.0.1:18080/a2a/
```

默认远端 Versatile 地址在 `src/main/resources/application.yml` 中：

```text
http://127.0.0.1:31113/v1/0/agents/{agent_id}/conversations/{conversation_id}
```

`url-template` 支持两个占位符，由 adapter 在每次请求时替换：

| 占位符 | 取值来源 | 缺失时 |
|---|---|---|
| `{conversation_id}` | 请求的 A2A `contextId` | 替换为空串 |
| `{agent_id}` | `params.metadata.agent_id`（metadata 顶层字段） | 替换为空串 |

> 注意 `{agent_id}` 取自 `params.metadata.agent_id`（metadata 顶层字段），不是 `params.metadata.body.*` 下的任何字段。
> 本 demo 的三轮请求都在 metadata 顶层带上了 `"agent_id": "main_planner"`，配合默认模板解析后
> 远端地址为 `.../agents/main_planner/conversations/<contextId>`。若模板不含 `{agent_id}`，
> 该字段被忽略，不会报错（见 `VersatileRequestExtractor` 单测）。

## 1. 打包和启动服务

在仓库根目录下执行。下面都使用 repo 内本地 Maven 仓库，避免写入全局 `.m2`。

```powershell
Set-Location <repo-root>

# 1. 拉取并安装 vendor/agent-runtime-java
.\scripts\update-agent-runtime.ps1 -LocalRepository .m2\repository

# 2. 打包安装当前 Versatile adapter
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\agent-runtime-ext-java\pom.xml" `
  -pl agent-service-adapters/agent-service-adapters-versatile -am clean install -DskipTests

# 3. 打包 example
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\versatile-a2a-adapter-demo\pom.xml" `
  clean package -DskipTests
```

启动服务：

```powershell
Set-Location <repo-root>\common\example\versatile-a2a-adapter-demo

# 可选：覆盖本地 runtime 端口
$env:SERVER_PORT = "18080"

# 可选：覆盖远端 Versatile 地址
$env:VERSATILE_URL = "http://127.0.0.1:31113/v1/0/agents/{agent_id}/conversations/{conversation_id}"

java -jar .\target\versatile-a2a-adapter-demo-0.1.0.jar
```

服务启动后，A2A 入口是：

```text
http://127.0.0.1:18080/a2a/
```

## 2. 通过 MainClient 连续执行三轮调用

`VersatileA2AClientMain` 会按顺序发送三轮 JSON-RPC 请求，并打印每轮请求体、HTTP 状态码和服务端返回流。

另开一个 PowerShell：

```powershell
Set-Location <repo-root>
$env:A2A_ENDPOINT_URL = "http://127.0.0.1:18080/a2a/"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\versatile-a2a-adapter-demo\pom.xml" `
  -DskipTests compile exec:java `
  "-Dexec.mainClass=com.openjiuwen.example.versatile.a2a.VersatileA2AClientMain"
```

如果想在 IDEA 里运行，直接运行：

```text
common\example\versatile-a2a-adapter-demo\src\main\java\com\openjiuwen\example\versatile\a2a\VersatileA2AClientMain.java
```

注意字段流向：

```text
message.parts[0].text            -> 解析 query / intent
params.metadata.body.custom_data -> 作为远端 HTTP body 基底
params.metadata.headers          -> 按 forward-header-whitelist 透传
params.metadata.query            -> 作为远端 URL query params
params.metadata.agent_id        -> 替换 url-template 中的 {agent_id}（缺失替换为空串）
contextId                        -> 替换 url-template 中的 {conversation_id}（缺失替换为空串）
headers-template                 -> application.yml 中固定配置，优先级最高
```

## 配置说明

`openjiuwen.service.versatile.*` 前缀下的全部配置项（来自 `VersatileProperties`）。
demo 的 `application.yml` 只用了其中一部分，其余按需添加。

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `url-template` | `String` | — | 远端 Versatile HTTP 地址模板，必填。支持 `{conversation_id}`、`{agent_id}` 占位符 |
| `timeout` | `Duration` | `600s` | 调用远端 HTTP 的超时时间 |
| `insecure-skip-verify` | `boolean` | `false` | 是否跳过 HTTPS 证书校验（仅本地调测用） |
| `headers-template` | `Map<String,String>` | `{}` | 固定写入远端请求头，**同名时覆盖**被透传的请求头（优先级最高） |
| `forward-header-whitelist` | `Set<String>` | `{}` | 允许从 `metadata.headers` 透传到远端的请求头白名单（大小写不敏感） |
| `result-node-name` | `String` | `null` | 非 stream 聚合时，命中该节点且远端流出现 `node_type=End` 才抽取 result；否则 result 为 `null` |
| `intents` | `List<{id,name}>` | `[]` | 注入远端 `body.inputs.intents` 的候选意图数组（JSON 字符串）。未配置时走 legacy 模式，不写 `intents` |
| `messages.source` | `String` | `serve_request_messages` | `body.inputs.messages` 的来源 |
| `messages.required` | `boolean` | `true` | `messages` 为空/无效时是否抛 `VERSATILE_INTENT_INPUT_MISSING` |
| `intent-agent-mapping` | `Map<String, List<{agent-card,priority}>>` | `{}` | 意图→候选 agentCard 映射 |
| `intent-agent-mapping-strategy` | `enum` | `FIRST` | 多候选选择策略：`FIRST` / `PRIORITY` / `ROUND_ROBIN` |
| `result-extractions` | `List<{match,get}>` | `[]` | 按 JSON path 抽取远端结果字段并存到指定 key |
| `interrupt.signal-match` | `String` | `null` | 用户交互打断信号匹配 |
| `interrupt.prompt-get` | `String` | `null` | 抽取打断提示文案的 path |
| `interrupt.input-requirement-get` | `String` | `null` | 抽取打断所需输入的 path |
| `interrupt.resume-token-get` | `String` | `null` | 抽取 resume token 的 path |
| `interrupt.resume-request-template.body` | `Map<String,Object>` | `{}` | resume 请求体模板，支持 `{字段名}` 引用 `metadata.body` 顶层字段 |
| `default-workflow.agent-card` | `String` | `null` | L2 自愈默认工作流 agentCard；未配置时歧义意图回退 L1 抛 `VERSATILE_INTENT_AMBIGUOUS` |
| `log-mask-sensitive` | `boolean` | `true` | DEBUG 日志是否掩码 `messages[].content`、`response_content`、metadata 值 |
| `ambiguous-intent-id` | `String` | `"1"` | 歧义意图回退时使用的 intent id |

### url-template 占位符

模板替换发生在 `VersatileRequestExtractor.extract()`，每次请求实时替换，顺序无关：

```text
{conversation_id}  <- request.conversationId（即 A2A contextId）
{agent_id}         <- request.metadata.agent_id（metadata 顶层字段）
```

两者缺失都替换为空串。模板不含某占位符时，对应字段被忽略、不报错。

> Spring Boot 解析 `${VERSATILE_URL:...}` 默认值时，裸 `}` 会被误判为占位符结束符，
> 因此 `application.yml` 里这两个占位符都通过 `versatile-demo.conversation-id-placeholder` /
> `versatile-demo.agent-id-placeholder` 嵌套引用。**通过 `VERSATILE_URL` 环境变量覆盖时无此限制**，
> 直接写真实占位符即可，例如：
> `VERSATILE_URL=http://host/v1/0/agents/{agent_id}/conversations/{conversation_id}`。

### metadata 字段流向

```text
params.message.parts[0].text      -> 解析 query/intent，覆盖远端 body.inputs.query/.intent
params.metadata.body.custom_data  -> 原样成为远端 body 顶层字段（body 基底）
params.metadata.body 其它顶层字段  -> 默认不进远端请求，除非 resume-request-template 用 {字段名} 引用
params.metadata.headers           -> 按 forward-header-whitelist 过滤后透传，再叠加 headers-template（同名覆盖）
params.metadata.query             -> 拼到远端 URL 上的 query 参数
params.metadata.agent_id          -> 替换 url-template 的 {agent_id}
```

## 3. PowerShell 手动发送三轮请求

假设服务已经启动，下面的 PowerShell 可以直接复制执行。每轮都会打印完整 JSON-RPC 请求体，再调用本地 `/a2a/`。

下面的请求故意让 `metadata.body.input.query/intent` 和 `metadata.body.custom_data.inputs.query/intent` 保持固定基底值；每轮真正变化的是 `message.parts[0].text` 中的 query/intent。adapter 会从 message text 提取 query/intent，并覆盖到最终远端 body.inputs。

先定义公共函数：

```powershell
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$a2aUrl = "http://127.0.0.1:18080/a2a/"

function New-A2ARequestJson {
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
        contextId = "versatile-a2a-1"
        parts = @(
          [ordered]@{
            text = $messageText
          }
        )
      }
      metadata = [ordered]@{
        agent_id = "main_planner"
        body = [ordered]@{
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
          "x-debug-trace" = "trace-from-example"
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

function Send-A2ARequestJson {
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
      "x-debug-trace" = "trace-from-example"
    } `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($RequestJson))

  Write-Host "HTTP $($response.StatusCode)"
  $response.RawContentStream.Position = 0
  $reader = New-Object System.IO.StreamReader($response.RawContentStream, [System.Text.Encoding]::UTF8)
  $reader.ReadToEnd()
}
```

第一轮：

```powershell
$requestJson1 = New-A2ARequestJson `
  -Id "versatile-a2a-demo-1" `
  -Query "先查询尾号为4241的银行卡余额，再转账5元给李四" `
  -Intent "查询账户余额"

Send-A2ARequestJson $requestJson1
```

第二轮：

```powershell
$requestJson2 = New-A2ARequestJson `
  -Id "versatile-a2a-demo-2" `
  -Query '[{"cardNum":"6222021816044054241","regAcctType":"011","cardAlias":""}]' `
  -Intent "LATEST"

Send-A2ARequestJson $requestJson2
```

第三轮：

```powershell
$round3Query = @'
{"bankCardBalanceList":[{"bankCardNumber":"6222021816044054241","mediumStatus":"0","currencyBalanceList":[{"currencyCode":"001","currencyName":"人民币可用余额","balance":"1500.92"}]}],"responseData":[{"answer":"已为您查询账户余额","readme":"已为您查询账户余额","pageData":"","type":"1"},{"answer":"","readme":"","pageData":{"id":"queryBalance","bankBalanceData":[{"layouttype":"1","actionFun_click":{"menu":{"param":"returnFlag=3","needLogin":"false","menuId":"account_1"}},"actionType_click":"menu","bankIoc":{"titleValueColor":"","titleValue":"","type":"pic","bgColor":"","bgPic":"","actionFun_click":"","actionType_click":""},"areaName":{"titleValueColor":"F4E1B3","titleValue":"广州","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"cardTypeDesc":{"titleValueColor":"F4E1B3","titleValue":"借记卡（I类）","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"alias":{"titleValueColor":"F4E1B3","titleValue":"","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"cardNumLast":{"titleValueColor":"F4E1B3","titleValue":"6222****4241","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balanceList":[{"balanceTitle":{"titleValueColor":"C3B9A1","titleValue":"人民币余额","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balance":{"titleValueColor":"F4E1B3","titleValue":"1970.23","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null}},{"balanceTitle":{"titleValueColor":"C3B9A1","titleValue":"人民币可用余额","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balance":{"titleValueColor":"F4E1B3","titleValue":"1500.92","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null}}],"showCardNumBtn":{"type":"button","btnId":"tttt","btnName":"查看","bgColor":null,"actionFun_click":"abc","actionType_click":"4"},"showAccountBalanceBtn":{"type":"button","btnId":"qqqq","btnName":"点击查询","bgColor":null,"actionFun_click":"def","actionType_click":"4"}},"queryStatus":"成功","failCause":"","type":"7"}]}
'@

$requestJson3 = New-A2ARequestJson `
  -Id "versatile-a2a-demo-3" `
  -Query $round3Query `
  -Intent "LATEST"

Send-A2ARequestJson $requestJson3
```

如果想观察 Versatile adapter 最终发给远端 HTTP 服务的完整参数，看服务端日志中的：

```text
Versatile remote request ...
Versatile outbound request=...
```

## 4. PowerShell 手动发送三轮非 stream 请求

A2A 非流式入口使用 JSON-RPC `SendMessage`。请求体结构和第三章一致，只是 `method` 改成 `SendMessage`，HTTP 返回是一次性 JSON，不是 `text/event-stream`。非流式会聚合成 `{role: "assistant", content: "..."}`：命中 `node_type=End` 且抽到 result 时使用 result，否则使用最后一条远端事件兜底；远端没有任何事件时 content 为空字符串。

下面是自包含版本，不依赖第三章函数。`metadata.body.input` 和 `metadata.body.custom_data.inputs` 中的 query/intent 是固定基底值；每轮真正变化的是 `message.parts[0].text` 中的 query/intent，adapter 会从 message text 提取后覆盖远端 body.inputs。

```powershell
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$a2aUrl = "http://127.0.0.1:18080/a2a/"

function New-A2ANonStreamRequestJson {
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
    method = "SendMessage"
    params = [ordered]@{
      message = [ordered]@{
        role = "ROLE_USER"
        contextId = "versatile-a2a-1"
        parts = @(
          [ordered]@{
            text = $messageText
          }
        )
      }
      metadata = [ordered]@{
        agent_id = "main_planner"
        body = [ordered]@{
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
          "x-debug-trace" = "trace-from-example"
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

function Send-A2ANonStreamRequestJson {
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
      Accept = "application/json"
      stream = "true"
      "x-invoke-mode" = "DEBUG"
      "x-language" = "zh-cn"
      "x-debug-trace" = "trace-from-example"
    } `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($RequestJson))

  Write-Host "HTTP $($response.StatusCode)"
  $response.RawContentStream.Position = 0
  $reader = New-Object System.IO.StreamReader($response.RawContentStream, [System.Text.Encoding]::UTF8)
  $reader.ReadToEnd()
}
```

第一轮非 stream：

```powershell
$requestJson1 = New-A2ANonStreamRequestJson `
  -Id "versatile-a2a-demo-non-stream-1" `
  -Query "先查询尾号为4241的银行卡余额，再转账5元给李四" `
  -Intent "查询账户余额"

Send-A2ANonStreamRequestJson $requestJson1
```

第二轮非 stream：

```powershell
$requestJson2 = New-A2ANonStreamRequestJson `
  -Id "versatile-a2a-demo-non-stream-2" `
  -Query '[{"cardNum":"6222021816044054241","regAcctType":"011","cardAlias":""}]' `
  -Intent "LATEST"

Send-A2ANonStreamRequestJson $requestJson2
```

第三轮非 stream：

```powershell
$round3Query = @'
{"bankCardBalanceList":[{"bankCardNumber":"6222021816044054241","mediumStatus":"0","currencyBalanceList":[{"currencyCode":"001","currencyName":"人民币可用余额","balance":"1500.92"}]}],"responseData":[{"answer":"已为您查询账户余额","readme":"已为您查询账户余额","pageData":"","type":"1"},{"answer":"","readme":"","pageData":{"id":"queryBalance","bankBalanceData":[{"layouttype":"1","actionFun_click":{"menu":{"param":"returnFlag=3","needLogin":"false","menuId":"account_1"}},"actionType_click":"menu","bankIoc":{"titleValueColor":"","titleValue":"","type":"pic","bgColor":"","bgPic":"","actionFun_click":"","actionType_click":""},"areaName":{"titleValueColor":"F4E1B3","titleValue":"广州","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"cardTypeDesc":{"titleValueColor":"F4E1B3","titleValue":"借记卡（I类）","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"alias":{"titleValueColor":"F4E1B3","titleValue":"","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"cardNumLast":{"titleValueColor":"F4E1B3","titleValue":"6222****4241","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balanceList":[{"balanceTitle":{"titleValueColor":"C3B9A1","titleValue":"人民币余额","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balance":{"titleValueColor":"F4E1B3","titleValue":"1970.23","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null}},{"balanceTitle":{"titleValueColor":"C3B9A1","titleValue":"人民币可用余额","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null},"balance":{"titleValueColor":"F4E1B3","titleValue":"1500.92","type":"text","bgColor":null,"actionFun_click":null,"actionType_click":null}}],"showCardNumBtn":{"type":"button","btnId":"tttt","btnName":"查看","bgColor":null,"actionFun_click":"abc","actionType_click":"4"},"showAccountBalanceBtn":{"type":"button","btnId":"qqqq","btnName":"点击查询","bgColor":null,"actionFun_click":"def","actionType_click":"4"}},"queryStatus":"成功","failCause":"","type":"7"}]}
'@

$requestJson3 = New-A2ANonStreamRequestJson `
  -Id "versatile-a2a-demo-non-stream-3" `
  -Query $round3Query `
  -Intent "LATEST"

Send-A2ANonStreamRequestJson $requestJson3
```

当前 Versatile adapter 的非 stream 只返回最终 `QueryResponse.result`。只有命中 `result-node-name` 且远端流中出现 `node_type=End` 时，`result` 才会有值；否则 result 为 `null`。

