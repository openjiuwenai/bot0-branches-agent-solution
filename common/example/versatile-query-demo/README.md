# Versatile Query Demo

这个 example 用 `agent-runtime-java` 的 A2A 入口启动一个只挂载 `VersatileAgentHandler` 的 runtime，然后通过 A2A `SendStreamingMessage` 调用远端 Versatile HTTP 服务。

默认本地 runtime 地址：

```text
http://127.0.0.1:18080/a2a/
```

默认远端 Versatile 地址在 `src/main/resources/application.yml` 中：

```text
http://127.0.0.1:31113/v1/0/agents/main_planner/conversations/{conversation_id}
```

## 1. 打包和启动服务

在 `D:\Code\openJiuwen\agent-solution` 下执行。下面都使用 repo 内本地 Maven 仓库，避免写入全局 `.m2`。

```powershell
Set-Location D:\Code\openJiuwen\agent-solution

# 1. 拉取并安装 vendor/agent-runtime-java
.\scripts\update-agent-runtime.ps1 -LocalRepository D:\Code\openJiuwen\agent-solution\.m2\repository

# 2. 打包安装当前 Versatile adapter
mvn "-Dmaven.repo.local=D:\Code\openJiuwen\agent-solution\.m2\repository" `
  -f "D:\Code\openJiuwen\agent-solution\common\agent-runtime-ext-java\pom.xml" `
  -pl agent-service-adapters/agent-service-adapters-versatile -am clean install -DskipTests

# 3. 打包 example
mvn "-Dmaven.repo.local=D:\Code\openJiuwen\agent-solution\.m2\repository" `
  -f "D:\Code\openJiuwen\agent-solution\common\example\versatile-query-demo\pom.xml" `
  clean package -DskipTests
```

启动服务：

```powershell
Set-Location D:\Code\openJiuwen\agent-solution\common\example\versatile-query-demo

# 可选：覆盖本地 runtime 端口
$env:SERVER_PORT = "18080"

# 可选：覆盖远端 Versatile 地址
$env:VERSATILE_URL = "http://127.0.0.1:31113/v1/0/agents/main_planner/conversations/{conversation_id}"

java -jar .\target\versatile-query-demo-0.1.0-SNAPSHOT.jar
```

服务启动后，A2A 入口是：

```text
http://127.0.0.1:18080/a2a/
```

## 2. 通过 MainClient 连续执行三轮调用

`VersatileA2AClientMain` 会按顺序发送三轮 JSON-RPC 请求，并打印每轮请求体、HTTP 状态码和服务端返回流。

另开一个 PowerShell：

```powershell
Set-Location D:\Code\openJiuwen\agent-solution
$env:A2A_ENDPOINT_URL = "http://127.0.0.1:18080/a2a/"

mvn "-Dmaven.repo.local=D:\Code\openJiuwen\agent-solution\.m2\repository" `
  -f "D:\Code\openJiuwen\agent-solution\common\example\versatile-query-demo\pom.xml" `
  -DskipTests compile exec:java `
  "-Dexec.mainClass=com.openjiuwen.example.versatile.a2a.VersatileA2AClientMain"
```

如果想在 IDEA 里运行，直接运行：

```text
D:\Code\openJiuwen\agent-solution\common\example\versatile-query-demo\src\main\java\com\openjiuwen\example\versatile\a2a\VersatileA2AClientMain.java
```

注意字段流向：

```text
message.parts[0].text            -> 解析 query / intent
params.metadata.body.custom_data -> 作为远端 HTTP body 基底
params.metadata.headers          -> 按 forward-header-whitelist 透传
params.metadata.query            -> 作为远端 URL query params
headers-template                 -> application.yml 中固定配置，优先级最高
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

