"""Media probing / thumbnail generation / destructive-edit helpers.

Everything in here is defensive: a broken file must never break a scan or a
request, it only marks the asset as `unsupported`/`failed` with an error text.
"""
from __future__ import annotations

import datetime as dt
import json
import logging
import math
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageFilter, ImageOps

import config
from util import parse_shutter

log = logging.getLogger("photoalbum.media")

# HEIC / HEIF support is optional (pillow-heif or libheif's heif-convert).
try:  # pragma: no cover - optional dependency
    import pillow_heif

    pillow_heif.register_heif_opener()
    HEIF_VIA_PILLOW = True
except Exception:  # pragma: no cover
    HEIF_VIA_PILLOW = False

try:
    Image.MAX_IMAGE_PIXELS = 512_000_000
except Exception:  # pragma: no cover
    pass

FFMPEG = shutil.which("ffmpeg")
FFPROBE = shutil.which("ffprobe")
HEIF_CONVERT = shutil.which("heif-convert")

IMG_EXTS = config.IMAGE_EXTENSIONS
VID_EXTS = config.VIDEO_EXTENSIONS


# --------------------------------------------------------------------------
# ffprobe
# --------------------------------------------------------------------------
def ffprobe_json(path: Path) -> dict | None:
    if not FFPROBE:
        return None
    cmd = [
        FFPROBE, "-v", "error", "-print_format", "json",
        "-show_format", "-show_streams", str(path),
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=60, check=False)
        if out.returncode != 0:
            return None
        return json.loads(out.stdout.decode("utf-8", "replace") or "{}")
    except Exception as exc:  # pragma: no cover
        log.warning("ffprobe failed for %s: %s", path.name, exc)
        return None


def _rotation_from_probe(probe: dict) -> int:
    for stream in probe.get("streams", []):
        tags = stream.get("tags") or {}
        for key in ("rotate", "rotation"):
            if key in tags:
                try:
                    return int(float(tags[key])) % 360
                except (TypeError, ValueError):
                    pass
        for side in stream.get("side_data_list") or []:
            if "rotation" in side:
                try:
                    return int(float(side["rotation"])) % 360
                except (TypeError, ValueError):
                    pass
    return 0


def probe_video(path: Path) -> dict:
    """Return width/height/duration/rotation/codec for a video (best effort)."""
    info: dict = {"width": None, "height": None, "duration_ms": None, "codec": None, "rotation": 0, "fps": None}
    probe = ffprobe_json(path)
    if not probe:
        return info
    fmt = probe.get("format") or {}
    if fmt.get("duration"):
        try:
            info["duration_ms"] = int(float(fmt["duration"]) * 1000)
        except (TypeError, ValueError):
            pass
    for stream in probe.get("streams", []):
        if stream.get("codec_type") != "video":
            continue
        info["width"] = stream.get("width") or info["width"]
        info["height"] = stream.get("height") or info["height"]
        info["codec"] = stream.get("codec_name")
        rate = stream.get("avg_frame_rate") or stream.get("r_frame_rate")
        if rate and rate not in {"0/0"}:
            try:
                num, den = rate.split("/")
                if float(den):
                    info["fps"] = round(float(num) / float(den), 2)
            except Exception:
                pass
        # 容器级时长才是准的：流级 duration 在裁剪/拼接过的文件里经常缺失或不准，
        # 所以只在容器没有给出时长时才退回用流级。
        if not info["duration_ms"] and stream.get("duration"):
            try:
                info["duration_ms"] = int(float(stream["duration"]) * 1000)
            except (TypeError, ValueError):
                pass
        if stream.get("width") and stream.get("height"):
            info["rotation"] = _rotation_from_probe(probe)
        break
    if info["rotation"] in (90, 270):
        info["width"], info["height"] = info["height"], info["width"]
    return info


