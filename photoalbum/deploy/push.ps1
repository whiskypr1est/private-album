# ==========================================================================
#  本机 -> 树莓派：上传服务端代码并部署
#  用法： powershell -ExecutionPolicy Bypass -File F:\树莓派开发\photoalbum\deploy\push.ps1
#  可选： -NoDeploy 只上传不部署
# ==========================================================================
param(
  [switch]$NoDeploy,
  [string]$PiHost = "",
  [string]$PiUser = "",
  [string]$Key    = ""
)

$ErrorActionPreference = "Stop"

# 本机私有配置（deploy/local.env，已被 .gitignore 忽略）：sudo 密码、相册口令等。
# 真实口令只存在这个本机文件里，仓库中永远不出现明文。
$localEnv = Join-Path $PSScriptRoot "local.env"
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
if (-not $PiHost) { throw "缺少树莓派地址：请在 deploy\local.env 里设置 PHOTOALBUM_PI_HOST" }
if (-not $PiUser) { throw "缺少树莓派用户名：请在 deploy\local.env 里设置 PHOTOALBUM_PI_USER" }
if (-not $Key)    { $Key = Join-Path (Split-Path -Parent $PSScriptRoot) "..\.ssh\pi_ed25519" }

# 本机路径由脚本位置推导，避免把个人目录写进公开仓库
$localRoot  = Split-Path -Parent $PSScriptRoot
$remoteRoot = "/home/$PiUser/photoalbum"

Write-Host "==> 打包服务端代码（保证 LF 行尾）" -ForegroundColor Green
$stage = Join-Path $env:TEMP "photoalbum-upload"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# 只上传源码与部署脚本，data/ 与 venv/ 永远不上传
$items = @(
  @{ Src = "$localRoot\server"; Dst = "server" },
  @{ Src = "$localRoot\deploy"; Dst = "deploy" }
)
foreach ($item in $items) {
  Get-ChildItem $item.Src -Recurse -File | Where-Object {
    $_.FullName -notmatch "__pycache__" -and $_.Extension -ne ".pyc"
  } | ForEach-Object {
    $rel = $_.FullName.Substring($item.Src.Length).TrimStart('\')
    $target = Join-Path (Join-Path $stage $item.Dst) $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    $text = [System.IO.File]::ReadAllText($_.FullName)
    $text = $text -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($target, $text, [System.Text.UTF8Encoding]::new($false))
  }
}
Write-Host ("    待上传文件: " + (Get-ChildItem $stage -Recurse -File).Count)

Write-Host "==> 上传到 $PiUser@$PiHost`:$remoteRoot" -ForegroundColor Green
# 注意：必须先用 tar 打成文件、再用 scp 传；
# 不能走 PowerShell 管道（管道会把二进制当文本处理，tar 包会被破坏）。
$tarExe = Get-Command tar.exe -ErrorAction SilentlyContinue
$archive = Join-Path $env:TEMP "photoalbum-src.tar"
if (Test-Path $archive) { Remove-Item $archive -Force }

if ($tarExe) {
  Push-Location $stage
  try {
    & tar.exe -cf $archive .
    if ($LASTEXITCODE -ne 0) { throw "本地打包失败（exit $LASTEXITCODE）" }
  } finally { Pop-Location }
  & scp -i $Key -o StrictHostKeyChecking=no $archive "${PiUser}@${PiHost}:/tmp/photoalbum-src.tar"
  if ($LASTEXITCODE -ne 0) { throw "scp 上传失败（exit $LASTEXITCODE）" }
  & ssh -i $Key -o StrictHostKeyChecking=no "$PiUser@$PiHost" "mkdir -p $remoteRoot; tar -xf /tmp/photoalbum-src.tar -C $remoteRoot; rm -f /tmp/photoalbum-src.tar; find $remoteRoot -name '*.sh' -exec chmod +x {} +; ls -la $remoteRoot $remoteRoot/server $remoteRoot/deploy"
  if ($LASTEXITCODE -ne 0) { throw "远端解包失败（exit $LASTEXITCODE）" }
} else {
  & scp -i $Key -o StrictHostKeyChecking=no -r "$stage\*" "$PiUser@${PiHost}:$remoteRoot/"
  if ($LASTEXITCODE -ne 0) { throw "scp 失败（exit $LASTEXITCODE）" }
}

if (-not $NoDeploy) {
  Write-Host "==> 远端执行 install.sh" -ForegroundColor Green
  # 通过 ssh 远程跑时没有终端，sudo 弹不出密码提示，
  # 所以把密码用环境变量传进去，install.sh 内部会用 sudo -S 喂给 sudo。
  $sudoPass = $env:PHOTOALBUM_SUDO_PASS
  if (-not $sudoPass) { throw "缺少 sudo 口令：请在 deploy\local.env 里设置 PHOTOALBUM_SUDO_PASS（该文件不会被提交）" }
  & ssh -i $Key -o StrictHostKeyChecking=no "$PiUser@$PiHost" "export PHOTOALBUM_SUDO_PASS='$sudoPass'; chmod +x $remoteRoot/deploy/*.sh; bash $remoteRoot/deploy/install.sh"
  if ($LASTEXITCODE -ne 0) { throw "远端部署失败（exit $LASTEXITCODE）" }
}
Write-Host "==> 完成" -ForegroundColor Green
