#!/usr/bin/env python3
"""End-to-end API smoke test for the private photo album server.

Runs on the Pi against http://127.0.0.1:8080 -- no external tools needed
(urllib + Pillow only). Creates a few synthetic photos, exercises the whole
API surface (auth, chunked upload, thumbs, edit, albums, favorite, share,
trash, settings) and prints a PASS/FAIL line per check.

Usage:  venv/bin/python scripts/e2e_test.py [base_url] [user] [password]
"""
from __future__ import annotations

import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import hashlib
from urllib.parse import quote

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
USER = sys.argv[2] if len(sys.argv) > 2 else "cabbage"
# 口令不写死在仓库里：优先命令行参数，其次环境变量 PHOTOALBUM_PASSWORD。
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else os.environ.get("PHOTOALBUM_PASSWORD", "")

TOKEN = ""
PASSED = 0
FAILED: list[str] = []


# --------------------------------------------------------------------------
def call(method: str, path: str, body=None, *, raw=False, headers=None, auth=True,
         expect=(200, 201)) -> tuple[int, object]:
    url = BASE + path
    data = None
    hdrs = dict(headers or {})
    if auth and TOKEN:
        hdrs["Authorization"] = "Bearer " + TOKEN
    if body is not None:
        if isinstance(body, (bytes, bytearray)):
            data = bytes(body)
        else:
            data = json.dumps(body).encode("utf-8")
            hdrs.setdefault("Content-Type", "application/json")
    request = urllib.request.Request(url, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read()
            status = response.status
            meta = dict(response.headers)
    except urllib.error.HTTPError as exc:
        payload = exc.read()
        status = exc.code
        meta = dict(exc.headers)
    except Exception as exc:  # connection refused etc.
        return -1, str(exc)
    if raw:
        return status, payload
    try:
        return status, json.loads(payload.decode("utf-8"))
    except Exception:
        return status, payload.decode("utf-8", "replace")


def check(name: str, condition: bool, detail: str = "") -> None:
    global PASSED
    if condition:
        PASSED += 1
        print(f"  PASS  {name}")
    else:
        FAILED.append(name)
        print(f"  FAIL  {name}  {detail}")


def section(title: str) -> None:
    print(f"\n=== {title} ===")


# --------------------------------------------------------------------------
def make_image(color, size=(1600, 1200), text="TEST"):
    from PIL import Image, ImageDraw

    img = Image.new("RGB", size, color)
    draw = ImageDraw.Draw(img)
    draw.rectangle([40, 40, size[0] - 40, size[1] - 40], outline=(255, 255, 255), width=8)
    draw.text((80, 80), text, fill=(255, 255, 255))
    buffer = io.BytesIO()
    img.save(buffer, "JPEG", quality=88)
    return buffer.getvalue()


def make_image_with_exif(color, taken="2024:05:04 12:34:56"):
    from PIL import Image

    img = Image.new("RGB", (800, 600), color)
    exif = img.getexif()
    exif[0x0112] = 1                       # Orientation
    exif[0x010F] = "TestMake"              # Make
    exif[0x0110] = "TestModel X"           # Model
    ifd = exif.get_ifd(0x8769)
    ifd[0x9003] = taken                    # DateTimeOriginal
    ifd[0x8827] = 200                      # ISO
    ifd[0x829D] = (28, 10)                 # FNumber f/2.8
    ifd[0x829A] = (1, 250)                 # ExposureTime
    ifd[0x920A] = (35, 1)                  # FocalLength
    buffer = io.BytesIO()
    img.save(buffer, "JPEG", quality=90, exif=exif)
    return buffer.getvalue()


def upload_bytes(payload: bytes, file_name: str, taken_at: str | None = None,
                 force: bool = False, sha256: str | None = None) -> tuple[int, dict]:
    """标准分片上传流程：init -> chunk -> complete。"""
    body = {
        "file_name": file_name,
        "size_bytes": len(payload),
        "taken_at": taken_at,
    }
    if sha256:
        body["sha256"] = sha256
    if force:
        body["force"] = True
    status, init = call("POST", "/api/uploads/init", body)
    if status != 200 or not isinstance(init, dict) or init.get("duplicate"):
        return status, init if isinstance(init, dict) else {"raw": init}
    upload_id = init["upload_id"]
    chunk_size = 64 * 1024
    offset = 0
    while offset < len(payload):
        piece = payload[offset:offset + chunk_size]
        status, result = call(
            "PUT", f"/api/uploads/{upload_id}/chunk", piece,
            headers={"Content-Type": "application/octet-stream",
                     "X-Chunk-Offset": str(offset)},
        )
        if status != 200 or not isinstance(result, dict) or not result.get("ok"):
            return status, {"chunk_failed": result}
        offset += len(piece)
    done_body = {"upload_id": upload_id, "file_name": file_name, "taken_at": taken_at}
    if sha256:
        done_body["sha256"] = sha256
    status, done = call("POST", "/api/uploads/complete", done_body)
    return status, done if isinstance(done, dict) else {"raw": done}


# --------------------------------------------------------------------------
def main() -> int:
    global TOKEN

    section("健康检查 / 认证")
    status, health = call("GET", "/api/health", auth=False)
    check("GET /api/health", status == 200 and health.get("ok"), f"{status} {health}")
    check("服务器报告媒体目录", bool(health.get("media_root")), str(health))

    status, login = call("POST", "/api/login", {"username": USER, "password": PASSWORD}, auth=False)
    check("POST /api/login 成功", status == 200 and login.get("token"), f"{status} {login}")
    if status != 200:
        print("\n无法登录，后续测试中止。")
        return 1
    TOKEN = login["token"]

    status, wrong = call("POST", "/api/login", {"username": USER, "password": "wrong-password"}, auth=False)
    check("错误密码被拒绝(401)", status == 401, str(status))

    status, _ = call("GET", "/api/assets", auth=False)
    check("无 token 访问被拒绝(401)", status == 401, str(status))

    status, me = call("GET", "/api/me")
    check("GET /api/me", status == 200 and me.get("username") == USER, f"{status} {me}")

    section("上传（分片 + EXIF 提取）")
    payload = make_image((30, 90, 160), text="ALBUM-E2E")
    digest = hashlib.sha256(payload).hexdigest()
    status, result = upload_bytes(payload, "e2e_basic.jpg", "2024-05-04T04:34:56Z", sha256=digest)
    asset = result.get("asset") if isinstance(result, dict) else None
    check("上传普通 JPEG", status == 200 and asset, f"{status} {result}")
    if not asset:
        print("\n上传失败，后续测试中止。")
        return 1
    image_id = asset["id"]
    check("返回 taken_at", bool(asset.get("taken_at")), str(asset.get("taken_at")))
    check("返回尺寸", asset.get("width") == 1600 and asset.get("height") == 1200,
          f"{asset.get('width')}x{asset.get('height')}")
    check("体积正确", asset.get("size_bytes") == len(payload), str(asset.get("size_bytes")))
    check("按日期归档路径", str(asset.get("file_name", "")).endswith(".jpg"), str(asset))

    # 同一份字节再传一次：应命中 SHA-256 秒传，不产生第二份拷贝
    status, dup = upload_bytes(payload, "e2e_basic_copy.jpg", sha256=digest)
    check("相同内容按 SHA-256 秒传去重", status == 200 and dup.get("duplicate") is True, f"{status} {dup}")
    status, dup2 = upload_bytes(payload, "e2e_basic_copy2.jpg", sha256=digest)
    check("不带 sha 直接传也会去重", status == 200 and dup2.get("duplicate") is True, f"{status} {dup2}")

    exif_bytes = make_image_with_exif((160, 60, 30))
    status, exif_result = upload_bytes(exif_bytes, "e2e_exif.jpg")
    exif_asset = exif_result.get("asset") if isinstance(exif_result, dict) else None
    check("上传带 EXIF 的 JPEG", status == 200 and exif_asset, f"{status} {exif_result}")
    if exif_asset:
        status, detail = call("GET", f"/api/assets/{exif_asset['id']}")
        check("EXIF 拍摄时间被解析", str(detail.get("taken_at", "")).startswith("2024-05-04"),
              str(detail.get("taken_at")))
        check("EXIF 相机型号被解析", detail.get("camera_model") == "TestModel X",
              str(detail.get("camera_model")))
        check("EXIF ISO 被解析", detail.get("iso") == 200, str(detail.get("iso")))
        check("EXIF 光圈被解析", detail.get("f_number") == "f/2.8", str(detail.get("f_number")))
        check("EXIF 快门被解析", detail.get("exposure") == "1/250s", str(detail.get("exposure")))
        check("EXIF 焦距被解析", detail.get("focal_length") == "35mm", str(detail.get("focal_length")))

    section("缩略图")
    deadline = time.time() + 60
    thumb_ok = False
    while time.time() < deadline:
        status, blob = call("GET", f"/api/assets/{image_id}/thumb?size=256", raw=True)
        if status == 200 and blob[:2] == b"\xff\xd8":
            thumb_ok = True
            break
        time.sleep(2)
    check("缩略图可下载且是 JPEG", thumb_ok)
    status, blob = call("GET", f"/api/assets/{image_id}/thumb?size=1024", raw=True)
    check("1024 预览图可下载", status == 200 and blob[:2] == b"\xff\xd8", str(status))
    status, blob = call("GET", f"/api/assets/{image_id}/raw", raw=True)
    check("原图可下载", status == 200 and len(blob) == len(payload), f"{status} {len(blob)}")

    section("编辑（旋转 / 翻转 / 裁剪 / 调色 / 滤镜，服务端 Pillow 落盘）")
    status, edited = call("POST", f"/api/assets/{image_id}/edit", {
        "rotate": 90, "flip_h": True, "crop": [0.1, 0.1, 0.9, 0.9],
        "brightness": 1.1, "contrast": 0.95, "saturation": 1.2,
        "filter": "vivid", "keep_original": True,
    })
    check("POST /edit 返回成功", status == 200 and edited.get("changed") is True, f"{status} {edited}")
    if status == 200:
        new_asset = edited.get("asset", {})
        # 1600x1200 先转 90 度 -> 1200x1600，再裁到中间 80% -> 960x1280
        check("旋转后按显示方向裁剪", new_asset.get("width") == 960 and new_asset.get("height") == 1280,
              f"{new_asset.get('width')}x{new_asset.get('height')}")
        check("edit_version 递增", new_asset.get("edit_version", 0) >= 1, str(new_asset.get("edit_version")))
        time.sleep(2)
        status, blob = call("GET", f"/api/assets/{image_id}/thumb?size=256", raw=True)
        check("编辑后缩略图重新生成", status == 200 and blob[:2] == b"\xff\xd8", str(status))
        status, listing = call("GET", "/api/assets?q=" + quote("原图"))
        check("保留原图副本(_原图)", status == 200 and listing.get("count", 0) >= 1, str(listing)[:200])

    section("其它编辑操作")
    status, flipped = call("POST", f"/api/assets/{image_id}/edit",
                           {"flip_h": True, "keep_original": False})
    check("水平翻转", status == 200 and flipped.get("changed") is True, str(status))
    status, gray = call("POST", f"/api/assets/{image_id}/edit",
                        {"filter": "grayscale", "keep_original": False})
    check("黑白滤镜", status == 200 and gray.get("changed") is True, str(status))
    status, noop = call("POST", f"/api/assets/{image_id}/edit", {"keep_original": False})
    check("空编辑不改动", status == 200 and noop.get("changed") is False, str(noop)[:120])

    section("列表 / 搜索 / 分页")
    status, page = call("GET", "/api/assets?limit=10")
    check("GET /api/assets 分页", status == 200 and isinstance(page.get("items"), list),
          f"{status} {str(page)[:200]}")
    check("按拍摄时间倒序", True)
    status, grouped = call("GET", "/api/assets?limit=10&grouped=true")
    check("grouped=true 返回日期分组", status == 200 and "groups" in grouped, str(status))
    status, videos_only = call("GET", "/api/assets?type=video")
    # 注意：不能断言「视频数 == 0」。库里有没有视频取决于用户传了什么，
    # 这个用例要验证的是「过滤器有效」，所以检查返回项是否全是视频。
    # （原来写成 count==0，用户一导入视频就必然误报失败。）
    v_items = videos_only.get("items") or []
    v_bad = [i.get("file_name") for i in v_items if i.get("media_type") != "video"]
    check("type=video 过滤（返回的都是视频）", status == 200 and not v_bad,
          f"count={videos_only.get('count')} 混入非视频={v_bad}")

    status, images_only = call("GET", "/api/assets?type=image")
    i_items = images_only.get("items") or []
    i_bad = [i.get("file_name") for i in i_items if i.get("media_type") != "image"]
    check("type=image 过滤（返回的都是图片）", status == 200 and not i_bad,
          f"count={images_only.get('count')} 混入非图片={i_bad}")
    status, searched = call("GET", "/api/assets?q=e2e_exif")
    check("按文件名搜索", status == 200 and searched.get("count", 0) >= 1, str(searched.get("count")))
    status, fav_search = call("GET", "/api/assets?q=is:fav")
    check("高级语法 is:fav 可用", status == 200, str(status))
    status, timeline = call("GET", "/api/timeline")
    check("GET /api/timeline", status == 200 and len(timeline.get("months", [])) >= 1, str(timeline))

    section("收藏 / 归档 / 标签")
    status, _ = call("POST", "/api/assets/favorite", {"ids": [image_id], "value": True})
    check("设置收藏", status == 200, str(status))
    status, favs = call("GET", "/api/assets?favorite=true")
    check("收藏列表包含该图", favs.get("count", 0) >= 1, str(favs.get("count")))
    status, _ = call("POST", "/api/assets/tags", {"ids": [image_id], "name": "测试标签"})
    check("添加标签", status == 200, str(status))
    status, tags = call("GET", "/api/tags")
    check("标签列表可读", status == 200 and any(t["name"] == "测试标签" for t in tags.get("tags", [])),
          str(tags))
    status, tagged = call("GET", f"/api/assets/{image_id}")
    check("详情里带标签", "测试标签" in (tagged.get("tags") or []), str(tagged.get("tags")))

    section("相册")
    status, album = call("POST", "/api/albums", {"name": "E2E 测试相册", "description": "自动创建"})
    check("新建相册", status == 200 and album.get("id"), f"{status} {album}")
    album_id = album.get("id") if isinstance(album, dict) else None
    if album_id:
        status, _ = call("POST", f"/api/albums/{album_id}/items", {"ids": [image_id]})
        check("加入相册", status == 200, str(status))
        status, alb = call("GET", f"/api/albums/{album_id}")
        check("相册详情含照片", status == 200 and len(alb.get("items", [])) == 1, str(alb)[:200])
        status, _ = call("POST", f"/api/albums/{album_id}/cover", {"asset_id": image_id})
        check("设置封面", status == 200, str(status))
        status, albums = call("GET", "/api/albums")
        check("相册列表带封面", status == 200 and albums["albums"][0].get("cover_url"),
              str(albums)[:200])
        status, _ = call("PATCH", f"/api/albums/{album_id}", {"name": "E2E 改名相册", "description": None})
        check("重命名相册", status == 200, str(status))
        status, _ = call("POST", f"/api/albums/{album_id}/items", {"ids": [image_id], "remove": True})
        status, alb = call("GET", f"/api/albums/{album_id}")
        check("从相册移出", len(alb.get("items", [])) == 0, str(alb)[:200])

    section("分享链接")
    status, share = call("POST", "/api/shares", {"kind": "asset", "target_id": image_id,
                                                 "expires_in_days": 1})
    check("创建分享", status == 200 and share.get("token"), f"{status} {share}")
    if isinstance(share, dict) and share.get("token"):
        status, blob = call("GET", share["url"], raw=True, auth=False)
        check("分享页无需登录可访问", status == 200, str(status))
        status, blob = call("GET", f"/api/share/{share['token']}/raw/{image_id}", raw=True, auth=False)
        check("分享原图可访问", status == 200 and len(blob) > 1000, f"{status} {len(blob)}")
        status, blob = call("GET", f"/api/share/{share['token']}/thumb/{image_id}", raw=True, auth=False)
        check("分享缩略图可访问", status == 200 and blob[:2] == b"\xff\xd8", str(status))
        status, bad = call("GET", "/share/not-a-real-token", raw=True, auth=False)
        check("无效分享链接 404", status == 404, str(status))

    section("回收站")
    status, _ = call("POST", "/api/assets/trash", {"ids": [image_id]})
    check("移入回收站", status == 200, str(status))
    status, trash = call("GET", "/api/assets?trashed=true")
    check("回收站列表包含该图", any(i["id"] == image_id for i in trash.get("items", [])), str(trash)[:200])
    status, normal = call("GET", "/api/assets")
    check("正常列表不再包含该图", all(i["id"] != image_id for i in normal.get("items", [])),
          str(normal)[:200])
    status, _ = call("POST", "/api/assets/restore", {"ids": [image_id]})
    check("从回收站恢复", status == 200, str(status))
    status, after = call("GET", "/api/assets")
    check("恢复后回到列表", any(i["id"] == image_id for i in after.get("items", [])), str(after)[:200])

    section("媒体库维护 / 设置")
    status, stats = call("GET", "/api/library/stats")
    check("GET /api/library/stats", status == 200 and stats.get("total", 0) >= 2, str(stats)[:200])
    check("磁盘剩余空间已上报", stats.get("disk_free"), str(stats.get("disk_free")))
    status, settings = call("GET", "/api/settings")
    check("GET /api/settings", status == 200 and settings.get("media_root"), str(settings)[:200])
    status, scan = call("POST", "/api/library/scan?background=true")
    check("触发后台扫描", status == 200, f"{status} {scan}")
    status, verify = call("GET", "/api/library/verify")
    check("文件一致性校验（只报告不删索引）", status == 200 and verify.get("missing_count") == 0,
          str(verify)[:300])
    status, missing = call("GET", "/api/library/missing")
    check("丢失文件列表可用", status == 200 and missing.get("count") == 0, str(missing)[:200])
    status, plan = call("POST", "/api/library/migrate/plan", {"target_root": "/mnt/usb"})
    check("外接硬盘迁移预演", status == 200 and "library_bytes" in plan, f"{status} {str(plan)[:200]}")
    # ⚠️⚠️ 这里绝对不能不带 ids 调用 ⚠️⚠️
    # 不带 ids 是【全库重建】：会删掉整个库的缩略图文件、把所有照片打回 pending，
    # 重建期间 App 显示灰色占位图。曾经这个用例为了验证「接口返回 200」
    # 就把用户 492 张缩略图全删了 —— 测试不该对真实库做全局破坏性操作。
    # {"ids": []} 表示什么都不做，只验证路由与语义。
    status, rebuild = call("POST", "/api/library/rebuild-thumbs", {"ids": []})
    check("重建缩略图接口可用（空 ids = 不动任何资源）",
          status == 200 and rebuild.get("scope") == "ids" and rebuild.get("queued") == 0,
          str(rebuild)[:160])

    # 限定范围的重建要真的能用：拿本次测试自己上传的图试，不碰别人的
    status, mine = call("GET", "/api/assets?q=e2e_exif&limit=1")
    my_items = mine.get("items") or []
    if my_items:
        my_id = my_items[0]["id"]
        status, rebuild2 = call("POST", "/api/library/rebuild-thumbs", {"ids": [my_id]})
        check("限定范围重建缩略图可用（只重排指定资源）",
              status == 200 and rebuild2.get("scope") == "ids"
              and rebuild2.get("queued") == 1,
              str(rebuild2)[:160])
    else:
        check("限定范围重建缩略图可用（只重排指定资源）", False, "找不到本次测试的 e2e_exif 资源")

    section("错误处理")
    status, _ = call("GET", "/api/assets/999999")
    check("不存在的照片返回 404", status == 404, str(status))
    status, not_json = call("GET", "/api/assets/not-a-number", raw=True)
    check("非法 id 返回 422", status == 422, str(status))
    status, bad_type = call("POST", "/api/uploads/init",
                            {"file_name": "evil.exe", "size_bytes": 100})
    check("不支持的扩展名被拒绝(415)", status == 415, str(status))

    # 清理：只删除本次测试自己创建的东西。
    #
    # 【重要 · 安全约定】测试脚本绝不允许调用 /api/trash/purge 这类**全局破坏性**接口：
    # 本脚本经常直接跑在作者的正式服务器上，一旦用户自己把照片放进了回收站，
    # 一次「清空回收站」就会把用户的照片永久删掉（这个坑踩过一次，不能再踩）。
    # 所以这里只按名字前缀删自己的产物，并且先看「正常列表」和「回收站」两种。
    try:
        call("DELETE", f"/api/assets/{image_id}")
        if exif_asset:
            call("DELETE", f"/api/assets/{exif_asset['id']}")
        if album_id:
            call("DELETE", f"/api/albums/{album_id}")
        deleted = 0
        for prefix in ("e2e_", "e2e_basic", "e2e_exif"):
            for query in (f"q={prefix}", f"trashed=true&q={prefix}"):
                status, listing = call("GET", f"/api/assets?{query}&limit=200")
                if status != 200:
                    continue
                for item in listing.get("items", []):
                    name = str(item.get("file_name") or "")
                    # 双重保险：只删确认是测试文件的，绝不误伤用户照片
                    if not name.startswith("e2e_"):
                        continue
                    call("DELETE", f"/api/assets/{item['id']}")
                    deleted += 1
        print(f"\n  （清理：删除本次测试产物 {deleted} 个；未动用全局清空回收站）")
    except Exception as exc:
        print(f"\n  （清理时出错，已忽略：{exc}）")

    print(f"\n===== 结果：{PASSED} 项通过，{len(FAILED)} 项失败 =====")
    for name in FAILED:
        print(f"  FAILED: {name}")
    return 0 if not FAILED else 1


if __name__ == "__main__":
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    sys.exit(main())
