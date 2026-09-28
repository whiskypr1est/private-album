# 私有相册 · 服务端（树莓派）

把家里树莓派变成一台**只属于你自己**的相册服务器：照片和录像保存在树莓派上，
手机 App 通过局域网或 Tailscale 内网访问，数据不经过任何第三方云。

本文档对应实际部署环境：

| 项 | 值 |
|---|---|
| 主机 | Raspberry Pi（aarch64, Debian 13 trixie, 内核 6.18） |
| 内存 / 存储 | 3.7 GB / 29 GB microSD（当前媒体存在 SD 卡，后续搬到外接机械硬盘） |
| Tailscale 地址 | 见本机 `deploy/local.env` 的 `PHOTOALBUM_PI_HOST` |
| 局域网地址 | 树莓派在同一 WiFi 下的地址（`hostname -I` 可查，仅同网段可用） |
| 服务端口 | `8080` |
| 账号 | 用户名默认 `cabbage`，口令见本机 `deploy/local.env`（**首次登录后请立刻改密码**） |
| 代码目录 | `/home/cabbage/photoalbum`（虚拟环境在同目录 `venv/`） |
| 服务名 | `photoalbum.service`（systemd，开机自启，崩溃自动重启） |

---

## 一、它长什么样

```
┌─────────────── 安卓手机（私有相册 App）────────────────┐
│  时间线网格 │ 全屏查看（缩放/视频） │ 编辑 │ 上传 │ 相册  │
└──────────────────────────┬─────────────────────────────┘
                           │ HTTP（Tailscale 加密隧道 / 家庭 WiFi）
┌──────────────────────────▼─────────────────────────────┐
│  树莓派  photoalbum.service  (uvicorn + FastAPI)        │
│   ├── SQLite 索引（拍摄时间、EXIF、相册、收藏、分享）      │
│   ├── 后台线程：生成缩略图 / 视频抽帧 / 720p 代理转码      │
│   └── Pillow 图像处理（旋转、裁剪、调色、滤镜）           │
└──────────────────────────┬─────────────────────────────┘
                           │
        /home/cabbage/photoalbum/data/
        ├── media/   原始照片与录像  ← 将来整体搬到外接硬盘
        ├── thumbs/  派生缩略图（可随时重建，丢了不心疼）
        ├── cache/   视频 720p 代理
        └── album.db 索引数据库
```

**设计要点**

- **原图永远是原图**：编辑由服务器用 Pillow 处理并覆盖工作副本，首次编辑前自动备份原始字节；
  打开「保留原图副本」时会额外生成一个 `xxx_原图.jpg`。
- **缩略图与代理都放在 `media/` 之外**：换硬盘时只需搬 `media/` 一个目录。
- **文件丢了不会删索引**：外接硬盘没挂载时，条目只标记 `is_missing`，不会自动进回收站。
- **上传支持断点续传**：4 MB 分片 + SHA-256 秒传去重，传过的照片不会存第二份。

---

## 二、文件布局

```
F:\树莓派开发\
├── 树莓派ssh.txt                     ← 连接信息（原始文件）
├── photoalbum\
│   ├── server\
│   │   ├── app\
│   │   │   ├── main.py               ← FastAPI 路由（全部 HTTP 接口）
│   │   │   ├── library.py            ← 媒体库：索引、扫描、缩略图队列、迁移
│   │   │   ├── media.py              ← 图像/视频处理（EXIF、缩略图、编辑、转码）
│   │   │   ├── db.py                 ← SQLite 表结构与迁移
│   │   │   ├── auth.py               ← HMAC 令牌 + PBKDF2 口令
│   │   │   ├── config.py             ← 路径与配置
│   │   │   └── util.py               ← 哈希 / 时间 / 文件名处理
│   │   ├── scripts\
│   │   │   ├── e2e_test.py           ← 接口端到端测试（71 项）
│   │   │   └── e2e_video_test.py     ← 视频/HEIC 专项测试（需要 ffmpeg）
│   │   └── requirements.txt
│   └── deploy\
│       ├── push.ps1                  ← 本机一键上传+部署
│       ├── install.sh                ← 树莓派端安装/升级
│       ├── photoalbum.service        ← systemd 单元
│       └── reset-data.sh             ← 清空数据重新开始（测试用）
└── tools\
    ├── pi-run.ps1                    ← 在树莓派上执行一段脚本
    ├── fix-ps1-bom.ps1               ← 修 .ps1 的 UTF-8 BOM（中文脚本跑不起来先跑它）
    └── verify-app-contract.ps1       ← 验证 App 与服务器的接口契约
```

