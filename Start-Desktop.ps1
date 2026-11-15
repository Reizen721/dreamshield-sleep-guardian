param([string]$Python = (Join-Path $PSScriptRoot '.venv\Scripts\python.exe'))
$ErrorActionPreference = 'Stop'
$pythonPath = (Resolve-Path -LiteralPath $Python).Path
$exe = Join-Path $PSScriptRoot 'build\DreamShield.exe'
if (-not (Test-Path -LiteralPath $exe)) { & (Join-Path $PSScriptRoot 'Build-Desktop.ps1') }
$oldPython = $env:DREAMSHIELD_PYTHON
try {
    $env:DREAMSHIELD_PYTHON = $pythonPath
    Start-Process -FilePath $exe
} finally {
    $env:DREAMSHIELD_PYTHON = $oldPython
}
