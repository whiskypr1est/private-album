# 私有相册 · Private Album

自建的私有相册：**树莓派上跑服务端，安卓 App 负责浏览 / 上传 / 编辑**。
照片和视频全部存在自己的硬盘上，不经过任何第三方云。

为「手机相册越来越满、又不想把私人照片交给云厂商」这个场景写的，
现在单人使用、约 500+ 张照片，后续计划把媒体从 SD 卡搬到外接机械硬盘。

## 项目结构

```
photoalbum/          服务端（Python）
  server/app/        FastAPI 应用：鉴权、媒体库、缩略图、编辑、分享
  server/scripts/    端到端测试（71 + 20 + 25 项）
  deploy/            部署脚本：install.sh / push.ps1 / systemd 单元
  README.md          服务端详细文档（部署、运维、排序规则、踩坑记录）
android/             安卓客户端（Java）
  app/src/main/      Activity / Fragment / 自研 HTTP 客户端 / 工具类
tools/               开发与验证脚本
  pi-run.ps1             在树莓派上执行一段脚本
  verify-app-contract.ps1 契约测试（35 项，复刻 App 的每个请求）
  fix-ps1-bom.ps1        修 .ps1 缺失的 UTF-8 BOM
```

> `android/` 在开发机上是一个目录联接（junction），指向真正的安卓工程目录。
> 对 Git 而言它就是普通目录，克隆下来是正常文件。

## 功能

- **浏览**：按天分组的网格、全屏查看、双指缩放、视频播放（HTTP Range 流）
- **上传**：多选上传、**整文件夹批量上传**、SHA-256 秒传、断点续传
- **上传排序可选**：上传前选「按文件名自动排序」（1、2、3、10、20）或「保留源文件夹顺序」
  （按文件在文件夹里出现的先后，文件名不参与排序）；选择会被记录并加锁，
  之后重扫媒体库也不会被覆盖
- **显示方式可切换**：网格 3 列 / 网格 5 列 / 列表（带文件名与信息），一键循环切换并记住选择
- **相册**：新建 / 改名 / 封面 / 批量加入；每行都有独立的上传按钮
- **子相册**：相册里还能建相册，层级不限；打开父相册看到的是子相册，面包屑显示当前路径；
  支持把相册移动到任意位置（含移回顶层），删除时子相册上移而不会被连带删掉
- **收藏与标签**、**回收站**（可恢复）、**分享链接**（可设有效期与密码）
- **编辑**：旋转 / 翻转 / 裁剪 / 亮度对比度饱和度 / 滤镜，可选保留原图
- **搜索**：`is:fav`、`type:video`、按日期与文件名
- **媒体处理**：EXIF 解析、缩略图两档（256 / 1024）、1080p 自动转 720p 代理、HEIC 支持
- **换硬盘**：迁移预演 + 一键迁移，媒体盘掉线只标记不删索引

## 排序规则（一个容易忽略的细节）

同一批上传的照片按**文件名自然序**排列，即 `1.png, 2.png, 3.png, 10.png, 20.png, 100.png`，
而不是字符串序的 `1.png, 10.png, 100.png, 2.png`。
实现方式是把文件名里的数字段补齐到 12 位再做字典序比较。

同一批的判定用「批次时间」（批次内最早的 `created_at`），
所以一批几十张照片不会因为后几张传得慢而被拆成两组。

详见 `photoalbum/README.md`。

## 快速开始

### 服务端

```bash
# 树莓派上（Debian / Raspberry Pi OS）
git clone <this-repo> ~/photoalbum && cd ~/photoalbum
bash deploy/install.sh          # 建 venv、装依赖、注册 systemd 服务
```

首次启动会创建一个默认账号，**密码是随机生成的**，从服务日志里读：

```bash
sudo journalctl -u photoalbum | grep 默认账号
```

也可以自己指定初始账号：`PHOTOALBUM_INITIAL_USER` / `PHOTOALBUM_INITIAL_PASSWORD`。
**仓库里不含任何可用的默认口令。**

