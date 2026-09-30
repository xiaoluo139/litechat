# Point the Android debug build's config at the local mock, and set one extra
# debug-only key. Development helper: it overwrites the app's SharedPreferences
# file, so it is never run automatically and never touches a release build
# (a release APK is not debuggable, so run-as cannot write to it).
#
# ASCII-only on purpose: Windows PowerShell 5.1 misreads non-ASCII .ps1 files
# that lack a UTF-8 BOM.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\set_android_prefs.ps1 `
#       -Adb <path to adb.exe> -Set "base_url=https://api.example.com/v1;model=x"
#
# -Set is ONE string of name=value pairs separated by ';' on purpose: passing an
# array through `powershell -File` collapses it into a single argument.
#
param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [string]$Package = 'com.litechat.app',
    [string]$Set = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$base = Join-Path $root 'tools\android_test_prefs.xml'
$tmp = Join-Path $env:TEMP 'litechat_android_prefs.xml'
$remote = '/data/local/tmp/litechat_assistant.xml'

$text = [System.IO.File]::ReadAllText($base, [System.Text.UTF8Encoding]::new($false))

$pairs = @()
if ($Set.Trim()) { $pairs = $Set.Split(';') | Where-Object { $_ -match '=' } }

foreach ($pair in $pairs) {
    $name, $value = $pair.Split('=', 2)
    # SharedPreferences is typed: a boolean stored as a <string> makes the app
    # throw ClassCastException the moment it reads that key. Write what the app
    # itself would write for this value.
    if ($value -eq 'true' -or $value -eq 'false') {
        $line = '    <boolean name="' + $name + '" value="' + $value + '" />'
    } elseif ($value -match '^-?\d+$') {
        $line = '    <int name="' + $name + '" value="' + $value + '" />'
    } else {
        $line = '    <string name="' + $name + '">' + $value + '</string>'
    }
    # Both shapes a <map> entry can take: the self-closing one
    # (<boolean name="x" value="true" />) and the element one
    # (<string name="x">value</string>).
    $esc = [regex]::Escape($name)
    $pattern = '\s*<(?:string|boolean|int) name="' + $esc +
        '"(?: value="[^"]*")?\s*/>' +
        '|\s*<(?:string|boolean|int) name="' + $esc + '"[^>]*>[^<]*</[a-z]+>'
    if ($text -match [regex]::Escape('name="' + $name + '"')) {
        $text = [regex]::Replace($text, $pattern, "`n" + $line, 1)
    } else {
        $text = $text.Replace('</map>', $line + "`n</map>")
    }
}

[System.IO.File]::WriteAllText($tmp, $text, [System.Text.UTF8Encoding]::new($false))

& $Adb shell am force-stop $Package | Out-Null
& $Adb push $tmp $remote | Out-Null
& $Adb shell "run-as $Package mkdir -p shared_prefs"
& $Adb shell "run-as $Package cp $remote shared_prefs/litechat_assistant.xml"

# Names only: a value may be an API key, and this line goes to the console.
Write-Host ('wrote prefs for ' + $Package +
    $(if ($pairs.Count) { ' (' + (($pairs | ForEach-Object { $_.Split('=', 2)[0] }) -join ', ') + ')' } else { '' }))
