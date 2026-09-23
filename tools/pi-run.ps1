# ==========================================================================
#  在树莓派上执行一段脚本（自动处理中文 / 引号 / 多行）
#  用法： powershell -ExecutionPolicy Bypass -File pi-run.ps1 -Script "uname -a"
#         powershell -ExecutionPolicy Bypass -File pi-run.ps1 -File deploy.sh
#
#  连接参数（地址 / 用户名 / 私钥）默认从 photoalbum\deploy\local.env 读取。
#  那个文件已被 .gitignore 忽略，所以仓库里不含你的真实地址与口令。
# ==========================================================================
param(
  [string]$Script = "",
  [string]$File = "",
  [string]$PiHost = "",
  [string]$PiUser = "",
  [string]$Key = "",
  [int]$TimeoutSec = 900
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$localEnv = Join-Path $projectRoot "photoalbum\deploy\local.env"
if (Test-Path $localEnv) {
  foreach ($line in Get-Content $localEnv) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
      Set-Item -Path ("env:" + $matches[1]) -Value $matches[2].Trim()
    }
  }
}
if (-not $PiHost) { $PiHost = $env:PHOTOALBUM_PI_HOST }
if (-not $PiUser) { $PiUser = $env:PHOTOALBUM_PI_USER }
if (-not $Key)    { $Key    = $env:PHOTOALBUM_SSH_KEY }
if (-not $Key)    { $Key    = Join-Path $projectRoot ".ssh\pi_ed25519" }
if (-not $PiHost) { throw "缺少树莓派地址：请在 photoalbum\deploy\local.env 里设置 PHOTOALBUM_PI_HOST" }
if (-not $PiUser) { throw "缺少树莓派用户名：请在 photoalbum\deploy\local.env 里设置 PHOTOALBUM_PI_USER" }
if (-not (Test-Path $Key)) { throw "找不到 SSH 私钥：$Key（可设置 PHOTOALBUM_SSH_KEY 或 -Key）" }

if ($File -ne "") {
  $text = [System.IO.File]::ReadAllText($File)
} else {
  $text = $Script
}
if ([string]::IsNullOrWhiteSpace($text)) { throw "需要 -Script 或 -File" }

# 统一 LF，然后 base64 编码，避免引号/中文在 ssh 命令行里被 shell 或 PowerShell 破坏
$text = $text -replace "`r`n", "`n"
$bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($text)
$b64 = [Convert]::ToBase64String($bytes)

$remote = "echo $b64 | base64 -d > /tmp/pi-run.sh && bash /tmp/pi-run.sh"
& ssh -i $Key -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=20 `
      "$PiUser@$PiHost" $remote
exit $LASTEXITCODE
