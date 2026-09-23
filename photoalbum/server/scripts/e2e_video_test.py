#!/usr/bin/env python3
"""视频 / HEIC 链路端到端测试。

与 e2e_test.py 分开，是因为这些用例依赖 ffmpeg（视频缩略图、转码、裁剪）；
没装 ffmpeg 时会明确跳过并给出提示，而不是假装通过。

【安全约定】本脚本只删除自己上传的资源，绝不调用 /api/trash/purge
等全局破坏性接口 —— 它可能跑在正式服务器上，不能碰用户的照片。

用法： venv/bin/python scripts/e2e_video_test.py [base_url]
"""
from __future__ import annotations

import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from e2e_test import BASE, PASSWORD, USER, call, check, section, upload_bytes  # noqa: E402

import e2e_test as base  # noqa: E402


def make_video(path: Path, seconds: int = 3, size: str = "640x480") -> bool:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        return False
    cmd = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"testsrc=size={size}:rate=25:duration={seconds}",
        "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-shortest", "-movflags", "+faststart",
        str(path),
    ]
    proc = subprocess.run(cmd, capture_output=True, timeout=300, check=False)
    return proc.returncode == 0 and path.exists() and path.stat().st_size > 1000


def make_large_video(path: Path) -> bool:
    """生成一段 1080p 视频，用来触发服务器端自动转码成 720p 代理。"""
    return make_video(path, seconds=2, size="1920x1080")


def _header(headers, name: str) -> str | None:
    """urllib 的 headers 是大小写不敏感映射，转成 dict 后要自己忽略大小写。"""
    for key, value in headers.items():
        if str(key).lower() == name.lower():
            return value
    return None


def range_request(url: str, start: int | None, end: int | None, token: str) -> tuple[int, dict, bytes]:
    """构造并发送 Range 请求。

    start=None, end=512  -> bytes=-512（后缀区间：最后 512 字节）
    start=0,    end=1023 -> bytes=0-1023
    start=4096, end=None -> bytes=4096-
    """
    if start is None:
        spec = f"-{end}"
    elif end is None:
        spec = f"{start}-"
    else:
        spec = f"{start}-{end}"
    request = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token,
        "Range": f"bytes={spec}",
    })
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read()


