param(
    [int]$MockRuntimePort = 19090,
    [int]$VerificationAppPort = 18080,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Find-ListeningPid([int]$Port) {
    $line = netstat -ano -p tcp | Select-String -Pattern "^\s*TCP\s+127\.0\.0\.1:$Port\s+.*LISTENING\s+(\d+)\s*$" | Select-Object -First 1
    if ($null -eq $line) { return $null }
    return [int]$line.Matches[0].Groups[1].Value
}

function Quote-PowerShellLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

function Quote-CmdArgument([string]$Value) {
    return '"' + $Value.Replace('"', '""') + '"'
}

function Start-JavaService(
    [string]$Classpath,
    [string]$MainClass,
    [string[]]$Arguments,
    [string]$LogFile,
    [string]$WorkingDirectory
) {
    if ([string]::IsNullOrWhiteSpace($LogFile) -or [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
        throw "Start-JavaService requires LogFile and WorkingDirectory"
    }
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $errorLog = Join-Path (Split-Path -Parent $LogFile) `
        (([System.IO.Path]::GetFileNameWithoutExtension($LogFile)) + '.error.log')
    $javaArguments = @((Quote-CmdArgument '-cp'), (Quote-CmdArgument $Classpath),
        (Quote-CmdArgument $MainClass))
    $javaArguments += $Arguments | ForEach-Object { Quote-CmdArgument $_ }
    $nativeArguments = $javaArguments -join ' '
    $quotedJava = Quote-PowerShellLiteral $java
    $quotedArguments = Quote-PowerShellLiteral $nativeArguments
    $quotedWorkDir = Quote-PowerShellLiteral $WorkingDirectory
    $quotedLog = Quote-PowerShellLiteral $LogFile
    $quotedErrorLog = Quote-PowerShellLiteral $errorLog
    $launcherScript = @"
try {
    `$info = [System.Diagnostics.ProcessStartInfo]::new()
    `$info.FileName = $quotedJava
    `$info.Arguments = $quotedArguments
    `$info.WorkingDirectory = $quotedWorkDir
    `$info.UseShellExecute = `$false
    `$info.CreateNoWindow = `$true
    `$info.RedirectStandardOutput = `$true
    `$info.RedirectStandardError = `$true
    `$process = [System.Diagnostics.Process]::Start(`$info)
    if (`$null -eq `$process) { throw 'Process.Start returned null' }
    [Console]::Out.WriteLine(`$process.Id)
    [Console]::Out.Flush()
    `$stdout = [System.IO.FileStream]::new($quotedLog, 'Create', 'Write', 'ReadWrite', 1, 'WriteThrough')
    `$stderr = [System.IO.FileStream]::new($quotedErrorLog, 'Create', 'Write', 'ReadWrite', 1, 'WriteThrough')
    `$stdoutCopy = `$process.StandardOutput.BaseStream.CopyToAsync(`$stdout)
    `$stderrCopy = `$process.StandardError.BaseStream.CopyToAsync(`$stderr)
    `$process.WaitForExit()
    `$stdoutCopy.Wait()
    `$stderrCopy.Wait()
    `$stdout.Dispose()
    `$stderr.Dispose()
    exit `$process.ExitCode
} catch {
    [Console]::Out.WriteLine('ERROR: ' + `$_.Exception.Message)
    [Console]::Out.Flush()
    exit 1
}
"@
    $launcherBytes = [System.Text.Encoding]::Unicode.GetBytes($launcherScript)
    $encodedCommand = [Convert]::ToBase64String($launcherBytes)
    $powerShell = (Get-Process -Id $PID).Path

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $powerShell
    $startInfo.Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encodedCommand"
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $launcher = [System.Diagnostics.Process]::Start($startInfo)
    if ($null -eq $launcher) {
        throw "Failed to start $MainClass"
    }
    $launcher.StandardInput.Close()
    $launcherResult = $launcher.StandardOutput.ReadLine()
    [int]$servicePid = 0
    if (-not [int]::TryParse($launcherResult, [ref]$servicePid)) {
        $launcher.WaitForExit()
        throw "Failed to start ${MainClass}: $launcherResult; see $LogFile and $errorLog"
    }
    $launcher.StandardOutput.Close()
    $launcher.StandardError.Close()
    $launcher.Dispose()
    return $servicePid
}

foreach ($port in @($MockRuntimePort, $VerificationAppPort)) {
    $owner = Find-ListeningPid $port
    if ($null -ne $owner) {
        throw "Port $port is already in use by process $owner"
    }
}

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

$mockLog = Join-Path $logDir 'mock-runtime.log'
$appLog = Join-Path $logDir 'verification-app.log'
$mockStart = @{
    Classpath = "$mockJar;$mockLib"
    MainClass = 'com.openjiuwen.mockruntime.MockRuntimeServer'
    Arguments = @("$MockRuntimePort")
    LogFile = $mockLog
    WorkingDirectory = $root
}
$appStart = @{
    Classpath = "$appJar;$appLib"
    MainClass = 'com.openjiuwen.client.runtimeverify.RuntimeVerificationApp'
    Arguments = @("$VerificationAppPort", "http://127.0.0.1:$MockRuntimePort")
    LogFile = $appLog
    WorkingDirectory = $root
}
$launchedMockPid = $null
$launchedAppPid = $null
try {
    $launchedMockPid = Start-JavaService @mockStart
    $launchedAppPid = Start-JavaService @appStart

    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 100
        $mockPid = Find-ListeningPid $MockRuntimePort
        $appPid = Find-ListeningPid $VerificationAppPort
    } while (($mockPid -ne $launchedMockPid -or $appPid -ne $launchedAppPid) -and
        [DateTime]::UtcNow -lt $deadline)

    if ($mockPid -ne $launchedMockPid -or $appPid -ne $launchedAppPid) {
        throw "Services did not own the requested ports " +
            "(mock=$mockPid/$launchedMockPid, app=$appPid/$launchedAppPid); see $logDir"
    }
} catch {
    @($launchedMockPid, $launchedAppPid) | Where-Object { $null -ne $_ } | ForEach-Object {
        Stop-Process -Id $_ -ErrorAction SilentlyContinue
    }
    throw
}

@{ mockRuntimePid = $mockPid; verificationAppPid = $appPid;
   mockRuntimePort = $MockRuntimePort; verificationAppPort = $VerificationAppPort } | ConvertTo-Json | Set-Content `
    -Encoding UTF8 (Join-Path $logDir 'processes.json')

Write-Host "Mock Runtime:     http://127.0.0.1:$MockRuntimePort/a2a"
Write-Host "Verification UI:  http://127.0.0.1:$VerificationAppPort/"
Write-Host "Logs:             $logDir"
Write-Host "Stop services:    .\stop-runtime-verification.ps1"
