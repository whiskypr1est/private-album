# 找出「同一个 id 名在不同布局里指向不同控件类型」的情况。
#
# 这类问题是本项目最大的坑：
#   fragment_grid.xml 里 @+id/empty 是 LinearLayout，
#   而某个别的布局里 @+id/empty 可能是 TextView；
#   代码里用同一个字段承接，findViewById 的泛型强转会插 (TextView)，
#   运行时就是 ClassCastException —— 编译期完全合法。
#
# 还要查 @+id 与 @id 的混用、以及 id 名和内容不符（比如叫 xxxText 却是 LinearLayout）。

$res = "F:\安卓测试\PhotoAlbumApp\app\src\main\res"
$map = @{}

Get-ChildItem "$res\layout" -Filter *.xml | ForEach-Object {
    $file = $_.BaseName
    $text = [System.IO.File]::ReadAllText($_.FullName, [System.Text.UTF8Encoding]::new($false))
    # 逐个 <标签 ...> 抓：先找所有标签起始，再在标签内找 id
    foreach ($m in [regex]::Matches($text, '(?s)<([A-Za-z_][\w\.]*)\b([^>]*?)/?>')) {
        $tag = $m.Groups[1].Value
        $attrs = $m.Groups[2].Value
        $idMatch = [regex]::Match($attrs, 'android:id\s*=\s*"(@\+?id/([\w]+))"')
        if (-not $idMatch.Success) { continue }
        $idName = $idMatch.Groups[2].Value
        $short = ($tag -split '\.')[-1]
        if (-not $map.ContainsKey($idName)) { $map[$idName] = @{} }
        if (-not $map[$idName].ContainsKey($short)) { $map[$idName][$short] = @() }
        $map[$idName][$short] += $file
    }
}

Write-Host "=== 同一个 id 名对应多种控件类型（危险） ===" -ForegroundColor Cyan
$conflicts = 0
foreach ($id in ($map.Keys | Sort-Object)) {
    if ($map[$id].Keys.Count -gt 1) {
        $conflicts++
        Write-Host ("  [!] R.id.{0}" -f $id) -ForegroundColor Red
        foreach ($cls in ($map[$id].Keys | Sort-Object)) {
            Write-Host ("        {0,-22} <- {1}" -f $cls, (($map[$id][$cls] | Sort-Object -Unique) -join ', ')) -ForegroundColor Yellow
        }
    }
}
if ($conflicts -eq 0) { Write-Host "  没有冲突（每个 id 名在所有布局里都是同一种控件）" -ForegroundColor Green }
else { Write-Host ("  共 {0} 个 id 名有冲突" -f $conflicts) -ForegroundColor Red }

Write-Host ""
Write-Host "=== 硬编码字符串 / 可疑写法 ===" -ForegroundColor Cyan
$java = "F:\安卓测试\PhotoAlbumApp\app\src\main\java"
foreach ($f in Get-ChildItem $java -Recurse -Filter *.java) {
    $lines = [System.IO.File]::ReadAllLines($f.FullName, [System.Text.UTF8Encoding]::new($false))
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match 'setContentView\(R\.layout\.(\w+)\)') {
            # 记录一下每个 Activity 用哪个布局，方便人工核对
        }
    }
}

Write-Host ""
Write-Host "=== 每个 Activity / Fragment 使用的布局与关键控件 ===" -ForegroundColor Cyan
foreach ($f in Get-ChildItem $java -Recurse -Filter *.java) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.UTF8Encoding]::new($false))
    $layouts = [regex]::Matches($text, '(?:setContentView|inflate|layoutId\s*\(\s*\)\s*\{\s*return)\(?\s*R\.layout\.(\w+)') | ForEach-Object { $_.Groups[1].Value }
    if ($layouts) {
        Write-Host ("  {0,-28} -> {1}" -f $f.Name, (($layouts | Sort-Object -Unique) -join ', '))
    }
}
