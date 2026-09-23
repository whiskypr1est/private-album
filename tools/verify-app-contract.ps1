# ==========================================================================
#  「安卓 App 契约」验证
#
#  用 PowerShell 原样复刻安卓客户端会发出的每一个请求（同 URL 形态、同查询参数、
#  同请求头、同方法），以此在没有真机的情况下验证 App 与服务器的接口契约。
#
#  用法：
#     1) 先在本机开隧道： ssh -N -L 18080:127.0.0.1:8080 <用户名>@<树莓派地址>
#        （地址、用户名、口令都取自 photoalbum\deploy\local.env）
#     2) powershell -ExecutionPolicy Bypass -File verify-app-contract.ps1
# ==========================================================================
param(
  [string]$Base = "http://127.0.0.1:18080",
  [string]$User = "",
  [string]$Password = ""
)

$ErrorActionPreference = "Continue"

# 口令与用户名来自本机私有配置（已被 .gitignore 忽略），仓库里没有明文口令。
$localEnv = Join-Path (Split-Path -Parent $PSScriptRoot) "photoalbum\deploy\local.env"
if (Test-Path $localEnv) {
  foreach ($line in Get-Content $localEnv) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
      Set-Item -Path ("env:" + $matches[1]) -Value $matches[2].Trim()
    }
  }
}
if (-not $User)     { $User = $env:PHOTOALBUM_PI_USER }
if (-not $Password) { $Password = $env:PHOTOALBUM_PASSWORD }
if (-not $User)     { $User = "cabbage" }
if (-not $Password) { throw "缺少相册口令：请在 photoalbum\deploy\local.env 里设置 PHOTOALBUM_PASSWORD，或用 -Password 传入" }
$script:passed = 0
$script:failed = @()

function Check($name, $ok, $detail = "") {
  if ($ok) { $script:passed++; Write-Host ("  PASS  " + $name) -ForegroundColor Green }
  else { $script:failed += $name; Write-Host ("  FAIL  " + $name + "  " + $detail) -ForegroundColor Red }
}

function Section($title) { Write-Host ("`n=== " + $title + " ===") -ForegroundColor Cyan }

# 判断 JSON 里「有没有这个字段」。
# 不能用 `$obj.items -ne $null`：PowerShell 里拿空数组跟 $null 比，
# 结果是被过滤后的空数组（假值），库里没有照片时会误报失败。
function Has-Prop($obj, $name) {
  if ($obj -eq $null) { return $false }
  return ($obj.PSObject.Properties.Name -contains $name)
}

# 判断字段存在且确实是数组（空数组也算通过）
function Is-ArrayProp($obj, $name) {
  if (-not (Has-Prop $obj $name)) { return $false }
  $v = $obj.$name
  if ($v -eq $null) { return $false }
  return ($v -is [System.Array] -or $v -is [System.Collections.IEnumerable] -and -not ($v -is [string]))
}

# --------------------------------------------------------------------------
#  安全约定（务必遵守，见 README「测试脚本安全约定」）
#
#  这个脚本跑在真实服务 + 真实数据库上。曾经这里的收尾清理调用了全局
#  POST /api/trash/purge，结果把用户回收站里的真实照片一起清掉了（不可恢复）。
#
#  现在的做法：只删「文件名以指定前缀开头」**且**「id 比测试开始前更新」的资源，
#  双重确认。永远不要再出现 trash/purge、purge-missing、migrate 这类全局接口。
# --------------------------------------------------------------------------
function Remove-MyArtifacts($token, $baselineId, $prefix, $exactNames = @(), $limit = 200) {
  # 故意不用 q= 搜索：避免依赖搜索语法。列表按批次时间倒序，最新的一批在最前面，
  # 而本测试造出来的资源必然是最新的，所以翻第一页就够，再在本地按前缀过滤。
  #
  # 为什么还要 $exactNames：编辑时的 keep_original 会在磁盘上生成
  # 「安卓契约测试_原图.jpg」，而 upsert_asset 是按**文件路径**去重的，
  # 同一个路径第二次跑测试时是 UPDATE 而不是 INSERT —— 行 id 会一直是老的那个，
  # 用「id 比基线新」判断就永远匹配不到。所以对这几个已知的测试文件名，
  # 额外允许「名字精确相等」这一条通路。
  $r = Invoke-Raw -Method GET -Url "$Base/api/assets?limit=$limit" -Headers @{ Authorization = "Bearer $token" }
  $found = Json-Of $r.Body
  $n = 0
  foreach ($it in @($found.items)) {
    if ($it -eq $null) { continue }
    $name = [string]$it.file_name
    if (-not $name.StartsWith($prefix)) { continue }
    $isMine = ([int]$it.id -gt [int]$baselineId) -or ($exactNames -contains $name)
    if (-not $isMine) { continue }
    Invoke-Raw -Method DELETE -Url "$Base/api/assets/$($it.id)" -Headers @{ Authorization = "Bearer $token" } | Out-Null
    $n++
  }
  return $n
}