---

## 三、部署 / 升级

### 一键部署（在本机执行）

```powershell
powershell -ExecutionPolicy Bypass -File "F:\树莓派开发\photoalbum\deploy\push.ps1"
```

它做三件事：

1. 收集 `server/` 与 `deploy/` 下的源码，统一成 LF 换行；
2. 打成 tar 包用 scp 传到树莓派（**注意：不能走 PowerShell 管道，二进制会被当文本破坏**）；
3. 远端执行 `deploy/install.sh`：建 venv、装依赖、语法自检、装 systemd、健康检查。

参数：

| 参数 | 说明 |
|---|---|
| `-NoDeploy` | 只上传代码，不重启服务 |

### 只想升级代码（已经部署过）

同上命令即可，`install.sh` 是幂等的，`data/` 目录不会被碰。

### 首次部署时手动做的事

```bash
# 1. 装系统依赖（视频功能必需的 ffmpeg）
sudo apt-get update
sudo apt-get install -y ffmpeg libheif-examples

# 2. 上传代码后执行
bash ~/photoalbum/deploy/install.sh
```

> **ffmpeg 说明**：树莓派 WiFi 较慢，`apt-get install ffmpeg` 要下载约 200 MB、解包 100+ 个包，
> 实测耗时 10～25 分钟。**装之前服务也能正常跑**，只是视频没有缩略图、不能转码 720p；
> 装完执行 `sudo systemctl restart photoalbum` 即可生效（`/api/health` 里的 `ffmpeg` 字段会变成 `true`）。

---

## 四、日常运维

```bash
# 看状态
systemctl status photoalbum

# 看日志（实时）
tail -f ~/photoalbum/logs/service.log
sudo journalctl -u photoalbum -f

# 重启 / 停止
sudo systemctl restart photoalbum
sudo systemctl stop photoalbum
```

### 备份

要备份的只有两样：**`data/media/`（照片本体）** 和 **`data/album.db`（索引）**。
`data/thumbs/`、`data/cache/` 是派生物，删掉后重新扫描会自动重建。

```bash
# 例：把索引和媒体打包到外接盘
tar -czf /mnt/usb/photoalbum-backup-$(date +%F).tar.gz -C ~/photoalbum/data media album.db
```

> 更彻底的做法：直接在 App 的「更多 → 迁移到外接硬盘」里把媒体库搬到外接硬盘，
> 之后备份就是复制那一个目录的事。

### 口令与安全

- 登录令牌用 HMAC-SHA256 签名，默认有效期 60 天；改密码不会让旧令牌立刻失效，
  如需强制失效，删除 `~/photoalbum/.secret` 并重启服务（所有人需要重新登录）。
- 口令用 PBKDF2-SHA256（15 万轮）存储，数据库泄露也无法反推。
- **服务默认监听 `0.0.0.0:8080` 且使用明文 HTTP**。请务必：
  - 只在家庭局域网 + Tailscale 内网使用；
  - 不要在路由器上把这个端口映射到公网；
  - 首次登录后立刻在 App 的「更多 → 服务器与账号」里改掉默认密码。

---

## 五、换成外接机械硬盘（后续步骤）

目标是：`media/` 从 SD 卡搬到机械硬盘，缩略图和索引都保留。

### 第 1 步：把硬盘接上并固定挂载点

```bash
lsblk -o NAME,SIZE,TYPE,MOUNTPOINT,MODEL        # 看硬盘识别成什么（一般 /dev/sda1）
sudo mkdir -p /mnt/usb

# 用 UUID 挂载，避免插拔后设备名变化
sudo blkid /dev/sda1
sudo nano /etc/fstab
# 追加一行（UUID 换成上一步看到的）：
# UUID=xxxx-xxxx  /mnt/usb  ext4  defaults,nofail,x-systemd.device-timeout=10  0  2

sudo mount -a
df -h /mnt/usb
```

