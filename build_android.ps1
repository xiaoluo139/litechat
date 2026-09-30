# LiteChat Android build script.
#
# Produces a signed release APK at
#   android\app\build\outputs\apk\release\app-release.apk
# and copies it to dist\LiteChat-Android-v<version>.apk (see $version below).
#
# Requires: JDK 17, Android SDK (platform-35, build-tools 35.0.0).
# Signing:  reads signing\litechat-release.properties by default; override the
#           path with the LITECHAT_KEYSTORE_PROPS environment variable.
#
# Usage: powershell -ExecutionPolicy Bypass -File build_android.ps1
#        (JAVA_HOME and ANDROID_HOME must be set first)
#
# ASCII-only on purpose: Windows PowerShell 5.1 misreads non-ASCII .ps1 files
# that lack a UTF-8 BOM.

$ErrorActionPreference = 'Stop'

# Single place the release version is written down; the APK name follows it.
$version = '1.25'

$root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$android = Join-Path $root 'android'
$distDir = Join-Path $root 'dist'

if (-not $env:JAVA_HOME) { throw 'set JAVA_HOME to a JDK 17 first' }

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'set ANDROID_HOME or ANDROID_SDK_ROOT first' }

$localProps = Join-Path $android 'local.properties'
if (-not (Test-Path $localProps)) {
    $escaped = $sdk -replace '\\', '\\' -replace ':', '\:'
    [System.IO.File]::WriteAllText($localProps, "sdk.dir=$escaped`n",
        [System.Text.UTF8Encoding]::new($false))
}

Push-Location $android
try {
    cmd /c 'gradlew.bat --no-daemon assembleRelease'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
} finally {
    Pop-Location
}

$apk = Join-Path $android 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) {
    throw "missing $apk (a signed release needs signing\litechat-release.properties)"
}

New-Item -ItemType Directory -Force -Path $distDir | Out-Null
Copy-Item $apk (Join-Path $distDir ("LiteChat-Android-v{0}.apk" -f $version)) -Force

Write-Host ''
Write-Host 'Done:'
Get-ChildItem $distDir | Where-Object { $_.Name -like '*Android*' } | ForEach-Object {
    $mb = [math]::Round($_.Length / 1MB, 1)
    Write-Host ("  {0}  ({1} MB)" -f $_.Name, $mb)
}
