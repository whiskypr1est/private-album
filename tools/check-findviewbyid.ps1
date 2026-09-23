# 检查 findViewById 的类型是否与布局里该 id 的真实控件类型一致。
# 这类错误编译期不报错（findViewById 是泛型 <T extends View>），
# 运行时才抛 ClassCastException —— 正是这次主界面闪退的原因。
$app = "F:\安卓测试\PhotoAlbumApp\app\src\main"
$viewClass = @{}

# 用正则扫布局，拿到 <类名 ... android:id="@+id/xxx" ...>
Get-ChildItem "$app\res\layout" -Filter *.xml | ForEach-Object {
  $lines = [System.IO.File]::ReadAllText($_.FullName, [System.Text.UTF8Encoding]::new($false))
  foreach ($m in [regex]::Matches($lines, '(?s)<([A-Za-z_][\w\.]*)([^>]*?)android:id\s*=\s*"@\+id/(\w+)"')) {
    $cls = $m.Groups[1].Value
    $id = $m.Groups[3].Value
    $short = ($cls -split '\.')[-1]
    if (-not $viewClass.ContainsKey($id)) { $viewClass[$id] = New-Object System.Collections.Generic.HashSet[string] }
    [void]$viewClass[$id].Add($short)
  }
  # id 写在类名后面的情况（属性顺序不同）
  foreach ($m in [regex]::Matches($lines, '(?s)<([A-Za-z_][\w\.]*)([^>]*?)"@\+id/(\w+)"\s*[^>]*?/?>')) {
    $cls = $m.Groups[1].Value
    $id = $m.Groups[3].Value
    $short = ($cls -split '\.')[-1]
    if (-not $viewClass.ContainsKey($id)) { $viewClass[$id] = New-Object System.Collections.Generic.HashSet[string] }
    [void]$viewClass[$id].Add($short)
  }
}

Write-Host "== 布局里各 id 的真实控件类型 ==" -ForegroundColor Cyan
$viewClass.Keys | Sort-Object | ForEach-Object { "  {0,-20} {1}" -f $_, (($viewClass[$_] | Sort-Object) -join " / ") }

Write-Host ""
Write-Host "== findViewById 赋值类型检查 ==" -ForegroundColor Cyan
$problems = 0
foreach ($f in Get-ChildItem "$app\java" -Recurse -Filter *.java) {
  $src = [System.IO.File]::ReadAllText($f.FullName, [System.Text.UTF8Encoding]::new($false))
  $lines = $src -split "`n"
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $m = [regex]::Match($lines[$i], '([A-Za-z_][\w\.]*(?:<[^>]*>)?)\s+\w+\s*=\s*[^;]*findViewById\(R\.id\.(\w+)\)')
    if (-not $m.Success) { continue }
    $declared = ($m.Groups[1].Value -split '\.')[-1]
    $id = $m.Groups[2].Value
    if (-not $viewClass.ContainsKey($id)) { continue }
    $actual = $viewClass[$id]
    $ok = $false
    foreach ($a in $actual) {
      if ($a -eq $declared) { $ok = $true }
      if ($declared -in @('View', 'ViewGroup', 'Object')) { $ok = $true }
    }
    if (-not $ok) {
      Write-Host ("  [!] {0}:{1}" -f $f.Name, ($i + 1)) -ForegroundColor Red
      Write-Host ("      声明为 {0}，但布局里 R.id.{1} 实际是 {2}" -f $declared, $id, (($actual | Sort-Object) -join '/')) -ForegroundColor Red
      $problems++
    }
  }
}
if ($problems -eq 0) { Write-Host "  未发现类型不匹配" -ForegroundColor Green } else { Write-Host "  共 $problems 处" -ForegroundColor Red }