> `nofail` 很重要：硬盘没插时不会导致树莓派卡在启动阶段。

### 第 2 步：让服务写入权限正确

```bash
sudo chown -R cabbage:cabbage /mnt/usb
```

### 第 3 步：在 App 里迁移

打开 App →「更多」→「迁移到外接硬盘」→ 填 `/mnt/usb/photoalbum`。

服务器会：

1. 用 `rsync -a` 把整个 `media/` 复制到目标（保留时间戳，中断可重跑）；
2. 复制完成后把媒体根目录指向新位置（写进数据库，重启后依然生效）；
3. 删除旧 SD 卡上的 `media/` 内容（`move=true`，挪完腾出 SD 卡空间）；
4. 清空并按需重建缩略图，重新扫描一遍索引。

**命令行等价操作**（App 不方便时用）：

```bash
# 1. 先看预演（要多少空间、装不装得下）
curl -s -X POST http://127.0.0.1:8080/api/library/migrate/plan \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"target_root":"/mnt/usb/photoalbum"}'

# 2. 真正执行
curl -s -X POST http://127.0.0.1:8080/api/library/migrate \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"target_root":"/mnt/usb/photoalbum","move":true}'
```

### 第 4 步：验证

```bash
curl -s http://127.0.0.1:8080/api/health | python3 -m json.tool | grep media_root
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/library/stats
```

App 里「更多」页的媒体目录应该显示成 `/mnt/usb/photoalbum`，照片数量不变。

### 如果硬盘掉线了怎么办

条目会被标记为「文件已丢失」，但**索引不会删**。重新挂载硬盘后：

- App「更多 → 存储用量与媒体库」手动触发一次扫描，或者
- 调 `POST /api/library/scan`，条目会自动恢复可见。

确认硬盘真的不用了，再调 `POST /api/library/purge-missing` 清理索引。

---

## 六、排序规则（重要）

照片列表的排序是：

```
批次时间 DESC       ← 最新上传的那一批排最前
文件名自然序 ASC     ← 同一批内：1.png, 2.png, 3.png, 10.png, 11.png, 100.png
id ASC              ← 兜底，保证稳定
```

### 为什么需要「自然序」

纯字典序会把序号照片排成 `1.png, 10.png, 100.png, 101.png, 2.png, …`，
按序号命名的图集就完全乱了。服务器在入库时给每张照片算一个**自然排序键**
（`name_sort_key`）：把文件名里的连续数字左边补 0 到 12 位，于是字典序 == 数值序。

```
1.png   -> 000000000001.png
2.png   -> 000000000002.png
10.png  -> 000000000010.png
100.png -> 000000000100.png
```

排序结果：`1.png < 2.png < 10.png < 100.png` ✓

也支持中文和混合命名：`照片1.jpg < 照片2.jpg < 照片10.jpg`，
`IMG_1.jpg < IMG_2.jpg < IMG_10.jpg`。

### 为什么需要「批次」

如果只按上传时间排，同一批文件因为上传完成的先后不同，
会被拆成好几段（第 1 张和第 3 张在一起、第 2 张跑到后面），
文件名顺序就断了。

所以每次上传会带一个 `batch_id`（App 自动生成，一次上传 / 一个文件夹共用一个），
服务器按 **该批次里最早的 created_at** 作为这一批的「批次时间」——
同批每一行算出来都一样，整批不会被拆散。

### 新增 / 修改的照片

- 单独上传一张 = 一个独立批次，按上传时间排在最前；
- 老数据（没有 batch）退化为按自身 created_at 排序；
- 扫描/编辑不会覆盖已有批次，批次只在「带 batch_id 上传」时写入。

---

### 上传时可以选排序方式（`order_index`）
默认按文件名自然排序。如果上传时想让照片**保持源文件夹里的先后**（比如扫图、漫画面页，
文件名顺序和实际顺序不一致），在 `POST /api/uploads/complete` 里带上 `order_index`：