# --------------------------------------------------------------------------
# EXIF
# --------------------------------------------------------------------------
# EXIF 结构：主 IFD 里存 Make/Model/Orientation，拍摄参数在 EXIF 子 IFD(0x8769)，
# 定位信息在 GPS 子 IFD(0x8825)。子 IFD 必须用这两个标签号取，
# 不能用 get_ifd(1)/get_ifd(2)（那是 IFD0/IFD1，取不到拍摄参数）。
TAG_EXIF_IFD = 0x8769
TAG_GPS_IFD = 0x8825
TAG_ORIENTATION = 0x0112
TAG_MAKE = 0x010F
TAG_MODEL = 0x0110
TAG_DATETIME = 0x0132
TAG_DATETIME_ORIGINAL = 0x9003
TAG_DATETIME_DIGITIZED = 0x9004
TAG_OFFSET_TIME = 0x9010
TAG_OFFSET_TIME_ORIGINAL = 0x9011
TAG_ISO = 0x8827
TAG_FNUMBER = 0x829D
TAG_EXPOSURE = 0x829A
TAG_FOCAL = 0x920A
TAG_LENS = 0xA434


def _to_float(value) -> float | None:
    try:
        if isinstance(value, tuple) and len(value) == 2:
            num, den = float(value[0]), float(value[1])
            return num / den if den else None
        return float(value)
    except (TypeError, ValueError):
        return None


def _to_rational_text(value) -> str | None:
    try:
        if isinstance(value, tuple) and len(value) == 2:
            num, den = int(value[0]), int(value[1])
            if not den:
                return None
            g = math.gcd(num, den) or 1
            num, den = num // g, den // g
            return f"{num}/{den}" if den != 1 else str(num)
        if value is None:
            return None
        return str(value)
    except (TypeError, ValueError):
        return None


def _gps_to_decimal(coord, ref) -> float | None:
    try:
        parts = [_to_float(p) for p in coord]
        if len(parts) != 3 or any(p is None for p in parts):
            return None
        deg, minutes, seconds = parts
        value = deg + minutes / 60.0 + seconds / 3600.0
        if isinstance(ref, bytes):
            ref = ref.decode("ascii", "ignore")
        if str(ref).upper() in {"S", "W"}:
            value = -value
        return round(value, 7)
    except Exception:
        return None


def _parse_exif_datetime(raw, offset: str | None = None) -> dt.datetime | None:
    if not raw:
        return None
    if isinstance(raw, bytes):
        raw = raw.decode("ascii", "ignore")
    text = str(raw).strip()
    for fmt in ("%Y:%m:%d %H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y:%m:%d %H:%M", "%Y-%m-%dT%H:%M:%S"):
        try:
            naive = dt.datetime.strptime(text, fmt)
        except ValueError:
            continue
        if offset and re.fullmatch(r"[+-]\d{2}:\d{2}", str(offset)):
            try:
                sign = 1 if str(offset)[0] == "+" else -1
                hours, minutes = (int(x) for x in str(offset)[1:].split(":"))
                tz = dt.timezone(sign * dt.timedelta(hours=hours, minutes=minutes))
                return naive.replace(tzinfo=tz)
            except Exception:
                pass
        # Cameras write local wall-clock time. Assume the Pi's configured zone
        # so wall-clock and UTC stay consistent across the whole library.
        tz = dt.timezone(dt.timedelta(hours=8))
        return naive.replace(tzinfo=tz)
    return None


