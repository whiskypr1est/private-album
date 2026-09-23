# 私有相册 · 安卓客户端

配合树莓派上的 `photoalbum` 服务端使用：照片和录像存在你自己的树莓派上，
手机通过局域网或 Tailscale 内网访问，**一张照片都不会上传到第三方云**。

| 项 | 值 |
|---|---|
| 包名 | `com.privatealbum.app` |
| 应用名 | 私有相册 |
| minSdk / targetSdk / compileSdk | 24（Android 7.0）/ 36 / 36 |
| 工程目录 | `F:\安卓测试\PhotoAlbumApp` |
| 产物 | `app\build\outputs\apk\debug\app-debug.apk`（约 6.7 MB） |
| 语言 / 依赖 | Java 17；AppCompat、Material 3、RecyclerView、ViewPager2、SwipeRefreshLayout、Glide、Gson |

---

## 一、功能

| 功能 | 说明 |
|---|---|
| **连接** | 填服务器地址 + 端口 + 账号密码；「测试连接」会显示服务端版本、照片数量、剩余空间、ffmpeg/HEIC 支持情况 |
| **相册** | 相册列表（第一行是「全部照片」入口）；新建/重命名/删除相册、设置封面、生成免登录分享链接 |
| **子相册** | 相册里还能建相册，层级不限。打开父相册，顶部横条显示它的子相册，点进去再开一层（系统返回键就是「上一层」）。相册名下方一行小字显示「6 个子相册 · 536 张」。长按相册可「移动到…」任意位置或移回顶层 |
| **每个相册单独上传** | 相册列表每行右侧有「上传」按钮，上传的照片**自动归入该相册**；相册详情页右下角「+」同样支持 |
| **选择文件夹批量上传** | 选一个文件夹 → 递归扫描里面所有图片/视频（含子目录）→ 整批加入上传队列 |
| **按文件名自然排序** | 同一批上传的照片按文件名排序：`1.png, 2.png, 3.png, 10.png, 100.png`（**不是**字典序的 `1, 10, 100, 2`） |
| **收藏** | 收藏网格，支持长按多选批量取消收藏 |
| **全屏查看** | 双指缩放、拖动、双击放大/还原、单击隐藏工具栏、左右滑动切换；视频内嵌播放并支持拖动进度（服务器支持 HTTP Range） |
| **照片编辑** | 左转/右转、水平/垂直翻转、自由裁剪（拖动四角）、亮度/对比度/饱和度、8 种滤镜；可选「保留原图副本」 |
| **视频裁剪** | 按秒裁剪时长，服务器用 ffmpeg 处理 |
| **上传** | 分片 + 断点续传 + SHA-256 秒传；进度条实时显示 |
| **分享进来就上传** | 在系统相册里选中照片 → 分享 → 「私有相册」，直接排队上传 |
| **整理** | 收藏、归档、标签、加入相册、移入回收站、详细 EXIF 信息 |
| **搜索** | `文件名关键词`，以及 `is:fav`、`type:video`、`after:2025-01-01`、`album:旅行`、`tag:樱花` 等组合语法 |
| **保存到手机** | 把服务器上的原图下载并写入系统相册（`Pictures/私有相册`） |
| **服务端管理** | 「更多」页可看存储用量、触发重新扫描、重建缩略图、迁移媒体库到外接硬盘、清理本地缓存、退出登录 |

---

## 二、构建 APK

### 一键构建

```powershell
powershell -ExecutionPolicy Bypass -File "F:\安卓测试\tools\build-album.ps1"
```

参数：

| 参数 | 说明 |
|---|---|
| `-Variant release` | 构建 release 包（当前复用 debug 签名） |
| `-Clean` | 先 clean 再构建 |
| `-AsciiPath` | 用 `subst` 把中文路径映射成 `Z:` 再构建（aapt2 出问题时的兜底） |

### 手动构建

```powershell
$env:JAVA_HOME        = 'C:\Java\jdk-17.0.19+10'
$env:GRADLE_USER_HOME = 'F:\安卓测试\gradle-home'
$env:ANDROID_HOME     = 'F:\安卓测试\android-sdk'

cd F:\安卓测试\PhotoAlbumApp
& 'F:\安卓测试\tools\gradle-8.13\bin\gradle.bat' assembleDebug --no-daemon
```

### 安装到手机

```powershell
& 'F:\安卓测试\android-sdk\platform-tools\adb.exe' install -r `
  'F:\安卓测试\PhotoAlbumApp\app\build\outputs\apk\debug\app-debug.apk'
