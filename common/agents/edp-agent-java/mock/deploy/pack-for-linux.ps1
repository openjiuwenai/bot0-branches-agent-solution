<#
.SYNOPSIS
  Package the current Versatile mock source and its independent deploy directory for Linux.

.DESCRIPTION
  The archive contains versatile_main.py, engine, config, workflows, and deploy resources.
  It excludes tests, caches, and deploy/.env.
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$DeployDir = $PSScriptRoot
$ProjectDir = Split-Path -Parent $DeployDir

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = $ProjectDir
}
elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $ProjectDir $OutputDirectory
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$required = @(
    (Join-Path $ProjectDir "versatile_main.py"),
    (Join-Path $ProjectDir "engine"),
    (Join-Path $ProjectDir "config\server.json"),
    (Join-Path $ProjectDir "workflows\default.json"),
    (Join-Path $DeployDir "Dockerfile"),
    (Join-Path $DeployDir "requirements.txt"),
    (Join-Path $DeployDir "README.md")
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required deployment source is missing: $path"
    }
}
if (-not (Get-Command tar -ErrorAction SilentlyContinue)) {
    throw "tar was not found. Windows 10 1803+ normally includes it."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$bundleName = "mock"
$archiveName = "versatile-mock-deploy-$stamp.tar.gz"
$archivePath = Join-Path $OutputDirectory $archiveName
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("versatile-mock-pack-" + [guid]::NewGuid().ToString("N"))
$bundleRoot = Join-Path $stageRoot $bundleName
$success = $false

try {
    New-Item -ItemType Directory -Force -Path $bundleRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $ProjectDir "versatile_main.py") -Destination $bundleRoot
    Copy-Item -LiteralPath (Join-Path $ProjectDir "engine") -Destination $bundleRoot -Recurse
    Copy-Item -LiteralPath (Join-Path $ProjectDir "config") -Destination $bundleRoot -Recurse
    Copy-Item -LiteralPath (Join-Path $ProjectDir "workflows") -Destination $bundleRoot -Recurse

    $deployDestination = Join-Path $bundleRoot "deploy"
    New-Item -ItemType Directory -Force -Path $deployDestination | Out-Null
    Get-ChildItem -LiteralPath $DeployDir -Force |
        Where-Object { $_.Name -ne ".env" -and $_.Name -notlike "*.tar.gz" } |
        ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $deployDestination -Recurse -Force
        }

    Get-ChildItem -LiteralPath $bundleRoot -Recurse -Directory -Filter "__pycache__" |
        Sort-Object FullName -Descending |
        Remove-Item -Recurse -Force
    Get-ChildItem -LiteralPath $bundleRoot -Recurse -File |
        Where-Object { $_.Extension -in ".pyc", ".pyo" } |
        Remove-Item -Force

    & tar -czf $archivePath -C $stageRoot $bundleName
    if ($LASTEXITCODE -ne 0) {
        throw "tar failed with exit code $LASTEXITCODE"
    }
    $success = $true
}
finally {
    if (Test-Path -LiteralPath $stageRoot) {
        Remove-Item -LiteralPath $stageRoot -Recurse -Force
    }
    if (-not $success -and (Test-Path -LiteralPath $archivePath)) {
        Remove-Item -LiteralPath $archivePath -Force
    }
}

$archive = Get-Item -LiteralPath $archivePath
$sizeMB = [math]::Round($archive.Length / 1MB, 1)
Write-Host "Versatile mock Linux bundle created: $archivePath ($sizeMB MB)" -ForegroundColor Green
Write-Host "On Linux:"
Write-Host "  tar xzf $archiveName"
Write-Host "  cd mock"
Write-Host "  cp deploy/.env.example deploy/.env"
Write-Host "  vi deploy/.env"
Write-Host "  bash deploy/deploy.sh"

