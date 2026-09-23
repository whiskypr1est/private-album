# Helper: run a command on the Raspberry Pi over Tailscale SSH (password auth via Posh-SSH).
# Usage: pwsh -File pi-ssh.ps1 -Command "uname -a" [-Upload local -Remote /tmp/x] [-Download remote -To local]
param(
  [Parameter(Mandatory=$true)][string]$Command,
  [string]$Upload = "",
  [string]$UploadTo = "",
  [int]$TimeoutSec = 600
)
$ErrorActionPreference = "Stop"
Import-Module Posh-SSH -ErrorAction Stop

$PiHost = $env:PHOTOALBUM_PI_HOST
$PiUser = $env:PHOTOALBUM_PI_USER
# 口令不写死在仓库里（本脚本已被 pi-run.ps1 的密钥登录取代，仅作备用）
$PiPass = $env:PHOTOALBUM_SUDO_PASS
if (-not $PiHost) { throw "缺少树莓派地址：请设置环境变量 PHOTOALBUM_PI_HOST" }
if (-not $PiUser) { throw "缺少树莓派用户名：请设置环境变量 PHOTOALBUM_PI_USER" }
if (-not $PiPass) { throw "缺少 SSH 口令：请设置环境变量 PHOTOALBUM_SUDO_PASS" }
$KeyPub = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ".ssh\pi_ed25519.pub"

$cred = New-Object System.Management.Automation.PSCredential($PiUser, (ConvertTo-SecureString $PiPass -AsPlainText -Force))
$session = New-SSHSession -ComputerName $PiHost -Credential $cred -AcceptKey -ConnectionTimeout 30 -ErrorAction Stop

try {
  if ($Upload -ne "") {
    Set-SCPItem -ComputerName $PiHost -Credential $cred -AcceptKey -Path $Upload -Destination $UploadTo -Force | Out-Null
  }
  $result = Invoke-SSHCommand -SSHSession $session -Command $Command -TimeOut $TimeoutSec
  Write-Output $result.Output
  if ($result.Error) { Write-Error ($result.Error -join "`n") }
  exit $result.ExitStatus
} finally {
  Remove-SSHSession -SSHSession $session | Out-Null
}