```

手机没插数据线时，直接把 APK 拷进手机点安装（需允许「安装未知来源应用」）。

---

## 三、首次使用

1. **让手机能通到树莓派**
   - 装了 Tailscale：手机和树莓派都登录同一个 Tailscale 账号，地址填树莓派的 Tailscale IP；
   - 只在家里 WiFi：填树莓派的局域网地址，例如 `192.168.1.50`。
2. 打开 App，地址里可以直接粘 `http://<树莓派地址>:8080` 这种完整地址，端口会自动解析出来。
3. 点「测试连接」——出现绿色「连接成功」和服务器信息就说明通了。
4. 填账号（默认 `cabbage`）和密码，点「登录」。
   > 如果服务器是全新部署（还没有任何账号），第一次点「登录」会直接把填的账号创建出来。
5. 进去后建议先做两件事：**改密码**（更多 → 服务器与账号），**调每行张数**（更多 → 每行显示张数）。

---

## 四、界面结构

```
┌─ 顶栏：标题 / 搜索（多选时变成全选 / 关闭）────────────┐
│ 页签： 相册 │ 收藏 │ 更多                              │
├────────────────────────────────────────────────────────┤
│  相册页：                                              │
│    ┌──────────────────────────────────────────────┐   │
│    │ [封面] 全部照片            12 项      [上传] │   │
│    │ [封面] JW1111 · patre图集  0 项       [上传] │   │
│    │ [封面] 旅行                36 项      [上传] │   │
│    └──────────────────────────────────────────────┘   │
│    点某一行 → 进相册看图；点该行「上传」→ 传到这个相册  │
│    右下角「+」→ 上传文件 / 上传文件夹 / 新建相册        │
│  收藏页：收藏网格 + 右下角「+」上传                     │
│  更多页：存储用量、重新扫描、重建缩略图、                │
│          迁移到外接硬盘、回收站、每行张数、              │
│          清理缓存、退出登录                             │
└────────────────────────────────────────────────────────┘

长按照片 → 进入多选 → 顶栏菜单可：收藏 / 加入相册 / 保存到手机 / 分享 / 删除
```

### 关于「全部照片」

「照片」页签已按需求去掉，但**不属于任何相册的照片不能变成看不见**，
所以在「相册」页第一行放了一个「全部照片」入口（含未分类照片）。
它的「上传」按钮 = 只进总库、不进任何相册。

### 上传方式

| 入口 | 行为 |
|---|---|
| 相册行右侧「上传」 | 选文件 / 选文件夹 → 传到**这个相册** |
| 相册详情页右下角「+」 | 同上 |
| 「全部照片」行的「上传」 | 只进总库，不进相册 |
| 顶栏菜单「上传照片 / 视频」 | 选文件或文件夹 → 只进总库 |
| 系统相册「分享」到本 App | 只进总库 |

**选择文件夹**用的是系统目录选择器（`ACTION_OPEN_DOCUMENT_TREE`），
选完会递归扫描（最多 8 层、最多 3000 个文件），只挑图片/视频，
自动跳过 .txt/.pdf 之类的文件，扫描过程中会实时显示已找到多少个。

### 排序行为

同一批上传（一次选文件 / 一次选文件夹 / 一次分享）共用一个 `batch_id`，
服务器按「批次时间 + 文件名自然序」返回，客户端**直接按服务器顺序渲染，不做二次排序**。
所以一次传 100 张 `1.png … 100.png`，看到的一定是 `1, 2, 3, …, 10, 11, …, 100`。

网格里**不再显示日期分组标题**：现在的排序是按上传批次，按拍摄日期分组会让顺序看起来是乱的。

查看器里：单击画面切换工具栏显示，顶栏有「编辑 / 详情 / 更多」，底部有删除按钮。

---

## 五、与服务器的交互（出问题时对照）

| App 动作 | 实际请求 |
|---|---|
| 测试连接 | `GET /api/health` |
| 登录 / 初始化 | `POST /api/login` 失败且服务端 `needs_setup=true` 时自动 `POST /api/setup` |
| 相册列表 | `GET /api/albums` + `GET /api/library/stats`（第一行「全部照片」的数量）。**返回的是全部层级的平表，App 按 `parent_id` 自己组树**（`util/AlbumTree.java`），相册列表页只显示顶层 |
| 收藏页 | `GET /api/assets?limit=120&favorite=true` |
| 全部照片 | `GET /api/assets?limit=120` |
| 进某个相册 | `GET /api/albums/{id}?limit=200`，返回 `album` + `items`（**直接**包含的照片）+ `sub_albums`（直接子相册）+ `breadcrumb`（根到当前的路径） |
| 移动到其它相册 | `POST /api/albums/{id}/move`，`{"parent_id": 12}`，`null` 表示移回顶层。成环会被服务端拒（400）；App 侧也会先把自己和后代从候选里剔掉 |
| 搜索 | `GET /api/assets?q=...` |
| 缩略图 | `GET /api/assets/{id}/thumb?size=256&token=...`（Glide 直接加载，带磁盘缓存） |
| 全屏预览 | `GET /api/assets/{id}/thumb?size=1024&token=...` |
| 视频播放 | `GET /api/assets/{id}/stream?token=...`（HTTP Range 206） |
| 上传 | `POST /api/uploads/init`（带 `album_id` / `batch_id`）→ 多次 `PUT /api/uploads/{id}/chunk` → `POST /api/uploads/complete` |
| 保存编辑 | `POST /api/assets/{id}/edit` |
| 保存到手机 | `GET /api/assets/{id}/download` → 写入 MediaStore |
| 生成分享 | `POST /api/shares` |

