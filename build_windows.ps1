# LiteChat desktop build script.
#
# Steps:
#   1. draw the app icon (if missing)
#   2. PyInstaller -> windows\dist\LiteChat\LiteChat.exe
#   3. NSIS        -> LiteChat-Setup-Windows-v1.0.exe, plus a portable zip
#
# Requires: Python 3.8+ with pyinstaller, NSIS 3 (makensis.exe).
# Usage:    powershell -ExecutionPolicy Bypass -File build_windows.ps1
#
# NOTE: this file is deliberately ASCII-only. Windows PowerShell 5.1 reads a
# .ps1 as ANSI unless it starts with a UTF-8 BOM, so non-ASCII text here (even
# inside comments) corrupts parsing. Localised strings live in LiteChat.nsi,
# which this script re-encodes to UTF-8 with BOM before makensis sees it.

$ErrorActionPreference = 'Stop'

# Single place the release version is written down; the package names follow it.
$version   = '1.25'

$root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$winDir    = Join-Path $root 'windows'
$installer = Join-Path $winDir 'installer'
$distDir   = Join-Path $root 'dist'
$appDist   = Join-Path $winDir 'dist\LiteChat'

Write-Host '[1/4] Generating the icon...'
python (Join-Path $root 'tools\make_icon.py') (Join-Path $winDir 'assets')
if ($LASTEXITCODE -ne 0) { throw 'icon generation failed' }

Write-Host '[2/4] Running PyInstaller...'
Push-Location $winDir
try {
    python -m PyInstaller --noconfirm --clean --windowed --name LiteChat `
        --icon 'assets\litechat.ico' `
        --add-data 'ocr_helper.ps1;.' `
        --add-data 'skills;skills' `
        litechat_win.py
    if ($LASTEXITCODE -ne 0) { throw 'PyInstaller failed' }
} finally {
    Pop-Location
}
if (-not (Test-Path (Join-Path $appDist 'LiteChat.exe'))) {
    throw "missing $appDist\LiteChat.exe"
}

Write-Host '[3/4] Re-encoding the NSIS script as UTF-8 with BOM...'
$src = Join-Path $installer 'LiteChat.nsi'
$tmp = Join-Path $installer '_LiteChat.generated.nsi'
$text = [System.IO.File]::ReadAllText($src, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($tmp, $text, [System.Text.UTF8Encoding]::new($true))

Write-Host '[4/4] Building the installer with NSIS...'
$makensis = @(
    'C:\Program Files (x86)\NSIS\makensis.exe',
    'C:\Program Files\NSIS\makensis.exe'
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $makensis) { throw 'makensis.exe not found - install NSIS 3 first' }

Push-Location $installer
try {
    & $makensis '/V2' $tmp
    if ($LASTEXITCODE -ne 0) { throw 'makensis failed' }
} finally {
    Pop-Location
}
Remove-Item $tmp -ErrorAction SilentlyContinue

$setup = Join-Path $installer ("LiteChat-Setup-Windows-v{0}.exe" -f $version)
if (-not (Test-Path $setup)) { throw "missing $setup" }

New-Item -ItemType Directory -Force -Path $distDir | Out-Null
Copy-Item $setup (Join-Path $distDir ("LiteChat-Setup-Windows-v{0}.exe" -f $version)) -Force

# Portable build: unzip and run, no installer.
$portable = Join-Path $distDir ("LiteChat-Windows-portable-v{0}.zip" -f $version)
if (Test-Path $portable) { Remove-Item $portable -Force }
Compress-Archive -Path (Join-Path $appDist '*') -DestinationPath $portable -Force

Write-Host ''
Write-Host 'Done:'
Get-ChildItem $distDir | Where-Object { $_.Name -like 'LiteChat-*' } | ForEach-Object {
    $mb = [math]::Round($_.Length / 1MB, 1)
    Write-Host ("  {0}  ({1} MB)" -f $_.Name, $mb)
}
