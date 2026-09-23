"""HTTP API (FastAPI) for the private photo album.

Auth: `Authorization: Bearer <token>` or `?token=<token>` (the latter lets
Glide / the video player fetch media straight from a URL).

Pagination uses an opaque keyset cursor over (taken_at DESC, id DESC) so the
timeline stays stable while photos are being uploaded.
"""
from __future__ import annotations

import base64
import datetime as dt
import json
import logging
import os
import secrets
import shutil
import subprocess
import threading
from pathlib import Path
from typing import Any, Iterable, Iterator, Literal

from fastapi import Body, Depends, FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field

import auth as auth_mod
import config
import library
import media as media_ops
from db import db
from util import human_size, now_iso, parse_iso, sanitize_name

log = logging.getLogger("photoalbum.api")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

config.ensure_dirs()
library.ensure_layout()

app = FastAPI(title=config.APP_NAME, version=config.APP_VERSION, docs_url="/docs", redoc_url=None)
app.add_middleware(
    CORSMiddleware,
    allow_origins=config.CORS_ORIGINS or ["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

bearer = HTTPBearer(auto_error=False)

CHUNK_SIZE = 1024 * 1024


# ==========================================================================
# auth dependencies
# ==========================================================================
def _token_from(request: Request, credentials: HTTPAuthorizationCredentials | None) -> str | None:
    if credentials and credentials.credentials:
        return credentials.credentials
    header = request.headers.get("authorization")
    if header and header.lower().startswith("bearer "):
        return header[7:].strip()
    return request.query_params.get("token")


def current_user(
    request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)
) -> str:
    token = _token_from(request, credentials)
    if not token:
        raise HTTPException(status_code=401, detail="缺少访问令牌")
    try:
        payload = auth_mod.verify_token(token)
    except auth_mod.AuthError as exc:
        raise HTTPException(status_code=401, detail=str(exc))
    return payload["sub"]


# ==========================================================================
# serialisation
# ==========================================================================
def _iso_to_local_display(value: str | None) -> str | None:
    return value


def _asset_dict(row: Any, *, include_exif: bool = False) -> dict:
    data = {
        "id": row["id"],
        "file_name": row["file_name"],
        "media_type": row["media_type"],
        "mime": row["mime"],
        "size_bytes": row["size_bytes"],
        "size_text": human_size(row["size_bytes"]),
        "width": row["width"],
        "height": row["height"],
        "duration_ms": row["duration_ms"],
        "taken_at": row["taken_at"],
        "taken_src": row["taken_src"],
        "is_favorite": bool(row["is_favorite"]),
        "is_archived": bool(row["is_archived"]),
        "is_trashed": bool(row["is_trashed"]),
        "is_missing": bool(row["is_missing"]) if "is_missing" in row.keys() else False,
        "name_sort_key": row["name_sort_key"] if "name_sort_key" in row.keys() else None,
        "upload_batch": row["upload_batch"] if "upload_batch" in row.keys() else None,
        "thumb_state": row["thumb_state"],
        "thumb_error": row["thumb_error"],
        "has_proxy": bool(row["has_proxy"]),
        "proxy_state": row["proxy_state"],
        "edit_version": row["edit_version"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "thumb_url": f"/api/assets/{row['id']}/thumb?size=256",
        "preview_url": f"/api/assets/{row['id']}/thumb?size=1024",
        "raw_url": f"/api/assets/{row['id']}/raw",
        "stream_url": f"/api/assets/{row['id']}/stream",
        "download_url": f"/api/assets/{row['id']}/download",
        "tags": [t["name"] for t in db.query(
            "SELECT t.name FROM tags t JOIN asset_tags at ON at.tag_id=t.id WHERE at.asset_id=? ORDER BY t.name",
            (row["id"],),
        )],
    }
    if include_exif:
        data["camera_make"] = row["camera_make"]
        data["camera_model"] = row["camera_model"]
        data["lens"] = row["lens"]
        data["iso"] = row["iso"]
        data["f_number"] = row["f_number"]
        data["exposure"] = row["exposure"]
        data["focal_length"] = row["focal_length"]
        data["gps_lat"] = row["gps_lat"]
        data["gps_lon"] = row["gps_lon"]
        data["orientation"] = row["orientation"]
        data["rel_path"] = row["rel_path"]
        data["sha256"] = row["sha256"]
        data["albums"] = [
            {"id": a["id"], "name": a["name"]}
            for a in db.query(
                "SELECT al.id, al.name FROM albums al JOIN album_items ai ON ai.album_id=al.id "
                "WHERE ai.asset_id=? ORDER BY al.name",
                (row["id"],),
            )
        ]
    return data


def _encode_cursor(row: Any) -> str:
    """游标 = (排序时间, 文件名自然序键, id)，与列表 ORDER BY 的三个键一一对应。

    排序时间优先取查询里算好的 group_time（批次时间）；
    没有的话退回 created_at，保证两个调用点都能用。
    """
    keys = row.keys()
    group_time = row["group_time"] if "group_time" in keys else row["created_at"]
    raw = json.dumps(
        [group_time or "", row["name_sort_key"] or "", row["id"]],
        separators=(",", ":"),
    ).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _decode_cursor(cursor: str) -> tuple[str, str, int]:
    try:
        pad = "=" * (-len(cursor) % 4)
        decoded = json.loads(base64.urlsafe_b64decode(cursor + pad))
        if len(decoded) == 2:
            # 兼容旧版游标（taken_at, id）
            return str(decoded[0]), "", int(decoded[1])
        created_at, sort_key, asset_id = decoded
        return str(created_at), str(sort_key or ""), int(asset_id)
    except Exception:
        raise HTTPException(status_code=400, detail="翻页游标无效")


def _get_asset(asset_id: int) -> Any:
    row = db.query_one("SELECT * FROM assets WHERE id=?", (asset_id,))
    if not row:
        raise HTTPException(status_code=404, detail="照片不存在")
    return row


# ==========================================================================
# query building
# ==========================================================================
def _search_clauses(q: str | None) -> tuple[list[str], list[Any]]:
    """Support is:fav / type:video / album:name / tag:x / after:date / before:date / plain text."""
    clauses: list[str] = []
    params: list[Any] = []
    if not q:
        return clauses, params
    free_text: list[str] = []
    for token in q.split():
        lowered = token.lower()
        if lowered.startswith("is:"):
            value = lowered[3:]
            if value in {"fav", "favorite", "star"}:
                clauses.append("a.is_favorite=1")
            elif value in {"video", "videos"}:
                clauses.append("a.media_type='video'")
            elif value in {"image", "photo", "images", "photos"}:
                clauses.append("a.media_type='image'")
            elif value == "trash":
                clauses.append("a.is_trashed=1")
            elif value == "archived":
                clauses.append("a.is_archived=1")
            else:
                free_text.append(token)
        elif lowered.startswith("type:"):
            value = lowered[5:]
            if value.startswith("vid"):
                clauses.append("a.media_type='video'")
            elif value.startswith("img") or value.startswith("pho"):
                clauses.append("a.media_type='image'")
        elif lowered.startswith("album:"):
            name = token[6:]
            clauses.append(
                "a.id IN (SELECT ai.asset_id FROM album_items ai JOIN albums al ON al.id=ai.album_id WHERE al.name LIKE ?)"
            )
            params.append(f"%{name}%")
        elif lowered.startswith("tag:"):
            name = token[4:]
            clauses.append("a.id IN (SELECT at.asset_id FROM asset_tags at JOIN tags t ON t.id=at.tag_id WHERE t.name LIKE ?)")
            params.append(f"%{name}%")
        elif lowered.startswith("after:"):
            value = token[6:]
            parsed = parse_iso(value)
            if parsed:
                clauses.append("a.taken_at >= ?")
                params.append(parsed.astimezone(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"))
        elif lowered.startswith("before:"):
            value = token[7:]
            parsed = parse_iso(value)
            if parsed:
                clauses.append("a.taken_at < ?")
                params.append(parsed.astimezone(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"))
        else:
            free_text.append(token)
    if free_text:
        like = f"%{' '.join(free_text)}%"
        clauses.append("a.file_name LIKE ?")
        params.append(like)
    return clauses, params


def _list_assets(
    *,
    q: str | None = None,
    media_type: str | None = None,
    favorite: bool = False,
    trashed: bool = False,
    archived: bool | None = None,
    missing: bool = False,
    cursor: str | None = None,
    limit: int = 100,
    before: str | None = None,
    after: str | None = None,
) -> dict:
    clauses: list[str] = []
    params: list[Any] = []
    if trashed:
        clauses.append("a.is_trashed=1")
    else:
        clauses.append("a.is_trashed=0")
    # 文件已丢失（例如外接硬盘还没挂载）的条目默认不显示，但不会删掉索引
    clauses.append("a.is_missing=1" if missing else "a.is_missing=0")
    if archived is None:
        clauses.append("a.is_archived=0")
    elif archived:
        clauses.append("a.is_archived=1")
    if media_type in {"image", "video"}:
        clauses.append("a.media_type=?")
        params.append(media_type)
    if favorite:
        clauses.append("a.is_favorite=1")
    if after:
        parsed = parse_iso(after)
        if parsed:
            clauses.append("a.taken_at >= ?")
            params.append(parsed.astimezone(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"))
    if before:
        parsed = parse_iso(before)
        if parsed:
            clauses.append("a.taken_at < ?")
            params.append(parsed.astimezone(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"))
    extra_clauses, extra_params = _search_clauses(q)
    clauses.extend(extra_clauses)
    params.extend(extra_params)

    where = " WHERE " + " AND ".join(clauses) if clauses else ""
    page_size = max(1, min(limit, 500))

    # ---- 排序键（务必和下面的 SELECT 完全一致）-----------------------------
    #   批次时间 = 该批次里最早的 created_at；同一批每行算出来都一样，
    #   这样整批不会被上传时刻的先后拆散。
    #   没有批次（老数据 / 单独上传）时退化为该行自己的 created_at。
    #
    #   最终顺序：批次时间 DESC  -> 最新上传的那一批排最前
    #             文件名自然序 ASC -> 1.png, 2.png, 3.png, 10.png, 100.png
    #             id ASC          -> 兜底，保证稳定
    GROUP_TIME = (
        "COALESCE((SELECT MIN(b.created_at) FROM assets b "
        "          WHERE b.upload_batch = a.upload_batch), a.created_at)"
    )
    SORT_KEY = "COALESCE(a.name_sort_key, a.file_name)"

    if cursor:
        cursor_time, cursor_sort, cursor_id = _decode_cursor(cursor)
        # 取「排在游标之后」的行。排序是 group_time DESC, sort_key ASC, id ASC，
        # 所以「更靠后」= group_time 更小，或相同则 sort_key 更大，或再相同则 id 更大。
        clauses.append(
            f"(({GROUP_TIME}) < ? "
            f" OR (({GROUP_TIME}) = ? AND {SORT_KEY} > ?) "
            f" OR (({GROUP_TIME}) = ? AND {SORT_KEY} = ? AND a.id > ?))"
        )
        params.extend([cursor_time, cursor_time, cursor_sort,
                       cursor_time, cursor_sort, cursor_id])

    where = " WHERE " + " AND ".join(clauses) if clauses else ""
    sql = (
        f"SELECT a.*, {GROUP_TIME} AS group_time FROM assets a{where} "
        f"ORDER BY group_time DESC, {SORT_KEY} ASC, a.id ASC "
        "LIMIT ?"
    )
    rows = db.query(sql, params + [page_size])
    next_cursor = _encode_cursor(rows[-1]) if len(rows) == max(1, min(limit, 500)) else None
    return {
        "items": [_asset_dict(r) for r in rows],
        "next_cursor": next_cursor,
        "count": len(rows),
    }


def _group_by_day(items: Iterable[dict]) -> list[dict]:
    groups: "list[dict]" = []
    current: dict | None = None
    for item in items:
        taken = item["taken_at"] or ""
        day = taken[:10]
        if current is None or current["day"] != day:
            current = {"day": day, "items": []}
            groups.append(current)
        current["items"].append(item)
    return groups


# ==========================================================================
# schemas
# ==========================================================================
class LoginBody(BaseModel):
    username: str
    password: str


class SetupBody(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=config.MIN_PASSWORD_LENGTH, max_length=256)


class PasswordBody(BaseModel):
    old_password: str
    new_password: str


class IdsBody(BaseModel):
    ids: list[int]


class EditBody(BaseModel):
    rotate: int = 0
    flip_h: bool = False
    flip_v: bool = False
    crop: list[float] | None = None       # [x0,y0,x1,y1] normalised 0..1
    brightness: float = 1.0
    contrast: float = 1.0
    saturation: float = 1.0
    filter: str | None = None
    keep_original: bool = True
    mark_edited: bool = True


class TrimBody(BaseModel):
    start_ms: int = 0
    end_ms: int


class AlbumBody(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    description: str | None = None


class AlbumCoverBody(BaseModel):
    asset_id: int | None = None


class TagBody(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    kind: str = "tag"


class ShareBody(BaseModel):
    kind: Literal["asset", "album", "all"] = "asset"
    target_id: int | None = None
    password: str | None = None
    expires_in_days: int | None = 30


class SettingsBody(BaseModel):
    media_root: str | None = None
    process_video: bool | None = None
    auto_proxy: bool | None = None
    organize_by_date: bool | None = None


class UploadCompleteBody(BaseModel):
    upload_id: str
    sha256: str | None = None
    taken_at: str | None = None
    file_name: str | None = None


class MigrateBody(BaseModel):
    target_root: str
    move: bool = True


# ==========================================================================
# health / setup / auth
# ==========================================================================
@app.get("/api/health")
def health() -> dict:
    stats = library.library_stats()
    return {
        "ok": True,
        "app": config.APP_NAME,
        "version": config.APP_VERSION,
        "needs_setup": auth_mod.needs_setup(),
        "server_time": now_iso(),
        "media_root": stats.get("media_root"),
        "items": stats.get("total") or 0,
        "disk_free": stats.get("disk_free"),
        "disk_free_text": human_size(stats["disk_free"]) if stats.get("disk_free") else None,
        "ffmpeg": bool(media_ops.FFMPEG),
        "pillow_heif": bool(media_ops.HEIF_VIA_PILLOW),
        "scan_running": SCAN_STATE["running"],
        "queue_depth": library.queue_depth(),
    }


@app.post("/api/setup")
def setup(body: SetupBody) -> dict:
    if not auth_mod.needs_setup():
        raise HTTPException(status_code=409, detail="服务已初始化")
    auth_mod.create_user(body.username, body.password)
    token, expires = auth_mod.create_token(body.username)
    return {"ok": True, "token": token, "expires_at": expires, "username": body.username}


@app.post("/api/login")
def login(body: LoginBody) -> dict:
    if not auth_mod.authenticate(body.username, body.password):
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    token, expires = auth_mod.create_token(body.username)
    return {"ok": True, "token": token, "expires_at": expires, "username": body.username}


@app.post("/api/logout")
def logout(user: str = Depends(current_user)) -> dict:
    return {"ok": True}


@app.get("/api/me")
def me(user: str = Depends(current_user)) -> dict:
    row = db.query_one("SELECT username, created_at, last_login_at FROM users WHERE username=?", (user,))
    return {"username": user, "created_at": row["created_at"] if row else None,
            "last_login_at": row["last_login_at"] if row else None}


@app.post("/api/password")
def change_password(body: PasswordBody, user: str = Depends(current_user)) -> dict:
    try:
        auth_mod.change_password(user, body.old_password, body.new_password)
    except auth_mod.AuthError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {"ok": True}


# ==========================================================================
# listing
# ==========================================================================
@app.get("/api/assets")
def list_assets(
    q: str | None = None,
    type: str | None = Query(default=None, alias="type"),
    favorite: bool = False,
    trashed: bool = False,
    archived: bool | None = None,
    missing: bool = False,
    cursor: str | None = None,
    limit: int = 100,
    before: str | None = None,
    after: str | None = None,
    grouped: bool = False,
    user: str = Depends(current_user),
) -> dict:
    result = _list_assets(
        q=q, media_type=type, favorite=favorite, trashed=trashed, archived=archived,
        missing=missing, cursor=cursor, limit=limit, before=before, after=after,
    )
    if grouped:
        result["groups"] = _group_by_day(result["items"])
    return result


@app.get("/api/timeline")
def timeline(
    year: int | None = None,
    month: int | None = None,
    user: str = Depends(current_user),
) -> dict:
    """Month buckets for the app's timeline scrubber."""
    clauses = ["is_trashed=0", "is_archived=0"]
    params: list[Any] = []
    if year:
        clauses.append("substr(taken_at,1,4)=?")
        params.append(f"{year:04d}")
    if month:
        clauses.append("substr(taken_at,6,2)=?")
        params.append(f"{month:02d}")
    rows = db.query(
        "SELECT substr(taken_at,1,7) AS month, COUNT(*) AS count, "
        "SUM(CASE WHEN media_type='video' THEN 1 ELSE 0 END) AS videos "
        f"FROM assets WHERE {' AND '.join(clauses)} GROUP BY month ORDER BY month DESC",
        params,
    )
    return {"months": [dict(r) for r in rows]}


@app.get("/api/assets/{asset_id}")
def asset_detail(asset_id: int, user: str = Depends(current_user)) -> dict:
    return _asset_dict(_get_asset(asset_id), include_exif=True)


@app.get("/api/duplicates")
def duplicates(user: str = Depends(current_user)) -> dict:
    rows = db.query(
        "SELECT sha256, COUNT(*) AS n, SUM(size_bytes) AS bytes FROM assets "
        "WHERE sha256 IS NOT NULL AND is_trashed=0 GROUP BY sha256 HAVING n > 1 ORDER BY bytes DESC LIMIT 200"
    )
    groups = []
    for row in rows:
        members = db.query("SELECT * FROM assets WHERE sha256=? AND is_trashed=0 ORDER BY id", (row["sha256"],))
        groups.append({"sha256": row["sha256"], "count": row["n"], "bytes": row["bytes"],
                       "items": [_asset_dict(m) for m in members]})
    return {"groups": groups, "wasted_bytes": sum((g["bytes"] or 0) for g in groups)}


# ==========================================================================
# binary payloads
# ==========================================================================
def _thumb_response(asset_id: int, size: int) -> Response:
    row = _get_asset(asset_id)
    allowed = (256, 1024) if size not in config.THUMB_SIZES else (size,)
    size = allowed[0]
    path = media_ops.thumb_path(asset_id, size)
    if not path.exists():
        if row["thumb_state"] in {"pending", "failed"}:
            library.enqueue(asset_id)
        fallback = media_ops.thumb_path(asset_id, 1024 if size == 256 else 256)
        if fallback.exists():
            path = fallback
        else:
            placeholder = PLACEHOLDER_PATH
            if not placeholder.exists():
                _write_placeholder(placeholder)
            return FileResponse(placeholder, media_type="image/jpeg",
                                headers=_cache_headers(60))
    return FileResponse(path, media_type="image/jpeg", headers=_cache_headers(86400 * 30))


def _cache_headers(seconds: int) -> dict:
    return {"Cache-Control": f"public, max-age={seconds}"}


PLACEHOLDER_PATH = config.cache_dir() / "placeholder.jpg"


def _write_placeholder(path: Path) -> None:
    from PIL import Image

    img = Image.new("RGB", (256, 256), (38, 40, 46))
    for x in range(0, 256, 32):
        for y in range(0, 256, 32):
            if (x // 32 + y // 32) % 2 == 0:
                img.paste((48, 51, 58), (x, y, x + 32, y + 32))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "JPEG", quality=70)


@app.get("/api/assets/{asset_id}/thumb")
def asset_thumb(asset_id: int, size: int = 256, user: str = Depends(current_user)) -> Response:
    return _thumb_response(asset_id, size)


@app.get("/api/assets/{asset_id}/raw")
def asset_raw(asset_id: int, user: str = Depends(current_user)) -> Response:
    row = _get_asset(asset_id)
    path = library.abs_path(row["rel_path"])
    if not path.exists():
        raise HTTPException(status_code=404, detail="文件已丢失")
    return FileResponse(path, media_type=row["mime"], filename=row["file_name"])


@app.get("/api/assets/{asset_id}/download")
def asset_download(asset_id: int, user: str = Depends(current_user)) -> Response:
    return asset_raw(asset_id, user)  # same bytes, keeps Content-Disposition


def _range_iterator(path: Path, start: int, length: int, chunk: int = CHUNK_SIZE) -> Iterator[bytes]:
    with open(path, "rb") as handle:
        handle.seek(start)
        remaining = length
        while remaining > 0:
            block = handle.read(min(chunk, remaining))
            if not block:
                break
            remaining -= len(block)
            yield block


@app.get("/api/assets/{asset_id}/stream")
def asset_stream(request: Request, asset_id: int, user: str = Depends(current_user)) -> Response:
    """HTTP Range streaming so ExoPlayer/VideoView can seek without downloading."""
    row = _get_asset(asset_id)
    use_proxy = (
        row["media_type"] == "video"
        and row["has_proxy"]
        and request.query_params.get("original") != "1"
    )
    path = library.proxy_path(asset_id) if use_proxy else library.abs_path(row["rel_path"])
    if not path.exists():
        path = library.abs_path(row["rel_path"])
    if not path.exists():
        raise HTTPException(status_code=404, detail="文件已丢失")

    file_size = path.stat().st_size
    media_type = "video/mp4" if use_proxy else row["mime"]
    headers = {
        "Accept-Ranges": "bytes",
        "Cache-Control": "private, max-age=3600",
        "Content-Disposition": f'inline; filename="{row["file_name"]}"',
    }
    range_header = request.headers.get("range")
    if not range_header:
        headers["Content-Length"] = str(file_size)
        return StreamingResponse(_range_iterator(path, 0, file_size), media_type=media_type, headers=headers)

    try:
        unit, _, spec = range_header.partition("=")
        if unit.strip().lower() != "bytes":
            raise ValueError
        spec = spec.strip()
        if "," in spec:
            # 多区间请求（少见）：只满足第一个区间，符合 RFC 允许的简化实现
            spec = spec.split(",", 1)[0].strip()
        start_s, _, end_s = spec.partition("-")
        if start_s:
            start = int(start_s)
            end = int(end_s) if end_s else file_size - 1
        else:
            # 后缀区间 bytes=-N（表示「最后 N 个字节」）——播放器拖到末尾时会用到
            if not end_s:
                raise ValueError
            length = int(end_s)
            if length <= 0:
                raise ValueError
            start = max(0, file_size - length)
            end = file_size - 1
    except ValueError:
        raise HTTPException(status_code=416, detail="Range 头无效",
                            headers={"Content-Range": f"bytes */{file_size}"})
    if start >= file_size or start < 0:
        raise HTTPException(status_code=416, detail="Range 越界",
                            headers={"Content-Range": f"bytes */{file_size}"})
    end = min(end, file_size - 1)
    if end < start:
        raise HTTPException(status_code=416, detail="Range 越界",
                            headers={"Content-Range": f"bytes */{file_size}"})
    length = end - start + 1
    headers.update({
        "Content-Range": f"bytes {start}-{end}/{file_size}",
        "Content-Length": str(length),
    })
    return StreamingResponse(_range_iterator(path, start, length), status_code=206,
                             media_type=media_type, headers=headers)


@app.get("/api/assets/{asset_id}/poster")
def asset_poster(asset_id: int, user: str = Depends(current_user)) -> Response:
    return _thumb_response(asset_id, 1024)


# ==========================================================================
# editing
# ==========================================================================
def _original_backup_path(asset_id: int) -> Path:
    return library.original_backup_path(asset_id)


def _backup_original(asset_id: int, path: Path) -> None:
    backup = _original_backup_path(asset_id)
    if not backup.exists():
        shutil.copy2(path, backup)


def _refresh_after_edit(asset_id: int) -> None:
    """编辑落盘后重新探测尺寸/时长，并让缩略图与代理重新生成。"""
    row = _get_asset(asset_id)
    path = library.abs_path(row["rel_path"])
    try:
        stat = path.stat()
    except OSError:
        return
    probe = media_ops.probe(path, row["media_type"])
    db.execute(
        "UPDATE assets SET size_bytes=?, width=?, height=?, duration_ms=?, updated_at=?, "
        "edit_version=edit_version+1 WHERE id=?",
        (stat.st_size, probe.width, probe.height, probe.duration_ms, now_iso(), asset_id),
    )
    for size in config.THUMB_SIZES:
        media_ops.thumb_path(asset_id, size).unlink(missing_ok=True)
    library.proxy_path(asset_id).unlink(missing_ok=True)
    db.execute("UPDATE assets SET thumb_state='pending', has_proxy=0, proxy_state='pending' WHERE id=?", (asset_id,))
    library.enqueue(asset_id)


@app.post("/api/assets/{asset_id}/edit")
def edit_asset(asset_id: int, body: EditBody, user: str = Depends(current_user)) -> dict:
    row = _get_asset(asset_id)
    if row["media_type"] != "image":
        raise HTTPException(status_code=400, detail="视频暂不支持该编辑操作，请使用裁剪视频接口")
    path = library.abs_path(row["rel_path"])
    if not path.exists():
        raise HTTPException(status_code=404, detail="文件已丢失")

    crop = None
    if body.crop and len(body.crop) == 4:
        x0, y0, x1, y1 = (max(0.0, min(1.0, float(v))) for v in body.crop)
        if x1 - x0 < 0.005 or y1 - y0 < 0.005:
            raise HTTPException(status_code=400, detail="裁剪区域过小")
        crop = (x0, y0, x1, y1)

    rotate = int(body.rotate) % 360
    if rotate not in (0, 90, 180, 270):
        rotate = 0

    noop = (
        rotate == 0 and not body.flip_h and not body.flip_v and crop is None
        and abs(body.brightness - 1.0) < 1e-3 and abs(body.contrast - 1.0) < 1e-3
        and abs(body.saturation - 1.0) < 1e-3 and not body.filter
    )
    if noop:
        return {"ok": True, "asset": _asset_dict(row, include_exif=True), "changed": False}

    _backup_original(asset_id, path)
    try:
        media_ops.edit_image(
            path, path,
            rotate=rotate, flip_h=body.flip_h, flip_v=body.flip_v, crop=crop,
            brightness=body.brightness, contrast=body.contrast, saturation=body.saturation,
            filter_name=body.filter,
        )
    except Exception as exc:
        log.exception("edit failed for %s", asset_id)
        raise HTTPException(status_code=500, detail=f"编辑失败: {exc}")

    _refresh_after_edit(asset_id)
    if body.keep_original:
        backup = _original_backup_path(asset_id)
        if backup.exists():
            directory = path.parent
            original_name = f"{path.stem}_原图{path.suffix}"
            try:
                final = directory / original_name
                if not final.exists():
                    shutil.copy2(backup, final)
                    library.upsert_asset(final, rel_path=library.rel_for(final))
            except Exception as exc:
                log.warning("could not materialise original copy: %s", exc)
            backup.unlink(missing_ok=True)
    return {"ok": True, "asset": _asset_dict(_get_asset(asset_id), include_exif=True), "changed": True}


@app.post("/api/assets/{asset_id}/restore-original")
def restore_original(asset_id: int, user: str = Depends(current_user)) -> dict:
    row = _get_asset(asset_id)
    backup = _original_backup_path(asset_id)
    if not backup.exists():
        raise HTTPException(status_code=404, detail="没有可恢复的原始版本")
    path = library.abs_path(row["rel_path"])
    shutil.copy2(backup, path)
    backup.unlink(missing_ok=True)
    _refresh_after_edit(asset_id)
    return {"ok": True, "asset": _asset_dict(_get_asset(asset_id), include_exif=True)}


@app.post("/api/assets/{asset_id}/trim")
def trim_asset(asset_id: int, body: TrimBody, user: str = Depends(current_user)) -> dict:
    row = _get_asset(asset_id)
    if row["media_type"] != "video":
        raise HTTPException(status_code=400, detail="只有视频可以裁剪时长")
    path = library.abs_path(row["rel_path"])
    if body.end_ms <= body.start_ms:
        raise HTTPException(status_code=400, detail="结束时间必须大于开始时间")
    _backup_original(asset_id, path)
    trimmed = library.media_root() / ".tmp_trim" / row["file_name"]
    if not media_ops.trim_video(path, trimmed, start_ms=body.start_ms, end_ms=body.end_ms):
        raise HTTPException(status_code=500, detail="视频裁剪失败（ffmpeg 未安装或文件损坏）")
    shutil.move(str(trimmed), str(path))
    shutil.rmtree(trimmed.parent, ignore_errors=True)
    _refresh_after_edit(asset_id)
    return {"ok": True, "asset": _asset_dict(_get_asset(asset_id), include_exif=True)}


@app.post("/api/assets/{asset_id}/rotate")
def quick_rotate(asset_id: int, degrees: int = 90, user: str = Depends(current_user)) -> dict:
    return edit_asset(asset_id, EditBody(rotate=int(degrees), keep_original=False), user)


# ==========================================================================
# state mutations
# ==========================================================================
@app.post("/api/assets/favorite")
def favourite(body: dict = Body(...), user: str = Depends(current_user)) -> dict:
    ids = [int(i) for i in body.get("ids", [])]
    value = 1 if body.get("value", True) else 0
    for asset_id in ids:
        db.execute("UPDATE assets SET is_favorite=?, updated_at=? WHERE id=?", (value, now_iso(), asset_id))
    return {"ok": True, "count": len(ids)}


@app.post("/api/assets/archive")
def archive(body: dict = Body(...), user: str = Depends(current_user)) -> dict:
    ids = [int(i) for i in body.get("ids", [])]
    value = 1 if body.get("value", True) else 0
    for asset_id in ids:
        db.execute("UPDATE assets SET is_archived=?, updated_at=? WHERE id=?", (value, now_iso(), asset_id))
    return {"ok": True, "count": len(ids)}


@app.post("/api/assets/trash")
def trash(body: IdsBody, user: str = Depends(current_user)) -> dict:
    return {"ok": True, "count": library.trash_assets(body.ids)}


@app.post("/api/assets/restore")
def restore(body: IdsBody, user: str = Depends(current_user)) -> dict:
    return {"ok": True, "count": library.trash_assets(body.ids, restore=True)}


@app.delete("/api/assets/{asset_id}")
def delete_asset(asset_id: int, user: str = Depends(current_user)) -> dict:
    _get_asset(asset_id)
    return {"ok": library.delete_asset_now(asset_id)}


@app.post("/api/assets/move")
def move_assets(body: dict = Body(...), user: str = Depends(current_user)) -> dict:
    ids = [int(i) for i in body.get("ids", [])]
    target_year = body.get("year")
    target_month = body.get("month")
    taken_at = body.get("taken_at")
    if taken_at:
        when = parse_iso(str(taken_at))
    elif target_year:
        when = dt.datetime(int(target_year), int(target_month or 1), 1, tzinfo=library._tz)
    else:
        raise HTTPException(status_code=400, detail="需要 taken_at 或 year/month")
    moved = 0
    for asset_id in ids:
        row = _get_asset(asset_id)
        source = library.abs_path(row["rel_path"])
        if not source.exists():
            continue
        rel = library.dest_rel_path(when, row["file_name"])
        dest = library.media_root() / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists():
            dest = dest.with_name(f"{dest.stem}_{int(asset_id)}{dest.suffix}")
            rel = library.rel_for(dest)
        shutil.move(str(source), str(dest))
        db.execute(
            "UPDATE assets SET rel_path=?, taken_at=?, taken_src='manual', updated_at=? WHERE id=?",
            (rel, when.astimezone(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
             now_iso(), asset_id),
        )
        moved += 1
    return {"ok": True, "moved": moved}


@app.post("/api/assets/tags")
def add_tag(body: dict = Body(...), user: str = Depends(current_user)) -> dict:
    ids = [int(i) for i in body.get("ids", [])]
    name = str(body.get("name", "")).strip()
    remove = bool(body.get("remove", False))
    if not name:
        raise HTTPException(status_code=400, detail="标签名不能为空")
    with db.write() as conn:
        conn.execute("INSERT OR IGNORE INTO tags(name) VALUES(?)", (name,))
        tag = conn.execute("SELECT id FROM tags WHERE name=?", (name,)).fetchone()
        tag_id = tag["id"]
        for asset_id in ids:
            if remove:
                conn.execute("DELETE FROM asset_tags WHERE asset_id=? AND tag_id=?", (asset_id, tag_id))
            else:
                conn.execute("INSERT OR IGNORE INTO asset_tags(asset_id, tag_id) VALUES(?,?)", (asset_id, tag_id))
    return {"ok": True}


@app.get("/api/tags")
def list_tags(user: str = Depends(current_user)) -> dict:
    rows = db.query(
        "SELECT t.id, t.name, t.kind, COUNT(at.asset_id) AS count FROM tags t "
        "LEFT JOIN asset_tags at ON at.tag_id=t.id GROUP BY t.id ORDER BY count DESC, t.name"
    )
    return {"tags": [dict(r) for r in rows]}


# ==========================================================================
# albums
# ==========================================================================
@app.get("/api/albums")
def list_albums(user: str = Depends(current_user)) -> dict:
    rows = db.query(
        "SELECT al.*, (SELECT COUNT(*) FROM album_items ai WHERE ai.album_id=al.id) AS count "
        "FROM albums al ORDER BY al.updated_at DESC"
    )
    albums = []
    for row in rows:
        album = dict(row)
        cover = row["cover_asset"]
        if cover is None:
            first = db.query_one(
                "SELECT asset_id FROM album_items WHERE album_id=? ORDER BY sort_key, added_at LIMIT 1",
                (row["id"],),
            )
            cover = first["asset_id"] if first else None
        album["cover_asset"] = cover
        album["cover_url"] = f"/api/assets/{cover}/thumb?size=256" if cover else None
        albums.append(album)
    return {"albums": albums}


@app.post("/api/albums")
def create_album(body: AlbumBody, user: str = Depends(current_user)) -> dict:
    stamp = now_iso()
    album_id = db.execute(
        "INSERT INTO albums(name, description, created_at, updated_at) VALUES(?,?,?,?)",
        (body.name.strip(), body.description, stamp, stamp),
    )
    return {"ok": True, "id": album_id}


@app.patch("/api/albums/{album_id}")
def update_album(album_id: int, body: AlbumBody, user: str = Depends(current_user)) -> dict:
    if not db.query_one("SELECT id FROM albums WHERE id=?", (album_id,)):
        raise HTTPException(status_code=404, detail="相册不存在")
    db.execute("UPDATE albums SET name=?, description=?, updated_at=? WHERE id=?",
               (body.name.strip(), body.description, now_iso(), album_id))
    return {"ok": True}


@app.delete("/api/albums/{album_id}")
def delete_album(album_id: int, user: str = Depends(current_user)) -> dict:
    db.execute("DELETE FROM albums WHERE id=?", (album_id,))
    return {"ok": True}


@app.post("/api/albums/{album_id}/cover")
def set_cover(album_id: int, body: AlbumCoverBody, user: str = Depends(current_user)) -> dict:
    db.execute("UPDATE albums SET cover_asset=?, updated_at=? WHERE id=?", (body.asset_id, now_iso(), album_id))
    return {"ok": True}


@app.post("/api/albums/{album_id}/items")
def add_album_items(album_id: int, body: dict = Body(...), user: str = Depends(current_user)) -> dict:
    ids = [int(i) for i in body.get("ids", [])]
    remove = bool(body.get("remove", False))
    stamp = now_iso()
    with db.write() as conn:
        for asset_id in ids:
            if remove:
                conn.execute("DELETE FROM album_items WHERE album_id=? AND asset_id=?", (album_id, asset_id))
            else:
                conn.execute(
                    "INSERT OR IGNORE INTO album_items(album_id, asset_id, added_at, sort_key) VALUES(?,?,?,0)",
                    (album_id, asset_id, stamp),
                )
        conn.execute("UPDATE albums SET updated_at=? WHERE id=?", (stamp, album_id))
    return {"ok": True}


@app.get("/api/albums/{album_id}")
def album_detail(
    album_id: int, cursor: str | None = None, limit: int = 200, user: str = Depends(current_user)
) -> dict:
    album = db.query_one("SELECT * FROM albums WHERE id=?", (album_id,))
    if not album:
        raise HTTPException(status_code=404, detail="相册不存在")
    clauses = ["a.is_trashed=0", "a.id IN (SELECT asset_id FROM album_items WHERE album_id=?)"]
    params: list[Any] = [album_id]
    page = max(1, min(limit, 500))

    GROUP_TIME = (
        "COALESCE((SELECT MIN(b.created_at) FROM assets b "
        "          WHERE b.upload_batch = a.upload_batch), a.created_at)"
    )
    SORT_KEY = "COALESCE(a.name_sort_key, a.file_name)"

    if cursor:
        cursor_time, cursor_sort, cursor_id = _decode_cursor(cursor)
        clauses.append(
            f"(({GROUP_TIME}) < ? "
            f" OR (({GROUP_TIME}) = ? AND {SORT_KEY} > ?) "
            f" OR (({GROUP_TIME}) = ? AND {SORT_KEY} = ? AND a.id > ?))"
        )
        params.extend([cursor_time, cursor_time, cursor_sort,
                       cursor_time, cursor_sort, cursor_id])

    rows = db.query(
        f"SELECT a.*, {GROUP_TIME} AS group_time FROM assets a "
        f"WHERE {' AND '.join(clauses)} "
        f"ORDER BY group_time DESC, {SORT_KEY} ASC, a.id ASC "
        "LIMIT ?",
        params + [page],
    )
    return {
        "album": dict(album),
        "items": [_asset_dict(r) for r in rows],
        "next_cursor": _encode_cursor(rows[-1]) if len(rows) == page else None,
    }


# ==========================================================================
# shares
# ==========================================================================
@app.get("/api/shares")
def list_shares(user: str = Depends(current_user)) -> dict:
    rows = db.query("SELECT * FROM shares ORDER BY created_at DESC")
    return {"shares": [{k: r[k] for k in r.keys() if k != "password_hash"} for r in rows]}


@app.post("/api/shares")
def create_share(body: ShareBody, user: str = Depends(current_user)) -> dict:
    token = secrets.token_urlsafe(12)
    expires = None
    if body.expires_in_days:
        expires = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=body.expires_in_days)) \
            .isoformat(timespec="seconds").replace("+00:00", "Z")
    password_hash = auth_mod.hash_password(body.password) if body.password else None
    share_id = db.execute(
        "INSERT INTO shares(token, kind, target_id, password_hash, expires_at, created_at) VALUES(?,?,?,?,?,?)",
        (token, body.kind, body.target_id, password_hash, expires, now_iso()),
    )
    return {"ok": True, "id": share_id, "token": token, "url": f"/share/{token}", "expires_at": expires}


@app.delete("/api/shares/{share_id}")
def delete_share(share_id: int, user: str = Depends(current_user)) -> dict:
    db.execute("DELETE FROM shares WHERE id=?", (share_id,))
    return {"ok": True}


# ==========================================================================
# uploads (chunked + resumable)
# ==========================================================================
@app.post("/api/uploads/init")
def upload_init(body: dict = Body(...), user: str = Depends(current_user)) -> dict:
    file_name = sanitize_name(str(body.get("file_name") or "upload.jpg"))
    size = int(body.get("size_bytes") or 0)
    if size <= 0:
        raise HTTPException(status_code=400, detail="缺少文件大小")
    if size > config.MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail=f"文件过大（上限 {human_size(config.MAX_UPLOAD_BYTES)}）")
    ext = Path(file_name).suffix.lower()
    if ext not in config.IMAGE_EXTENSIONS | config.VIDEO_EXTENSIONS:
        raise HTTPException(status_code=415, detail=f"不支持的格式: {ext or '未知'}")

    client_sha = body.get("sha256")
    if client_sha:
        duplicate = library.find_duplicate(str(client_sha), size)
        if duplicate and not body.get("force"):
            return {"ok": True, "duplicate": True, "asset": _asset_dict(duplicate)}

    upload_id = secrets.token_hex(12)
    part = config.UPLOAD_TMP_DIR / f"{upload_id}.part"
    part.touch()
    # 上传时就能指定归属相册（相册页里的「上传到此相册」用这个参数）
    album_id = body.get("album_id")
    if album_id is not None:
        try:
            album_id = int(album_id)
        except (TypeError, ValueError):
            raise HTTPException(status_code=400, detail="album_id 必须是数字")
        if not db.query_one("SELECT id FROM albums WHERE id=?", (album_id,)):
            raise HTTPException(status_code=404, detail="相册不存在")
    batch_id = body.get("batch_id") or upload_id
    db.execute(
        "INSERT INTO uploads(id, rel_path, target_dir, file_name, size_bytes, received, client_sha, "
        "client_mtime, status, created_at, updated_at, album_id, batch_id) "
        "VALUES(?,?,?,?,?,0,?,?, 'open', ?, ?, ?, ?)",
        (upload_id, "", "", file_name, size, client_sha, body.get("client_mtime"),
         now_iso(), now_iso(), album_id, str(batch_id)),
    )
    return {
        "ok": True,
        "upload_id": upload_id,
        "received": 0,
        "chunk_size": CHUNK_SIZE,
        "duplicate": False,
        "album_id": album_id,
    }


@app.put("/api/uploads/{upload_id}/chunk")
async def upload_chunk(
    request: Request, upload_id: str, user: str = Depends(current_user)
) -> dict:
    row = db.query_one("SELECT * FROM uploads WHERE id=?", (upload_id,))
    if not row:
        raise HTTPException(status_code=404, detail="上传会话不存在")
    if row["status"] != "open":
        raise HTTPException(status_code=409, detail="上传会话已结束")
    part = config.UPLOAD_TMP_DIR / f"{upload_id}.part"
    if not part.exists():
        part.touch()
    offset = int(request.headers.get("x-chunk-offset", str(part.stat().st_size)))
    body = await request.body()
    if offset != part.stat().st_size:
        # client and server disagree: tell the client where to resume
        return {"ok": False, "received": part.stat().st_size, "expected_offset": part.stat().st_size}
    with open(part, "ab") as handle:
        handle.write(body)
    received = part.stat().st_size
    db.execute("UPDATE uploads SET received=?, updated_at=? WHERE id=?", (received, now_iso(), upload_id))
    if received > row["size_bytes"] + 1024:
        part.unlink(missing_ok=True)
        db.execute("UPDATE uploads SET status='aborted', received=0 WHERE id=?", (upload_id,))
        raise HTTPException(status_code=400, detail="收到的数据超过声明的文件大小")
    return {"ok": True, "received": received, "complete": received >= row["size_bytes"]}


@app.post("/api/uploads/complete")
def upload_complete(body: UploadCompleteBody, user: str = Depends(current_user)) -> dict:
    row = db.query_one("SELECT * FROM uploads WHERE id=?", (body.upload_id,))
    if not row:
        raise HTTPException(status_code=404, detail="上传会话不存在")
    part = config.UPLOAD_TMP_DIR / f"{body.upload_id}.part"
    if not part.exists():
        raise HTTPException(status_code=410, detail="临时文件已丢失，请重新上传")
    received = part.stat().st_size
    if received < row["size_bytes"]:
        return {"ok": False, "received": received, "expected": row["size_bytes"],
                "message": "上传未完成，请从该偏移继续"}

    file_name = sanitize_name(body.file_name or row["file_name"])
    taken_at = parse_iso(body.taken_at) if body.taken_at else None
    sha = body.sha256 or row["client_sha"]
    batch_id = row["batch_id"] or body.upload_id
    album_id = row["album_id"]

    if sha:
        duplicate = library.find_duplicate(str(sha), received)
        if duplicate:
            part.unlink(missing_ok=True)
            db.execute("UPDATE uploads SET status='done', updated_at=? WHERE id=?", (now_iso(), body.upload_id))
            # 秒传进来的文件也要归入目标相册
            if album_id:
                _add_asset_to_album(int(duplicate["id"]), int(album_id))
            return {"ok": True, "duplicate": True, "asset": _asset_dict(_get_asset(int(duplicate["id"])))}

    rel, dest = library.move_into_library(part, file_name, taken_at)
    try:
        asset_id = library.upsert_asset(
            dest, rel_path=rel, taken_at=taken_at,
            taken_src="manual" if taken_at else None,
            compute_sha=True,
            client_mtime=row["client_mtime"],
            upload_batch=str(batch_id),
        )
    except Exception as exc:
        log.exception("index failed after upload")
        raise HTTPException(status_code=500, detail=f"入库失败: {exc}")
    if asset_id is None:
        dest.unlink(missing_ok=True)
        raise HTTPException(status_code=415, detail="不支持的文件类型")

    # 以服务器自己算出的 SHA-256 为准：客户端上报的哈希只用于秒传预检，
    # 如果两边不一致，说明预检没命中，这里再查一次真去重（宁可多算一次，也不信客户端）。
    stored = db.query_one("SELECT sha256 FROM assets WHERE id=?", (asset_id,))
    server_sha = stored["sha256"] if stored else None
    if server_sha and sha and server_sha != sha:
        log.info("client sha mismatch for %s: client=%s server=%s", file_name, sha, server_sha)
    if server_sha:
        twin = db.query_one(
            "SELECT id FROM assets WHERE sha256=? AND is_trashed=0 AND id != ? LIMIT 1",
            (server_sha, asset_id),
        )
        if twin:
            library.delete_asset_now(asset_id)
            db.execute("UPDATE uploads SET status='done', rel_path='', updated_at=? WHERE id=?",
                       (now_iso(), body.upload_id))
            if album_id:
                _add_asset_to_album(int(twin["id"]), int(album_id))
            return {"ok": True, "duplicate": True, "asset": _asset_dict(_get_asset(twin["id"]))}

    db.execute("UPDATE uploads SET status='done', rel_path=?, updated_at=? WHERE id=?",
               (rel, now_iso(), body.upload_id))
    if album_id:
        _add_asset_to_album(asset_id, int(album_id))
    result = _asset_dict(_get_asset(asset_id), include_exif=True)
    result["album_id"] = album_id
    return {"ok": True, "duplicate": False, "asset": result}


def _add_asset_to_album(asset_id: int, album_id: int) -> None:
    """把资源加入相册（已存在则忽略），并更新相册时间与封面。"""
    with db.write() as conn:
        conn.execute(
            "INSERT OR IGNORE INTO album_items(album_id, asset_id, added_at, sort_key) VALUES(?,?,?,0)",
            (album_id, asset_id, now_iso()),
        )
        conn.execute("UPDATE albums SET updated_at=? WHERE id=?", (now_iso(), album_id))
        row = conn.execute("SELECT cover_asset FROM albums WHERE id=?", (album_id,)).fetchone()
        if row is not None and row["cover_asset"] is None:
            conn.execute("UPDATE albums SET cover_asset=? WHERE id=?", (asset_id, album_id))


@app.post("/api/uploads/simple")
async def upload_simple(
    file: UploadFile = File(...),
    file_name: str | None = Form(default=None),
    taken_at: str | None = Form(default=None),
    user: str = Depends(current_user),
) -> dict:
    """Single request upload for small files (used by the web UI and fallback path)."""
    name = sanitize_name(file_name or file.filename or "upload.jpg")
    tmp = config.UPLOAD_TMP_DIR / f"simple_{secrets.token_hex(8)}{Path(name).suffix}"
    size = 0
    with open(tmp, "wb") as handle:
        while True:
            block = await file.read(CHUNK_SIZE)
            if not block:
                break
            size += len(block)
            if size > config.MAX_UPLOAD_BYTES:
                handle.close()
                tmp.unlink(missing_ok=True)
                raise HTTPException(status_code=413, detail="文件过大")
            handle.write(block)
    when = parse_iso(taken_at) if taken_at else None
    rel, dest = library.move_into_library(tmp, name, when)
    asset_id = library.upsert_asset(dest, rel_path=rel, taken_at=when,
                                    taken_src="manual" if when else None, compute_sha=True)
    if asset_id is None:
        dest.unlink(missing_ok=True)
        raise HTTPException(status_code=415, detail="不支持的文件类型")
    return {"ok": True, "asset": _asset_dict(_get_asset(asset_id), include_exif=True)}


@app.get("/api/uploads")
def list_uploads(user: str = Depends(current_user)) -> dict:
    rows = db.query("SELECT * FROM uploads WHERE status='open' ORDER BY created_at DESC LIMIT 100")
    return {"uploads": [dict(r) for r in rows]}


# ==========================================================================
# library maintenance
# ==========================================================================
SCAN_STATE: dict = {"running": False, "added": 0, "updated": 0, "skipped": 0, "missing": 0,
                    "error": None, "finished_at": None}


def _run_scan(compute_sha: bool) -> None:
    SCAN_STATE.update({"running": True, "error": None, "finished_at": None})
    try:
        result = library.scan_full(compute_sha=compute_sha)
        SCAN_STATE.update(result)
    except Exception as exc:
        log.exception("scan failed")
        SCAN_STATE["error"] = str(exc)
    finally:
        SCAN_STATE["running"] = False
        SCAN_STATE["finished_at"] = now_iso()


@app.post("/api/library/scan")
def library_scan(background: bool = True, compute_sha: bool = False, user: str = Depends(current_user)) -> dict:
    if SCAN_STATE["running"]:
        return {"ok": True, "running": True, "message": "扫描已在进行中"}
    if background:
        threading.Thread(target=_run_scan, args=(compute_sha,), daemon=True).start()
        return {"ok": True, "running": True}
    _run_scan(compute_sha)
    return {"ok": True, "running": False, **SCAN_STATE}


@app.get("/api/library/stats")
def library_stats(user: str = Depends(current_user)) -> dict:
    stats = library.library_stats()
    stats["scan"] = SCAN_STATE
    stats["queue_depth"] = library.queue_depth()
    return stats


@app.get("/api/library/verify")
def library_verify(limit: int = 2000, fix: bool = False, user: str = Depends(current_user)) -> dict:
    """抽查索引与磁盘是否一致。

    fix=false：只报告，不改数据库（默认，安全）。
    fix=true ：把「磁盘上确实没有」的记录标记为 missing（仍然不删除记录）。
    """
    rows = db.query(
        "SELECT id, rel_path, is_missing FROM assets WHERE is_trashed=0 LIMIT ?", (limit,)
    )
    missing = [r["rel_path"] for r in rows if not library.abs_path(r["rel_path"]).exists()]
    if fix and missing:
        stamp = now_iso()
        for rel in missing:
            db.execute("UPDATE assets SET is_missing=1, missing_since=? WHERE rel_path=?", (stamp, rel))
    total_missing = db.count("SELECT COUNT(*) FROM assets WHERE is_missing=1")
    return {
        "checked": len(rows),
        "missing": missing,
        "missing_count": len(missing),
        "indexed_missing": total_missing,
        "fixed": bool(fix and missing),
        "hint": "文件暂时找不到时只会标记，不会删除索引；确认硬盘确实没接再调用 purge-missing。",
    }


@app.get("/api/library/missing")
def list_missing(user: str = Depends(current_user)) -> dict:
    return _list_assets(missing=True, limit=200)


@app.post("/api/library/purge-missing")
def purge_missing(user: str = Depends(current_user)) -> dict:
    """把确认丢失的记录从索引中删除（不碰磁盘上的其他内容）。"""
    removed = library.purge_missing()
    return {"ok": True, "removed": removed}


@app.post("/api/library/rebuild-thumbs")
def rebuild_thumbs(user: str = Depends(current_user)) -> dict:
    count = library.rebuild_all_thumbs()
    return {"ok": True, "queued": count}


@app.post("/api/library/cleanup-orphans")
def cleanup_orphans(user: str = Depends(current_user)) -> dict:
    """清理磁盘上没有索引记录的残留（缩略图 / 编辑备份 / 视频代理）。"""
    result = library.cleanup_orphans()
    return {"ok": True, **result}


@app.get("/api/trash")
def list_trash(user: str = Depends(current_user)) -> dict:
    return _list_assets(trashed=True, limit=200)


@app.post("/api/trash/purge")
def purge_trash(user: str = Depends(current_user)) -> dict:
    return {"ok": True, "removed": library.purge_trashed()}


@app.get("/api/settings")
def get_settings(user: str = Depends(current_user)) -> dict:
    return {
        "media_root": str(library.media_root()),
        "thumbs_dir": str(config.thumbs_dir()),
        "cache_dir": str(config.cache_dir()),
        "process_video": library.process_video_enabled(),
        "auto_proxy": library.auto_proxy(),
        "organize_by_date": library.organize_by_date(),
        "max_upload_bytes": config.MAX_UPLOAD_BYTES,
        "version": config.APP_VERSION,
        "ffmpeg": bool(media_ops.FFMPEG),
        "pillow_heif": bool(media_ops.HEIF_VIA_PILLOW),
        "video_proxy_height": config.VIDEO_PROXY_MAX_HEIGHT,
        "thumbs": list(config.THUMB_SIZES),
    }


@app.put("/api/settings")
def put_settings(body: SettingsBody, user: str = Depends(current_user)) -> dict:
    if body.media_root:
        target = Path(body.media_root).expanduser()
        if not target.is_absolute():
            raise HTTPException(status_code=400, detail="请使用绝对路径")
        target.mkdir(parents=True, exist_ok=True)
        library.set_media_root(target)
        library.ensure_layout()
        library.scan_full()
    if body.process_video is not None:
        db.set_setting(library.SETTING_PROCESS_VIDEO, "1" if body.process_video else "0")
    if body.auto_proxy is not None:
        db.set_setting(library.SETTING_AUTO_PROXY, "1" if body.auto_proxy else "0")
    if body.organize_by_date is not None:
        db.set_setting(library.SETTING_ORGANIZE, "1" if body.organize_by_date else "0")
    return get_settings(user)


@app.post("/api/library/migrate/plan")
def migrate_plan(body: dict = Body(...), user: str = Depends(current_user)) -> dict:
    target = Path(str(body.get("target_root", ""))).expanduser()
    if not target.is_absolute():
        raise HTTPException(status_code=400, detail="请使用绝对路径")
    plan = library.migration_plan(target)
    plan["target_exists"] = target.exists()
    plan["target_mounted"] = os.path.ismount(target) or os.path.ismount(target.parent)
    return plan


@app.post("/api/library/migrate")
def migrate_run(body: MigrateBody, user: str = Depends(current_user)) -> dict:
    """Copy (or move) the whole library to another disk, then re-point the root."""
    target = Path(body.target_root).expanduser()
    if not target.is_absolute():
        raise HTTPException(status_code=400, detail="请使用绝对路径")
    source = library.media_root()
    if target == source:
        raise HTTPException(status_code=400, detail="目标目录与当前目录相同")
    target.mkdir(parents=True, exist_ok=True)
    library.ensure_layout()
    if shutil.which("rsync"):
        cmd = ["rsync", "-a", "--info=progress2", f"{source}/", f"{target}/"]
        proc = subprocess.run(cmd, capture_output=True, timeout=60 * 60 * 12, check=False)
        output = proc.stdout.decode("utf-8", "replace")[-2000:] + proc.stderr.decode("utf-8", "replace")[-2000:]
        if proc.returncode != 0:
            raise HTTPException(status_code=500, detail=f"rsync 失败: {output[-500:]}")
    else:
        copied = 0
        for path in library.iter_media_files(source):
            rel = path.relative_to(source)
            dest = target / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            if not dest.exists() or dest.stat().st_size != path.stat().st_size:
                shutil.copy2(path, dest)
            copied += 1
        for extra in (".originals", "undated"):
            extra_dir = source / extra
            if extra_dir.is_dir():
                shutil.copytree(extra_dir, target / extra, dirs_exist_ok=True)
        output = f"copied {copied} files"

    library.set_media_root(target)
    library.ensure_layout()
    if body.move:
        for child in source.iterdir():
            if child.name in {"tmp"}:
                continue
            try:
                if child.is_dir():
                    shutil.rmtree(child, ignore_errors=True)
                else:
                    child.unlink(missing_ok=True)
            except OSError as exc:
                log.warning("could not clean up %s: %s", child, exc)
    for size in config.THUMB_SIZES:
        for path in config.thumbs_dir().rglob(f"*_{size}.jpg"):
            path.unlink(missing_ok=True)
    result = library.scan_full()
    library.retry_failed_thumbs()
    return {"ok": True, "media_root": str(target), "moved": body.move, "scan": result, "log": output[-500:]}


# ==========================================================================
# minimal web UI (browser fallback / share landing page)
# ==========================================================================
INDEX_HTML = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>私有相册 · 服务端</title>
<style>
 body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#14161a;color:#e8eaed;
      margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center}
 .card{background:#1e2126;padding:32px 36px;border-radius:16px;max-width:520px;box-shadow:0 12px 40px #0008}
 h1{margin:0 0 8px;font-size:22px} .muted{color:#9aa0a6;font-size:14px;line-height:1.7}
 code{background:#2a2e35;padding:2px 6px;border-radius:6px;font-size:13px}
 .ok{color:#81c995} a{color:#8ab4f8}
</style></head><body><div class="card">
<h1>📷 私有相册服务端</h1>
<p class="muted">服务已运行。请在安卓 App 中填写本机地址登录使用。</p>
<p class="muted">地址：<code>__HOST__</code><br>接口文档：<a href="/docs">/docs</a><br>
健康检查：<a href="/api/health">/api/health</a></p>
<p class="muted">状态：<span class="ok">__HEALTH__</span></p>
</div></body></html>"""


@app.get("/", response_class=HTMLResponse)
def index(request: Request = None) -> str:
    stats = library.library_stats()
    free = human_size(stats["disk_free"]) if stats.get("disk_free") else "未知"
    # 回显访客实际访问用的地址，不要写死任何一台机器的 IP
    host = ""
    if request is not None:
        host = request.headers.get("host") or ""
    if not host:
        host = f"127.0.0.1:{config.PORT}"
    return (
        INDEX_HTML
        .replace("__HOST__", host)
        .replace("__HEALTH__", f"{stats.get('total', 0) or 0} 项 · 剩余 {free}")
    )


SHARE_HTML = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>分享</title>
<style>body{background:#14161a;color:#e8eaed;font-family:system-ui;margin:0;padding:24px}
 img,video{max-width:100%;border-radius:12px;display:block;margin:12px auto}
 .muted{color:#9aa0a6;text-align:center;font-size:14px}
 .tile{width:150px;height:150px;object-fit:cover;display:inline-block;margin:4px;border-radius:10px}</style>
</head><body>__BODY__</body></html>"""


def _render_share(body: str) -> str:
    # 注意：不要用 % 格式化 —— 模板里的 CSS 有 100% 之类的百分号会被当成格式符
    return SHARE_HTML.replace("__BODY__", body)


@app.get("/share/{token}", response_class=HTMLResponse)
def share_page(token: str, password: str | None = None) -> str:
    row = db.query_one("SELECT * FROM shares WHERE token=? AND enabled=1", (token,))
    if not row:
        raise HTTPException(status_code=404, detail="分享链接不存在")
    expires = parse_iso(row["expires_at"])
    if expires and expires < dt.datetime.now(dt.timezone.utc):
        raise HTTPException(status_code=410, detail="分享链接已过期")
    if not auth_mod.verify_share_password(password, row["password_hash"]):
        return _render_share('<p class="muted">需要密码：在链接后加 <code>?password=...</code></p>')
    db.execute("UPDATE shares SET visit_count=visit_count+1 WHERE id=?", (row["id"],))

    if row["kind"] == "asset" and row["target_id"]:
        asset = db.query_one("SELECT * FROM assets WHERE id=?", (row["target_id"],))
        if not asset:
            raise HTTPException(status_code=404, detail="照片不存在")
        if asset["media_type"] == "video":
            tag = f'<video controls src="/api/share/{token}/stream"></video>'
        else:
            tag = f'<img src="/api/share/{token}/raw">'
        return _render_share(tag + f'<p class="muted">{asset["file_name"]}</p>')

    if row["kind"] == "album" and row["target_id"]:
        rows = db.query(
            "SELECT a.* FROM assets a JOIN album_items ai ON ai.asset_id=a.id "
            "WHERE ai.album_id=? AND a.is_trashed=0 ORDER BY a.taken_at DESC LIMIT 500",
            (row["target_id"],),
        )
    else:
        rows = db.query("SELECT * FROM assets WHERE is_trashed=0 ORDER BY taken_at DESC LIMIT 200")
    tiles = []
    for asset in rows:
        url = f"/api/share/{token}/thumb/{asset['id']}"
        full = f"/api/share/{token}/raw/{asset['id']}"
        badge = "🎬 " if asset["media_type"] == "video" else ""
        tiles.append(
            f'<a href="{full}"><img class="tile" src="{url}" loading="lazy" alt=""></a>'
            f'<div class="muted">{badge}{asset["file_name"]}</div>'
        )
    body = "".join(tiles) if tiles else '<p class="muted">相册为空</p>'
    return _render_share(body)


def _share_guard(token: str, password: str | None) -> None:
    row = db.query_one("SELECT * FROM shares WHERE token=? AND enabled=1", (token,))
    if not row:
        raise HTTPException(status_code=404, detail="分享链接不存在")
    expires = parse_iso(row["expires_at"])
    if expires and expires < dt.datetime.now(dt.timezone.utc):
        raise HTTPException(status_code=410, detail="分享链接已过期")
    if not auth_mod.verify_share_password(password, row["password_hash"]):
        raise HTTPException(status_code=403, detail="需要分享密码")


@app.get("/api/share/{token}/thumb/{asset_id}")
def share_thumb(token: str, asset_id: int, password: str | None = None) -> Response:
    _share_guard(token, password)
    return _thumb_response(asset_id, 256)


@app.get("/api/share/{token}/raw/{asset_id}")
def share_raw(token: str, asset_id: int, password: str | None = None) -> Response:
    _share_guard(token, password)
    row = _get_asset(asset_id)
    return FileResponse(library.abs_path(row["rel_path"]), media_type=row["mime"], filename=row["file_name"])


@app.get("/api/share/{token}/raw")
def share_raw_single(token: str, password: str | None = None) -> Response:
    return share_raw(token, db.query_one("SELECT target_id FROM shares WHERE token=?", (token,))["target_id"], password)


@app.get("/api/share/{token}/stream")
def share_stream(token: str, request: Request = None, password: str | None = None) -> Response:
    _share_guard(token, password)
    row = db.query_one("SELECT target_id FROM shares WHERE token=?", (token,))
    return asset_stream(request, int(row["target_id"]), user="share")


# ==========================================================================
# startup
# ==========================================================================
@app.on_event("startup")
def on_startup() -> None:
    config.ensure_dirs()
    library.ensure_layout()
    created = auth_mod.ensure_default_user()
    if created:
        log.warning("已创建默认账号 %s / %s —— 请在 App 中尽快修改密码",
                    os.environ.get("PHOTOALBUM_INITIAL_USER", "cabbage"), created)
    library.start_worker()
    threading.Thread(target=_run_scan, args=(False,), daemon=True).start()
    log.info("%s %s 已启动，媒体目录: %s", config.APP_NAME, config.APP_VERSION, library.media_root())


@app.on_event("shutdown")
def on_shutdown() -> None:
    library.stop_worker()
