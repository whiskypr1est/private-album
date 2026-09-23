"""Media library: path layout, indexing, scanning, background processing.

Layout (all under the configurable media root, today the SD card):

    media/2025/07/20250731_181233_ab12cd.jpg      originals, bucketed by date
    media/undated/IMG_1234.jpg                    files without a usable date
    thumbs/<id//1000>/<id>_<size>.jpg             derived images (cheap to rebuild)
    cache/proxy/<id>.mp4                          transcoded videos for mobile
    tmp/<upload_id>.part                          in-flight chunked uploads

Thumbnails/cache/proxies live beside `media/` but outside it, so moving the
library to the external HDD only has to move the `media` subtree.
"""
from __future__ import annotations

import datetime as dt
import logging
import mimetypes
import os
import shutil
import threading
import time
from pathlib import Path
from typing import Iterable

import config
import media as media_ops
from db import db
from util import (
    guess_mime,
    media_type_for,
    natural_sort_key,
    now_iso,
    sanitize_name,
    sha256_file,
    unique_name,
)

log = logging.getLogger("photoalbum.library")

_tz = dt.timezone(dt.timedelta(hours=8))  # library wall clock (Asia/Shanghai)

# --------------------------------------------------------------------------
# locations (DB backed so the external disk can be pointed at later)
# --------------------------------------------------------------------------
SETTING_MEDIA_ROOT = "media_root"
SETTING_PROCESS_VIDEO = "process_video"
SETTING_AUTO_PROXY = "auto_proxy"
SETTING_ORGANIZE = "organize_by_date"


def media_root() -> Path:
    raw = db.get_setting(SETTING_MEDIA_ROOT, str(config.media_dir()))
    return Path(raw or config.media_dir())


def set_media_root(path: Path) -> None:
    db.set_setting(SETTING_MEDIA_ROOT, str(Path(path)))


def organize_by_date() -> bool:
    return db.get_setting(SETTING_ORGANIZE, "1") == "1"


def auto_proxy() -> bool:
    return db.get_setting(SETTING_AUTO_PROXY, "1") == "1"


def process_video_enabled() -> bool:
    return db.get_setting(SETTING_PROCESS_VIDEO, "1") == "1"


def ensure_layout() -> None:
    for directory in (
        media_root(),
        media_root() / "undated",
        config.thumbs_dir(),
        config.cache_dir() / "proxy",
        config.UPLOAD_TMP_DIR,
    ):
        directory.mkdir(parents=True, exist_ok=True)


def abs_path(rel_path: str) -> Path:
    return media_root() / rel_path


def rel_for(path: Path) -> str:
    return path.relative_to(media_root()).as_posix()


def dest_rel_path(taken_at: dt.datetime | None, file_name: str) -> str:
    """Where a new original should live."""
    if taken_at is None or not organize_by_date():
        return f"undated/{file_name}"
    local = taken_at.astimezone(_tz)
    return f"{local.year:04d}/{local.month:02d}/{file_name}"


# --------------------------------------------------------------------------
# date helpers
# --------------------------------------------------------------------------
_DATE_PATTERNS = (
    "%Y%m%d_%H%M%S", "%Y-%m-%d %H-%M-%S", "%Y-%m-%d %H.%M.%S",
    "%Y%m%d%H%M%S", "%Y-%m-%d_%H-%M-%S", "%Y%m%d-%H%M%S",
)
_DATE_ONLY = ("%Y%m%d", "%Y-%m-%d", "%Y_%m_%d")


def _sample_for(pattern: str) -> str:
    """'%Y%m%d_%H%M%S' -> '20000101_000000' so we know how many chars to slice."""
    out = pattern
    for code, value in (("%Y", "2000"), ("%m", "01"), ("%d", "01"),
                        ("%H", "00"), ("%M", "00"), ("%S", "00")):
        out = out.replace(code, value)
    return out