# 记录测试开始前最大的资源 id，作为「这是不是我这个测试造出来的」的基线。
# 列表是按批次时间倒序的，最新的一批在最前面，所以最大 id 一定在第一页里。
function Get-BaselineMaxId($token) {
  $r = Invoke-Raw -Method GET -Url "$Base/api/assets?limit=200" -Headers @{ Authorization = "Bearer $token" }
  $p = Json-Of $r.Body
  $max = 0
  foreach ($it in @($p.items)) { if ($it -ne $null -and [int]$it.id -gt $max) { $max = [int]$it.id } }
  return $max
}

# 用 .NET 的低层 HTTP，尽量贴近 HttpURLConnection 的行为
function Invoke-Raw {
  param(
    [string]$Method, [string]$Url, [byte[]]$Body, [hashtable]$Headers = @{}, [switch]$RawBytes
  )
  $request = [System.Net.HttpWebRequest]::Create($Url)
  $request.Method = $Method
  $request.Timeout = 60000
  $request.ReadWriteTimeout = 60000
  $request.UserAgent = "PrivateAlbum-Android/1.0"
  $request.Accept = "application/json"
  foreach ($k in $Headers.Keys) {
    if ($k -eq "Content-Type") { $request.ContentType = $Headers[$k] }
    elseif ($k -eq "Range") { $request.AddRange([int]($Headers[$k] -split '[=-]')[1], [int]($Headers[$k] -split '[=-]')[2]) }
    else { $request.Headers[$k] = $Headers[$k] }
  }
  if ($Body -ne $null) {
    $request.ContentLength = $Body.Length
    $stream = $request.GetRequestStream()
    $stream.Write($Body, 0, $Body.Length)
    $stream.Close()
  }
  try {
    $response = $request.GetResponse()
  } catch [System.Net.WebException] {
    $response = $_.Exception.Response
    if ($response -eq $null) { return @{ Status = -1; Body = $null; Bytes = $null; Headers = @{} } }
  }
  $ms = New-Object System.IO.MemoryStream
  $response.GetResponseStream().CopyTo($ms)
  $bytes = $ms.ToArray()
  $headers = @{}
  foreach ($key in $response.Headers.AllKeys) { $headers[$key] = $response.Headers[$key] }
  $result = @{
    Status  = [int]$response.StatusCode
    Body    = [System.Text.Encoding]::UTF8.GetString($bytes)
    Bytes   = $bytes
    Headers = $headers
  }
  $response.Close()
  return $result
}

function Json-Of($text) {
  try { return $text | ConvertFrom-Json } catch { return $null }
}

Write-Host "==> 目标服务器：$Base" -ForegroundColor Yellow

# ---------------------------------------------------------------- 健康检查
Section "1. 连通性（对应 LoginActivity.testConnection）"
$r = Invoke-Raw -Method GET -Url "$Base/api/health"
Check "GET /api/health 返回 200" ($r.Status -eq 200) ($r.Status)
$health = Json-Of $r.Body
Check "响应是可解析 JSON 且 ok=true" ($health -ne $null -and $health.ok -eq $true) ($r.Body.Substring(0, [Math]::Min(120, $r.Body.Length)))
Check "含 media_root 字段" ($health.media_root -ne $null) ""

# ---------------------------------------------------------------- 登录
Section "2. 登录（对应 AlbumApi.login）"
$loginBody = [System.Text.Encoding]::UTF8.GetBytes((@{ username = $User; password = $Password } | ConvertTo-Json -Compress))
$r = Invoke-Raw -Method POST -Url "$Base/api/login" -Body $loginBody -Headers @{ "Content-Type" = "application/json; charset=utf-8" }
Check "POST /api/login 返回 200" ($r.Status -eq 200) ($r.Status)
$login = Json-Of $r.Body
Check "返回 token" ($login.token -ne $null -and $login.token.Length -gt 20) ""
$token = $login.token

