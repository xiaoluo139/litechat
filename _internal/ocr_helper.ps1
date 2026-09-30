# LiteChat screen text reader.
#
# Two modes:
#   -Image <png>            OCR one file, print a JSON array of lines.
#   -Server                 long-running helper; one JSON command per line on
#                           stdin, one JSON reply per line on stdout.
#
# Server commands:
#   {"id":1,"cmd":"ping"}
#   {"id":2,"cmd":"shot","x":0,"y":0,"w":900,"h":600}   screen region -> OCR
#   {"id":3,"cmd":"ocr","path":"C:\\tmp\\a.png"}          existing image -> OCR
#   {"cmd":"quit"}
#
# Reply: {"id":n,"ok":true,"lines":[{"t":"text","x":..,"y":..,"w":..,"h":..}]}
#
# This file is deliberately pure ASCII: Windows PowerShell 5.1 reads .ps1 files
# as ANSI unless they carry a BOM, so any literal Chinese here would be mangled.
# The CJK regex classes below use .NET's \uXXXX escapes instead.

param(
    [switch]$Server,
    [string]$Image
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class LiteChatDpi {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
"@

[LiteChatDpi]::SetProcessDPIAware() | Out-Null

[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.SoftwareBitmap, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.FileAccessMode, Windows.Storage, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.Streams.DataWriter, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and
    $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

# One engine for the whole session; creating it loads the OCR language model.
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) {
    throw 'No OCR engine for the current user profile. Install a Windows language pack with OCR support.'
}

# CJK line text comes back with a space between every "word"; drop the ones that
# only sit between two CJK characters so "对 方 ： 你 好" reads "对方：你好".
$cjk = '[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]'
$reCjkGap = [regex]::new("(?<=$cjk)\s+(?=$cjk)")

function Convert-Lines($result) {
    $out = New-Object System.Collections.ArrayList
    foreach ($line in $result.Lines) {
        $words = @($line.Words)
        if ($words.Count -eq 0) { continue }
        $x1 = [double]::MaxValue; $y1 = [double]::MaxValue
        $x2 = 0.0; $y2 = 0.0
        foreach ($w in $words) {
            $r = $w.BoundingRect
            if ($r.X -lt $x1) { $x1 = $r.X }
            if ($r.Y -lt $y1) { $y1 = $r.Y }
            if (($r.X + $r.Width) -gt $x2) { $x2 = $r.X + $r.Width }
            if (($r.Y + $r.Height) -gt $y2) { $y2 = $r.Y + $r.Height }
        }
        $t = $reCjkGap.Replace($line.Text, '')
        [void]$out.Add([pscustomobject]@{
            t = $t
            x = [int]$x1
            y = [int]$y1
            w = [int]($x2 - $x1)
            h = [int]($y2 - $y1)
        })
    }
    return $out
}

function Read-Image([string]$path) {
    # Read the bytes with plain .NET first. Going through StorageFile would keep
    # a WinRT handle on the file alive for the life of this long-running helper,
    # and the next capture could then not overwrite it (which surfaced as a
    # baffling "invalid argument" from the caller's open()).
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $stream = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream
    $writer = New-Object Windows.Storage.Streams.DataWriter($stream)
    try {
        $writer.WriteBytes($bytes)
        Await ($writer.StoreAsync()) ([uint32]) | Out-Null
        $writer.DetachStream() | Out-Null
        $stream.Seek(0)
        $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
        return Convert-Lines $result
    } finally {
        try { $stream.Dispose() } catch { }
    }
}

function Save-Shot([int]$x, [int]$y, [int]$w, [int]$h, [string]$path) {
    if ($w -lt 8 -or $h -lt 8) { throw 'Region too small to read.' }
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.CopyFromScreen($x, $y, 0, 0, (New-Object System.Drawing.Size($w, $h)))
    } finally {
        $g.Dispose()
    }
    try {
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bmp.Dispose()
    }
}

# OCR is markedly better on larger text, and desktop chat clients draw their
# message text quite small. Upscaling the crop before recognition costs a few
# tens of milliseconds and recovers characters that would otherwise be dropped.
function Scale-Image([string]$src, [string]$dst, [double]$factor, [int]$pad) {
    $img = [System.Drawing.Image]::FromFile($src)
    try {
        $w = [int][Math]::Round($img.Width * $factor) + 2 * $pad
        $h = [int][Math]::Round($img.Height * $factor) + 2 * $pad
        $bmp = New-Object System.Drawing.Bitmap($w, $h)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        try {
            $g.Clear([System.Drawing.Color]::White)
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $g.DrawImage($img, $pad, $pad, $w - 2 * $pad, $h - 2 * $pad)
        } finally {
            $g.Dispose()
        }
        # Flatten to greyscale. Chat clients draw text with ClearType subpixel
        # antialiasing, which puts coloured fringes on the glyph edges; the
        # recognizer is more reliable on the flattened version.
        $grey = New-Object System.Drawing.Bitmap($w, $h)
        $g2 = [System.Drawing.Graphics]::FromImage($grey)
        try {
            $g2.Clear([System.Drawing.Color]::White)
            $cm = New-Object System.Drawing.Imaging.ColorMatrix
            $cm.Matrix00 = 0.299; $cm.Matrix01 = 0.299; $cm.Matrix02 = 0.299
            $cm.Matrix10 = 0.587; $cm.Matrix11 = 0.587; $cm.Matrix12 = 0.587
            $cm.Matrix20 = 0.114; $cm.Matrix21 = 0.114; $cm.Matrix22 = 0.114
            $cm.Matrix33 = 1.0;   $cm.Matrix44 = 1.0
            $ia = New-Object System.Drawing.Imaging.ImageAttributes
            $ia.SetColorMatrix($cm)
            $rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
            $g2.DrawImage($bmp, $rect, 0, 0, $w, $h,
                [System.Drawing.GraphicsUnit]::Pixel, $ia)
        } finally {
            $g2.Dispose()
        }
        try {
            $grey.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $grey.Dispose()
            $bmp.Dispose()
        }
    } finally {
        $img.Dispose()
    }
}

# OCR one file, optionally magnified; boxes come back in ORIGINAL image pixels.
function Ocr-File([string]$path, [double]$scale) {
    if ($scale -lt 1.0) { $scale = 1.0 }
    # A white margin around the crop matters more than it sounds: text that
    # touches the edge of the image is routinely dropped by the recognizer, and
    # a conversation header is exactly a short line hugging the left edge.
    $pad = 12
    $scaled = Join-Path $env:TEMP 'litechat_ocr_scaled.png'
    Scale-Image $path $scaled $scale $pad
    $lines = @(Read-Image $scaled)
    $out = New-Object System.Collections.ArrayList
    foreach ($ln in $lines) {
        [void]$out.Add([pscustomobject]@{
            t = $ln.t
            x = [int][Math]::Max(0, [Math]::Round(($ln.x - $pad) / $scale))
            y = [int][Math]::Max(0, [Math]::Round(($ln.y - $pad) / $scale))
            w = [int][Math]::Round($ln.w / $scale)
            h = [int][Math]::Round($ln.h / $scale)
        })
    }
    return $out
}

function Write-Json($obj) {
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 6))
    [Console]::Out.Flush()
}