def read_exif(path: Path) -> dict:
    """Extract the EXIF fields we care about. Never raises."""
    result: dict = {
        "width": None, "height": None, "taken_at": None, "orientation": None,
        "camera_make": None, "camera_model": None, "lens": None, "iso": None,
        "f_number": None, "exposure": None, "focal_length": None,
        "gps_lat": None, "gps_lon": None,
    }
    try:
        with Image.open(path) as img:
            result["width"], result["height"] = img.size
            try:
                exif = img.getexif()
            except Exception:
                exif = None
            if not exif:
                return result

            def top(tag: int):
                """主 IFD（IFD0）里的标签。"""
                try:
                    return exif.get(tag)
                except Exception:
                    return None

            def sub(ifd_tag: int, tag: int):
                """子 IFD（EXIF / GPS）里的标签。"""
                try:
                    return (exif.get_ifd(ifd_tag) or {}).get(tag)
                except Exception:
                    return None

            orientation = top(TAG_ORIENTATION)
            if orientation:
                try:
                    result["orientation"] = int(orientation)
                except (TypeError, ValueError):
                    pass
            result["camera_make"] = top(TAG_MAKE) or None
            result["camera_model"] = top(TAG_MODEL) or None
            result["lens"] = sub(TAG_EXIF_IFD, TAG_LENS)

            taken = (
                sub(TAG_EXIF_IFD, TAG_DATETIME_ORIGINAL)
                or sub(TAG_EXIF_IFD, TAG_DATETIME_DIGITIZED)
                or top(TAG_DATETIME)
            )
            offset = (
                sub(TAG_EXIF_IFD, TAG_OFFSET_TIME_ORIGINAL)
                or sub(TAG_EXIF_IFD, TAG_OFFSET_TIME)
            )
            result["taken_at"] = _parse_exif_datetime(taken, offset)

            iso = sub(TAG_EXIF_IFD, TAG_ISO)
            if iso:
                try:
                    result["iso"] = int(iso if not isinstance(iso, (tuple, list)) else iso[0])
                except (TypeError, ValueError):
                    pass
            fnum = sub(TAG_EXIF_IFD, TAG_FNUMBER)
            if fnum is not None:
                result["f_number"] = _format_fnumber(fnum)
            exposure = sub(TAG_EXIF_IFD, TAG_EXPOSURE)
            if exposure is not None:
                result["exposure"] = parse_shutter(_to_float(exposure))
            focal = sub(TAG_EXIF_IFD, TAG_FOCAL)
            if focal is not None:
                value = _to_float(focal)
                if value:
                    result["focal_length"] = f"{value:g}mm"

            gps = None
            try:
                gps = exif.get_ifd(TAG_GPS_IFD)
            except Exception:
                gps = None
            if gps:
                result["gps_lat"] = _gps_to_decimal(gps.get(2), gps.get(1))
                result["gps_lon"] = _gps_to_decimal(gps.get(4), gps.get(3))
    except Exception as exc:
        log.debug("read_exif failed for %s: %s", path, exc)
    return result


def _format_fnumber(value) -> str | None:
    num = _to_float(value)
    if num is None:
        return None
    return f"f/{num:g}"


# --------------------------------------------------------------------------
# image loading / thumbs
# --------------------------------------------------------------------------
def load_image(path: Path) -> Image.Image:
    """Open an image, apply EXIF orientation, and return a standalone RGB copy."""
    with Image.open(path) as img:
        img.load()
        try:
            img = ImageOps.exif_transpose(img)
        except Exception:
            pass
        if img.mode in ("RGBA", "LA", "P"):
            background = Image.new("RGB", img.size, (255, 255, 255))
            converted = img.convert("RGBA")
            background.paste(converted, mask=converted.split()[-1])
            return background
        if img.mode != "RGB":
            return img.convert("RGB")
        return img.copy()


def load_image_via_heif(path: Path) -> Image.Image | None:
    """Fallback for HEIC on systems without pillow-heif."""
    if not HEIF_CONVERT:
        return None
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "out.jpg"
        try:
            proc = subprocess.run(
                [HEIF_CONVERT, str(path), str(out)],
                capture_output=True, timeout=120, check=False,
            )
            if proc.returncode != 0 or not out.exists():
                return None
            with Image.open(out) as img:
                img.load()
                return img.copy()
        except Exception:
            return None


def image_dimensions(path: Path) -> tuple[int, int] | None:
    try:
        with Image.open(path) as img:
            width, height = img.size
            orientation = 0
            try:
                orientation = int(img.getexif().get(0x0112) or 0)
            except Exception:
                orientation = 0
            if orientation in (5, 6, 7, 8):
                width, height = height, width
            return width, height
    except Exception:
        pass
    if path.suffix.lower() in {".heic", ".heif"}:
        img = load_image_via_heif(path)
        if img is not None:
            return img.size
    return None


def thumb_path(asset_id: int, size: int) -> Path:
    bucket = asset_id // 1000
    directory = config.thumbs_dir() / str(bucket)
    directory.mkdir(parents=True, exist_ok=True)
    return directory / f"{asset_id}_{size}.jpg"


def _save_jpeg(img: Image.Image, dest: Path, *, quality: int = 82) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".tmp")
    img.save(tmp, "JPEG", quality=quality, optimize=True, progressive=True)
    tmp.replace(dest)


def make_image_thumb(source: Path, asset_id: int, size: int) -> Path:
    dest = thumb_path(asset_id, size)
    try:
        img = load_image(source)
    except Exception:
        img = load_image_via_heif(source)
        if img is None:
            raise
    img.thumbnail((size, size), Image.LANCZOS)
    _save_jpeg(img, dest, quality=80 if size <= 256 else 84)
    return dest