def date_from_filename(name: str) -> dt.datetime | None:
    stem = Path(name).stem
    for pattern in _DATE_PATTERNS:
        candidate = stem[: len(_sample_for(pattern))]
        try:
            return dt.datetime.strptime(candidate, pattern).replace(tzinfo=_tz)
        except ValueError:
            continue
    for pattern in _DATE_ONLY:
        candidate = stem[: len(_sample_for(pattern))]
        try:
            return dt.datetime.strptime(candidate, pattern).replace(tzinfo=_tz)
        except ValueError:
            continue
    # Try to find a date substring anywhere (IMG_20250731_181233.jpg)
    import re

    match = re.search(r"(20\d{2})[-_]?(\d{2})[-_]?(\d{2})", stem)
    if match:
        year, month, day = (int(g) for g in match.groups())
        time_match = re.search(r"[_\-T ](\d{2})[-_:.]?(\d{2})[-_:.]?(\d{2})", stem[match.end():])
        try:
            if time_match and all(int(g) < 60 for g in time_match.groups()):
                hour, minute, second = (int(g) for g in time_match.groups())
                return dt.datetime(year, month, day, hour, minute, second, tzinfo=_tz)
            return dt.datetime(year, month, day, tzinfo=_tz)
        except ValueError:
            return None
    return None