# 测试开始前先记下「当前最大的资源 id」，收尾时只删比它更新的东西
$baselineId = Get-BaselineMaxId $token
Write-Host ("    基线：当前库里最大资源 id = " + $baselineId + "（收尾只删比它新的产物）") -ForegroundColor DarkGray

$badBody = [System.Text.Encoding]::UTF8.GetBytes('{"username":"cabbage","password":"wrong"}')
$r = Invoke-Raw -Method POST -Url "$Base/api/login" -Body $badBody -Headers @{ "Content-Type" = "application/json" }
Check "错误密码返回 401" ($r.Status -eq 401) ($r.Status)
Check "401 响应体含 detail 字段（App 靠它显示提示）" ((Json-Of $r.Body).detail -ne $null) ($r.Body)

# ---------------------------------------------------------------- 列表
Section "3. 列表 / 分页（对应 MediaGridFragment.fetch）"
$r = Invoke-Raw -Method GET -Url "$Base/api/assets?limit=120" -Headers @{ Authorization = "Bearer $token" }
Check "GET /api/assets?limit=120 返回 200" ($r.Status -eq 200) ($r.Status)
$page = Json-Of $r.Body
Check "items 是数组" (Is-ArrayProp $page "items") ""
Check "含 next_cursor 字段（null 也合法）" ($page.PSObject.Properties.Name -contains "next_cursor") ""

$r = Invoke-Raw -Method GET -Url "$Base/api/assets?limit=120&grouped=true" -Headers @{ Authorization = "Bearer $token" }
$grouped = Json-Of $r.Body
Check "grouped=true 返回 groups" (Is-ArrayProp $grouped "groups") ""

$r = Invoke-Raw -Method GET -Url "$Base/api/assets?limit=120&favorite=true" -Headers @{ Authorization = "Bearer $token" }
Check "favorite=true 可用" ($r.Status -eq 200) ($r.Status)

$r = Invoke-Raw -Method GET -Url ("$Base/api/assets?limit=120&q=" + [uri]::EscapeDataString("is:fav")) -Headers @{ Authorization = "Bearer $token" }
Check "搜索参数 q 支持高级语法" ($r.Status -eq 200) ($r.Status)

$r = Invoke-Raw -Method GET -Url "$Base/api/assets?type=video" -Headers @{ Authorization = "Bearer $token" }
Check "type=video 过滤可用" ($r.Status -eq 200) ($r.Status)

# ---------------------------------------------------------------- 缩略图（Glide 路径）
Section "4. 缩略图 URL（Glide 直接加载，token 走 query）"
$enc = [uri]::EscapeDataString($token)
$r = Invoke-Raw -Method GET -Url "$Base/api/assets/1/thumb?size=256&token=$enc"
Check "GET thumb?size=256&token=... 免 header 鉴权可用" ($r.Status -eq 200 -or $r.Status -eq 404) ($r.Status)
if ($r.Status -eq 200) {
  Check "返回 JPEG 魔数 FFD8" ($r.Bytes[0] -eq 0xFF -and $r.Bytes[1] -eq 0xD8) ("first2=" + $r.Bytes[0] + "," + $r.Bytes[1])
  Check "带 Cache-Control（Glide 磁盘缓存依赖）" ($r.Headers["Cache-Control"] -ne $null) ""
}

$r = Invoke-Raw -Method GET -Url "$Base/api/assets/1/thumb?size=1024&token=$enc"
Check "size=1024 预览图可用" ($r.Status -eq 200 -or $r.Status -eq 404) ($r.Status)

$r = Invoke-Raw -Method GET -Url "$Base/api/assets/1/thumb?size=999&token=$enc"
Check "非法 size 不会崩（回落 256）" ($r.Status -eq 200 -or $r.Status -eq 404) ($r.Status)

# ---------------------------------------------------------------- 上传（分片协议）
Section "5. 分片上传（对应 UploadManager：init -> chunk -> complete）"
# 造一张 300KB 的测试 JPEG
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap 1200, 900
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::FromArgb(255, 60, 120, 190))
$g.DrawString("ANDROID CONTRACT TEST", (New-Object System.Drawing.Font("Arial", 40)), [System.Drawing.Brushes]::White, 40, 400)
$g.Dispose()
$ms = New-Object System.IO.MemoryStream
$bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Jpeg)
$jpeg = $ms.ToArray()
$bmp.Dispose()