def make_video_thumb(source: Path, asset_id: int, size: int, *, duration_ms: int | None = None) -> Path:
    if not FFMPEG:
        raise RuntimeError("ffmpeg 未安装，无法生成视频缩略图")
    dest = thumb_path(asset_id, size)
    dest.parent.mkdir(parents=True, exist_ok=True)
    seek = 1.0
    if duration_ms and duration_ms > 3000:
        seek = min(3.0, duration_ms / 4000.0)
    base = [
        FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
        "-ss", f"{seek:.2f}", "-i", str(source),
        "-frames:v", "1",
        "-vf", f"scale={size}:{size}:force_original_aspect_ratio=decrease",
        "-q:v", "4", str(dest),
    ]
    proc = subprocess.run(base, capture_output=True, timeout=180, check=False)
    if proc.returncode != 0 or not dest.exists():
        # Retry from the very first frame (very short clips, odd containers).
        fallback = [
            FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(source), "-frames:v", "1",
            "-vf", f"scale={size}:{size}:force_original_aspect_ratio=decrease",
            "-q:v", "4", str(dest),
        ]
        proc = subprocess.run(fallback, capture_output=True, timeout=180, check=False)
        if proc.returncode != 0 or not dest.exists():
            raise RuntimeError("ffmpeg 提取视频帧失败: " + proc.stderr.decode("utf-8", "replace")[-300:])
    return dest


def make_proxy(source: Path, asset_id: int, *, max_height: int | None = None) -> Path | None:
    """Transcode a video to a smaller H.264 MP4 for smooth mobile playback."""
    if not FFMPEG:
        return None
    max_height = max_height or config.VIDEO_PROXY_MAX_HEIGHT
    dest = config.cache_dir() / "proxy" / f"{asset_id}.mp4"
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".mp4.part")
    cmd = [
        FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
        "-vf", f"scale=-2:'min({max_height},ih)'",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "26",
        "-profile:v", "high", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        # 必须显式指定容器：ffmpeg 无法从 ".mp4.part" 推断出 mp4
        # （否则报 Unable to choose an output format / Invalid argument）
        "-f", "mp4",
        str(tmp),
    ]
    proc = subprocess.run(cmd, capture_output=True, timeout=60 * 60, check=False)
    if proc.returncode != 0 or not tmp.exists():
        log.warning("proxy transcode failed for %s: %s", source.name, proc.stderr.decode("utf-8", "replace")[-200:])
        tmp.unlink(missing_ok=True)
        return None
    tmp.replace(dest)
    return dest


# --------------------------------------------------------------------------
# editing
# --------------------------------------------------------------------------
def edit_image(
    source: Path,
    dest: Path,
    *,
    rotate: int = 0,
    flip_h: bool = False,
    flip_v: bool = False,
    crop: tuple[float, float, float, float] | None = None,
    brightness: float = 1.0,
    contrast: float = 1.0,
    saturation: float = 1.0,
    filter_name: str | None = None,
    quality: int = 92,
) -> tuple[int, int]:
    """Apply edits and write the result to *dest* (same directory, atomic swap).

    操作顺序与 App 预览一致：先旋转/翻转（几何方向），再按当前显示的画面裁剪，
    最后做调色/滤镜。这样用户在手机上框选的范围和服务器裁出来的完全一致。
    """
    img = load_image(source)

    if rotate:
        img = img.rotate(-rotate, expand=True)
    if flip_h:
        img = ImageOps.mirror(img)
    if flip_v:
        img = ImageOps.flip(img)

    if crop:
        x0, y0, x1, y1 = crop
        width, height = img.size
        left = max(0, min(width - 1, int(round(x0 * width))))
        top = max(0, min(height - 1, int(round(y0 * height))))
        right = max(left + 1, min(width, int(round(x1 * width))))
        bottom = max(top + 1, min(height, int(round(y1 * height))))
        img = img.crop((left, top, right, bottom))

    if abs(brightness - 1.0) > 1e-3 or abs(contrast - 1.0) > 1e-3:
        from PIL import ImageEnhance

        if abs(brightness - 1.0) > 1e-3:
            img = ImageEnhance.Brightness(img).enhance(brightness)
        if abs(contrast - 1.0) > 1e-3:
            img = ImageEnhance.Contrast(img).enhance(contrast)
    if abs(saturation - 1.0) > 1e-3:
        from PIL import ImageEnhance

        img = ImageEnhance.Color(img).enhance(saturation)
    if filter_name:
        img = apply_filter(img, filter_name)

    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".edit")
    img.save(tmp, "JPEG", quality=quality, optimize=True, progressive=True, exif=b"")
    tmp.replace(dest)
    return img.size


