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


def plain_get_status(url: str, token: str) -> int:
    """不带 Range 头的普通 GET，返回状态码。

    注意别拿 range_request(url, None, None) 当「整段请求」用 ——
    那会拼出 `Range: bytes=-None` 这种非法头，服务器按后缀区间解析失败返回 416。
    """
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            response.read(1024)
            return response.status
    except urllib.error.HTTPError as exc:
        return exc.code


def wait_queue_idle(timeout: float) -> bool:
    """等后台转码队列清空。

    媒体库里可能已经有别的转码任务在排队（比如刚导入的用户视频），
    挤在一起会让后面的用例超时误判。先等队列空下来，测量才有意义。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        status, stats = call("GET", "/api/library/stats")
        if status == 200 and not stats.get("queue_depth") and not stats.get("pending_thumbs"):
            return True
        time.sleep(5)
    return False


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
                    # 超时给足并记录真实耗时。
                    # 1080p 转 720p 在树莓派上是纯 CPU 活，媒体又放在 USB 机械盘上
                    # （读写约 80MB/s），大视频转码要几分钟。
                    # 之前给 240 秒：单独跑够用，但「连着跑完前几个套件、队列里还有任务」时
                    # 就不够，于是出现「单独跑能过、连跑就挂」的假失败。
                    # 先等队列清空再计时：否则会和库里其它转码任务抢 CPU，
                    # 900 秒也可能跑不完，那测的是「排队等了多久」而不是「转码要多久」。
                    idle = wait_queue_idle(1800)
                    check("后台转码队列已清空（下面的耗时才有意义）", idle,
                          "队列一直忙，耗时会被排队拉长")
                    deadline = time.time() + 900
                    started = time.time()
                    ready = False
                    while time.time() < deadline:
                        status, detail = call("GET", f"/api/assets/{big_id}")
                        if detail.get("has_proxy"):
                            ready = True
                            break
                        time.sleep(5)
                    elapsed = time.time() - started
                    check(f"已生成 720p 代理（has_proxy，耗时 {elapsed:.0f} 秒）", ready,
                          str(detail.get("proxy_state")))
                    if ready:
                        stream = BASE + f"/api/assets/{big_id}/stream"
                        status, headers, chunk = range_request(stream, 0, 1023, token)
                        check("代理流可 Range 播放", status == 206 and len(chunk) == 1024,
                              f"{status} {len(chunk)}")
                        status, headers, chunk = range_request(
                            BASE + f"/api/assets/{big_id}/stream?original=1", 0, 1023, token)
                        check("original=1 可强制播原片", status == 206, str(status))
                    call("DELETE", f"/api/assets/{big_id}")

    # ------------------------------------------------------------------
    section("非 ASCII 文件名（回归：弯引号/中文曾经让 /stream 直接 500）")
    # HTTP 头的值只能是 latin-1。文件名里的 ’ 和中文都编不出来，
    # 之前手拼 Content-Disposition 会让 Starlette 编码响应头时抛
    # UnicodeEncodeError，接口整个 500 —— 表现就是「文件名带弯引号或中文的
    # 视频完全播不了」，而原文件明明是好的。
    # 这个 bug 一直没被测出来，是因为原有用例全都用 e2e_big1080.mp4 这种纯 ASCII 名。
    tricky_names = [
        "e2e_弯引号’测试.mp4",       # 弯引号 U+2019 + 中文
        "e2e_中文字幕.mp4",           # 纯中文
        "e2e_ascii_baseline.mp4",     # 对照组：纯 ASCII
    ]
    with tempfile.TemporaryDirectory() as tmp2:
        src = Path(tmp2) / "src.mp4"
        if make_video(src, seconds=2, size="320x240"):
            payload = src.read_bytes()
            for tricky in tricky_names:
                # force=True：三份内容相同，否则会被 SHA-256 秒传拦掉，测不到
                status, result = upload_bytes(payload, tricky, force=True)
                asset = result.get("asset") if isinstance(result, dict) else None
                check(f"上传「{tricky}」", status == 200 and asset, f"{status} {str(result)[:150]}")
                if not asset:
                    continue
                aid = asset["id"]
                stream = BASE + f"/api/assets/{aid}/stream"
                status, headers, chunk = range_request(stream, 0, 1023, token)
                check(f"  含非 ASCII 文件名也能 Range 播放（{tricky}）",
                      status == 206 and len(chunk) == 1024, f"HTTP {status}")
                disp = _header(headers, "content-disposition") or ""
                check(f"  Content-Disposition 全部是 latin-1 可编码字符（{tricky}）",
                      bool(disp) and all(ord(c) < 256 for c in disp), disp[:70])
                check(f"  带上了 filename* 的 UTF-8 原名（{tricky}）",
                      "filename*=UTF-8''" in disp, disp[:70])
                # 不带 Range 的整段请求也要能过（播放入口会先发一次）
                whole_status = plain_get_status(stream, token)
                check(f"  整段请求（不带 Range）也正常（{tricky}）",
                      whole_status == 200, f"HTTP {whole_status}")
                call("DELETE", f"/api/assets/{aid}")
        else:
            check("能生成非 ASCII 用例的测试视频", False, "ffmpeg 造视频失败")

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
