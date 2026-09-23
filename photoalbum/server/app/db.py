"""SQLite layer: schema, migrations, and small query helpers.

The connection is opened per request (cheap for SQLite) with WAL enabled so the
scanner thread can write while the API serves reads.
"""
from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterable, Iterator, Sequence

import config

SCHEMA_VERSION = 2

_SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    last_login_at TEXT,
    is_admin      INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS assets (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    rel_path      TEXT NOT NULL UNIQUE,
    file_name     TEXT NOT NULL,
    ext           TEXT NOT NULL,
    media_type    TEXT NOT NULL,              -- image | video
    mime          TEXT NOT NULL,
    size_bytes    INTEGER NOT NULL,
    sha256        TEXT,
    width         INTEGER,
    height        INTEGER,
    duration_ms   INTEGER,
    taken_at      TEXT,                        -- ISO8601 UTC-ish, from EXIF/文件名/文件时间
    taken_src     TEXT,                        -- exif | filename | mtime | manual
    camera_make   TEXT,
    camera_model  TEXT,
    lens          TEXT,
    iso           INTEGER,
    f_number      TEXT,
    exposure      TEXT,
    focal_length  TEXT,
    gps_lat       REAL,
    gps_lon       REAL,
    orientation   INTEGER,
    edit_version  INTEGER NOT NULL DEFAULT 0,
    is_favorite   INTEGER NOT NULL DEFAULT 0,
    is_archived   INTEGER NOT NULL DEFAULT 0,
    is_trashed    INTEGER NOT NULL DEFAULT 0,
    trashed_at    TEXT,
    is_missing    INTEGER NOT NULL DEFAULT 0,   -- 磁盘上找不到文件（可能硬盘没挂载）
    missing_since TEXT,
    thumb_state   TEXT NOT NULL DEFAULT 'pending',  -- pending | ready | failed | unsupported
    thumb_error   TEXT,
    proxy_state   TEXT NOT NULL DEFAULT 'pending',
    has_proxy     INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    deleted_at    TEXT
);
CREATE INDEX IF NOT EXISTS idx_assets_taken    ON assets(taken_at DESC);
CREATE INDEX IF NOT EXISTS idx_assets_type     ON assets(media_type);
CREATE INDEX IF NOT EXISTS idx_assets_fav      ON assets(is_favorite);
CREATE INDEX IF NOT EXISTS idx_assets_trash    ON assets(is_trashed);
CREATE INDEX IF NOT EXISTS idx_assets_sha      ON assets(sha256);
CREATE INDEX IF NOT EXISTS idx_assets_name     ON assets(file_name);

CREATE TABLE IF NOT EXISTS tags (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL DEFAULT 'tag'   -- tag | person | place
);

CREATE TABLE IF NOT EXISTS asset_tags (
    asset_id INTEGER NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    tag_id   INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (asset_id, tag_id)
);

