#!/usr/bin/env python3
"""把本机文件（通常是 downloads 里刚爬下来的视频）批量上传到相册。

为什么要有这个脚本：
    爬虫把文件下到 F:\\树莓派开发\\downloads，而相册在树莓派上（加密盘）。
    从手机上一个个分享太慢，所以直接用接口上传，并且**一次调用就把整批放进同一个相册**。

它做的事（就是 App 上传时走的同一套接口）：
    1. POST /api/login                         拿令牌
    2. GET  /api/albums                        按名字找相册，没有就建（可指定父相册）
    3. POST /api/uploads/init                  每个文件开一个上传会话
       带 album_id   -> 上传完自动进这个相册（不用事后再归一次）
       带同一个 batch_id -> 这一批在列表里聚在一起
    4. PUT  .../chunk                          分片续传（偏移必须等于服务端已有字节数）
    5. POST /api/uploads/complete              入库；给 order_index 就按给定顺序并加锁

用法：
    # 把 downloads 里所有 [Erovirus] 开头的 mp4 传到 Patreon 下的 Erovirus 相册
    python tools/upload-to-album.py --parent Patreon --album Erovirus downloads/*.mp4

    # 想严格按命令行给的顺序排（而不是按文件名自然序）
    python tools/upload-to-album.py --album X --order given a.mp4 b.mp4

安全约定：
    - 只上传，不删除任何东西；重复文件交给服务端秒传（duplicate），不会产生第二份。
    - 口令从 photoalbum/deploy/local.env 或环境变量 PHOTOALBUM_PASSWORD 读，不写进代码。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL_ENV = ROOT / "photoalbum" / "deploy" / "local.env"
MEDIA_EXT = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif",
             ".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi", ".3gp"}


def load_env() -> None:
    if not LOCAL_ENV.exists():
        return
    for line in LOCAL_ENV.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if key and value and key not in os.environ:
            os.environ[key] = value


class Api:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")
        self.token = ""

    def call(self, method: str, path: str, body=None, *, raw: bytes | None = None,
             headers: dict | None = None, timeout: int = 300):
        h = dict(headers or {})
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            h["Content-Type"] = "application/json"
        elif raw is not None:
            data = raw
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(self.base + path, data=data, headers=h, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                payload = resp.read().decode("utf-8", "replace")
                return resp.status, (json.loads(payload) if payload.strip() else {})
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(payload)
            except json.JSONDecodeError:
                return e.code, {"detail": payload[:300]}

    def login(self, user: str, password: str) -> None:
        status, data = self.call("POST", "/api/login",
                                 {"username": user, "password": password})
        if status != 200 or "token" not in data:
            die(f"登录失败：{status} {data}")
        self.token = data["token"]


def die(message: str, code: int = 2):
    print("  错误：" + message, file=sys.stderr)
    sys.exit(code)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def resolve_album(api: Api, name: str, parent: str | None) -> int:
    """按名字找相册；给 parent 就在那个相册下找。找不到就建。返回相册 id。"""
    status, data = api.call("GET", "/api/albums")
    if status != 200:
        die(f"读相册列表失败：{status} {data}")
    albums = data.get("albums") or []
    by_id = {a["id"]: a for a in albums}

    parent_id = None
    if parent:
        matches = [a for a in albums if a["name"] == parent]
        if not matches:
            die(f"找不到父相册「{parent}」（现有顶层相册："
                + "、".join(a["name"] for a in albums if a.get("parent_id") is None) + "）")
        parent_id = matches[0]["id"]
        print(f"  父相册「{parent}」= id {parent_id}")

    for a in albums:
        if a["name"] == name and a.get("parent_id") == parent_id:
            print(f"  相册「{name}」已存在 = id {a['id']}（直接用它）")
            return int(a["id"])

    body = {"name": name, "parent_id": parent_id}
    status, data = api.call("POST", "/api/albums", body)
    if status != 200 or not data.get("id"):
        die(f"建相册失败：{status} {data}")
    print(f"  新建相册「{name}」= id {data['id']}"
          + (f"（挂在「{parent}」下）" if parent else "（顶层）"))
    return int(data["id"])


def upload_one(api: Api, path: Path, album_id: int, batch_id: str,
               order_index: int | None, chunk: int, quiet: bool = False) -> dict:
    size = path.stat().st_size
    print(f"\n  ▶ {path.name}")
    print(f"    {size / 1024 / 1024:.2f} MiB")
    digest = sha256_of(path)

    init_body = {
        "file_name": path.name,
        "size_bytes": size,
        "sha256": digest,
        "album_id": album_id,
        "batch_id": batch_id,
        "client_mtime": int(path.stat().st_mtime),
    }
    status, data = api.call("POST", "/api/uploads/init", init_body)
    if status != 200:
        die(f"init 失败：{status} {data}")

    if data.get("duplicate"):
        # 秒传：服务器已有同一份。init 这条路径不会顺手归相册，所以这里补一次。
        asset = data.get("asset") or {}
        asset_id = asset.get("id")
        if asset_id:
            api.call("POST", f"/api/albums/{album_id}/items",
                     {"ids": [asset_id], "remove": False})
        print(f"    ⚡ 秒传（服务器已有）：asset id={asset_id}，已确保它在目标相册里")
        return {"ok": True, "duplicate": True, "asset_id": asset_id, "name": path.name}

    upload_id = data["upload_id"]
    server_chunk = min(chunk, int(data.get("chunk_size") or chunk))

    sent = 0
    started = time.time()
    with path.open("rb") as handle:
        while sent < size:
            block = handle.read(server_chunk)
            if not block:
                break
            status, res = api.call(
                "PUT", f"/api/uploads/{upload_id}/chunk", raw=block,
                headers={"Content-Type": "application/octet-stream",
                         "X-Chunk-Offset": str(sent)},
            )
            if status != 200:
                die(f"分片失败（offset={sent}）：{status} {res}")
            if not res.get("ok"):
                # 服务端说偏移不一致：按它给的偏移续传
                sent = int(res.get("expected_offset", res.get("received", sent)))
                handle.seek(sent)
                continue
            sent += len(block)
            if not quiet:
                pct = sent * 100 / size
                print(f"\r    上传中 {pct:5.1f}%  ({sent / 1024 / 1024:.1f}/"
                      f"{size / 1024 / 1024:.1f} MiB)", end="", flush=True)
    elapsed = max(0.001, time.time() - started)
    print(f"\r    上传完成 100%  用时 {elapsed:.1f}s"
          f"（{size / 1024 / 1024 / elapsed:.1f} MiB/s）")

    done_body = {"upload_id": upload_id, "file_name": path.name, "sha256": digest}
    if order_index is not None:
        done_body["order_index"] = order_index
    status, done = api.call("POST", "/api/uploads/complete", done_body)
    if status != 200 or not done.get("ok"):
        die(f"complete 失败：{status} {done}")
    asset = done.get("asset") or {}
    print(f"    入库：asset id={asset.get('id')}"
          + ("（服务器判定为重复）" if done.get("duplicate") else ""))
    return {"ok": True, "duplicate": bool(done.get("duplicate")),
            "asset_id": asset.get("id"), "name": path.name}


def gather(paths: list[str]) -> list[Path]:
    out: list[Path] = []
    for p in paths:
        path = Path(p)
        if path.is_dir():
            out.extend(sorted((f for f in path.iterdir()
                               if f.is_file() and f.suffix.lower() in MEDIA_EXT),
                              key=lambda f: f.name))
        elif path.is_file():
            out.append(path)
        else:
            die(f"找不到文件：{p}")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(
        description="把本机文件批量上传到树莓派相册（不删除任何东西）")
    parser.add_argument("files", nargs="+", help="文件或目录（目录会取里面的图片/视频）")
    parser.add_argument("--server", default=os.environ.get("PHOTOALBUM_SERVER",
                                                           "http://100.82.85.15:8080"))
    parser.add_argument("--album", required=True, help="目标相册名（不存在会新建）")
    parser.add_argument("--parent", default=None, help="父相册名（会把相册建在它下面）")
    parser.add_argument("--order", choices=["name", "given"], default="name",
                        help="name=按文件名自然序（默认）；given=严格按命令行给的顺序")
    parser.add_argument("--chunk", type=int, default=1024 * 1024, help="分片大小（字节）")
    parser.add_argument("--dry-run", action="store_true", help="只列出要传什么，不真的传")
    args = parser.parse_args()

    load_env()
    files = gather(args.files)
    if not files:
        die("没有匹配到任何文件")

    print(f"服务器：{args.server}")
    print(f"目标相册：「{args.album}」" + (f"（父相册「{args.parent}」）" if args.parent else ""))
    print(f"待上传 {len(files)} 个文件，共 {sum(f.stat().st_size for f in files) / 1024 / 1024:.2f} MiB")
    for i, f in enumerate(files, 1):
        print(f"  {i:2d}. {f.name}  ({f.stat().st_size / 1024 / 1024:.2f} MiB)")
    if args.dry_run:
        print("\n（--dry-run：什么都没做）")
        return 0

    password = os.environ.get("PHOTOALBUM_PASSWORD", "")
    if not password:
        die("缺少口令：请在 photoalbum/deploy/local.env 设置 PHOTOALBUM_PASSWORD，"
            "或用环境变量传入")
    user = os.environ.get("PHOTOALBUM_PI_USER") or "cabbage"

    api = Api(args.server)
    api.login(user, password)
    print("\n登录成功")
    album_id = resolve_album(api, args.album, args.parent)

    batch_id = "b" + str(int(time.time() * 1000))
    print(f"  批次 id = {batch_id}（这一批会在列表里聚在一起）")
    print(f"  排序方式 = {'严格按命令行顺序（加锁）' if args.order == 'given' else '按文件名自然序'}")

    results = []
    for i, f in enumerate(files):
        results.append(upload_one(api, f, album_id, batch_id,
                                  i if args.order == "given" else None, args.chunk))

    print("\n=== 相册现在的实际顺序（服务器返回的，就是 App 里看到的顺序）===")
    status, detail = api.call("GET", f"/api/albums/{album_id}?limit=500")
    if status != 200:
        die(f"读相册内容失败：{status} {detail}")
    album = detail["album"]
    print(f"  {album['name']}：直接含 {album['count']} 项，含子相册 {album['total_count']} 项")
    for it in detail["items"]:
        print(f"    id={it['id']:<5} {it['media_type']:<6} {it.get('size_text','?'):>10}  "
              f"缩略图={it['thumb_state']:<8} 代理={it['proxy_state']:<8} {it['file_name']}")

    dup = sum(1 for r in results if r.get("duplicate"))
    print(f"\n完成：{len(results)} 个文件，其中秒传（服务器已有）{dup} 个。"
          f"\n新上传的视频会在后台抽帧 + 转 720p 代理，稍等一两分钟再刷新。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n已中断（已上传的部分不会丢，重跑本脚本会从断点续传）")
        sys.exit(130)
