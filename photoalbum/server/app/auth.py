"""Token based auth (HMAC signed, stdlib only) + first-run setup."""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import os
import secrets
import time
from typing import Any

import config
from db import db
from util import hash_password, now_iso, verify_password

_SECRET = config.load_secret()


class AuthError(Exception):
    """Raised when a request cannot be authenticated."""


def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _b64d(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def _sign(payload: bytes) -> str:
    return _b64e(hmac.new(_SECRET, payload, hashlib.sha256).digest())


def create_token(username: str, *, ttl: int | None = None) -> tuple[str, str]:
    """Return (token, expires_at_iso)."""
    expires = int(time.time()) + int(ttl or config.TOKEN_TTL_SECONDS)
    body = {"sub": username, "exp": expires, "n": secrets.token_hex(8)}
    payload = _b64e(json.dumps(body, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    token = f"{payload}.{_sign(payload.encode('ascii'))}"
    import datetime as dt

    exp_iso = dt.datetime.fromtimestamp(expires, dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    return token, exp_iso


def verify_token(token: str) -> dict[str, Any]:
    if not token or "." not in token:
        raise AuthError("malformed token")
    payload, signature = token.rsplit(".", 1)
    expected = _sign(payload.encode("ascii", "ignore"))
    if not hmac.compare_digest(signature, expected):
        raise AuthError("bad signature")
    try:
        body = json.loads(_b64d(payload))
    except (binascii.Error, ValueError):
        raise AuthError("bad payload")
    if int(body.get("exp", 0)) < int(time.time()):
        raise AuthError("token expired")
    return body


def verify_share_password(password: str | None, encoded: str | None) -> bool:
    if not encoded:
        return True
    if not password:
        return False
    return verify_password(password, encoded)


# --------------------------------------------------------------------------
# users
# --------------------------------------------------------------------------
def user_count() -> int:
    return db.count("SELECT COUNT(*) FROM users")


def needs_setup() -> bool:
    return user_count() == 0


def create_user(username: str, password: str, *, is_admin: bool = True) -> None:
    db.execute(
        "INSERT INTO users(username, password_hash, created_at, is_admin) VALUES(?,?,?,?)",
        (username, hash_password(password), now_iso(), 1 if is_admin else 0),
    )


def ensure_default_user() -> str | None:
    """First boot: create the default account if no user exists.

    Returns the generated password when one was created, else None.

    The password is taken from PHOTOALBUM_INITIAL_PASSWORD when set; otherwise a
    random one is generated and printed to the service log. This repository
    therefore never ships a usable default password -- read the generated one
    with:  sudo journalctl -u photoalbum | grep 默认账号
    """
    if not needs_setup():
        return None
    username = os.environ.get("PHOTOALBUM_INITIAL_USER", "cabbage")
    password = os.environ.get("PHOTOALBUM_INITIAL_PASSWORD") or secrets.token_urlsafe(12)
    create_user(username, password)
    db.set_setting("initial_password", password)
    return password


def authenticate(username: str, password: str) -> bool:
    row = db.query_one("SELECT * FROM users WHERE username=?", (username,))
    if not row:
        return False
    if not verify_password(password, row["password_hash"]):
        return False
    db.execute("UPDATE users SET last_login_at=? WHERE id=?", (now_iso(), row["id"]))
    return True


def change_password(username: str, old_password: str, new_password: str) -> None:
    if len(new_password) < config.MIN_PASSWORD_LENGTH:
        raise AuthError(f"密码至少 {config.MIN_PASSWORD_LENGTH} 位")
    if not authenticate(username, old_password):
        raise AuthError("原密码不正确")
    db.execute(
        "UPDATE users SET password_hash=? WHERE username=?",
        (hash_password(new_password), username),
    )