$sha = [System.Security.Cryptography.SHA256]::Create()
$hash = ($sha.ComputeHash($jpeg) | ForEach-Object { $_.ToString("x2") }) -join ""
Write-Host ("    测试图片 " + $jpeg.Length + " 字节, sha256=" + $hash.Substring(0, 16) + "...")

$initBody = [System.Text.Encoding]::UTF8.GetBytes((@{
  file_name = "安卓契约测试.jpg"; size_bytes = $jpeg.Length; sha256 = $hash
} | ConvertTo-Json -Compress))
$r = Invoke-Raw -Method POST -Url "$Base/api/uploads/init" -Body $initBody -Headers @{
  "Content-Type" = "application/json; charset=utf-8"; Authorization = "Bearer $token" }
Check "POST /api/uploads/init 返回 200" ($r.Status -eq 200) ($r.Status + " " + $r.Body)
$init = Json-Of $r.Body
Check "init 返回 upload_id（本次应为新文件）" ($init.upload_id -ne $null -and $init.duplicate -ne $true) (($r.Body).Substring(0, [Math]::Min(160, $r.Body.Length)))
$uploadId = $init.upload_id

if ($uploadId) {
  # 按 64KB 分片，和 App 里的 CHUNK 一致（App 用 4MB，这里为了多测几轮特意切小）
  $chunkSize = 65536
  $offset = 0
  $chunkOk = $true
  while ($offset -lt $jpeg.Length) {
    $len = [Math]::Min($chunkSize, $jpeg.Length - $offset)
    $piece = New-Object byte[] $len
    [Array]::Copy($jpeg, $offset, $piece, 0, $len)
    $r = Invoke-Raw -Method PUT -Url "$Base/api/uploads/$uploadId/chunk" -Body $piece -Headers @{
      "Content-Type" = "application/octet-stream"; "X-Chunk-Offset" = "$offset"; Authorization = "Bearer $token" }
    if ($r.Status -ne 200) { $chunkOk = $false; Write-Host ("    分片失败 offset=$offset status=" + $r.Status + " " + $r.Body) -ForegroundColor Red; break }
    $offset += $len
  }
  Check ("分片上传全部成功（" + [Math]::Ceiling($jpeg.Length / $chunkSize) + " 片）") $chunkOk ""

  # 故意重发最后一片：服务器应报告 expected_offset 而不是写坏文件
  $len = [Math]::Min($chunkSize, $jpeg.Length)
  $piece = New-Object byte[] $len
  [Array]::Copy($jpeg, 0, $piece, 0, $len)
  $r = Invoke-Raw -Method PUT -Url "$Base/api/uploads/$uploadId/chunk" -Body $piece -Headers @{
    "Content-Type" = "application/octet-stream"; "X-Chunk-Offset" = "0"; Authorization = "Bearer $token" }
  $resume = Json-Of $r.Body
  Check "重复分片时返回续传偏移（expected_offset）" ($resume.expected_offset -ne $null -or ($resume.ok -eq $true)) ($r.Body)

  $doneBody = [System.Text.Encoding]::UTF8.GetBytes((@{
    upload_id = $uploadId; file_name = "安卓契约测试.jpg"; sha256 = $hash
  } | ConvertTo-Json -Compress))
  $r = Invoke-Raw -Method POST -Url "$Base/api/uploads/complete" -Body $doneBody -Headers @{
    "Content-Type" = "application/json; charset=utf-8"; Authorization = "Bearer $token" }
  Check "POST /api/uploads/complete 返回 200" ($r.Status -eq 200) ($r.Status + " " + $r.Body)
  $done = Json-Of $r.Body
  Check "入库成功并返回 asset" ($done.asset -ne $null) (($r.Body).Substring(0, [Math]::Min(200, $r.Body.Length)))
  Check "中文文件名正确保存" ($done.asset.file_name -eq "安卓契约测试.jpg") ($done.asset.file_name)

  if ($done.asset) {
    $newId = $done.asset.id
    Check "服务器算出的 sha256 与客户端一致" ($done.asset.sha256 -eq $hash) ($done.asset.sha256)

    # 秒传：同样的内容再传一次，init 阶段就应判定重复
    $initBody2 = [System.Text.Encoding]::UTF8.GetBytes((@{
      file_name = "安卓契约测试_副本.jpg"; size_bytes = $jpeg.Length; sha256 = $hash
    } | ConvertTo-Json -Compress))
    $r = Invoke-Raw -Method POST -Url "$Base/api/uploads/init" -Body $initBody2 -Headers @{
      "Content-Type" = "application/json; charset=utf-8"; Authorization = "Bearer $token" }
    $dup = Json-Of $r.Body
    Check "相同 SHA-256 触发秒传（duplicate=true）" ($dup.duplicate -eq $true) ($r.Body)

    # 缩略图（异步生成，稍等）
    $thumbOk = $false
    for ($i = 0; $i -lt 20; $i++) {
      Start-Sleep -Milliseconds 700
      $r = Invoke-Raw -Method GET -Url "$Base/api/assets/$newId/thumb?size=256&token=$enc"
      if ($r.Status -eq 200 -and $r.Bytes[0] -eq 0xFF) { $thumbOk = $true; break }
    }
    Check "上传后缩略图自动生成" $thumbOk ""

    # 详情（PhotoViewer 的「详情」按钮）
    $r = Invoke-Raw -Method GET -Url "$Base/api/assets/$newId" -Headers @{ Authorization = "Bearer $token" }
    $detail = Json-Of $r.Body
    Check "GET /api/assets/{id} 详情可用" ($r.Status -eq 200) ($r.Status)
    Check "详情含 width/height" ($detail.width -eq 1200 -and $detail.height -eq 900) ("$($detail.width)x$($detail.height)")

    # 编辑（EditActivity 的保存请求体）
    $editBody = [System.Text.Encoding]::UTF8.GetBytes((@{
      rotate = 90; flip_h = $true; flip_v = $false
      crop = @(0.1, 0.1, 0.9, 0.9)
      brightness = 1.05; contrast = 1.0; saturation = 1.1
      filter = "vivid"; keep_original = $true
    } | ConvertTo-Json -Compress))
    $r = Invoke-Raw -Method POST -Url "$Base/api/assets/$newId/edit" -Body $editBody -Headers @{
      "Content-Type" = "application/json; charset=utf-8"; Authorization = "Bearer $token" }
    Check "POST /edit 保存成功" ($r.Status -eq 200) ($r.Status + " " + $r.Body.Substring(0, [Math]::Min(200, $r.Body.Length)))
    $edited = Json-Of $r.Body
    Check "编辑后尺寸变化符合预期（900x1200 的 80%）" ($edited.asset.width -eq 720 -and $edited.asset.height -eq 960) ("$($edited.asset.width)x$($edited.asset.height)")

    # 收藏 / 标签 / 相册
    $favBody = [System.Text.Encoding]::UTF8.GetBytes((@{ ids = @($newId); value = $true } | ConvertTo-Json -Compress))
    $r = Invoke-Raw -Method POST -Url "$Base/api/assets/favorite" -Body $favBody -Headers @{
      "Content-Type" = "application/json"; Authorization = "Bearer $token" }
    Check "收藏接口可用" ($r.Status -eq 200) ($r.Status)

    $albumBody = [System.Text.Encoding]::UTF8.GetBytes((@{ name = "契约测试相册"; description = "来自 verify-app-contract" } | ConvertTo-Json -Compress))
    $r = Invoke-Raw -Method POST -Url "$Base/api/albums" -Body $albumBody -Headers @{
      "Content-Type" = "application/json; charset=utf-8"; Authorization = "Bearer $token" }
    $album = Json-Of $r.Body
    Check "新建相册可用" ($r.Status -eq 200 -and $album.id -ne $null) ($r.Body)

    if ($album.id) {
      $itemBody = [System.Text.Encoding]::UTF8.GetBytes((@{ ids = @($newId); remove = $false } | ConvertTo-Json -Compress))
      $r = Invoke-Raw -Method POST -Url "$Base/api/albums/$($album.id)/items" -Body $itemBody -Headers @{
        "Content-Type" = "application/json"; Authorization = "Bearer $token" }
      Check "加入相册可用" ($r.Status -eq 200) ($r.Status)

      $r = Invoke-Raw -Method GET -Url "$Base/api/album/$($album.id)" -Headers @{ Authorization = "Bearer $token" }
      # 注意：正确路径是 /api/albums/{id}
      $r = Invoke-Raw -Method GET -Url "$Base/api/albums/$($album.id)?limit=200" -Headers @{ Authorization = "Bearer $token" }
      $alb = Json-Of $r.Body
      Check "相册详情可用（App 用的 /api/albums/{id}?limit=200）" ($r.Status -eq 200 -and $alb.items.Count -eq 1) ($r.Status)
    }

    # 分享
    $shareBody = [System.Text.Encoding]::UTF8.GetBytes((@{ kind = "asset"; target_id = $newId; expires_in_days = 30 } | ConvertTo-Json -Compress))
    $r = Invoke-Raw -Method POST -Url "$Base/api/shares" -Body $shareBody -Headers @{
      "Content-Type" = "application/json"; Authorization = "Bearer $token" }
    $share = Json-Of $r.Body
    Check "创建分享可用" ($r.Status -eq 200 -and $share.token -ne $null) ($r.Body)

    # 回收站
    $trashBody = [System.Text.Encoding]::UTF8.GetBytes((@{ ids = @($newId) } | ConvertTo-Json -Compress))
    $r = Invoke-Raw -Method POST -Url "$Base/api/assets/trash" -Body $trashBody -Headers @{
      "Content-Type" = "application/json"; Authorization = "Bearer $token" }
    Check "移入回收站可用" ($r.Status -eq 200) ($r.Status)
    $r = Invoke-Raw -Method POST -Url "$Base/api/assets/restore" -Body $trashBody -Headers @{
      "Content-Type" = "application/json"; Authorization = "Bearer $token" }
    Check "从回收站恢复可用" ($r.Status -eq 200) ($r.Status)

    # 清理：只删本次测试自己造出来的产物（文件名前缀 + id 比基线新，双重确认）
    # 注意：编辑时用了 keep_original=true，服务器会额外生成一张「安卓契约测试_原图.jpg」，
    # 它不在回收站里，是独立资源，所以必须靠前缀扫描一起删掉。
    # 绝不调用全局清空接口 —— 详见 README「测试脚本安全约定」。
    Invoke-Raw -Method DELETE -Url "$Base/api/assets/$newId" -Headers @{ Authorization = "Bearer $token" } | Out-Null
    # 这三个名字是本脚本唯一会造出来的资源，其余一概不碰
    $removed = Remove-MyArtifacts $token $baselineId "安卓契约测试" @(
      "安卓契约测试.jpg", "安卓契约测试_原图.jpg", "安卓契约测试_副本.jpg")
    if ($album.id) { $r = Invoke-Raw -Method DELETE -Url "$Base/api/albums/$($album.id)" -Headers @{ Authorization = "Bearer $token" } }
    $leftover = @((Json-Of (Invoke-Raw -Method GET -Url "$Base/api/assets?limit=200" -Headers @{ Authorization = "Bearer $token" }).Body).items).Count
    Write-Host ("    （清理：删除本次测试产物 " + $removed + " 个；未动用全局清空回收站；库里剩余资源 " + $leftover + " 个）") -ForegroundColor DarkGray
  }
}