### 安卓 App

```powershell
# 需要 JDK 17 + Android SDK 36 + Gradle 8.13
$env:JAVA_HOME = "C:\Java\jdk-17"
$env:ANDROID_HOME = "<你的 Android SDK>"
cd android
gradle assembleDebug
```

App 首次打开时填写服务端地址（`http://<地址>:8080`）与账号密码。

## 安全设计

- 所有照片 / 相册 / 库管理接口都要求登录，未登录一律 401。
  令牌是 HMAC-SHA256 签名的，密钥在首次启动时随机生成并落盘（`data/.secret`，权限 600）。
- 口令用 **PBKDF2-SHA256 + 15 万轮**加盐哈希，不可逆。
- 服务默认监听 `0.0.0.0:8080`，**建议只允许从 Tailscale 访问**，这样流量全程由
  WireGuard 加密，也不会暴露给同网段的其它设备：

  ```bash
  sudo ufw allow in on tailscale0 to any port 8080 proto tcp
  sudo ufw deny 8080/tcp
  ```

- 本仓库**不包含**任何口令、私钥或真实连接地址。开发脚本从
  `photoalbum/deploy/local.env`（已 gitignore）读取这些值。
- 这个仓库是**公开**的，所以忽略规则必须写通配的，否则新增的同类目录会「裸奔」：

  | 目录 | 里面是什么 | 规则 |
  |---|---|---|
  | `downloads/` | 下载下来的视频原片 | `downloads/` |
  | `.pages*/` | 抓取的页面快照 + 下载来源清单（`index.csv` 里有来源 URL 和标题） | `.pages*/` |
  | `.diag/` | 排查用的临时脚本与转储 | `.diag/` |
  | `.ssh/`、`树莓派ssh.txt` | 私钥与登录信息 | 逐条列名 |

  踩过的坑：`.pages*/` 原来写的是 `.pages/`。爬第二个站点时脚本换了个目录名
  （`.pages2`），新目录就**完全不受保护**了 —— 一次 `git add -A` 就会把 5 份页面
  快照和下载来源清单推上公开仓库。改用通配符后 `.pages2`、`.pages3`、`.pagesX`
  都盖住了（已验证）。

  **推之前一定要跑 `git add --dry-run --all .`**：它列出的才是真正会被提交的东西，
  比 `git status` 可靠 —— `git check-ignore` 对**不存在**的路径会给出误导结果
  （带尾斜杠的规则只匹配目录，而它没法判断不存在的路径是不是目录）。

## 测试

服务端五个端到端套件 + 一个本机契约套件，共 **236 项**，全部针对真实服务运行：

| 套件 | 项数 | 覆盖 |
|---|---|---|
| `e2e_test.py` | 73 | 鉴权、分片上传、秒传、EXIF、编辑、分页、搜索、相册、分享、回收站 |
| `e2e_video_test.py` | 41 | MP4、Range 流、裁剪、720p 代理、HEIC、非 ASCII 文件名回归 |
| `e2e_album_tree_test.py` | 35 | 子相册：任意深度、面包屑、张数聚合、移动、成环拦截、删除时子相册上移 |
| `e2e_sort_test.py` | 23 | 自然排序、批次分组、分页游标（按权威总数推导，不写死页数） |
| `e2e_upload_order_test.py` | 14 | 上传排序：按文件名 / 保留源顺序，以及**重扫后自定顺序不被冲掉** |
| `verify-app-contract.ps1` | 50 | 复刻 App 的每个请求形态，验证接口契约 |

合计 **236 项**。

> ⚠️ 测试脚本有一条**安全约定**：只允许删除自己造出来的资源
> （按文件名前缀 + id 基线双重确认），**绝不调用 `trash/purge` 这类全局清空接口**。
> 这条规则是用一次真实的数据误删换来的，详见 `photoalbum/README.md`。
