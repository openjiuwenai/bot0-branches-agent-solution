param(
    [string]$IntentAgentBaseUrl = "http://127.0.0.1:18200",
    [int]$RequestTimeoutSeconds = 600
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("intent-bank-" + [guid]::NewGuid())
$processes = @()
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
    $payload = [ordered]@{
        jsonrpc = "2.0"
        id = [guid]::NewGuid().ToString()
        method = "SendMessage"
        params = @{ message = $requestMessage }
    } | ConvertTo-Json -Depth 20
    return Invoke-RestMethod -Method Post -Uri "$IntentAgentBaseUrl/a2a/" -ContentType "application/json" `
        -Body $payload -TimeoutSec $RequestTimeoutSeconds
}

function Assert-Task($Response, [string]$ExpectedState, [string[]]$ExpectedText = @()) {
    if ($Response.error) {
        Fail ("JSON-RPC error: " + ($Response.error | ConvertTo-Json -Depth 20 -Compress))
    }
    $task = $Response.result.task
    if ($task.status.state -ne $ExpectedState) {
        Fail "task state is $($task.status.state), expected $ExpectedState"
    }
    $json = ($Response | ConvertTo-Json -Depth 30 -Compress).ToLowerInvariant().Replace(",", "").Replace(" ", "")
    foreach ($text in $ExpectedText) {
        $normalized = $text.ToLowerInvariant().Replace(",", "").Replace(" ", "")
        if (-not $json.Contains($normalized)) {
            Fail "response does not contain '$text'"
        }
    }
    return $task
}

function Assert-TaskContainsAny($Response, [string[]]$ExpectedText) {
    $json = ($Response | ConvertTo-Json -Depth 30 -Compress).ToLowerInvariant().Replace(",", "").Replace(" ", "")
    foreach ($text in $ExpectedText) {
        $normalized = $text.ToLowerInvariant().Replace(",", "").Replace(" ", "")
        if ($json.Contains($normalized)) {
            return
        }
    }
    Fail "response does not contain any accepted date format"
}

function Run-Completed([string]$Label, [string]$Message, [string[]]$ExpectedText) {
    $response = Send-BankMessage (New-Context $Label) "" $Message
    $null = Assert-Task $response "TASK_STATE_COMPLETED" $ExpectedText
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
    Run-Completed "fallback-routing" "请帮我写一首关于星空的诗" @("匹配", "银行")

    $context = New-Context "transfer-confirm"
    $response = Send-BankMessage $context "" "给张三转100元"
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" @("确认")
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
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" @("确认")
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("稳盈90天", "10000")
    Write-Host "PASS: wealth purchase confirmation and resume"

    $context = New-Context "intent-change"
    $response = Send-BankMessage $context "" "给王五转50元"
    $task = Assert-Task $response "TASK_STATE_INPUT_REQUIRED"
    $response = Send-BankMessage $context $task.id "改为购买1000元稳盈90天理财"
    $null = Assert-Task $response "TASK_STATE_INPUT_REQUIRED" @("理财", "确认")
    $response = Send-BankMessage $context $task.id "确认"
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("稳盈90天", "1000")
    Write-Host "PASS: intent change re-enters intent_match"

    $context = New-Context "transfer-plan"
    $response = Send-BankMessage $context "" "给张三和李四各转100元"
    $task = $response.result.task
    for ($step = 0; $step -lt 4 -and $task.status.state -ne "TASK_STATE_COMPLETED"; $step++) {
        $null = Assert-Task $response "TASK_STATE_INPUT_REQUIRED"
        $response = Send-BankMessage $context $task.id "确认"
        $task = $response.result.task
    }
    $null = Assert-Task $response "TASK_STATE_COMPLETED" @("张三", "李四", "100")
    Write-Host "PASS: DeepAgent plan executes two routed transfer steps"
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

Remove-Item -Recurse -Force $tempDir
