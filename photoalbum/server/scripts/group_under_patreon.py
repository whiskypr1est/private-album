#!/usr/bin/env python3
"""把现有顶层相册全部归入一个名为 Patreon 的大相册。

用法（在树莓派上）：
    venv/bin/python server/scripts/group_under_patreon.py [BASE] [USER] [PASSWORD]

设计要点：
  · 走 HTTP 接口而不是直接写数据库 —— 顺便验证 /api/albums/{id}/move 是好的
  · 可重复执行：Patreon 已存在就复用，已经在里面的相册会跳过
  · 迁移前后都对账「照片总数」，数量不一致就直接报错退出
  · 只改相册的层级关系，绝不碰任何照片
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
USER = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("PHOTOALBUM_INITIAL_USER", "cabbage")
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else os.environ.get("PHOTOALBUM_PASSWORD", "")

PARENT_NAME = "Patreon"

TOKEN = ""


def call(method: str, path: str, body=None):
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, {"detail": raw}


def fetch_albums() -> list[dict]:
    status, data = call("GET", "/api/albums")
    if status != 200:
        raise SystemExit(f"拉取相册失败：HTTP {status} {data}")
    return data["albums"]


def total_photos(albums: list[dict]) -> int:
    """所有相册直接包含的照片数之和（只按顶层相册统计，避免重复计算）。"""
    return sum(int(a["count"]) for a in albums)


def show(title: str, albums: list[dict]) -> None:
    by_parent: dict[object, list[dict]] = {}
    for a in albums:
        by_parent.setdefault(a["parent_id"], []).append(a)

    def walk(parent, depth: int) -> None:
        for a in sorted(by_parent.get(parent, []), key=lambda x: x["name"]):
            kids = a["child_count"]
            extra = f"  ({kids} 个子相册)" if kids else ""
            print(f"{'    ' * depth}· {a['name']}  — 直接 {a['count']} 张 / 合计 {a['total_count']} 张{extra}")
            walk(a["id"], depth + 1)

    print(f"\n===== {title} =====")
    walk(None, 0)


def main() -> int:
    global TOKEN
    if not PASSWORD:
        print("缺少口令：请设置环境变量 PHOTOALBUM_PASSWORD，或作为第 3 个参数传入")
        return 2

    status, data = call("POST", "/api/login", {"username": USER, "password": PASSWORD})
    if status != 200 or "token" not in data:
        print(f"登录失败：HTTP {status} {data}")
        return 2
    TOKEN = data["token"]

    before = fetch_albums()
    show("迁移前", before)
    photos_before = total_photos(before)
    print(f"\n  顶层相册 {sum(1 for a in before if a['parent_id'] is None)} 个，照片合计 {photos_before} 张")

    # ---- 找到或创建 Patreon ------------------------------------------------
    parent = next((a for a in before if a["name"] == PARENT_NAME and a["parent_id"] is None), None)
    if parent is not None:
        parent_id = parent["id"]
        print(f"\n已存在顶层相册「{PARENT_NAME}」(id={parent_id})，直接复用")
    else:
        status, data = call("POST", "/api/albums",
                            {"name": PARENT_NAME, "description": "把所有图集合集归到这里"})
        if status != 200:
            print(f"创建「{PARENT_NAME}」失败：HTTP {status} {data}")
            return 1
        parent_id = data["id"]
        print(f"\n已创建顶层相册「{PARENT_NAME}」(id={parent_id})")

    # ---- 把其它顶层相册移进去 ---------------------------------------------
    to_move = [a for a in before if a["parent_id"] is None and a["id"] != parent_id]
    if not to_move:
        print("没有需要移动的相册（已经都归好了）")
    for a in to_move:
        status, data = call("POST", f"/api/albums/{a['id']}/move", {"parent_id": parent_id})
        if status != 200:
            print(f"  ★ 移动「{a['name']}」失败：HTTP {status} {data}")
            return 1
        print(f"  已归入：{a['name']}  (id={a['id']})")

    # ---- 对账 -------------------------------------------------------------
    after = fetch_albums()
    show("迁移后", after)
    photos_after = total_photos(after)

    print(f"\n===== 对账 =====")
    print(f"  照片总数：迁移前 {photos_before}  ->  迁移后 {photos_after}")
    print(f"  相册总数：迁移前 {len(before)}  ->  迁移后 {len(after)}")
    if photos_after != photos_before:
        print("  ★ 照片数量发生变化，请立刻人工检查！★")
        return 1
    if len(after) != len(before) + (0 if parent is not None else 1):
        print("  ★ 相册数量异常 ★")
        return 1
    print("  一致 ✓ —— 照片一张没动，只是相册层级变了")

    # 幂等自检：Patreon 的子相册数应等于移动后的顶层相册数
    patreon = next((a for a in after if a["id"] == parent_id), None)
    print(f"  「{PARENT_NAME}」现在有 {patreon['child_count']} 个子相册，"
          f"合计 {patreon['total_count']} 张照片")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
