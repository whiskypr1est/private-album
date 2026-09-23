"""Runtime configuration for the private photo album server.

Every value can be overridden with an environment variable, and any value that
lives in the database (data dir, thumb size) can be changed later from the app's
settings screen -- that is how we will move media onto the external HDD.
"""
from __future__ import annotations

import os
import secrets
from pathlib import Path

APP_NAME = "PhotoAlbum"
APP_VERSION = "1.1.0"

# --- paths ---------------------------------------------------------------
HOME = Path(os.environ.get("PHOTOALBUM_HOME", Path.home() / "photoalbum"))
CONFIG_DIR = Path(os.environ.get("PHOTOALBUM_CONFIG_DIR", HOME))
DATA_DIR = Path(os.environ.get("PHOTOALBUM_DATA_DIR", HOME / "data"))

DB_PATH = Path(os.environ.get("PHOTOALBUM_DB", DATA_DIR / "album.db"))
SECRET_PATH = Path(os.environ.get("PHOTOALBUM_SECRET_FILE", CONFIG_DIR / ".secret"))
LOG_DIR = Path(os.environ.get("PHOTOALBUM_LOG_DIR", HOME / "logs"))

# --- network -------------------------------------------------------------
HOST = os.environ.get("PHOTOALBUM_HOST", "0.0.0.0")
PORT = int(os.environ.get("PHOTOALBUM_PORT", "8080"))

# --- security ------------------------------------------------------------
TOKEN_TTL_SECONDS = int(os.environ.get("PHOTOALBUM_TOKEN_TTL", str(60 * 60 * 24 * 60)))
SHARE_TTL_DEFAULT_SECONDS = int(os.environ.get("PHOTOALBUM_SHARE_TTL", str(60 * 60 * 24 * 30)))
MIN_PASSWORD_LENGTH = 6

# --- uploads -------------------------------------------------------------
MAX_UPLOAD_BYTES = int(os.environ.get("PHOTOALBUM_MAX_UPLOAD", str(64 * 1024 * 1024 * 1024)))
UPLOAD_TMP_DIR = DATA_DIR / "tmp"

# --- media processing ----------------------------------------------------
THUMB_SIZES = (256, 1024)          # grid size, preview size
DEFAULT_THUMB_SIZE = 256
VIDEO_PROXY_MAX_HEIGHT = 720       # larger videos get a 720p proxy for smooth playback
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif", ".tif", ".tiff"}
VIDEO_EXTENSIONS = {".mp4", ".mov", ".m4v", ".3gp", ".mkv", ".webm", ".avi", ".ts"}

# --- behaviour -----------------------------------------------------------
CORS_ORIGINS = [o for o in os.environ.get("PHOTOALBUM_CORS", "*").split(",") if o]
SERVE_WEB_UI = os.environ.get("PHOTOALBUM_WEB_UI", "1") not in {"0", "false", "no"}
HOME_TZ = os.environ.get("PHOTOALBUM_TZ", "Asia/Shanghai")


def ensure_dirs() -> None:
    for path in (HOME, CONFIG_DIR, DATA_DIR, DB_PATH.parent, LOG_DIR, UPLOAD_TMP_DIR):
        path.mkdir(parents=True, exist_ok=True)


def load_secret() -> bytes:
    """Persistent HMAC secret used to sign login tokens."""
    env = os.environ.get("PHOTOALBUM_SECRET")
    if env:
        return env.encode("utf-8")
    SECRET_PATH.parent.mkdir(parents=True, exist_ok=True)
    if SECRET_PATH.exists():
        raw = SECRET_PATH.read_bytes().strip()
        if raw:
            return raw
    raw = secrets.token_urlsafe(48).encode("ascii")
    SECRET_PATH.write_bytes(raw)
    try:
        SECRET_PATH.chmod(0o600)
    except OSError:
        pass
    return raw


def media_dir() -> Path:
    return DATA_DIR / "media"


def thumbs_dir() -> Path:
    return DATA_DIR / "thumbs"


def cache_dir() -> Path:
    return DATA_DIR / "cache"
