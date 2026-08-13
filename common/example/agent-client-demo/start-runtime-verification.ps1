param(
    [int]$MockRuntimePort = 19090,
    [int]$VerificationAppPort = 18080,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $NoBuild) {
    Push-Location $root
    try {
        & mvn.cmd -q -o -pl mock-runtime,verification-app-to-runtime -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

$mockJar = Join-Path $root 'mock-runtime\target\mock-runtime.jar'
$mockLib = Join-Path $root 'mock-runtime\target\lib\*'
$appJar = Join-Path $root 'verification-app-to-runtime\target\verification-app-to-runtime.jar'
$appLib = Join-Path $root 'verification-app-to-runtime\target\lib\*'
$logDir = Join-Path $root 'target\runtime-verification-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$launcher = Join-Path $root 'RuntimeVerificationLauncher.java'
$mockLog = Join-Path $logDir 'mock-runtime.log'
$appLog = Join-Path $logDir 'verification-app.log'
$launched = & java.exe $launcher "$mockJar;$mockLib" "$MockRuntimePort" "$appJar;$appLib" `
    "$VerificationAppPort" "http://127.0.0.1:$MockRuntimePort" "$mockLog" "$appLog" "$root"
if ($LASTEXITCODE -ne 0 -or $launched -notmatch '^(\d+),(\d+)$') {
    throw "Failed to launch verification services: $launched"
}
$launchedMockPid = [int]$Matches[1]
$launchedAppPid = [int]$Matches[2]

Start-Sleep -Milliseconds 700
function Find-ListeningPid([int]$Port) {
    $line = netstat -ano -p tcp | Select-String -Pattern "^\s*TCP\s+127\.0\.0\.1:$Port\s+.*LISTENING\s+(\d+)\s*$" | Select-Object -First 1
    if ($null -eq $line) { return $null }
    return [int]$line.Matches[0].Groups[1].Value
}
$mockPid = Find-ListeningPid $MockRuntimePort
$appPid = Find-ListeningPid $VerificationAppPort
if ($null -eq $mockPid -or $null -eq $appPid) {
    Stop-Process -Id $launchedMockPid,$launchedAppPid -ErrorAction SilentlyContinue
    throw "Services failed to listen on ports $MockRuntimePort and $VerificationAppPort"
}

@{ mockRuntimePid = $mockPid; verificationAppPid = $appPid;
   mockRuntimePort = $MockRuntimePort; verificationAppPort = $VerificationAppPort } | ConvertTo-Json | Set-Content `
    -Encoding UTF8 (Join-Path $logDir 'processes.json')

Write-Host "Mock Runtime:     http://127.0.0.1:$MockRuntimePort/a2a"
Write-Host "Verification UI:  http://127.0.0.1:$VerificationAppPort/"
Write-Host "Logs:             $logDir"
Write-Host "Stop services:    .\stop-runtime-verification.ps1"