if (-not $Server) {
    if (-not $Image) { throw 'Pass -Image <png> or -Server.' }
    # Read-Image already returns line objects: convert once, here.
    Write-Output ((Read-Image $Image) | ConvertTo-Json -Compress -Depth 6)
    exit 0
}

try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$shotPath = Join-Path $env:TEMP 'litechat_shot.png'

while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    $line = $line.Trim()
    if ($line -eq '') { continue }
    $reply = $null
    try {
        $cmd = $line | ConvertFrom-Json
        $id = $cmd.id
        switch ($cmd.cmd) {
            'quit' { return }
            'ping' { $reply = [pscustomobject]@{ id = $id; ok = $true; lines = @() } }
            'shot' {
                Save-Shot ([int]$cmd.x) ([int]$cmd.y) ([int]$cmd.w) ([int]$cmd.h) $shotPath
                $sc = 1.0
                if ($cmd.scale) { $sc = [double]$cmd.scale }
                $reply = [pscustomobject]@{
                    id = $id; ok = $true; lines = @(Ocr-File $shotPath $sc)
                }
            }
            'ocr' {
                $sc = 1.0
                if ($cmd.scale) { $sc = [double]$cmd.scale }
                $reply = [pscustomobject]@{
                    id = $id; ok = $true; lines = @(Ocr-File ([string]$cmd.path) $sc)
                }
            }
            default {
                $reply = [pscustomobject]@{ id = $id; ok = $false; error = "unknown cmd: $($cmd.cmd)" }
            }
        }
    } catch {
        $reply = [pscustomobject]@{ id = $id; ok = $false; error = "$($_.Exception.Message)" }
    }
    Write-Json $reply
}
