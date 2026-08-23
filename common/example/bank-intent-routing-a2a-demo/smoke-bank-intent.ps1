param(
    [string]$IntentAgentBaseUrl = "http://127.0.0.1:18200",
    [int]$RequestTimeoutSeconds = 600,
    [switch]$KeepArtifacts
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("intent-bank-" + [guid]::NewGuid())
$processes = @()
$keepRunArtifacts = $KeepArtifacts -or $env:BANK_INTENT_KEEP_ARTIFACTS -eq "true"
New-Item -ItemType Directory -Path $tempDir | Out-Null

function Fail([string]$Message) {
    throw "FAIL: $Message"
}

function Start-Agent([string]$Label, [string]$Jar) {
    $stdout = Join-Path $tempDir "$Label.out.log"
    $stderr = Join-Path $tempDir "$Label.err.log"
    $process = Start-Process -FilePath "java" -ArgumentList @("-jar", $Jar) -WorkingDirectory $scriptDir `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $script:processes += $process
    return $process
}

function Wait-Health([string]$Label, [string]$Url, $Process) {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($Process.HasExited) {
            Fail "$Label exited before becoming healthy; see $tempDir"
        }
        try {
            $health = Invoke-RestMethod -Uri "$Url/health" -TimeoutSec 3
            if ($health.status -eq "healthy") {
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    Fail "$Label did not become healthy; see $tempDir"
}

function New-Context([string]$Label) {
    return "intent-bank-$Label-$([guid]::NewGuid())"
}

function Send-BankMessage([string]$ContextId, [string]$TaskId, [string]$Message) {
    $requestMessage = [ordered]@{
        role = "ROLE_USER"
        contextId = $ContextId
        parts = @(@{ text = $Message })
    }
    if ($TaskId) {
        $requestMessage.taskId = $TaskId
    }
    $request = [ordered]@{
        jsonrpc = "2.0"
        id = [guid]::NewGuid().ToString()
        method = "SendStreamingMessage"
        params = @{ message = $requestMessage }
    }
    $payload = $request | ConvertTo-Json -Depth 20
    Write-Host "`n=== REQUEST ==="
    Write-Host ($request | ConvertTo-Json -Depth 20)
    $rawResponse = Invoke-WebRequest -Method Post -Uri "$IntentAgentBaseUrl/a2a/" `
        -ContentType "application/json" -Headers @{ Accept = "text/event-stream" } `
        -Body $payload -TimeoutSec $RequestTimeoutSeconds
    $events = @()
    foreach ($line in ($rawResponse.Content -split "`r?`n")) {
        if ($line.StartsWith("data:")) {
            $events += ($line.Substring(5).Trim() | ConvertFrom-Json)
        }
    }
    if ($events.Count -eq 0) {
        Fail "SSE response contained no JSON-RPC data events"
    }
    Write-Host "`n=== RESPONSE ==="
    Write-Host ($events | ConvertTo-Json -Depth 30)
    return [pscustomobject]@{ Events = $events }
}

function Assert-Task($Response, [string]$ExpectedState, [string[]]$ExpectedText = @()) {
    foreach ($event in $Response.Events) {
        if ($event.error) {
            Fail ("JSON-RPC error: " + ($event.error | ConvertTo-Json -Depth 20 -Compress))
        }
    }
    $updates = @($Response.Events | ForEach-Object {
        if ($_.result.statusUpdate) { $_.result.statusUpdate }
        elseif ($_.result.artifactUpdate) { $_.result.artifactUpdate }
    })
    $states = @($updates | ForEach-Object { if ($_.status.state) { $_.status.state } })
    $state = if ($states.Count -gt 0) { $states[-1] } else { "" }
    if ($state -ne $ExpectedState) {
        Fail "task state is $state, expected $ExpectedState"
    }
    $taskIds = @($updates | ForEach-Object { if ($_.taskId) { [string]$_.taskId } } | Select-Object -Unique)
    if ($taskIds.Count -ne 1) {
        Fail "SSE response did not contain one stable taskId: $($taskIds -join ', ')"
    }
    $json = ($Response.Events | ConvertTo-Json -Depth 30 -Compress).ToLowerInvariant().Replace(",", "").Replace(" ", "")
    foreach ($text in $ExpectedText) {
        $normalized = $text.ToLowerInvariant().Replace(",", "").Replace(" ", "")
        if (-not $json.Contains($normalized)) {
            Fail "response does not contain '$text'"
        }
    }
    return [pscustomobject]@{ id = $taskIds[0]; status = [pscustomobject]@{ state = $state } }
}

function Assert-TaskContainsAny($Response, [string[]]$ExpectedText) {
    $json = ($Response.Events | ConvertTo-Json -Depth 30 -Compress).ToLowerInvariant().Replace(",", "").Replace(" ", "")
    foreach ($text in $ExpectedText) {
        $normalized = $text.ToLowerInvariant().Replace(",", "").Replace(" ", "")
        if ($json.Contains($normalized)) {
            return
        }
    }
    Fail "response does not contain any accepted date format"
}

function Assert-EventOrder($Response, [string]$First, [string]$Second) {
    $firstIndex = -1
    $secondIndex = -1
    for ($index = 0; $index -lt $Response.Events.Count; $index++) {
        $json = ($Response.Events[$index] | ConvertTo-Json -Depth 30 -Compress).ToLowerInvariant()
        if ($firstIndex -lt 0 -and $json.Contains($First.ToLowerInvariant())) {
            $firstIndex = $index
        }
        if ($secondIndex -lt 0 -and $json.Contains($Second.ToLowerInvariant())) {
            $secondIndex = $index
        }
    }
    if ($firstIndex -lt 0 -or $secondIndex -lt 0 -or $firstIndex -ge $secondIndex) {
        Fail "expected SSE event '$First' before '$Second'"
    }
}

function Run-Completed([string]$Label, [string]$Message, [string[]]$ExpectedText) {
    $response = Send-BankMessage (New-Context $Label) "" $Message
    $null = Assert-Task $response "TASK_STATE_COMPLETED" $ExpectedText
    Write-Host "PASS: $Label"
}

function Assert-LogCount([string]$Label, [string]$LogFile, [int]$Expected, [string[]]$Needles) {
    $actual = 0
    foreach ($line in Get-Content -Path $LogFile -Encoding UTF8) {
        $matches = $true
        foreach ($needle in $Needles) {
            $contains = if ($needle -match '\d$') {
                [regex]::IsMatch($line, [regex]::Escape($needle) + '(?![0-9.])')
            } else {
                $line.Contains($needle)
            }
            if (-not $contains) {
                $matches = $false
                break
            }
        }
        if ($matches) {
            $actual++
        }
    }
    if ($actual -ne $Expected) {
        Fail "$Label count=$actual, expected=$Expected"
    }
}

function Assert-LogOrder([string]$Label, [string[]]$Lines, [string]$First, [string]$Second) {
    $firstIndex = -1
    $secondIndex = -1
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($firstIndex -lt 0 -and $Lines[$index].Contains($First)) {
            $firstIndex = $index
        }
        if ($secondIndex -lt 0 -and $Lines[$index].Contains($Second)) {
            $secondIndex = $index
        }
    }
    if ($firstIndex -lt 0 -or $secondIndex -lt 0 -or $firstIndex -ge $secondIndex) {
        Fail "$Label did not observe '$First' before '$Second'"
    }
    Write-Host "PASS: $Label"
}