**令牌放在 query 里**（`?token=`）不是偷懒：Glide 和 VideoView 只接受 URL，
没法给它们挂请求头。令牌本身是 HMAC 签名的短期凭据，配合 Tailscale 内网使用是安全的。

---

## 七、已知限制

- **视频编辑只支持裁剪时长**，没有滤镜和裁剪画面（移动端对视频做像素级处理不现实）。
- **分享链接是明文 HTTP 页面**，只在 Tailscale 内网或家里 WiFi 有意义；
  要给外人看需要自己做内网穿透 + HTTPS。
- **多选时「分享」一次只能选一张**（服务器一条分享对应一张 / 一个相册）。
- **上传走的是普通后台线程**，App 被系统杀死后会上传中断；
  重新进入 App 再选一次会从断点继续（服务器记得已收到的字节数）。
- **文件夹扫描上限**：递归 8 层、最多 3000 个文件，防止误选整个存储卡把队列撑爆。
- **相册内不能调整顺序**：顺序完全由「上传批次 + 文件名」决定，
  想改顺序就重命名文件再传一次。
- 没有实现「按人脸 / 地点聚类」，只在数据库里预留了 `tags.kind`（tag/person/place）字段。

---

## 七、改名 / 改包名

1. `app/build.gradle.kts` 里的 `namespace` 与 `applicationId`；
2. `java\com\privatealbum\app\` 目录名与各文件的 `package` 声明；
3. `res/values/strings.xml` 的 `app_name`；
4. 改完重新跑 `build-album.ps1`。

---

## 八、构建踩坑记录（给未来的自己）

| 坑 | 现象 | 解决 |
|---|---|---|
| **javac 源码编码** | 报「找不到符号」，实际是中文注释被当成 GBK 解码 | `compileOptions { encoding = "UTF-8" }` + `tasks.withType<JavaCompile> { options.encoding = "UTF-8" }` |
| **javac 中文报错乱码** | Windows 控制台里错误信息全是乱码，没法排查 | `options.forkOptions.jvmArgs = listOf("-Duser.language=en", "-Duser.country=US")` |
| **lambda 参数名撞车** | 嵌套 lambda 里层参数遮蔽外层，报「variable dialog is already defined」 | 嵌套 lambda 用不同参数名（`dlgBtn`/`idxBtn`），或改用匿名类 |
| **`.ps1` 缺 BOM** | 脚本里的中文被按 GBK 解码，直接语法报错 | `tools\build-album.ps1` 等必须存成 **UTF-8 with BOM** |
| **PowerShell 管道传二进制** | `tar -cf - . \| ssh ...` 把 tar 包按文本处理，远端报「This does not look like a tar archive」 | 先 `tar -cf 文件` 再 `scp` 传 |
| **正则替换文件内容** | 用 `-replace` 批量改源码容易把文件改废（`(` 被吃成 `d`） | 改源码用 `edit` / `write` 工具；必须批量时先备份再逐行核对 |
| **自定义类名撞名** | 自己的 `Insets` 工具类与 `androidx.core.graphics.Insets` 冲突，同一文件里没法同时用 | 命名避开 AndroidX 已有类名（已改叫 `SystemBars`） |

### 三个「编译期不报错、运行时才炸」的坑（最坑人，按发现顺序）

**① `findViewById` 泛型强转 —— ClassCastException**

```java
protected android.widget.TextView emptyView;   // ← 布局里其实是 LinearLayout
emptyView = root.findViewById(R.id.empty);     // ← 运行时 ClassCastException
```

`findViewById` 是 `<T extends View> T findViewById(...)`，类型由**左边赋值目标**推断，
编译器会悄悄插一个 `(TextView)` 强转。编译完全合法，只有运行到那一行才炸。
表现：**一进主界面就闪退**，而且崩在 Fragment 的 `onCreateView` 里
（`commitAllowingStateLoss()` 是延迟执行的，所以 `MainActivity.onCreate` 的日志可能已经打完）。

> 已写脚本 `F:\树莓派开发\tools\check-findviewbyid.ps1`：
> 抽取每个布局里 id 的真实控件类型，和所有 `findViewById` 的赋值类型逐条比对。
> 注意它不认识继承关系，`SquareImageView`→`ImageView`、`TextInputEditText`→`EditText`
> 这类向上转型会被报成误报，需要人工判断。

**② `Toolbar.setTitle(int)` —— MIUI 上 StackOverflowError**

```java
toolbar.setTitle(R.string.app_name);        // ← 小米/红米上爆栈
toolbar.setTitle(getText(R.string.app_name)); // ← 正确
```

堆栈特征：

```
java.lang.StackOverflowError: stack size 8188KB
    at android.content.res.MiuiResourcesImpl.getThemeString(MiuiResourcesImpl.java:433)
    at android.content.res.MiuiResourcesImpl.getText(MiuiResourcesImpl.java:86)
    at android.content.res.MiuiResources.getText(MiuiResources.java:102)
    at android.content.Context.getText(Context.java:898)
    at androidx.appcompat.widget.Toolbar.setTitle(Toolbar.java:818)
    ...（反复循环直到栈打满）
```

`Toolbar.setTitle(int)` 内部会走 `Context.getText(resId)`；在 MIUI/HyperOS 上这条路径
会绕进系统的主题资源加载（`getThemeString`）并**递归回到 `Toolbar.setTitle`**，把 8MB 栈打满。

**同一个隐患存在于任何「对象方法 + int 资源 id」的组合**，不只 Toolbar：

`setTitle` / `setMessage` / `setText` / `setHint` / `makeText` /
`setPositiveButton` / `setNegativeButton` / `setNeutralButton` / `setError`

项目里已统一改成先解析再传（共 63 处，脚本 `fix-resource-id-calls.ps1`）。
布局 XML 里的 `app:title="@string/x"` **不受影响**（走 `TypedArray.getText()`，不是 `getText(int)`）。

**③ `registerForActivityResult` 写在字段初始化器里**

```java
private final ActivityResultLauncher<String> pickMedia =
        registerForActivityResult(...);   // ← 字段初始化在构造函数里执行，基类还没准备好
```

异常发生在 `super.onCreate()` **之前**，所以 `onCreate` 第一行日志都打不出来，
只能看到「Application.onCreate 开始」然后就没了。已在 `onCreate` 里注册并加 try/catch。

**④ 排查 StackOverflowError 的两个关键点**（v1.0.5 → v1.0.6 期间）

1. **手机弹窗只显示堆栈最上面 5 行，而真正的循环藏在中间。**
   8MB 栈的 `StackOverflowError`，顶部那几帧（比如 `markKnownViewsInvalid`）
   只是「循环最后一次进入的位置」，看不出谁调了谁。
   为此加了 `Guard` 工具：
   - `Guard.enter/exit(tag)`：同一线程同一调用链里某方法重复进入 N 次就报警，
     并把**过滤后的完整调用链**（只留本项目 + RecyclerView + Fragment 的帧）写进日志；
   - `Guard.count(tag)`：一秒内被调用超过 60 次就报警，专门抓「刷新死循环」。
   两个入口都接进了关键路径（`BaseFragment.load` / `onBindRows` / `submit`）。

2. **要一份完整堆栈**。`CrashActivity` 虽然能滚动，但手机上不方便全文复制；
   排查时优先要 `Android/data/com.privatealbum.app/files/crash-last.txt`
   这个文件的完整内容（或用「复制」按钮），而不是只看弹窗的第一屏。

另外，`SelfTestActivity` 里的第 14 步是**专门用来复现「切换页签闪退」的**：
把 RecyclerView 真的挂到窗口上，反复 `setAdapter` + `notifyDataSetChanged`
（空 → 有数据 → 空）+ 进入/退出多选，共 20 轮，每轮之间真的走一遍 measure/layout。
布局相关的递归只有真的 layout 才会触发，所以这一步是「真复现」而不是「假装测试」。

### 排查这类问题的顺序（血的教训）

1. **先要堆栈**，别推断。前两轮我按「onCreate 之前的崩溃」去猜（字段初始化、`requireContext` 时机），
   方向全偏了；用户贴上那一行 `ClassCastException` 后一次就命中。
2. **`adb devices` 为空时，绝不能靠读代码就下结论** —— 内置崩溃上报（`CrashReporter` + `CrashActivity`）
   和启动步骤日志（`Trace`）比猜一百遍都值钱。这套自检工具留在包里了：
   「更多 → 自检 / 查看上次崩溃信息」，登录页也有「诊断」入口。
3. **写静态检查脚本**。`findViewById` 那类错误人眼扫代码很容易漏，
   用脚本把布局的 id 类型和 Java 的赋值类型做比对，一次就能扫全。
4. **改完源码立刻重新编译**。批量替换脚本会引入新错误（这次 `setEmptyText` 被误包裹就编译不过），
   编译过了再往下走。

