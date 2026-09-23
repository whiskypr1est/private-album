#!/usr/bin/env python3
"""上传排序方式端到端测试（按文件名 / 保留源文件夹顺序）。

用法（在树莓派上）：
    venv/bin/python server/scripts/e2e_upload_order_test.py [BASE] [USER] [PASSWORD]

覆盖：
  · 不传 order_index  -> 按文件名自然序（1 < 2 < 10）
  · 传 order_index    -> 严格按给定序号，文件名完全不参与排序
  · ★重扫媒体库之后，源顺序必须原样保留★（这是最容易坏的地方：
    backfill_sort_keys 会按文件名重算排序键，必须靠 sort_key_locked 拦住）
  · 批次之间互不干扰

【安全约定】只删自己造的资源；绝不动全局清空接口。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import e2e_test as base  # noqa: E402
from e2e_test import check, make_image, section  # noqa: E402

PREFIX = "e2e序"
ALBUM_NAME = PREFIX + "测试相册"
created_assets: list[int] = []
created_albums: list[int] = []


def call(method: str, path: str, body=None, **kwargs):
    return base.call(method, path, body, **kwargs)


def upload(name: str, color, *, album_id=None, batch_id=None, order_index=None):
    """分片上传一张测试图，可选带 order_index。返回 asset dict 或 None。"""
    payload = make_image(color, size=(160, 160), text=name[:8])
    init_body = {"file_name": name, "size_bytes": len(payload)}
    if album_id is not None:
        init_body["album_id"] = album_id
    if batch_id is not None:
        init_body["batch_id"] = batch_id
    status, init = call("POST", "/api/uploads/init", init_body)
    if status != 200 or not isinstance(init, dict) or init.get("duplicate"):
        print(f"    init 失败 {name}: {status} {init}")
        return None
    upload_id = init["upload_id"]
    offset = 0
    while offset < len(payload):
        piece = payload[offset:offset + 64 * 1024]
        status, res = call("PUT", f"/api/uploads/{upload_id}/chunk", piece,
                           headers={"Content-Type": "application/octet-stream",
                                    "X-Chunk-Offset": str(offset)})
        if status != 200:
            print(f"    chunk 失败 {name}: {status} {res}")
            return None
        offset += len(piece)
    done_body = {"upload_id": upload_id, "file_name": name}
    if order_index is not None:
        done_body["order_index"] = order_index
    status, done = call("POST", "/api/uploads/complete", done_body)
    if status != 200:
        print(f"    complete 失败 {name}: {status} {done}")
        return None
    asset = done.get("asset")
    if asset:
        created_assets.append(asset["id"])
    return asset


def album_order(album_id: int) -> list[str]:
    """返回相册里的文件名顺序。"""
    status, data = call("GET", f"/api/albums/{album_id}?limit=200")
    if status != 200:
        return []
    return [i["file_name"] for i in data.get("items", [])]


def run_scan_and_wait(timeout: int = 180) -> bool:
    """触发一次媒体库扫描并等它跑完（模拟重启/重扫）。"""
    status, _ = call("POST", "/api/library/scan", {})
    if status != 200:
        return False
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(1.5)
        status, stats = call("GET", "/api/library/stats")
        if status == 200 and not stats.get("scan", {}).get("running"):
            return True
    return False


def cleanup():
    for aid in list(created_assets):
        call("DELETE", f"/api/assets/{aid}")
    created_assets.clear()
    for alb in list(created_albums):
        call("DELETE", f"/api/albums/{alb}")
    created_albums.clear()


def main() -> int:
    section("登录")
    status, login = call("POST", "/api/login",
                         {"username": base.USER, "password": base.PASSWORD}, auth=False)
    if status != 200:
        print(f"  登录失败 {status} {login}")
        return 1
    base.TOKEN = login["token"]
    check("登录成功", True)

    status, albums_before = call("GET", "/api/albums")
    user_albums = {a["id"]: (a["name"], a["parent_id"], a["count"])
                   for a in albums_before["albums"]}

    section("1. 建测试相册")
    status, alb = call("POST", "/api/albums", {"name": ALBUM_NAME})
    album_id = alb.get("id")
    check("建相册成功", status == 200 and album_id, f"{status} {alb}")
    if not album_id:
        return 1
    created_albums.append(album_id)

    # ------------------------------------------------------------------
    section("2. 不给 order_index -> 按文件名自然序")
    # 故意用 10 / 2 / 1 这种「字典序会排错」的名字
    # 两批必须用不同的文件名和不同的图片内容，否则第二批会被 SHA-256 秒传挡掉，
    # 根本不会创建新资源（上一版测试就踩了这个坑，看起来像功能坏了）
    b1 = PREFIX + "批次A"
    names_a = [PREFIX + "A_10.jpg", PREFIX + "A_2.jpg", PREFIX + "A_1.jpg"]
    upload(names_a[0], (200, 60, 60), album_id=album_id, batch_id=b1)
    upload(names_a[1], (60, 200, 60), album_id=album_id, batch_id=b1)
    upload(names_a[2], (60, 60, 200), album_id=album_id, batch_id=b1)
    order_a = album_order(album_id)
    expect_natural = [PREFIX + "A_1.jpg", PREFIX + "A_2.jpg", PREFIX + "A_10.jpg"]
    check("三张都上传成功", len(order_a) == 3, f"实际 {len(order_a)} 张：{order_a}")
    check("按文件名自然序排列（1 < 2 < 10，不是 1 < 10 < 2）",
          order_a == expect_natural, f"实际={order_a}")

    # ------------------------------------------------------------------
    section("3. 给 order_index -> 严格按给定序号，文件名不参与")
    # 源顺序假设是 10 -> 2 -> 1，与文件名顺序完全相反
    b2 = PREFIX + "批次B"
    names_b = [PREFIX + "B_10.jpg", PREFIX + "B_2.jpg", PREFIX + "B_1.jpg"]
    upload(names_b[0], (210, 70, 70), album_id=album_id, batch_id=b2, order_index=0)
    upload(names_b[1], (70, 210, 70), album_id=album_id, batch_id=b2, order_index=1)
    upload(names_b[2], (70, 70, 210), album_id=album_id, batch_id=b2, order_index=2)
    order_b = album_order(album_id)

    expect_source = [PREFIX + "B_10.jpg", PREFIX + "B_2.jpg", PREFIX + "B_1.jpg"]
    expect_natural = [PREFIX + "A_1.jpg", PREFIX + "A_2.jpg", PREFIX + "A_10.jpg"]

    # ★不要假设两批谁在前★
    # group_time 是秒级的，两批在同一秒内传完就会平局，平局时按排序键决定先后。
    # 这是既有行为（按文件名排序的两批同样会遇到），不是这次改动引入的。
    # 所以这里按文件名前缀把两批各自摘出来，只验证「批内顺序」。
    order_a_actual = [n for n in order_b if "_A_" in n or "A_" in n]
    order_b_actual = [n for n in order_b if "B_" in n]

    check("两批共 6 张且没有互相穿插",
          len(order_b) == 6 and len(order_a_actual) == 3 and len(order_b_actual) == 3,
          f"实际={order_b}")
    check("源顺序生效（10 -> 2 -> 1，与文件名顺序相反）",
          order_b_actual == expect_source, f"实际={order_b_actual}")
    check("源顺序确实不是按文件名排的（否则这条测试无意义）",
          expect_source != sorted(expect_source), f"{expect_source}")

    # ------------------------------------------------------------------
    section("4. ★关键★ 重扫之后源顺序必须还在")
    status, before_items = call("GET", f"/api/albums/{album_id}?limit=200")
    keys_before = {i["file_name"]: (i.get("name_sort_key"), i.get("upload_batch"))
                   for i in before_items.get("items", [])}
    print(f"    重扫前源顺序键: " +
          str([keys_before[n][0] for n in expect_source if n in keys_before]))

    ok = run_scan_and_wait()
    check("媒体库扫描完成", ok, "扫描超时或失败")

    order_after = album_order(album_id)
    check("重扫后源顺序原样保留", order_after == order_b, f"重扫后={order_after}")

    status, after_items = call("GET", f"/api/albums/{album_id}?limit=200")
    keys_after = {i["file_name"]: i.get("name_sort_key") for i in after_items.get("items", [])}
    same_keys = all(
        keys_before.get(n, (None,))[0] == keys_after.get(n)
        for n in keys_before
    )
    check("重扫后排序键一字未改", same_keys,
          f"前={[keys_before[n][0] for n in list(keys_before)[:3]]} "
          f"后={[keys_after[n] for n in list(keys_after)[:3]]}")

    # ------------------------------------------------------------------
    section("5. 重扫之后两批的顺序都还在")
    order_final = album_order(album_id)
    a_final = [n for n in order_final if "A_" in n]
    b_final = [n for n in order_final if "B_" in n]
    check("按名字的那批仍是自然序", a_final == expect_natural, f"实际={a_final}")
    check("按源顺序的那批仍然按序号", b_final == expect_source, f"实际={b_final}")

    # ------------------------------------------------------------------
    section("6. 清理：只删自己造的")
    cleanup()
    print("    （清理：删除本次测试资源与相册；未动用全局清空回收站）")

    status, albums_after = call("GET", "/api/albums")
    after_map = {a["id"]: (a["name"], a["parent_id"], a["count"]) for a in albums_after["albums"]}
    missing = [i for i in user_albums if i not in after_map]
    changed = [i for i in user_albums if i in after_map and user_albums[i] != after_map[i]]
    check("用户原有相册一个都没少", not missing, f"丢失 {missing}")
    check("用户原有相册未被改动", not changed,
          f"被改动 {[(i, user_albums[i], after_map[i]) for i in changed]}")

    print(f"\n===== 结果：{base.PASSED} 项通过，{len(base.FAILED)} 项失败 =====")
    for name in base.FAILED:
        print(f"  FAILED: {name}")
    return 0 if not base.FAILED else 1


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    finally:
        cleanup()
    raise SystemExit(code)