try {
    if (-not (Test-Path (Join-Path $scriptDir "application-intent_local.yml") -PathType Leaf)) {
        Fail "copy application-intent_local-example.yml to application-intent_local.yml and configure both models"
    }

    Push-Location $scriptDir
    & mvn -q clean package
    if ($LASTEXITCODE -ne 0) {
        Fail "Maven build failed"
    }

    $balance = Start-Agent "balance" "balance-agent-runtime/target/intent-bank-balance-agent-runtime-0.1.0.jar"
    $transfer = Start-Agent "transfer" "transfer-agent-runtime/target/intent-bank-transfer-agent-runtime-0.1.0.jar"
    $advisor = Start-Agent "wealth-advisor" "wealth-advisor-agent-runtime/target/intent-bank-wealth-advisor-agent-runtime-0.1.0.jar"
    $purchase = Start-Agent "wealth-purchase" "wealth-purchase-agent-runtime/target/intent-bank-wealth-purchase-agent-runtime-0.1.0.jar"
    Wait-Health "balance" "http://127.0.0.1:18201" $balance
    Wait-Health "transfer" "http://127.0.0.1:18202" $transfer
    Wait-Health "wealth-advisor" "http://127.0.0.1:18203" $advisor
    Wait-Health "wealth-purchase" "http://127.0.0.1:18204" $purchase

    $intent = Start-Agent "intent" "intent-agent-runtime/target/intent-bank-intent-agent-runtime-0.1.0.jar"
    Wait-Health "intent" $IntentAgentBaseUrl $intent
    foreach ($port in 18200..18204) {
        $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/.well-known/agent-card.json" -TimeoutSec 10
    }
    Write-Host "PASS: five health checks and Agent Cards"

    Run-Completed "balance-routing" "查询我的账户余额" @("12800")
    Run-Completed "wealth-advisor-routing" "推荐一款稳健的三个月理财" @("稳盈90天")
    Run-Completed "calculator-routing" "帮我计算 6 * 7" @("42")
    $today = Get-Date
    $response = Send-BankMessage (New-Context "date-routing") "" "今天是几号"
    $null = Assert-Task $response "TASK_STATE_COMPLETED"
    Assert-TaskContainsAny $response @($today.ToString("yyyy-MM-dd"), "$($today.Year)年$($today.Month)月$($today.Day)日")
    Write-Host "PASS: date-routing"
    Run-Completed "weather-routing" "深圳天气怎么样" @("深圳")
    Run-Completed "fallback-routing" "请帮我写一首关于星空的诗" @("银行")
    Assert-LogCount "fallback intent result" (Join-Path $tempDir "intent.out.log") 1 `
        @("BANK_DEMO_TOOL_RESULT tool=intent_match", '"status":"FALLBACK"', `
            '"intentId":"bank-intent-fallback"')

    # 相近语义负向语料：意图目录必须区分开容易混淆的能力，而不只是"能匹配上"。
    # 计算类请求同时用于验证不会被误路由到 balance-agent，并验证所有请求都经过意图工具。
    Run-Completed "date-not-weather" "今天星期几" @()
    Run-Completed "weather-not-date" "明天深圳会不会下雨" @("深圳")
    Run-Completed "calculator-not-balance" "帮我算一下 128 减去 28 等于多少" @("100")

    $context = New-Context "transfer-confirm"
    $response = Send-BankMessage $context "" "给张三转100元"
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" @("确认", "张三", "100")
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("张三", "100")
    Write-Host "PASS: transfer confirmation and resume"

    $context = New-Context "transfer-followup"
    $response = Send-BankMessage $context "" "我要转账"
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED"
    $response = Send-BankMessage $context $task.id "收款人是李四"
    $null = Assert-Task $response "TASK_STATE_INPUT_REQUIRED"
    $response = Send-BankMessage $context $task.id "金额是200元"
    $null = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" @("确认")
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("李四", "200")
    Write-Host "PASS: transfer information follow-up and resume"

    $context = New-Context "wealth-purchase"
    $response = Send-BankMessage $context "" "购买一万元稳盈90天"
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" @("确认", "稳盈90天", "10000")
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("稳盈90天", "10000")
    Write-Host "PASS: wealth purchase confirmation and resume"

    $context = New-Context "semantic-reference"
    $response = Send-BankMessage $context "" "推荐一款稳健的三个月理财"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("稳盈90天")
    $response = Send-BankMessage $context "" "购买刚才推荐的第一个产品，投入5000元"
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" @("确认", "稳盈90天", "5000")
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("稳盈90天", "5000")
    Write-Host "PASS: semantic reference uses conversation history"

    $context = New-Context "intent-change"
    $response = Send-BankMessage $context "" "给王五转50元"
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED"
    $response = Send-BankMessage $context $task.id "改为购买1000元稳盈90天理财"
    $null = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" @("理财", "确认")
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("稳盈90天", "1000")
    Write-Host "PASS: intent change re-enters intent_match"

    $context = New-Context "transfer-plan"
    $intentLog = Join-Path $tempDir "intent.out.log"
    $planLogStart = @(Get-Content -Path $intentLog -Encoding UTF8).Count
    $response = Send-BankMessage $context "" "给张三和李四各转100元"
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" `
        @("执行计划", "1. 给张三转账100元", "2. 给李四转账100元", "当前执行第 1/2 步", "确认")
    Assert-EventOrder $response "bank_plan_progress" "TASK_STATE_INPUT_REQUIRED"
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" `
        @("第 1/2 步已完成", "当前执行第 2/2 步", "李四", "确认")
    Assert-EventOrder $response "bank_plan_progress" "TASK_STATE_INPUT_REQUIRED"
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("张三", "李四", "100")
    Write-Host "PASS: DeepAgent exposes its plan and completes routed transfers one by one"

    $planLines = @(Get-Content -Path $intentLog -Encoding UTF8 | Select-Object -Skip $planLogStart)
    Assert-LogOrder "todo_create precedes planned intent routing" $planLines `
        "BANK_DEMO_TOOL_CALL tool=todo_create" "BANK_DEMO_TOOL_CALL tool=intent_match"
    $planAuditLog = Join-Path $tempDir "intent-plan.log"
    $planLines | Set-Content -Path $planAuditLog -Encoding UTF8
    Assert-LogCount "planned intent_match calls" $planAuditLog 2 @("BANK_DEMO_TOOL_CALL tool=intent_match")

    # 负向语料"帮我算一下 128 减去 28"也必须经过意图工具，且不得被误路由到 balance-agent。
    Assert-LogCount "balance execution" (Join-Path $tempDir "balance.out.log") 1 `
        @("BANK_DEMO_EXECUTION tool=query_balance")
    Assert-LogCount "wealth recommendation execution" (Join-Path $tempDir "wealth-advisor.out.log") 2 `
        @("BANK_DEMO_EXECUTION tool=recommend_wealth")
    Assert-LogCount "calculator execution" $intentLog 2 @("BANK_DEMO_EXECUTION tool=bank_calculator")
    Assert-LogCount "date execution" $intentLog 2 @("BANK_DEMO_EXECUTION tool=current_date")
    Assert-LogCount "weather execution" $intentLog 2 @("BANK_DEMO_EXECUTION tool=weather_query")
    $transferLog = Join-Path $tempDir "transfer.out.log"
    Assert-LogCount "all confirmed transfer executions" $transferLog 4 `
        @("BANK_DEMO_EXECUTION tool=execute_transfer")
    Assert-LogCount "no abandoned Wang Wu transfer" $transferLog 0 `
        @("BANK_DEMO_EXECUTION tool=execute_transfer", "recipient=王五")
    Assert-LogCount "Zhang San 100 transfers" $transferLog 2 `
        @("BANK_DEMO_EXECUTION tool=execute_transfer", "recipient=张三", "amount=100")
    Assert-LogCount "Li Si follow-up transfer" $transferLog 1 `
        @("BANK_DEMO_EXECUTION tool=execute_transfer", "recipient=李四", "amount=200")
    Assert-LogCount "Li Si planned transfer" $transferLog 1 `
        @("BANK_DEMO_EXECUTION tool=execute_transfer", "recipient=李四", "amount=100")
    $purchaseLog = Join-Path $tempDir "wealth-purchase.out.log"
    Assert-LogCount "all confirmed wealth purchases" $purchaseLog 3 `
        @("BANK_DEMO_EXECUTION tool=purchase_wealth")
    Assert-LogCount "10000 wealth purchase" $purchaseLog 1 `
        @("BANK_DEMO_EXECUTION tool=purchase_wealth", "amount=10000")
    Assert-LogCount "1000 wealth purchase after intent change" $purchaseLog 1 `
        @("BANK_DEMO_EXECUTION tool=purchase_wealth", "amount=1000")
    Assert-LogCount "5000 wealth purchase from semantic reference" $purchaseLog 1 `
        @("BANK_DEMO_EXECUTION tool=purchase_wealth", "product=稳盈90天", "amount=5000")
    Write-Host "PASS: business routing and exact execution audit"
    Write-Host "All bank intent routing scenarios passed."
} catch {
    Write-Error $_
    Write-Host "Logs retained in $tempDir" -ForegroundColor Yellow
    exit 1
} finally {
    foreach ($process in $processes) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Pop-Location -ErrorAction SilentlyContinue
}

if ($keepRunArtifacts) {
    Write-Host "Logs and responses retained in $tempDir" -ForegroundColor Yellow
} else {
    Remove-Item -Recurse -Force $tempDir
}