| 请求 | 排序键 | 效果 |
|---|---|---|
| 不带 `order_index` | `natural_sort_key(文件名)` | 按文件名：1、2、3、10、20、100 |
| 带 `order_index: 0,1,2…` | `"~" + 序号补零12位` | 严格按给定序号，文件名不参与 |

`order_index` 写入的同时会把 `assets.sort_key_locked` 置 1。**这个锁是必需的**：
`upsert_asset()` 默认总是用文件名重算 `name_sort_key`，`backfill_sort_keys()` 也会对
「含数字但没补零」的行重算 —— 没有锁的话，一次重扫或重启就会把用户选的顺序抹掉。
两处都已按锁跳过。

> 曾考虑靠「序号补零后恰好含 8 个连续 0」去躲过 `backfill_sort_keys` 的判断，
> 但那是巧合而非设计：序号 123456789 补零后是 `000123456789`，不含 8 个连续零，就会被覆盖。
> 所以用了显式列，不靠格式巧合。

秒传（SHA-256 命中已有文件）的那一张**不会**被重新排序 —— 它属于原批次，保持原位。

`~` 前缀让「人为指定的顺序」在库里一眼可辨。副作用：当两批的 `group_time`（秒级）
恰好相同时，`~` 开头的键排在字母之后，也就是这两批的先后是不确定的。
这是既有行为（两批按文件名排序时同样会遇到平局），不是这里引入的。

---

## 七、子相册（相册里还能有相册）

数据模型只加了一列：`albums.parent_id`，为空表示顶层相册。层级深度不限。

### 两条设计决定（重要，别改错了）

**1. 父相册不「聚合」子相册的照片，只做容器。**

打开 `Patreon` 看到的是它的 6 个子相册，而不是 536 张混在一起的照片。
每个相册的 `count` 是**直接**包含的照片数，另有 `total_count` 表示含所有后代的合计。
列表里显示成「6 个子相册 · 536 张」。

这样做的理由：同一张照片不会在多处重复出现，翻页游标也只需处理一层。
（如果你想要「父相册显示全部子孙照片」，改动点只在 `album_detail` 的 `items` 查询，
把 `album_items.album_id = ?` 换成 `IN (自己 + 所有后代)` 即可。）

**2. 删除相册时，子相册上移一层，而不是被一起删掉。**

`DELETE /api/albums/{id}` 返回 `{"promoted_children": N}`，把子相册的 `parent_id`
改成被删相册的 `parent_id`。照片在任何情况下都不受影响 —— 删相册永远只是「解散集合」。

理由：一次误删不该连带毁掉整个树。这也和「删相册不删照片」的既有约定一致。

### 防环

移动相册时会拦住两类非法操作，都返回 **400**：

- 把相册移进它自己；
- 把相册移进它自己的某个后代（会让整棵树成环、谁都到不了顶层）。

`_album_descendant_ids()` 用 visited 集合逐层展开，即使库里已经有脏环也不会死循环；
`_album_breadcrumb()` 也有 64 层的防御上限。App 侧 `AlbumTree.moveTargets()` 会先把
自己和后代从候选列表里剔掉，用户根本点不到必然失败的选项。

### 手工归组脚本

`server/scripts/group_under_patreon.py` 会把当时所有顶层相册归入一个叫 `Patreon`
的顶层相册。走 HTTP 接口而不是直接写库，**可重复执行**（已存在的相册会跳过），
并在迁移前后对账照片总数，数量不一致就报错退出。这次就是用它把 6 个相册归进去的：

```bash
venv/bin/python server/scripts/group_under_patreon.py
```

---

## 八、接口一览

所有接口除 `/api/health`、`/api/login`、`/api/setup`、`/share/*` 外都需要令牌：
请求头 `Authorization: Bearer <token>`，或查询参数 `?token=<token>`
（后者是为了让图片加载器和播放器能直接吃 URL）。

