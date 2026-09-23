# ==========================================================================
#  fix-ps1-bom.ps1
#
#  WHY THIS EXISTS
#  Windows PowerShell 5.1 reads a .ps1 file that has NO UTF-8 BOM as ANSI
#  (GBK on a Chinese Windows). Every Chinese character inside then becomes
#  mojibake and the parser explodes with misleading errors such as
#  "Unexpected token '}'" or "An empty pipe element is not allowed".
#
#  Many editors and AI agents rewrite files as "UTF-8 without BOM", which
#  silently breaks every Chinese-commented script in this project. If a
#  script that used to work suddenly fails to PARSE, this is almost always
#  the reason. Run this tool to repair it.
#
#  This file is deliberately ASCII-only, so it still runs correctly even
#  when its own encoding has been damaged.
#
#  USAGE
#     powershell -ExecutionPolicy Bypass -File fix-ps1-bom.ps1
#     powershell -ExecutionPolicy Bypass -File fix-ps1-bom.ps1 -Check
#     powershell -ExecutionPolicy Bypass -File fix-ps1-bom.ps1 -Root "D:\other"
#
#  -Check only reports; it changes nothing and exits 1 if any file is broken.
# ==========================================================================
param(
  [string[]]$Root,
  [switch]$Check
)

$ErrorActionPreference = "Continue"

if (-not $Root -or $Root.Count -eq 0) {
  # tools\ lives directly under the project root, so the parent of this
  # script's folder is the project root. Avoids hardcoding a Chinese path.
  $Root = @((Split-Path -Parent $PSScriptRoot))
}

# When invoked via "powershell -File x.ps1 -Root a,b" the whole "a,b" arrives
# as ONE literal string instead of an array. Split it so both forms work.
$expanded = @()
foreach ($r in $Root) {
  foreach ($piece in ([string]$r -split ',')) {
    $t = $piece.Trim().Trim('"')
    if ($t) { $expanded += $t }
  }
}
$Root = $expanded

$bomlessUtf8 = New-Object System.Text.UTF8Encoding($false)
$bomUtf8     = New-Object System.Text.UTF8Encoding($true)

$fixed = 0
$ok = 0
$broken = @()

foreach ($dir in $Root) {
  if (-not (Test-Path $dir)) {
    Write-Host ("[skip] not found: " + $dir) -ForegroundColor DarkGray
    continue
  }
  $files = Get-ChildItem -Path $dir -Filter *.ps1 -Recurse -File -ErrorAction SilentlyContinue
  foreach ($f in $files) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    if ($hasBom) { $ok++; continue }

    # Only touch files that actually contain multi-byte characters; a pure
    # ASCII script is safe either way and adding a BOM is still harmless,
    # but reporting it separately keeps the output focused.
    $text = [System.IO.File]::ReadAllText($f.FullName, $bomlessUtf8)
    $nonAscii = ($text -match '[^\x00-\x7F]')

    if ($Check) {
      $rel = $f.FullName.Substring($dir.Length).TrimStart('\')
      $broken += $rel
      $tag = if ($nonAscii) { "BROKEN (has non-ASCII)" } else { "no BOM (ascii only)" }
      Write-Host ("[FAIL] " + $tag + "  " + $rel) -ForegroundColor Red
      continue
    }

    [System.IO.File]::WriteAllText($f.FullName, $text, $bomUtf8)
    $rel = $f.FullName.Substring($dir.Length).TrimStart('\')
    if ($nonAscii) {
      Write-Host ("[fixed] " + $rel) -ForegroundColor Yellow
    } else {
      Write-Host ("[fixed] (ascii only) " + $rel) -ForegroundColor DarkGray
    }
    $fixed++
  }
}

Write-Host ""
if ($Check) {
  Write-Host ("BOM check: " + $ok + " ok, " + $broken.Count + " need repair") -ForegroundColor Yellow
  if ($broken.Count -eq 0) { exit 0 } else { exit 1 }
}
Write-Host ("BOM repaired: " + $fixed + " file(s) fixed, " + $ok + " already ok") -ForegroundColor Green
exit 0