# --------------------------------------------------------------------------
# indexing
# --------------------------------------------------------------------------
def upsert_asset(
    path: Path,
    *,
    rel_path: str | None = None,
    taken_at: dt.datetime | None = None,
    taken_src: str | None = None,
    compute_sha: bool = False,
    client_mtime: str | None = None,
    upload_batch: str | None = None,
    sort_order: int | None = None,
) -> int | None:
    """Insert or refresh one file. Returns the asset id (None if not media)."""
    rel = rel_path or rel_for(path)
    mt = media_type_for(path)
    if mt is None:
        return None
    try:
        stat = path.stat()
    except OSError:
        return None
    mime = guess_mime(path)
    existing = db.query_one("SELECT * FROM assets WHERE rel_path=?", (rel,))

    exif: dict = {}
    width = height = duration = None
    if mt == "image":
        info = media_ops.probe(path, "image")
        width, height = info.width, info.height
        exif = info.exif or {}
        if taken_at is None and exif.get("taken_at"):
            taken_at, taken_src = exif["taken_at"], "exif"
    else:
        info = media_ops.probe(path, "video")
        width, height, duration = info.width, info.height, info.duration_ms

    if taken_at is None:
        from_filename = date_from_filename(path.name)
        if from_filename:
            taken_at, taken_src = from_filename, "filename"
    if taken_at is None:
        taken_at = dt.datetime.fromtimestamp(stat.st_mtime, _tz)
        taken_src = taken_src or "mtime"
    if taken_src is None:
        taken_src = "mtime"

    taken_iso = taken_at.astimezone(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    timestamp = now_iso()
    sha = sha256_file(path) if compute_sha else (existing["sha256"] if existing else None)

    fields = dict(
        rel_path=rel,
        file_name=path.name,
        ext=path.suffix.lower(),
        media_type=mt,
        mime=mime,
        size_bytes=stat.st_size,
        sha256=sha,
        width=width,
        height=height,
        duration_ms=duration,
        taken_at=taken_iso,
        taken_src=taken_src,
        camera_make=exif.get("camera_make"),
        camera_model=exif.get("camera_model"),
        lens=exif.get("lens"),
        iso=exif.get("iso"),
        f_number=exif.get("f_number"),
        exposure=exif.get("exposure"),
        focal_length=exif.get("focal_length"),
        gps_lat=exif.get("gps_lat"),
        gps_lon=exif.get("gps_lon"),
        orientation=exif.get("orientation"),
        updated_at=timestamp,
    )

    # 排序键的三种情况（这里最容易出错，别简化）：
    #   1. 本次上传明确要求「保留源顺序」 -> 用序号，并打上锁
    #   2. 已有行带着锁（之前选过源顺序）  -> 原样保留，绝不能被文件名重算冲掉，
    #      否则一次重扫就把用户选的顺序毁了
    #   3. 其它情况 -> 按文件名算自然排序键（1.png < 2.png < 10.png）
    already_locked = bool(existing["sort_key_locked"]) if (
        existing is not None and "sort_key_locked" in existing.keys()
    ) else False
    if sort_order is not None:
        fields["name_sort_key"] = explicit_sort_key(sort_order)
        fields["sort_key_locked"] = 1
    elif already_locked:
        fields["name_sort_key"] = existing["name_sort_key"]
        fields["sort_key_locked"] = 1
    else:
        fields["name_sort_key"] = natural_sort_key(path.name)

    if upload_batch:
        # 只在本次上传明确了批次时才写，避免扫描/编辑把已有批次覆盖掉
        fields["upload_batch"] = upload_batch

    if existing:
        needs_thumb = (
            existing["thumb_state"] != "ready"
            or existing["size_bytes"] != stat.st_size
            or not media_ops.thumb_path(existing["id"], config.DEFAULT_THUMB_SIZE).exists()
        )
        assignments = ", ".join(f"{k}=?" for k in fields)
        values = list(fields.values()) + [existing["id"]]
        with db.write() as conn:
            conn.execute(f"UPDATE assets SET {assignments} WHERE id=?", values)
            if needs_thumb:
                conn.execute("UPDATE assets SET thumb_state='pending' WHERE id=?", (existing["id"],))
        asset_id = existing["id"]
        if needs_thumb:
            enqueue(asset_id)
        return asset_id

    columns = ", ".join(fields) + ", created_at, thumb_state, proxy_state"
    placeholders = ", ".join("?" for _ in fields) + ", ?, 'pending', 'pending'"
    with db.write() as conn:
        cur = conn.execute(
            f"INSERT INTO assets({columns}) VALUES({placeholders})",
            list(fields.values()) + [timestamp],
        )
        asset_id = int(cur.lastrowid)
    enqueue(asset_id, kind="thumb")
    return asset_id


def explicit_sort_key(index: int) -> str:
    """「保留源文件夹顺序」时用的排序键。

    前缀 ~ 只是为了让它在库里一眼能认出来是人指定的顺序，不是文件名算出来的。
    补零到 12 位，保证字典序等于数字序（和 natural_sort_key 同一套宽度）。
    """
    try:
        n = max(0, int(index))
    except (TypeError, ValueError):
        n = 0
    return f"~{n:012d}"


def backfill_sort_keys() -> int:
    """把老数据里不正确的 name_sort_key 补成真正的自然排序键。

    数据库迁移时只能用 SQL 的低成本写法（lower(file_name)）兜底，
    真正的「补零」逻辑在 Python 里，所以这里补一遍。
    只处理明显没补过零的行（含数字但键里没有补零痕迹）。
    """
    rows = db.query(
        "SELECT id, file_name, name_sort_key FROM assets "
        "WHERE (name_sort_key IS NULL OR name_sort_key = '') "
        "AND COALESCE(sort_key_locked, 0) = 0"
    )
    fixed = 0
    for row in rows:
        db.execute(
            "UPDATE assets SET name_sort_key=? WHERE id=?",
            (natural_sort_key(row["file_name"]), row["id"]),
        )
        fixed += 1
    # 迁移时用 lower(file_name) 兜底过的行：含数字且键里还没补零，就重算。
    # ★必须排除 sort_key_locked 的行★ —— 那些是用户上传时明确选择的「源文件夹顺序」，
    # 一旦按文件名重算，用户选的顺序就没了。
    rows = db.query(
        "SELECT id, file_name, name_sort_key FROM assets "
        "WHERE name_sort_key IS NOT NULL AND COALESCE(sort_key_locked, 0) = 0"
    )
    for row in rows:
        name = row["file_name"] or ""
        key = row["name_sort_key"] or ""
        if not any(ch.isdigit() for ch in name):
            continue
        if "00000000" in key:
            continue  # 已经补过零
        db.execute(
            "UPDATE assets SET name_sort_key=? WHERE id=?",
            (natural_sort_key(name), row["id"]),
        )
        fixed += 1
    if fixed:
        log.info("backfilled natural sort keys for %d assets", fixed)
    return fixed


def iter_media_files(root: Path) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for name in filenames:
            if name.startswith("."):
                continue
            path = Path(dirpath) / name
            if media_type_for(path):
                yield path


def scan_full(*, compute_sha: bool = False, progress=None) -> dict:
    """Walk the media root and index everything that is new or changed.

    注意：磁盘上暂时找不到的文件只标记 is_missing，绝不自动丢进回收站。
    外接硬盘没挂载 / 正在迁移时，把整个库「误删」是不可接受的；
    真正要清理时由作者显式调用 purge_missing()。
    """
    ensure_layout()
    root = media_root()
    backfill_sort_keys()
    # 顺手清一次孤儿文件（删资源时漏掉的缩略图/编辑备份/代理），开销很小
    try:
        cleanup_orphans()
    except Exception as exc:
        log.warning("orphan cleanup failed: %s", exc)
    added = updated = skipped = 0
    seen: set[str] = set()
    for path in iter_media_files(root):
        rel = rel_for(path)
        seen.add(rel)
        before = db.query_one("SELECT id, size_bytes FROM assets WHERE rel_path=?", (rel,))
        if before:
            try:
                if path.stat().st_size == before["size_bytes"]:
                    skipped += 1
                    continue
            except OSError:
                continue
            updated += 1
        else:
            added += 1
        try:
            upsert_asset(path, rel_path=rel, compute_sha=compute_sha)
        except Exception as exc:
            log.warning("index failed for %s: %s", rel, exc)
        if progress and (added + updated) % 25 == 0:
            progress(added + updated)

    # 文件不见了：只打标记，不动 is_trashed
    missing_rows = [
        row
        for row in db.query("SELECT id, rel_path FROM assets WHERE is_missing=0")
        if row["rel_path"] and not abs_path(row["rel_path"]).exists()
    ]
    stamp = now_iso()
    for row in missing_rows:
        db.execute(
            "UPDATE assets SET is_missing=1, missing_since=? WHERE id=?",
            (stamp, row["id"]),
        )
    # 之前标记为丢失、现在又出现的（比如硬盘重新挂载），把标记清掉
    restored = 0
    for row in db.query("SELECT id, rel_path FROM assets WHERE is_missing=1"):
        if row["rel_path"] and abs_path(row["rel_path"]).exists():
            db.execute("UPDATE assets SET is_missing=0, missing_since=NULL WHERE id=?", (row["id"],))
            restored += 1
    if missing_rows or restored:
        log.info("scan: %d missing, %d back", len(missing_rows), restored)
    return {
        "added": added,
        "updated": updated,
        "skipped": skipped,
        "missing": len(missing_rows),
        "restored": restored,
    }


def purge_missing() -> int:
    """把标记为「文件已丢失」的记录真正删掉（需要用户确认后调用）。"""
    rows = db.query("SELECT id FROM assets WHERE is_missing=1")
    for row in rows:
        db.execute("DELETE FROM assets WHERE id=?", (row["id"],))
    return len(rows)


def library_stats() -> dict:
    row = db.query_one(
        # total 是「看得见的照片数」，必须和 /api/assets 列表口径一致（都不含回收站）。
        # 之前用 COUNT(*) 会把回收站里的也算进去，于是首页显示 605 张、
        # 列表里却只有 485 张，看起来像丢了 120 张照片。
        # 库里真实存在的总行数单独用 total_all 给出。
        "SELECT"
        " SUM(CASE WHEN is_trashed=0 THEN 1 ELSE 0 END) AS total,"
        " COUNT(*) AS total_all,"
        " SUM(CASE WHEN media_type='image' AND is_trashed=0 THEN 1 ELSE 0 END) AS images,"
        " SUM(CASE WHEN media_type='video' AND is_trashed=0 THEN 1 ELSE 0 END) AS videos,"
        " SUM(CASE WHEN is_favorite=1 AND is_trashed=0 THEN 1 ELSE 0 END) AS favorites,"
        " SUM(CASE WHEN is_trashed=1 THEN 1 ELSE 0 END) AS trashed,"
        # 占用空间按全部文件算：回收站里的照片同样占着磁盘
        " SUM(size_bytes) AS bytes FROM assets"
    )
    stats = dict(row) if row else {}
    # SUM 在没有行时返回 NULL
    for key in ("total", "total_all", "images", "videos", "favorites", "trashed"):
        if stats.get(key) is None:
            stats[key] = 0
    root = media_root()
    stats["media_root"] = str(root)
    stats["missing"] = db.count("SELECT COUNT(*) FROM assets WHERE is_missing=1")
    # shutil.disk_usage 返回 namedtuple（total/used/free），不是 dict
    try:
        usage = shutil.disk_usage(root if root.exists() else root.parent)
        stats["disk_total"] = usage.total
        stats["disk_free"] = usage.free
        stats["disk_used"] = usage.used
    except OSError:
        stats["disk_total"] = None
        stats["disk_free"] = None
        stats["disk_used"] = None
    stats["pending_thumbs"] = db.count("SELECT COUNT(*) FROM assets WHERE thumb_state='pending'")
    return stats


# --------------------------------------------------------------------------
# background processing (thumbnails + video proxy)
# --------------------------------------------------------------------------
_queue: "list[tuple[int, str]]" = []
_queue_lock = threading.Condition()
_worker: threading.Thread | None = None
_running = False


def enqueue(asset_id: int, kind: str = "thumb") -> None:
    with _queue_lock:
        if (asset_id, kind) not in _queue:
            _queue.append((asset_id, kind))
        _queue_lock.notify()


def queue_depth() -> int:
    with _queue_lock:
        return len(_queue)


def retry_failed_thumbs() -> int:
    rows = db.query("SELECT id FROM assets WHERE thumb_state IN ('failed','pending','unsupported')")
    for row in rows:
        db.execute("UPDATE assets SET thumb_state='pending', thumb_error=NULL WHERE id=?", (row["id"],))
        enqueue(row["id"])
    return len(rows)


def start_worker() -> None:
    global _worker, _running
    if _worker and _worker.is_alive():
        return
    _running = True
    _worker = threading.Thread(target=_loop, name="media-worker", daemon=True)
    _worker.start()
    log.info("media worker started")


def stop_worker() -> None:
    global _running
    _running = False
    with _queue_lock:
        _queue_lock.notify_all()


def _loop() -> None:
    while _running:
        with _queue_lock:
            while _running and not _queue:
                _queue_lock.wait(timeout=2.0)
            if not _running:
                return
            asset_id, kind = _queue.pop(0)
        try:
            if kind == "proxy":
                build_proxy(asset_id)
            else:
                process_asset(asset_id)
        except Exception as exc:  # never let the worker die
            log.exception("worker task failed for %s/%s: %s", asset_id, kind, exc)


def process_asset(asset_id: int) -> None:
    row = db.query_one("SELECT * FROM assets WHERE id=?", (asset_id,))
    if not row:
        return
    path = abs_path(row["rel_path"])
    if not path.exists():
        db.execute("UPDATE assets SET thumb_state='failed', thumb_error=? WHERE id=?", ("源文件不存在", asset_id))
        return
    try:
        if row["media_type"] == "image":
            media_ops.make_image_thumb(path, asset_id, 256)
            media_ops.make_image_thumb(path, asset_id, 1024)
            width, height = media_ops.image_dimensions(path) or (row["width"], row["height"])
            db.execute(
                "UPDATE assets SET thumb_state='ready', thumb_error=NULL, width=?, height=?, updated_at=? WHERE id=?",
                (width, height, now_iso(), asset_id),
            )
        else:
            # 元数据（时长/分辨率）总是要探测的，否则列表里的时长显示不出来
            probe = media_ops.probe(path, "video")
            db.execute(
                "UPDATE assets SET width=?, height=?, duration_ms=?, updated_at=? WHERE id=?",
                (probe.width, probe.height, probe.duration_ms, now_iso(), asset_id),
            )
            if not process_video_enabled():
                # 树莓派性能吃紧时可以关掉视频处理：不抽帧、不转码，只保留元数据
                db.execute(
                    "UPDATE assets SET thumb_state='skipped', proxy_state='skipped' WHERE id=?",
                    (asset_id,),
                )
                return
            media_ops.make_video_thumb(path, asset_id, 256, duration_ms=probe.duration_ms)
            media_ops.make_video_thumb(path, asset_id, 1024, duration_ms=probe.duration_ms)
            db.execute(
                "UPDATE assets SET thumb_state='ready', thumb_error=NULL WHERE id=?",
                (asset_id,),
            )
            if auto_proxy() and probe.height and probe.height > config.VIDEO_PROXY_MAX_HEIGHT:
                db.execute("UPDATE assets SET proxy_state='pending' WHERE id=?", (asset_id,))
                enqueue(asset_id, kind="proxy")
            else:
                db.execute("UPDATE assets SET proxy_state='skipped' WHERE id=?", (asset_id,))
    except Exception as exc:
        log.warning("thumb failed for asset %s: %s", asset_id, exc)
        db.execute(
            "UPDATE assets SET thumb_state='failed', thumb_error=?, updated_at=? WHERE id=?",
            (str(exc)[:400], now_iso(), asset_id),
        )


def build_proxy(asset_id: int) -> bool:
    row = db.query_one("SELECT * FROM assets WHERE id=?", (asset_id,))
    if not row or row["media_type"] != "video":
        return False
    path = abs_path(row["rel_path"])
    if not path.exists():
        db.execute("UPDATE assets SET proxy_state='failed' WHERE id=?", (asset_id,))
        return False
    db.execute("UPDATE assets SET proxy_state='running' WHERE id=?", (asset_id,))
    result = media_ops.make_proxy(path, asset_id)
    if result is None:
        db.execute("UPDATE assets SET proxy_state='failed' WHERE id=?", (asset_id,))
        return False
    db.execute(
        "UPDATE assets SET proxy_state='ready', has_proxy=1, updated_at=? WHERE id=?",
        (now_iso(), asset_id),
    )
    return True


def proxy_path(asset_id: int) -> Path:
    return config.cache_dir() / "proxy" / f"{asset_id}.mp4"


# --------------------------------------------------------------------------
# mutations
# --------------------------------------------------------------------------
def move_into_library(temp_path: Path, file_name: str, taken_at: dt.datetime | None) -> tuple[str, Path]:
    """Move an uploaded temp file into the library, returning (rel_path, path)."""
    name = sanitize_name(file_name, fallback=f"upload_{int(time.time())}")
    if not Path(name).suffix:
        name += ".bin"
    directory = media_root() / Path(dest_rel_path(taken_at, name)).parent
    directory.mkdir(parents=True, exist_ok=True)
    final_name = unique_name(directory, name)
    dest = directory / final_name
    shutil.move(str(temp_path), str(dest))
    return rel_for(dest), dest


def find_duplicate(sha256: str, size: int) -> dict | None:
    row = db.query_one(
        "SELECT * FROM assets WHERE sha256=? AND size_bytes=? AND is_trashed=0 LIMIT 1",
        (sha256, size),
    )
    return dict(row) if row else None


def trash_assets(asset_ids: Iterable[int], *, restore: bool = False) -> int:
    count = 0
    for asset_id in asset_ids:
        row = db.query_one("SELECT * FROM assets WHERE id=?", (asset_id,))
        if not row:
            continue
        db.execute(
            "UPDATE assets SET is_trashed=?, trashed_at=?, updated_at=? WHERE id=?",
            (0 if restore else 1, None if restore else now_iso(), now_iso(), asset_id),
        )
        count += 1
    return count


def original_backup_path(asset_id: int) -> Path:
    """编辑前备份的原始字节（`media/.originals/<id>.orig`）。"""
    directory = media_root() / ".originals"
    directory.mkdir(parents=True, exist_ok=True)
    return directory / f"{asset_id}.orig"


def _remove_derived_files(asset_id: int) -> None:
    """删掉某个资源的全部派生物：缩略图、视频代理、编辑备份。

    注意备份文件（.originals/<id>.orig）以前漏掉了，
    结果反复编辑+删除测试会在媒体目录里留下一堆孤儿备份。
    """
    for size in config.THUMB_SIZES:
        media_ops.thumb_path(asset_id, size).unlink(missing_ok=True)
    proxy_path(asset_id).unlink(missing_ok=True)
    try:
        original_backup_path(asset_id).unlink(missing_ok=True)
    except OSError as exc:
        log.warning("cannot remove original backup for %s: %s", asset_id, exc)


def purge_trashed() -> int:
    rows = db.query("SELECT * FROM assets WHERE is_trashed=1")
    removed = 0
    for row in rows:
        path = abs_path(row["rel_path"])
        try:
            path.unlink(missing_ok=True)
        except OSError as exc:
            log.warning("cannot delete %s: %s", path, exc)
        _remove_derived_files(row["id"])
        db.execute("DELETE FROM assets WHERE id=?", (row["id"],))
        removed += 1
    return removed


def delete_asset_now(asset_id: int) -> bool:
    row = db.query_one("SELECT * FROM assets WHERE id=?", (asset_id,))
    if not row:
        return False
    abs_path(row["rel_path"]).unlink(missing_ok=True)
    _remove_derived_files(asset_id)
    db.execute("DELETE FROM assets WHERE id=?", (asset_id,))
    return True


def cleanup_orphans() -> dict:
    """清理磁盘上没有对应索引记录的残留文件（缩略图 / 备份 / 代理）。

    用于收拾历史遗留（比如早期版本删除资源时没清干净的情况）。
    """
    thumbs_removed = 0
    for path in config.thumbs_dir().rglob("*.jpg"):
        stem = path.stem                      # "<asset_id>_<size>"
        try:
            asset_id = int(stem.split("_")[0])
        except (ValueError, IndexError):
            continue
        if not db.query_one("SELECT id FROM assets WHERE id=?", (asset_id,)):
            path.unlink(missing_ok=True)
            thumbs_removed += 1

    backups_removed = 0
    backup_dir = media_root() / ".originals"
    if backup_dir.is_dir():
        for path in backup_dir.glob("*.orig"):
            try:
                asset_id = int(path.stem)
            except ValueError:
                continue
            if not db.query_one("SELECT id FROM assets WHERE id=?", (asset_id,)):
                path.unlink(missing_ok=True)
                backups_removed += 1

    proxies_removed = 0
    proxy_dir = config.cache_dir() / "proxy"
    if proxy_dir.is_dir():
        for path in proxy_dir.glob("*.mp4"):
            try:
                asset_id = int(path.stem)
            except ValueError:
                continue
            if not db.query_one("SELECT id FROM assets WHERE id=?", (asset_id,)):
                path.unlink(missing_ok=True)
                proxies_removed += 1

    result = {
        "thumbs_removed": thumbs_removed,
        "backups_removed": backups_removed,
        "proxies_removed": proxies_removed,
    }
    if any(result.values()):
        log.info("orphan cleanup: %s", result)
    return result


def rebuild_all_thumbs() -> int:
    for size in config.THUMB_SIZES:
        directory = config.thumbs_dir()
        for path in directory.rglob(f"*_{size}.jpg"):
            path.unlink(missing_ok=True)
    return retry_failed_thumbs()


def migration_plan(new_root: Path) -> dict:
    """What a move of the library to another disk would involve."""
    root = media_root()
    free_bytes = None
    try:
        anchor = new_root if new_root.exists() else (new_root.anchor or "/")
        free_bytes = shutil.disk_usage(anchor).free
    except OSError:
        free_bytes = None
    row = db.query_one("SELECT COALESCE(SUM(size_bytes),0) AS bytes FROM assets")
    library_bytes = int(row["bytes"]) if row else 0
    return {
        "current_root": str(root),
        "target_root": str(new_root),
        "library_bytes": library_bytes,
        "target_free_bytes": free_bytes,
        "fits": free_bytes is None or free_bytes > library_bytes,
    }