def apply_filter(img: Image.Image, name: str) -> Image.Image:
    name = (name or "").lower()
    if name in {"", "none", "original"}:
        return img
    if name == "grayscale":
        return ImageOps.grayscale(img).convert("RGB")
    if name == "sepia":
        gray = ImageOps.grayscale(img).convert("RGB")
        return Image.blend(gray, ImageOps.colorize(ImageOps.grayscale(img), "#2b1a06", "#ffe6bf").convert("RGB"), 0.85)
    if name == "warm":
        r, g, b = img.split()
        r = r.point(lambda v: min(255, int(v * 1.08)))
        b = b.point(lambda v: int(v * 0.94))
        return Image.merge("RGB", (r, g, b))
    if name == "cool":
        r, g, b = img.split()
        r = r.point(lambda v: int(v * 0.94))
        b = b.point(lambda v: min(255, int(v * 1.08)))
        return Image.merge("RGB", (r, g, b))
    if name == "vivid":
        from PIL import ImageEnhance

        img = ImageEnhance.Color(img).enhance(1.35)
        return ImageEnhance.Contrast(img).enhance(1.08)
    if name == "blur":
        return img.filter(ImageFilter.GaussianBlur(1.2))
    if name == "sharpen":
        return img.filter(ImageFilter.UnsharpMask(radius=2, percent=140, threshold=3))
    return img


def trim_video(source: Path, dest: Path, *, start_ms: int, end_ms: int) -> bool:
    """Trim a video with a stream copy (fast, keyframe aligned)."""
    if not FFMPEG:
        return False
    start = max(0, start_ms) / 1000.0
    duration = max(0, end_ms - start_ms) / 1000.0
    if duration <= 0:
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".trim")
    # 同样必须显式 -f mp4：临时文件名结尾是 ".mp4.trim"，ffmpeg 认不出来
    cmd = [
        FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
        "-ss", f"{start:.3f}", "-i", str(source), "-t", f"{duration:.3f}",
        "-c", "copy", "-avoid_negative_ts", "make_zero", "-movflags", "+faststart",
        "-f", "mp4", str(tmp),
    ]
    proc = subprocess.run(cmd, capture_output=True, timeout=60 * 30, check=False)
    if proc.returncode != 0 or not tmp.exists():
        tmp.unlink(missing_ok=True)
        # 流复制失败（关键帧不齐 / 音视频不同步）时重编码，保证用户拿到裁剪结果
        cmd = [
            FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
            "-ss", f"{start:.3f}", "-i", str(source), "-t", f"{duration:.3f}",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
            "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-movflags", "+faststart", "-f", "mp4", str(tmp),
        ]
        proc = subprocess.run(cmd, capture_output=True, timeout=60 * 60, check=False)
        if proc.returncode != 0 or not tmp.exists():
            log.warning("trim failed for %s: %s", source.name, proc.stderr.decode("utf-8", "replace")[-300:])
            tmp.unlink(missing_ok=True)
            return False
    tmp.replace(dest)
    return True


@dataclass
class ProbeResult:
    media_type: str
    width: int | None = None
    height: int | None = None
    duration_ms: int | None = None
    taken_at: dt.datetime | None = None
    exif: dict = field(default_factory=dict)
    extra: dict = field(default_factory=dict)


def probe(path: Path, media_type: str) -> ProbeResult:
    if media_type == "video":
        info = probe_video(path)
        return ProbeResult(
            media_type="video",
            width=info.get("width"),
            height=info.get("height"),
            duration_ms=info.get("duration_ms"),
            extra={"codec": info.get("codec"), "fps": info.get("fps")},
        )
    exif = read_exif(path)
    width, height = exif.get("width"), exif.get("height")
    if exif.get("orientation") in (5, 6, 7, 8) and width and height:
        width, height = height, width
    return ProbeResult(
        media_type="image",
        width=width,
        height=height,
        taken_at=exif.get("taken_at"),
        exif=exif,
    )
