"""Small shared helpers: hashing, timestamps, filename hygiene."""
from __future__ import annotations

import base64
import datetime as dt
import hashlib
import hmac
import mimetypes
import os
import re
import secrets
import unicodedata
from pathlib import Path

import config

PBKDF2_ROUNDS = 150_000

_ILLEGAL = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_MULTI_US = re.compile(r"_{2,}")


# --------------------------------------------------------------------------
# passwords
# --------------------------------------------------------------------------
def hash_password(password: str, *, rounds: int = PBKDF2_ROUNDS) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, rounds)
    return "pbkdf2_sha256${}${}${}".format(
        rounds,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(dk).decode("ascii"),
    )


def verify_password(password: str, encoded: str) -> bool:
    try:
        algo, rounds_s, salt_b64, hash_b64 = encoded.split("$")
        if algo != "pbkdf2_sha256":
            return False
        rounds = int(rounds_s)
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(hash_b64)
    except Exception:
        return False
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, rounds)
    return hmac.compare_digest(dk, expected)


# --------------------------------------------------------------------------
# time
# --------------------------------------------------------------------------
def utcnow() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def iso(d: dt.datetime | None) -> str | None:
    if d is None:
        return None
    if d.tzinfo is None:
        d = d.replace(tzinfo=dt.timezone.utc)
    return d.astimezone(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def now_iso() -> str:
    return iso(utcnow())  # type: ignore[return-value]


def parse_iso(value: str | None) -> dt.datetime | None:
    if not value:
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    for fmt in (None, "%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%Y/%m/%d %H:%M:%S", "%Y:%m:%d %H:%M:%S"):
        try:
            if fmt is None:
                parsed = dt.datetime.fromisoformat(text)
            else:
                parsed = dt.datetime.strptime(text, fmt)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=dt.timezone.utc)
            return parsed
        except ValueError:
            continue
    return None


def parse_shutter(value) -> str | None:
    if value is None:
        return None
    try:
        num = float(value)
    except (TypeError, ValueError):
        return str(value)
    if num <= 0:
        return None
    if num >= 1:
        return f"{num:g}s"
    return f"1/{round(1 / num)}s"


# --------------------------------------------------------------------------
# media naming
# --------------------------------------------------------------------------
def sanitize_name(name: str, *, fallback: str = "file") -> str:
    name = os.path.basename(name.replace("\\", "/"))
    name = unicodedata.normalize("NFC", name)
    name = _ILLEGAL.sub("_", name).strip().strip(".")
    name = _MULTI_US.sub("_", name)
    if not name:
        name = fallback
    if len(name) > 180:
        stem, ext = os.path.splitext(name)
        name = stem[: 180 - len(ext)] + ext
    return name


def unique_name(directory: Path, name: str) -> str:
    """Return a file name inside *directory* that does not exist yet."""
    stem, ext = os.path.splitext(name)
    candidate = name
    index = 1
    while (directory / candidate).exists():
        candidate = f"{stem}_{index}{ext}"
        index += 1
    return candidate


def guess_mime(path: Path) -> str:
    ext = path.suffix.lower()
    special = {
        ".heic": "image/heic",
        ".heif": "image/heif",
        ".avif": "image/avif",
        ".webp": "image/webp",
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".png": "image/png",
        ".gif": "image/gif",
        ".bmp": "image/bmp",
        ".tif": "image/tiff",
        ".tiff": "image/tiff",
        ".mp4": "video/mp4",
        ".m4v": "video/x-m4v",
        ".mov": "video/quicktime",
        ".mkv": "video/x-matroska",
        ".webm": "video/webm",
        ".avi": "video/x-msvideo",
        ".3gp": "video/3gpp",
        ".ts": "video/mp2t",
    }
    if ext in special:
        return special[ext]
    guessed, _ = mimetypes.guess_type(str(path))
    return guessed or "application/octet-stream"


def media_type_for(path: Path) -> str | None:
    ext = path.suffix.lower()
    if ext in config.IMAGE_EXTENSIONS:
        return "image"
    if ext in config.VIDEO_EXTENSIONS:
        return "video"
    return None


def sha256_file(path: Path, *, chunk: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            block = handle.read(chunk)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def human_size(num: int) -> str:
    step = float(num)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if step < 1024 or unit == "TB":
            return f"{step:.0f} {unit}" if unit == "B" else f"{step:.1f} {unit}"
        step /= 1024
    return f"{num} B"


# --------------------------------------------------------------------------
# 自然排序（让 2.png 排在 10.png 前面）
# --------------------------------------------------------------------------
_NUM_IN_NAME = re.compile(r"(\d+)")


def natural_sort_key(name: str) -> str:
    """把文件名转成可以直接用字典序比较的「自然排序键」。

    纯字典序会把 10.png 排在 2.png 前面（因为 '1' < '2'），
    这对按序号命名的照片（1.png、2.png、……、10.png）完全不可接受。

    做法：把名字里的每段连续数字左边补 0 到固定宽度（12 位），
    这样字典序 = 数值序；同时大小写统一，避免 A.png / a.png 的意外分堆。

        1.png   -> 000000000001.png
        2.png   -> 000000000002.png
        10.png  -> 000000000010.png
        100.png -> 000000000100.png

    排序结果：1.png < 2.png < 10.png < 100.png ✓
    """
    text = (name or "").lower()
    parts = []
    last = 0
    for match in _NUM_IN_NAME.finditer(text):
        parts.append(text[last:match.start()])
        digits = match.group(1)
        # 超长数字（>12 位）就不再补零，直接原样比，避免键过长
        parts.append(digits.zfill(12) if len(digits) <= 12 else digits)
        last = match.end()
    parts.append(text[last:])
    return "".join(parts)
