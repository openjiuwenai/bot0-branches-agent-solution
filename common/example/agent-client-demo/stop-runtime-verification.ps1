$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$processFile = Join-Path $root 'target\runtime-verification-logs\processes.json'
if (-not (Test-Path $processFile)) {
    Write-Host 'No recorded verification processes.'
    exit 0
}

$record = Get-Content -Raw -Encoding UTF8 $processFile | ConvertFrom-Json
foreach ($id in @($record.mockRuntimePid, $record.verificationAppPid)) {
    $process = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        Stop-Process -Id $id
        Write-Host "Stopped process $id"
    }
}
foreach ($port in @($record.mockRuntimePort, $record.verificationAppPort)) {
    if ($null -eq $port) { continue }
    $line = netstat -ano -p tcp | Select-String -Pattern "^\s*TCP\s+127\.0\.0\.1:$port\s+.*LISTENING\s+(\d+)\s*$" | Select-Object -First 1
    if ($null -ne $line) {
        $listenerPid = [int]$line.Matches[0].Groups[1].Value
        Stop-Process -Id $listenerPid -ErrorAction SilentlyContinue
        Write-Host "Stopped listener $listenerPid on port $port"
    }
}
Remove-Item -LiteralPath $processFile -Force
