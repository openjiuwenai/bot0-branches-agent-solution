$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "   Preset Importer venv Init" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

$venvDir = Join-Path $root ".venv"
$pythonExe = Join-Path $venvDir "Scripts\python.exe"

if (-not (Test-Path $pythonExe)) {
    Write-Host "[1/3] Creating venv..." -NoNewline
    & python -m venv $venvDir
    if ($?) { Write-Host " [OK]" -ForegroundColor Green }
    else { Write-Host " [FAIL]" -ForegroundColor Red; exit 1 }
} else {
    Write-Host "[1/3] venv exists, skip" -ForegroundColor Yellow
}

Write-Host "[2/3] Installing requirements..." -NoNewline
$env:PIP_INDEX_URL = "https://mirrors.huaweicloud.com/repository/pypi/simple"
$env:PIP_TRUSTED_HOST = "mirrors.huaweicloud.com"
& $pythonExe -m pip install --upgrade pip -q 2>$null
& $pythonExe -m pip install -r (Join-Path $root "requirements.txt") -q 2>&1 | Out-Null
if ($?) { Write-Host " [OK]" -ForegroundColor Green }
else { Write-Host " [FAIL]" -ForegroundColor Red; exit 1 }

Write-Host "[3/3] Verifying..."
$ok = $true
foreach ($pkg in @("requests","pymysql","boto3")) {
    $r = & $pythonExe -c "import $pkg; print('$pkg', $pkg.__version__)" 2>&1
    if ($LASTEXITCODE -ne 0) { $ok = $false; Write-Host "  [MISSING] $pkg" -ForegroundColor Red }
    else { Write-Host "  $r" -ForegroundColor DarkGray }
}
if ($ok) {
    Write-Host "  [OK] All deps ready" -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Some deps missing" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  venv: $pythonExe" -ForegroundColor Cyan
Write-Host "  Run: $pythonExe import-presets.py" -ForegroundColor White
Write-Host "  Run: $pythonExe setup-model-auth.py --api-key sk-xxx --api-url https://..." -ForegroundColor White
Write-Host "============================================" -ForegroundColor Cyan