| 功能 | 方法与路径 |
|---|---|
| 健康检查 | `GET /api/health` |
| 初始化 / 登录 / 改密 | `POST /api/setup`、`POST /api/login`、`POST /api/password` |
| 当前账号 | `GET /api/me` |
| 时间线列表 | `GET /api/assets?limit=&cursor=&type=&favorite=&trashed=&q=&grouped=` |
| 月份聚合 | `GET /api/timeline` |
| 照片详情 | `GET /api/assets/{id}` |
| 缩略图 / 预览图 | `GET /api/assets/{id}/thumb?size=256\|1024` |
| 原图 / 下载 | `GET /api/assets/{id}/raw`、`/download` |
| 视频流（支持 Range） | `GET /api/assets/{id}/stream`（`?original=1` 强制原片） |
| 编辑 | `POST /api/assets/{id}/edit` |
| 恢复原始版本 | `POST /api/assets/{id}/restore-original` |
| 视频裁剪 | `POST /api/assets/{id}/trim` |
| 收藏 / 归档 / 回收站 | `POST /api/assets/favorite`、`/archive`、`/trash`、`/restore` |
| 彻底删除 | `DELETE /api/assets/{id}` |
| 标签 | `GET /api/tags`、`POST /api/assets/tags` |
| 相册 | `GET/POST /api/albums`、`GET/PATCH/DELETE /api/albums/{id}`、`POST /api/albums/{id}/items`、`/cover`、**`POST /api/albums/{id}/move`（移动层级）** |
| 分享链接 | `GET/POST /api/shares`、`DELETE /api/shares/{id}`、`GET /share/{token}` |
| 分片上传 | `POST /api/uploads/init` → `PUT /api/uploads/{id}/chunk` → `POST /api/uploads/complete` |
| 小文件直传 | `POST /api/uploads/simple`（multipart） |

**上传时可以带的参数**（`/api/uploads/init` 的 JSON body）：

| 字段 | 说明 |
|---|---|
| `file_name` | 文件名（必填，决定自然排序位置） |
| `size_bytes` | 文件大小（必填） |
| `sha256` | 客户端算的哈希，用于秒传预检 |
| `taken_at` | 拍摄时间（可选） |
| `album_id` | **上传完成后自动进入该相册**（相册页「上传到此相册」用） |
| `batch_id` | **同一批上传共用一个值**，服务器据此分组排序 |

> `album_id` 不存在会返回 404，非数字返回 400；秒传（相同内容已存在）时
> 也会把已有资源加入目标相册，不会因为去重而漏掉相册归属。
| 维护 | `POST /api/library/scan`、`GET /api/library/stats`、`GET /api/library/verify`、`POST /api/library/rebuild-thumbs`、`GET /api/library/missing`、`POST /api/library/purge-missing`、`POST /api/library/cleanup-orphans` |

> `GET /api/library/stats` 的 `total` 是**可见照片数（不含回收站）**，和 `/api/assets` 列表口径一致；
> 库里真实总行数在 `total_all` 里，回收站数量在 `trashed` 里。
> 早期版本 `total` 用的是 `COUNT(*)`（把回收站也算了），会出现「首页说 605 张、列表只有 485 张」
> 这种看起来像丢照片的现象，已修。`bytes`（占用空间）仍按全部文件算 —— 回收站里的照片同样占磁盘。
| 迁移 | `POST /api/library/migrate/plan`、`POST /api/library/migrate` |
| 设置 | `GET /api/settings`、`PUT /api/settings` |
| 在线接口文档 | `GET /docs`（FastAPI 自动生成，可直接在浏览器点击调试） |

### 搜索语法

`GET /api/assets?q=<关键词>` 支持组合条件：

| 写法 | 含义 |
|---|---|
| `樱花` | 文件名包含「樱花」 |
| `is:fav` | 只看收藏 |
| `type:video` / `type:image` | 只看视频 / 只看照片 |
| `after:2025-01-01` | 该日期之后拍摄 |
| `before:2025-06-30` | 该日期之前拍摄 |
| `album:旅行` | 属于名称含「旅行」的相册 |
| `tag:樱花` | 带该标签 |

多个条件用空格分隔，例如 `type:video album:旅行 after:2025-01-01`。

---

## 九、测试

### 接口端到端测试（71 项）

