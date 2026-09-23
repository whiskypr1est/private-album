#!/usr/bin/env python3
"""自然排序 / 批量上传 / 相册归属 的专项测试。

核心验证目标（用户明确提出的需求）：
  在同一批上传的图片里，按文件名排序必须是
      1.png, 2.png, 3.png, ..., 10.png, 11.png, ..., 100.png, 101.png
  而**不能**是字典序的
      1.png, 10.png, 100.png, 101.png, 11.png, 2.png, ...

另外验证：
  · 上传时直接指定 album_id，文件会自动进入该相册
  · 秒传（相同 SHA-256）进来的文件也会归入目标相册
  · 分页游标在「按上传时间 + 文件名自然序」下不漏不重

用法：venv/bin/python scripts/e2e_sort_test.py
"""
from __future__ import annotations

import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))
import e2e_test as base  # noqa: E402
from e2e_test import BASE, PASSWORD, USER, call, check, section  # noqa: E402
from util import natural_sort_key  # noqa: E402


def make_png(color, size=(320, 240)) -> bytes:
    from PIL import Image

    img = Image.new("RGB", size, color)
    buffer = io.BytesIO()
    img.save(buffer, "PNG")
    return buffer.getvalue()


def upload_named(name: str, payload: bytes, album_id=None, batch_id=None,
                 taken_at=None) -> dict | None:
    """按名字上传一张，返回 asset。用 base 的分片流程，但支持 album/batch。"""
    body = {"file_name": name, "size_bytes": len(payload)}
    if album_id is not None:
        body["album_id"] = album_id
    if batch_id:
        body["batch_id"] = batch_id
    if taken_at:
        body["taken_at"] = taken_at
    status, init = call("POST", "/api/uploads/init", body)
    if status != 200:
        print(f"    init 失败 {name}: {status} {init}")
        return None
    if isinstance(init, dict) and init.get("duplicate"):
        return init.get("asset")
    upload_id = init["upload_id"]
    offset = 0
    chunk = 64 * 1024
    while offset < len(payload):
        piece = payload[offset:offset + chunk]
        status, res = call("PUT", f"/api/uploads/{upload_id}/chunk", piece,
                           headers={"Content-Type": "application/octet-stream",
                                    "X-Chunk-Offset": str(offset)})
        if status != 200 or not isinstance(res, dict) or not res.get("ok"):
            print(f"    chunk 失败 {name}: {status} {res}")
            return None
        offset += len(piece)
    done_body = {"upload_id": upload_id, "file_name": name}
    if taken_at:
        done_body["taken_at"] = taken_at
    status, done = call("POST", "/api/uploads/complete", done_body)
    if status != 200:
        print(f"    complete 失败 {name}: {status} {done}")
        return None
    return done.get("asset")


