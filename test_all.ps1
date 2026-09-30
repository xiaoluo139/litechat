# LiteChat test runner: both sides, one command.
#
#   1. Windows pure logic   - Python unittest (URL building, model-output
#                             parsing, OCR line grouping, window geometry)
#   2. Android pure logic   - JVM unit tests (the same URL builder, the reply
#                             parser, and the conversation-session token)
#
# What this does NOT cover is anything that needs a live chat window or a real
# API key; those are checked by hand with tools/fake_chat_window.py and
# tools/mock_llm_server.py (see docs/verification/README.md).
#
# Usage: powershell -ExecutionPolicy Bypass -File test_all.ps1
#        (JAVA_HOME and ANDROID_HOME must be set for the Android half)
#
# ASCII-only on purpose: Windows PowerShell 5.1 misreads non-ASCII .ps1 files
# that lack a UTF-8 BOM.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$failed = @()

Write-Host '=== 1/2  Windows logic tests ==='
Push-Location $root
try {
    python -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { $failed += 'python unittest' }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '=== 2/2  Android unit tests ==='
if (-not $env:JAVA_HOME) {
    Write-Host 'SKIPPED: JAVA_HOME is not set'
} else {
    Push-Location (Join-Path $root 'android')
    try {
        cmd /c 'gradlew.bat --no-daemon testDebugUnitTest'
        if ($LASTEXITCODE -ne 0) { $failed += 'gradle testDebugUnitTest' }
    } finally {
        Pop-Location
    }
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host ("FAILED: " + ($failed -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All tests passed.' -ForegroundColor Green