```bash
ssh 到树莓派后：
cd ~/photoalbum
venv/bin/python server/scripts/e2e_test.py
```

覆盖：鉴权与权限、分片上传、SHA-256 秒传、EXIF 解析（拍摄时间/机型/ISO/光圈/快门/焦距）、
缩略图生成、编辑（旋转/翻转/裁剪/调色/滤镜/保留原图）、列表分页与分组、搜索语法、
收藏/标签、相册增删改查与封面、分享链接（含免登录访问与过期）、回收站、
媒体库统计与一致性校验、迁移预演、错误码（404/415/422）。

### 视频与 HEIC 专项测试（25 项）

```bash
venv/bin/python server/scripts/e2e_video_test.py
```

覆盖：MP4 上传、时长/分辨率解析、ffmpeg 抽帧缩略图、
**HTTP Range 流（206 / Content-Range / 中间区间 / 后缀区间 bytes=-N / 开放区间 / 416 越界）**、
视频裁剪（流复制）、1080p 自动转 720p 代理、`?original=1` 强制原片、HEIC（iPhone 照片）上传与缩略图。

### 本机侧契约测试（35 项）

```powershell
# 1. 开隧道
ssh -N -L 18080:127.0.0.1:8080 <用户名>@<树莓派地址>
# 2. 另开一个窗口
powershell -ExecutionPolicy Bypass -File "F:\树莓派开发\tools\verify-app-contract.ps1"
```

它用本机的 .NET HTTP 栈原样复刻安卓 App 会发出的请求（同样的 URL 形态、查询参数、
请求头、分片偏移），在没有真机的情况下验证接口契约。

### 排序 / 批量上传 / 相册归属专项测试（20 项）

```bash
venv/bin/python server/scripts/e2e_sort_test.py
```

覆盖：自然排序键本身、**相册内返回顺序必须是 1<2<3<10<100**、
两批上传时新批整体在前且批内仍有序、分页游标在自然序下不漏不重、
上传带 `album_id` 自动进相册、秒传文件同样进相册、非法 `album_id` 的报错。

### 本轮实测结果

| 测试 | 结果 |
|---|---|
| `e2e_test.py` | **71 项通过 / 0 项失败** |
| `e2e_sort_test.py` | **20 项通过 / 0 项失败** |
| `e2e_video_test.py` | **25 项通过 / 0 项失败** |
| `verify-app-contract.ps1` | **35 项通过 / 0 项失败** |

合计 **151 项**，全部在真实服务上跑通。

三个 Python 套件和契约测试都改成了「只删自己造出来的产物」。验证方式：
连跑两次契约测试，两次都是 **35/35**、收尾报告「删除本次测试产物 1 个；库里剩余资源 0 个」，
且跑完 `assets` 表 0 行、`media` 目录 0 文件、用户相册 `JW1111` 完好无损 ——
说明清理逻辑既能清干净自己，又不再有全局破坏性动作。

测试过程中发现并修掉的真问题（记录在案，避免重犯）：