def main() -> int:
    section("登录")
    status, login = call("POST", "/api/login", {"username": USER, "password": PASSWORD}, auth=False)
    if status != 200:
        print(f"  登录失败 {status} {login}")
        return 1
    base.TOKEN = login["token"]
    check("登录成功", True)

    # ------------------------------------------------------------------
    section("1. 自然排序键本身的正确性")
    ordered = sorted(
        ["1.png", "10.png", "100.png", "101.png", "11.png", "2.png", "20.png", "3.png"],
        key=natural_sort_key,
    )
    expect = ["1.png", "2.png", "3.png", "10.png", "11.png", "20.png", "100.png", "101.png"]
    check("纯数字序号排序正确", ordered == expect, f"{ordered}")
    check("字典序会错、自然序才对",
          ordered != sorted(["1.png", "10.png", "100.png", "101.png", "11.png",
                             "2.png", "20.png", "3.png"]),
          "（说明测试本身有效）")

    # ------------------------------------------------------------------
    section("2. 相册中新建 + 指定 album_id 批量上传")
    status, album = call("POST", "/api/albums", {"name": "排序测试相册", "description": "自动创建"})
    album_id = album.get("id") if isinstance(album, dict) else None
    check("新建相册", status == 200 and album_id is not None, f"{status} {album}")
    if not album_id:
        return 1

    # 故意用「字典序会乱」的一组文件名，且故意乱序上传
    names = ["100.png", "2.png", "10.png", "1.png", "101.png", "3.png", "11.png", "20.png"]
    batch = f"sort-test-{int(time.time())}"
    uploaded = []
    for index, name in enumerate(names):
        payload = make_png((index * 25 % 255, 80, 160))
        asset = upload_named(name, payload, album_id=album_id, batch_id=batch)
        if asset:
            uploaded.append(asset)
    check(f"批量上传 {len(names)} 张（同一 batch）", len(uploaded) == len(names),
          f"成功 {len(uploaded)}/{len(names)}")

    # 等后台缩略图跑完，避免干扰
    time.sleep(2)

    # ------------------------------------------------------------------
    section("3. 相册内的实际返回顺序（核心需求）")
    status, detail = call("GET", f"/api/albums/{album_id}?limit=200")
    check("读取相册内容", status == 200 and detail.get("items"), f"{status}")
    got = [item["file_name"] for item in detail.get("items", [])]
    want = sorted(names, key=natural_sort_key)
    print(f"    服务器返回: {got}")
    print(f"    期望顺序  : {want}")
    check("相册内容按文件名自然序排列", got == want, f"实际={got}")
    check("不是字典序",
          got != sorted(names),
          "（如果这里失败，说明退化成了字典序）")

    # ------------------------------------------------------------------
    section("3b. 第二批上传应该整体排在第一批前面，且批内仍按文件名自然序")
    names2 = ["10.png", "2.png", "1.png"]
    batch2 = f"sort-test-2-{int(time.time())}"
    for index, name in enumerate(names2):
        upload_named(name + ".b2.png", make_png((20, index * 40 % 255, 90)),
                     album_id=album_id, batch_id=batch2)
    time.sleep(2)
    status, detail_new = call("GET", f"/api/albums/{album_id}?limit=200")
    all_names = [i["file_name"] for i in detail_new.get("items", [])]
    print(f"    两批合并后: {all_names}")
    # 第二批应该排在前面
    first_names = set(n + ".b2.png" for n in names2)
    head = all_names[:len(names2)]
    check("新的一批整体排在最前", set(head) == first_names, f"前三项={head}")
    check("第二批内部仍是自然序", head == ["1.png.b2.png", "2.png.b2.png", "10.png.b2.png"],
          f"实际={head}")
    # 第一批应该完整地跟在后面，没有被新批次插散
    tail = all_names[len(names2):]
    check("第一批保持完整且有序", tail == want, f"实际={tail}")
    for item in detail_new.get("items", []):
        if item["file_name"].endswith(".b2.png"):
            call("DELETE", f"/api/assets/{item['id']}")

    # ------------------------------------------------------------------
    section("4. 全库列表顺序（按上传时间倒序 + 文件名自然序）")
    status, page = call("GET", "/api/assets?limit=200")
    items = page.get("items", [])
    check("全库列表可读", status == 200 and len(items) >= len(names), f"{len(items)}")

    # 找出属于本批的那些，检查它们在列表里的相对顺序
    batch_items = [i for i in items if i.get("upload_batch") == batch]
    batch_names = [i["file_name"] for i in batch_items]
    print(f"    本批在列表中的顺序: {batch_names}")
    # 列表是倒序（最新在前），所以本批内部应按「上一次上传的那批整体在前」，
    # 但批内一定还是自然升序。这里只验证批内升序。
    check("同一批内仍是自然升序", batch_names == sorted(batch_names, key=natural_sort_key),
          f"实际={batch_names}")

    # ------------------------------------------------------------------
    section("5. 分页游标在自然序下不漏不重")
    # 注意：不能写死翻页次数。这个库会随使用不断变大（现已 500+ 张），
    # 翻页上限必须由权威总数推导，否则库一大就必然"翻不完"而误报失败；
    # 也不能拿 limit=500 当"全量"——接口上限就是 500，那样比的两边都是截断的。
    status, stats = call("GET", "/api/library/stats")
    total = int(stats.get("total", 0)) if status == 200 else 0
    check("能读到媒体库权威总数", total > 0, f"{total}")

    PAGE = 7                      # 故意取一个不是总数因数的页大小
    seen: list[int] = []
    cursor = None
    pages = 0
    max_pages = (total // PAGE) + 10
    while pages < max_pages:
        path = f"/api/assets?limit={PAGE}" + (f"&cursor={cursor}" if cursor else "")
        status, chunk_page = call("GET", path)
        if status != 200:
            break
        seen.extend(int(item["id"]) for item in chunk_page.get("items", []))
        pages += 1
        cursor = chunk_page.get("next_cursor")
        if not cursor:
            break

    check("分页没有重复项", len(seen) == len(set(seen)),
          f"{len(seen)} 项 / {len(set(seen))} 唯一")
    check("分页能翻到库尾（游标最终耗尽）", not cursor, f"翻了 {pages} 页后仍有游标")
    check(f"分页覆盖全部 {total} 张（每页 {PAGE} 条）", len(seen) == total,
          f"分页取回 {len(seen)} / 权威总数 {total}")

    status, all_page = call("GET", "/api/assets?limit=500")
    all_ids = [int(i["id"]) for i in all_page.get("items", [])]
    check("翻页顺序与一次性读取逐条一致", all_ids == seen[:len(all_ids)],
          f"大页 {len(all_ids)} 条与小页顺序不一致")

    # ------------------------------------------------------------------
    section("6. 秒传进来的文件也归入目标相册")
    payload = make_png((11, 22, 33), size=(200, 200))
    first = upload_named("dedup_a.png", payload)
    check("先上传一张作为去重源", first is not None)
    if first:
        status, album2 = call("POST", "/api/albums", {"name": "秒传测试相册"})
        album2_id = album2.get("id")
        second = upload_named("dedup_a_copy.png", payload, album_id=album2_id)
        check("相同内容秒传成功", second is not None and second.get("id") == first.get("id"),
              f"{second.get('id') if second else None} vs {first.get('id')}")
        status, detail2 = call("GET", f"/api/albums/{album2_id}?limit=50")
        in_album = [i["id"] for i in detail2.get("items", [])]
        check("秒传的文件已进入目标相册", first["id"] in in_album, f"相册内={in_album}")
        call("DELETE", f"/api/albums/{album2_id}")
        call("DELETE", f"/api/assets/{first['id']}")

    # ------------------------------------------------------------------
    section("7. 指定不存在的相册应当被拒绝")
    status, bad = call("POST", "/api/uploads/init",
                       {"file_name": "x.png", "size_bytes": 100, "album_id": 999999})
    check("album_id 不存在时返回 404", status == 404, f"{status} {bad}")
    status, bad2 = call("POST", "/api/uploads/init",
                        {"file_name": "x.png", "size_bytes": 100, "album_id": "abc"})
    check("album_id 非数字时返回 400", status == 400, f"{status} {bad2}")

    # ------------------------------------------------------------------
    # 清理：只删本次测试自己上传的东西。
    #
    # 【安全约定】绝不在测试里调用 /api/trash/purge ——
    # 这个脚本会跑在作者的正式服务器上，全局清空回收站会把用户自己的照片永久删掉。
    print("\n  （清理：删除本次测试产物）")
    for item in uploaded:
        call("DELETE", f"/api/assets/{item['id']}")
    call("DELETE", f"/api/albums/{album_id}")

    print(f"\n===== 结果：{base.PASSED} 项通过，{len(base.FAILED)} 项失败 =====")
    for name in base.FAILED:
        print(f"  FAILED: {name}")
    return 0 if not base.FAILED else 1


if __name__ == "__main__":
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    sys.exit(main())
