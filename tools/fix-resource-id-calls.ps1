# 把「把 R.string.xxx 资源 id 直接塞给控件方法」的写法，改成先解析成字符串再传。
#
# 为什么必须改：
#   Toolbar.setTitle(int) -> Context.getText(resId) -> Resources.getText(resId)
#   在 MIUI/HyperOS 上会绕进 MiuiResourcesImpl.getThemeString，然后**递归回到
#   Toolbar.setTitle**，最终 StackOverflowError（栈 8MB 打满）。
#   换成 setTitle(CharSequence) 就完全绕开了这条路径。
#
# 同样的隐患存在于任何「对象方法 + int 资源 id」的组合：
#   setTitle/setMessage/setText/setHint/makeText/setPositiveButton/setNegativeButton/
#   setNeutralButton/setError/setPlaceholderText
# 统一改成 getText(R.string.x)。

$root = "F:\安卓测试\PhotoAlbumApp\app\src\main\java"
$methods = @('setTitle', 'setMessage', 'setText', 'setHint', 'makeText',
             'setPositiveButton', 'setNegativeButton', 'setNeutralButton',
             'setError', 'setPlaceholderText', 'setEmptyText')

$pattern = '(\b(?:' + ($methods -join '|') + ')\s*\(\s*)(R\.string\.[A-Za-z0-9_]+)'

$totalFiles = 0
$totalHits = 0

foreach ($f in Get-ChildItem $root -Recurse -Filter *.java) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.UTF8Encoding]::new($false))
    $hits = [regex]::Matches($text, $pattern).Count
    if ($hits -eq 0) { continue }

    # 回调：只包一层，已经包过的跳过（前面紧跟 getText(/getString( 的不再处理）
    $evaluator = {
        param($m)
        $prefix = $m.Groups[1].Value
        $res = $m.Groups[2].Value
        $before = $m.Index - 1
        if ($before -ge 0) {
            $head = $text.Substring([Math]::Max(0, $m.Index - 12), [Math]::Min(12, $m.Index))
            if ($head -match '(getText|getString)\(\s*$') {
                return $m.Value
            }
        }
        return $prefix + 'getText(' + $res + ')'
    }

    $new = [regex]::Replace($text, $pattern, $evaluator)
    if ($new -ne $text) {
        [System.IO.File]::WriteAllText($f.FullName, $new, [System.Text.UTF8Encoding]::new($false))
        Write-Host ("  {0,-30} 修改 {1} 处" -f $f.Name, $hits) -ForegroundColor Green
        $totalFiles++
        $totalHits += $hits
    }
}

Write-Host ""
Write-Host ("共修改 $totalFiles 个文件，$totalHits 处") -ForegroundColor Cyan

# 复查：还有没有漏网的「对象方法 + 资源 id」
Write-Host ""
Write-Host "=== 复查剩余的可疑写法 ===" -ForegroundColor Cyan
$left = 0
foreach ($f in Get-ChildItem $root -Recurse -Filter *.java) {
    $lines = [System.IO.File]::ReadAllLines($f.FullName, [System.Text.UTF8Encoding]::new($false))
    for ($i = 0; $i -lt $lines.Count; $i++) {
        # 形如 .setXxx(R.string.yyy 且前面不是 getText(/getString(
        foreach ($m in [regex]::Matches($lines[$i], '\.\w+\s*\(\s*R\.string\.[A-Za-z0-9_]+')) {
            Write-Host ("  {0}:{1}: {2}" -f $f.Name, ($i + 1), $lines[$i].Trim()) -ForegroundColor Yellow
            $left++
        }
    }
}
if ($left -eq 0) { Write-Host "  没有漏网（全部已改成 getText(R.string.x)）" -ForegroundColor Green }