| 问题 | 根因 | 修复 |
|---|---|---|
| EXIF 只读到机型，读不到拍摄时间/ISO/光圈 | 子 IFD 用 `get_ifd(1)` 取，而正确的标签号是 `0x8769`(EXIF) / `0x8825`(GPS) | `media.py` 引入 `TAG_EXIF_IFD` / `TAG_GPS_IFD` 常量 |
| `shutil.disk_usage` 报 `'usage' object has no attribute 'get'` | 它返回 namedtuple 而不是 dict | 改用 `.total/.free/.used` 属性 |
| 分享页 500：`unsupported format character ';'` | 模板用 `%` 格式化，而 CSS 里的 `100%;` 被当成格式符 | 改用 `__BODY__` 占位符替换 |
| 清空媒体目录后索引被自动丢进回收站 | 扫描时把「文件不存在」等同于「用户删除」 | 新增 `is_missing` 状态，只标记不删除；提供 `/api/library/purge-missing` |
| 1080p 视频转码失败：`Unable to choose an output format` | ffmpeg 无法从 `.mp4.part` 推断容器格式 | 显式加 `-f mp4`（转码与裁剪两处） |
| 裁剪后时长没变 | 裁剪接口返回的是编辑前的数据库行 | `_refresh_after_edit` 补上 `duration_ms`，接口返回刷新后的数据 |
| `bytes=-512` 后缀 Range 返回整个文件 | Range 解析把空的起始位置当成 0 | 重写解析：支持后缀区间 / 开放区间 / 多区间取第一个，统一 416 处理 |
| 删资源后磁盘留下孤儿文件 | `delete_asset_now` 没清 `media/.originals/<id>.orig` 编辑备份 | 抽出 `_remove_derived_files()` 统一清理缩略图/代理/备份，并加 `cleanup_orphans()` 扫历史遗留（启动扫描时自动跑） |
| 测试跑多了库里攒下 `xxx_原图.jpg` | 测试只删主资源，没删「保留原图」生成的副本 | 测试清理改为按名字前缀扫一遍全删，并调 `cleanup-orphans` |
| 相册内排序被上传时刻打断 | 只用 `created_at` 排序，同一批完成时间不同就被拆开 | 改为按「批次时间」（批次内最早 created_at）分组，整批不再拆散 |
| 分页在批次数排序下重复第一页 | 游标条件方向写反（DESC 列用了 `>`），且游标没带批次时间 | 游标改为 `(批次时间, 文件名键, id)`，条件与 ORDER BY 方向严格对应 |
| `install.sh` 远程执行时报 sudo 需要密码 | 非交互 ssh 没有终端，`sudo` 弹不出提示 | 支持 `PHOTOALBUM_SUDO_PASS` 环境变量 + `sudo -S`，`push.ps1` 自动传入 |
| **测试脚本误删了用户的 3 张真实照片（不可恢复）** | `e2e_test.py` / `e2e_sort_test.py` / `verify-app-contract.ps1` 收尾时都调用了全局 `POST /api/trash/purge`，前一步删除失败时会把回收站里的用户照片一起清掉 | 三个脚本的全局 purge 全部移除，改为「文件名前缀 + id 比基线新」双重确认后只删自己的产物；并立下「测试脚本安全约定」三条铁律 |
| `.ps1` 脚本突然**语法报错**：`Unexpected token '}'`、`An empty pipe element is not allowed` | Windows PowerShell 5.1 把**没有 UTF-8 BOM** 的 `.ps1` 当 ANSI(GBK) 读，文件里的中文全变乱码，解析器被带崩。编辑器/AI 工具把文件另存成「UTF-8 无 BOM」就会触发 | 新增 `tools/fix-ps1-bom.ps1`，扫全目录给漏 BOM 的脚本补上；本次一口气修好 8 个（含 6 个 `vision\*.ps1`） |
| 契约测试报「`items` 是数组」失败，但服务器返回明明是对的 | PowerShell 里 `@() -ne $null` 的结果是**被过滤后的空数组**（假值），库里没照片时 `items: []` 就被误判成失败 | 改判「字段是否存在」：新增 `Has-Prop` / `Is-ArrayProp`，空数组也算通过 |
| 同一张 `_原图.jpg` 反复跑测试却一直清不掉 | `upsert_asset` 按**文件路径**去重，同路径第二次是 UPDATE 不是 INSERT，行 id 不变，用「id 比基线新」永远匹配不到 | 清理函数额外接受一份「已知测试文件名」清单，精确相等也允许删 |

### ⚠️ 测试脚本安全约定（务必遵守）

**测试脚本跑在真实服务、真实数据库上，绝不能调用全局破坏性接口。**

本次开发中真的踩过这个坑：`e2e_test.py`、`e2e_sort_test.py` 和
`verify-app-contract.ps1` 的收尾清理里都调用了全局 `POST /api/trash/purge`
（清空整个回收站）。当时的调用链是
「删掉自己上传的测试文件 → 文件进回收站 → 清空回收站」，看着很合理，
但实际上前一步失败时（比如删除接口报错），回收站里躺着的就是**用户的真实照片**，
全局 purge 会一并抹掉，且不可恢复。

