# FEAT-012 real-broker acceptance entry (fail-closed) -- Windows/PowerShell port of broker-acceptance.sh.
#
# Usage:
#   powershell -File common\agent-bus\event-bus\broker-acceptance.ps1 127.0.0.1:9876
#   .\common\agent-bus\event-bus\broker-acceptance.ps1 127.0.0.1:9876
#
#   Add -CI for non-interactive/CI: no pause, result via process exit code (fail=1, pass=0).
#   Default (interactive) pauses at the end for Enter so the window does not close on you.
#
# Acceptance semantics (mirrors ISSUE-80):
#   1. Missing nameserver => immediate non-zero exit (never a false "acceptance passed").
#   2. The three RealBroker*IntegrationTest run > 0, with Skipped=0 (env guard not skipped).
#   3. Any test failure => non-zero exit.
#
# Scope: RealBroker*IT are component-level real-broker integration slices, not a product E2E.
# ASCII-only on purpose: Windows PowerShell 5.1 reads non-BOM UTF-8 as ANSI (GBK), which
# mis-parses CJK comments. Keep this file ASCII.
param(
    [Parameter(Position=0)] [string] $Nameserver = $env:ROCKETMQ_NAMESERVER,
    [switch] $CI
)
$ErrorActionPreference = 'Stop'

function Fail([string]$msg) {
    Write-Host "`n>>> FAIL: $msg" -ForegroundColor Red
}

if ([string]::IsNullOrWhiteSpace($Nameserver)) {
    Write-Host "ROCKETMQ_NAMESERVER is required -- pass it as the first argument:" -ForegroundColor Red
    Write-Host "  powershell -File common\agent-bus\event-bus\broker-acceptance.ps1 127.0.0.1:9876" -ForegroundColor Yellow
    if (-not $CI) { Read-Host "`nPress Enter to continue" }
    if ($CI) { exit 1 } else { return }
}
$env:ROCKETMQ_NAMESERVER = $Nameserver   # propagate to the surefire test JVM so @EnabledIfEnvironmentVariable enables the tests

$scriptDir = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }   # -File fallback
$pom = Join-Path $scriptDir 'pom.xml'
$reportsDir = Join-Path $scriptDir 'event-bus-relay\target\surefire-reports'
$tests = 'RealBrokerProduceSideIntegrationTest,RealBrokerResponseSideIntegrationTest,RealBrokerTwoHopRelayIntegrationTest'

Write-Host ">>> mvn -f $pom -pl event-bus-relay test -Dtest=$tests"
mvn -f $pom -pl event-bus-relay test "-Dtest=$tests"
$mvnExit = $LASTEXITCODE

$failed = $false
if ($null -eq $mvnExit -or $mvnExit -ne 0) {
    Fail "mvn exited $mvnExit (see BUILD FAILURE above -- likely RealBroker*IT cold-start flakiness)"
    $failed = $true
} else {
    $reports = Get-ChildItem (Join-Path $reportsDir 'TEST-*RealBroker*.xml') -ErrorAction SilentlyContinue
    if (-not $reports) {
        Fail 'no surefire reports -- RealBroker*IT did not execute'
        $failed = $true
    } elseif (Select-String -Path $reports.FullName -Pattern 'skipped="[1-9][0-9]*"' -Quiet) {
        Fail 'a RealBroker*IT was SKIPPED (env guard) -- acceptance failed'
        $failed = $true
    } else {
        Write-Host "`n>>> PASS: all RealBroker*IT executed with Skipped=0" -ForegroundColor Green
    }
}

if (-not $CI) { Read-Host "`nPress Enter to continue" }
if ($CI) { if ($failed) { exit 1 } else { exit 0 } }