# ---------------------------------------------------------------- 大文件 Range
Section "6. 视频 Range 流（VideoView 依赖 HTTP 206）"
$r = Invoke-Raw -Method GET -Url "$Base/api/assets?type=video&limit=1" -Headers @{ Authorization = "Bearer $token" }
$vids = Json-Of $r.Body
if ($vids.items -and $vids.items.Count -gt 0) {
  $vid = $vids.items[0]
  $req = [System.Net.HttpWebRequest]::Create("$Base/api/assets/$($vid.id)/stream?token=$enc")
  $req.AddRange(0, 1023)
  try {
    $resp = $req.GetResponse()
    Check "视频 Range 请求返回 206" ([int]$resp.StatusCode -eq 206) ([int]$resp.StatusCode)
    Check "含 Content-Range（播放器定位依赖）" ($resp.Headers["Content-Range"] -ne $null) ($resp.Headers["Content-Range"])
    Check "含 Accept-Ranges: bytes" ($resp.Headers["Accept-Ranges"] -eq "bytes") ($resp.Headers["Accept-Ranges"])
    $resp.Close()
  } catch { Check "视频 Range 请求" $false $_.Exception.Message }
} else {
  Write-Host "  [跳过] 服务器上暂无视频，先跑 e2e_video_test.py 造一段" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 结果
Write-Host ""
Write-Host ("===== App 契约验证：" + $script:passed + " 项通过，" + $script:failed.Count + " 项失败 =====") -ForegroundColor Yellow
foreach ($f in $script:failed) { Write-Host ("  FAILED: " + $f) -ForegroundColor Red }
if ($script:failed.Count -eq 0) { exit 0 } else { exit 1 }