**已经被误删的数据**：3 张 2026-09-22 上传的照片
（`mmexport1790063608451.jpg`、`1789910197025.jpg`、`1789910196950.jpg`）。
`uploads` 表里还留着记录，但 `assets` 表和磁盘文件已不在。
原始文件在手机上还有，**重新上传一次即可恢复**。

因此定下三条铁律，写死在每个测试脚本的注释里：

| 铁律 | 说明 |
|---|---|
| 不调全局清空 | `POST /api/trash/purge`、`/api/library/purge-missing`、`/api/migrate` 一律禁止出现在测试脚本里 |
| **不调全库重建** | **`POST /api/library/rebuild-thumbs` 不带 `ids` 就是全库重建**：删掉整个库的缩略图文件、把所有照片打回 pending，重建期间 App 显示灰格子。测试只能传 `{"ids":[...]}` 限定范围，`{"ids": []}` 表示什么都不做。曾有用例为了验证「返回 200」把用户 492 张缩略图全删了 |
| 删除必须指名 | 只能按自己创建的资源 id 删，或按 `e2e_` 前缀过滤后删 |
| 收尾只清自己 | 清理前先按 `e2e_` / `test_` 前缀过滤，加双重确认再删 |

判断方法：脚本里搜 `trash/purge` 和 `rebuild-thumbs`。前者只允许出现在注释里；后者必须带 `ids`。

### 清空数据重来

```bash
bash ~/photoalbum/deploy/reset-data.sh --yes
```

会删除 `data/` 下全部媒体与索引，并重建默认账号。

> 这是**唯一**允许清空的入口，且只能手工执行、只能由人执行。
> 测试脚本永远不许碰它。

---

## 十、故障排查

| 现象 | 原因与处理 |
|---|---|
| App 显示「连接超时」 | 手机没连 Tailscale 或不在同一 WiFi。先在本机 `ping <树莓派地址>` 确认通路 |
| 上传视频没有缩略图 | 没装 ffmpeg。`sudo apt-get install -y ffmpeg && sudo systemctl restart photoalbum` |
| `/api/health` 的 `ffmpeg` 为 false | 同上 |
| 缩略图全是灰格子 | `POST /api/library/rebuild-thumbs` 重建；查日志确认 Pillow 是否报错 |
| iPhone 的 HEIC 打不开 | 服务端需 `pillow-heif`（已装）或 `heif-convert`（`libheif-examples`） |
| 照片数量突然变 0 | 外接硬盘没挂载。重新挂载后跑一次扫描，条目会自动恢复 |
| 服务起不来 | `sudo journalctl -u photoalbum -n 50`；常见原因是 `data/` 权限或 venv 被删 |
| 中文文件名乱码 | 上传/部署链路必须是 UTF-8；`deploy/push.ps1` 会强制转 LF + UTF-8 |
| **`.ps1` 脚本突然报语法错**（`Unexpected token '}'`、`An empty pipe element is not allowed`） | 文件被存成了「UTF-8 无 BOM」，PowerShell 5.1 按 GBK 读导致中文乱码崩解析。跑 `powershell -ExecutionPolicy Bypass -File "F:\树莓派开发\tools\fix-ps1-bom.ps1"` 修好即可 |
| `fix-ps1-bom.ps1 -Check` 退出码 1 | 说明还有脚本缺 BOM。直接不带 `-Check` 跑一遍就修好了 |

---

## 十一、为什么这么设计（给未来的自己）

- **为什么索引和文件分开**：照片本体是用户资产，索引是可重建的派生数据。
  分开之后，换硬盘、重建缩略图、重装服务都不会碰到照片。
- **为什么编辑在服务器做**：手机端做图像处理会受内存和画质限制；
  在服务器上用 Pillow 处理，原始像素只解码一次，且所有客户端看到的结果一致。
- **为什么要「文件丢失」这个状态**：把「磁盘上暂时没有」和「用户主动删除」混为一谈，
  是自建相册最容易踩的坑——一次没插硬盘就可能把整个库的索引清空。
- **为什么缩略图分 256 / 1024 两档**：网格用 256（省流量、滚动流畅），
  全屏预览用 1024（够清晰又不至于让千兆以下网络卡顿），原图只在「下载」时才拉。