CREATE TABLE IF NOT EXISTS albums (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    description TEXT,
    cover_asset INTEGER,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS album_items (
    album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
    asset_id INTEGER NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    added_at TEXT NOT NULL,
    sort_key INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (album_id, asset_id)
);

CREATE TABLE IF NOT EXISTS shares (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    token         TEXT NOT NULL UNIQUE,
    kind          TEXT NOT NULL,          -- asset | album | all
    target_id     INTEGER,
    password_hash TEXT,
    expires_at    TEXT,
    created_at    TEXT NOT NULL,
    visit_count   INTEGER NOT NULL DEFAULT 0,
    enabled       INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS uploads (
    id           TEXT PRIMARY KEY,
    rel_path     TEXT NOT NULL,
    target_dir   TEXT NOT NULL,
    file_name    TEXT NOT NULL,
    size_bytes   INTEGER NOT NULL,
    received     INTEGER NOT NULL DEFAULT 0,
    client_sha   TEXT,
    client_mtime TEXT,
    status       TEXT NOT NULL DEFAULT 'open',  -- open | done | aborted
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);
"""


class Database:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self.init_schema()

    # -- connection -------------------------------------------------------
    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=30, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        return conn

    @contextmanager
    def write(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            conn = self.connect()
            try:
                yield conn
                conn.commit()
            except Exception:
                conn.rollback()
                raise
            finally:
                conn.close()

    @contextmanager
    def read(self) -> Iterator[sqlite3.Connection]:
        conn = self.connect()
        try:
            yield conn
        finally:
            conn.close()

    def init_schema(self) -> None:
        with self.write() as conn:
            conn.executescript(_SCHEMA)
            self._migrate(conn)
            row = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
            if row is None:
                conn.execute(
                    "INSERT INTO meta(key, value) VALUES('schema_version', ?)",
                    (str(SCHEMA_VERSION),),
                )
            elif row["value"] != str(SCHEMA_VERSION):
                conn.execute(
                    "UPDATE meta SET value=? WHERE key='schema_version'",
                    (str(SCHEMA_VERSION),),
                )

    @staticmethod
    def _migrate(conn: sqlite3.Connection) -> None:
        """给老库补上后加的列（SQLite 的 ADD COLUMN 很便宜）。"""
        existing = {row["name"] for row in conn.execute("PRAGMA table_info(assets)")}
        additions = {
            "is_missing": "INTEGER NOT NULL DEFAULT 0",
            "missing_since": "TEXT",
            # 自然排序键：把 1.png / 2.png / 10.png 排成 1 < 2 < 10
            "name_sort_key": "TEXT",
            # 同一批上传的标识（同一次上传的图片归为一批）
            "upload_batch": "TEXT",
        }
        for column, definition in additions.items():
            if column not in existing:
                conn.execute(f"ALTER TABLE assets ADD COLUMN {column} {definition}")
        # 给「按批 + 按文件名」排序加个索引
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_assets_batch_sort "
            "ON assets(upload_batch, name_sort_key)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_assets_created_sort "
            "ON assets(created_at DESC, name_sort_key ASC)"
        )
        # albums 表补列：支持子相册（parent_id 为空表示顶层相册）
        album_cols = {row["name"] for row in conn.execute("PRAGMA table_info(albums)")}
        if "parent_id" not in album_cols:
            conn.execute("ALTER TABLE albums ADD COLUMN parent_id INTEGER")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_albums_parent ON albums(parent_id)")

        # uploads 表补列：上传时就指定归属相册
        upload_cols = {row["name"] for row in conn.execute("PRAGMA table_info(uploads)")}
        if "album_id" not in upload_cols:
            conn.execute("ALTER TABLE uploads ADD COLUMN album_id INTEGER")
        if "batch_id" not in upload_cols:
            conn.execute("ALTER TABLE uploads ADD COLUMN batch_id TEXT")
        # 给已经存在的老数据补上排序键（用 SQLite 侧的低成本近似：
        # 先按文件名兜底，服务端启动时会把需要重算的补全）
        conn.execute(
            "UPDATE assets SET name_sort_key = lower(file_name) "
            "WHERE name_sort_key IS NULL OR name_sort_key = ''"
        )

    # -- settings ---------------------------------------------------------
    def get_setting(self, key: str, default: str | None = None) -> str | None:
        with self.read() as conn:
            row = conn.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return row["value"] if row else default

    def set_setting(self, key: str, value: str) -> None:
        with self.write() as conn:
            conn.execute(
                "INSERT INTO settings(key, value) VALUES(?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, value),
            )

    def all_settings(self) -> dict[str, str]:
        with self.read() as conn:
            rows = conn.execute("SELECT key, value FROM settings").fetchall()
        return {r["key"]: r["value"] for r in rows}

    # -- generic helpers --------------------------------------------------
    def query(self, sql: str, params: Sequence[Any] = ()) -> list[sqlite3.Row]:
        with self.read() as conn:
            return conn.execute(sql, params).fetchall()

    def query_one(self, sql: str, params: Sequence[Any] = ()) -> sqlite3.Row | None:
        with self.read() as conn:
            return conn.execute(sql, params).fetchone()

    def execute(self, sql: str, params: Sequence[Any] = ()) -> int:
        with self.write() as conn:
            cur = conn.execute(sql, params)
            return cur.lastrowid or 0

    def execute_many(self, sql: str, rows: Iterable[Sequence[Any]]) -> None:
        with self.write() as conn:
            conn.executemany(sql, rows)

    def count(self, sql: str, params: Sequence[Any] = ()) -> int:
        row = self.query_one(sql, params)
        return int(row[0]) if row else 0


db = Database(config.DB_PATH)