def main() -> int:
    section("登录")
    status, login = call("POST", "/api/login", {"username": USER, "password": PASSWORD}, auth=False)
    if status != 200:
        print(f"  登录失败：{status} {login}")
        return 1
    base.TOKEN = login["token"]
    token = login["token"]
    check("登录成功", True)

    has_ffmpeg = bool(shutil.which("ffmpeg"))
    if not has_ffmpeg:
        print("\n  [跳过] 未安装 ffmpeg，视频相关用例无法执行。")
        print("  安装： sudo apt-get install -y ffmpeg")
    else:
        section("视频上传与处理")
        with tempfile.TemporaryDirectory() as tmp:
            small = Path(tmp) / "e2e_video.mp4"
            if not make_video(small):
                print("  [跳过] 本地 ffmpeg 生成测试视频失败")
            else:
                payload = small.read_bytes()
                status, result = upload_bytes(payload, "e2e_video.mp4")
                video = result.get("asset") if isinstance(result, dict) else None
                check("上传 MP4", status == 200 and video, f"{status} {str(result)[:200]}")

                if video:
                    video_id = video["id"]
                    check("识别为 video 类型", video.get("media_type") == "video", str(video.get("media_type")))
                    check("时长被解析", (video.get("duration_ms") or 0) > 1000, str(video.get("duration_ms")))
                    check("分辨率被解析", video.get("width") == 640 and video.get("height") == 480,
                          f"{video.get('width')}x{video.get('height')}")

                    deadline = time.time() + 90
                    thumb_ok = False
                    while time.time() < deadline:
                        status, blob = call("GET", f"/api/assets/{video_id}/thumb?size=256", raw=True)
                        if status == 200 and blob[:2] == b"\xff\xd8" and len(blob) > 500:
                            thumb_ok = True
                            break
                        time.sleep(3)
                    check("视频缩略图（ffmpeg 抽帧）已生成", thumb_ok)

                    # Range 请求：VideoView / ExoPlayer 拖动进度条就靠它
                    stream = BASE + f"/api/assets/{video_id}/stream"
                    status, headers, chunk = range_request(stream, 0, 1023, token)
                    check("Range 请求返回 206", status == 206, str(status))
                    check("Content-Range 正确",
                          str(_header(headers, "content-range") or "").startswith("bytes 0-1023/"),
                          str(_header(headers, "content-range")))
                    check("返回 1024 字节", len(chunk) == 1024, str(len(chunk)))
                    check("支持 Accept-Ranges", _header(headers, "accept-ranges") == "bytes",
                          str(_header(headers, "accept-ranges")))

                    status, headers, middle = range_request(stream, 4096, 8191, token)
                    check("中间区间 Range 可用", status == 206 and len(middle) == 4096,
                          f"{status} {len(middle)}")

                    status, headers, tail = range_request(stream, None, 512, token)
                    check("后缀 Range（bytes=-512）可用", status == 206 and len(tail) == 512,
                          f"{status} {len(tail)}")
                    check("后缀 Range 的 Content-Range 指向文件末尾",
                          str(_header(headers, "content-range") or "").startswith(
                              f"bytes {max(0, video['size_bytes'] - 512)}-"),
                          str(_header(headers, "content-range")))

                    status, headers, open_ended = range_request(stream, 1000, None, token)
                    check("开放区间 Range（bytes=1000-）可用", status == 206 and len(open_ended) > 0,
                          f"{status} {len(open_ended)}")

                    # 越界 Range 必须回 416
                    total_size = video.get("size_bytes", 0)
                    request = urllib.request.Request(stream, headers={
                        "Authorization": "Bearer " + token,
                        "Range": f"bytes={total_size + 10}-{total_size + 20}",
                    })
                    try:
                        with urllib.request.urlopen(request, timeout=30) as response:
                            over_status = response.status
                    except urllib.error.HTTPError as exc:
                        over_status = exc.code
                    check("越界 Range 返回 416", over_status == 416, str(over_status))

                    # 视频裁剪（ffmpeg 流复制）：接口返回的 asset 必须是刷新后的数据
                    status, trimmed = call("POST", f"/api/assets/{video_id}/trim",
                                           {"start_ms": 500, "end_ms": 2000})
                    check("视频裁剪成功", status == 200 and trimmed.get("asset"), f"{status} {str(trimmed)[:200]}")
                    if status == 200:
                        response_duration = trimmed["asset"].get("duration_ms") or 0
                        check("裁剪响应里的时长立即更新", 0 < response_duration < 3000, str(response_duration))
                        status, reread = call("GET", f"/api/assets/{video_id}")
                        check("重新查询时长也变短", 0 < (reread.get("duration_ms") or 0) < 3000,
                              str(reread.get("duration_ms")))

                    call("DELETE", f"/api/assets/{video_id}")

        section("大视频自动转码成 720p 代理")
        with tempfile.TemporaryDirectory() as tmp:
            big = Path(tmp) / "e2e_big1080.mp4"
            if make_large_video(big):
                status, result = upload_bytes(big.read_bytes(), "e2e_big1080.mp4")
                asset = result.get("asset") if isinstance(result, dict) else None
                check("上传 1080p 视频", status == 200 and asset, str(status))
                if asset:
                    big_id = asset["id"]
                    deadline = time.time() + 240
                    ready = False
                    while time.time() < deadline:
                        status, detail = call("GET", f"/api/assets/{big_id}")
                        if detail.get("has_proxy"):
                            ready = True
                            break
                        time.sleep(5)
                    check("已生成 720p 代理（has_proxy）", ready, str(detail.get("proxy_state")))
                    if ready:
                        stream = BASE + f"/api/assets/{big_id}/stream"
                        status, headers, chunk = range_request(stream, 0, 1023, token)
                        check("代理流可 Range 播放", status == 206 and len(chunk) == 1024,
                              f"{status} {len(chunk)}")
                        status, headers, chunk = range_request(
                            BASE + f"/api/assets/{big_id}/stream?original=1", 0, 1023, token)
                        check("original=1 可强制播原片", status == 206, str(status))
                    call("DELETE", f"/api/assets/{big_id}")

    section("HEIC（iPhone 照片）")
    try:
        import pillow_heif
        from PIL import Image

        img = Image.new("RGB", (1200, 900), (200, 120, 40))
        buffer = io.BytesIO()
        saved = False
        for fmt in ("HEIF", "HEIC"):
            try:
                img.save(buffer, format=fmt, quality=80)
                saved = True
                break
            except Exception:
                buffer = io.BytesIO()
        if not saved:
            # 直接走 pillow_heif 自己的 API
            buffer = io.BytesIO()
            pillow_heif.from_pillow(img).save(buffer)
            saved = True
        check("能生成 HEIC 测试数据（pillow-heif " + pillow_heif.__version__ + "）", saved)
        status, result = upload_bytes(buffer.getvalue(), "e2e_iphone.heic")
        heic = result.get("asset") if isinstance(result, dict) else None
        check("上传 HEIC", status == 200 and heic, f"{status} {str(result)[:200]}")
        if heic:
            deadline = time.time() + 60
            ok = False
            while time.time() < deadline:
                status, blob = call("GET", f"/api/assets/{heic['id']}/thumb?size=256", raw=True)
                if status == 200 and blob[:2] == b"\xff\xd8" and len(blob) > 300:
                    ok = True
                    break
                time.sleep(2)
            check("HEIC 能生成 JPEG 缩略图", ok)
            call("DELETE", f"/api/assets/{heic['id']}")
    except ImportError:
        print("  [跳过] 服务端没有 pillow-heif，HEIC 用例无法执行")

    print(f"\n===== 结果：{base.PASSED} 项通过，{len(base.FAILED)} 项失败 =====")
    for name in base.FAILED:
        print(f"  FAILED: {name}")
    return 0 if not base.FAILED else 1


if __name__ == "__main__":
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    sys.exit(main())
