#!/usr/bin/env python3
"""嵌套相册（子相册）端到端测试。

用法（在树莓派上）：
    venv/bin/python server/scripts/e2e_album_tree_test.py [BASE] [USER] [PASSWORD]

覆盖：任意深度的父子相册、面包屑、子相册列表、直接/合计张数、
      移动相册（含移回顶层）、成环拦截、自环拦截、父相册不存在、
      删除相册时子相册上移而非级联删除、以及「全程不碰用户已有相册」。

【安全约定】本脚本只删除自己创建的相册与资源：
  · 相册名一律以 e2e 开头，删除前再次校验名字前缀
  · 只删除自己上传的资源 id
  · 绝不再调用 trash/purge 这类全局破坏性接口（曾因此误删过用户的真实照片）
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import e2e_test as base  # noqa: E402
from e2e_test import check, make_image, section, upload_bytes  # noqa: E402

PREFIX = "e2e树"
created_albums: list[int] = []
created_assets: list[int] = []


def api(method: str, path: str, body=None):
    return base.call(method, path, body)


def new_album(name: str, parent_id=None):
    body = {"name": name}
    if parent_id is not None:
        body["parent_id"] = parent_id
    status, data = api("POST", "/api/albums", body)
    if status == 200:
        created_albums.append(data["id"])
        return data["id"]
    return None


def album_of(album_id: int):
    status, data = api("GET", f"/api/albums/{album_id}")
    return data if status == 200 else {}


def find(album_id: int):
    """从 /api/albums 里取出某个相册的概要。"""
    status, data = api("GET", "/api/albums")
    for a in data.get("albums", []):
        if a["id"] == album_id:
            return a
    return {}


def main() -> int:
    section("登录")
    status, login = base.call("POST", "/api/login",
                              {"username": base.USER, "password": base.PASSWORD}, auth=False)
    if status != 200:
        print(f"  登录失败 {status} {login}")
        return 1
    base.TOKEN = login["token"]
    check("登录成功", True)

    # ------------------------------------------------------------------
    section("0. 先记录用户已有相册结构（测试期间必须保持不变）")
    status, before_all = api("GET", "/api/albums")
    user_albums = {a["id"]: (a["name"], a["parent_id"], a["count"])
                   for a in before_all["albums"]}
    check("能读到相册列表", status == 200, f"{status}")
    print(f"    当前共 {len(user_albums)} 个相册")

    # ------------------------------------------------------------------
    section("1. 建三层相册 A > B > C")
    a = new_album(PREFIX + "A")
    check("建顶层相册 A", a is not None, str(a))
    b = new_album(PREFIX + "B", parent_id=a)
    check("在 A 里建子相册 B", b is not None, str(b))
    c = new_album(PREFIX + "C", parent_id=b)
    check("在 B 里建子相册 C（第三层）", c is not None, str(c))

    if not (a and b and c):
        print("  建相册失败，中止")
        return 1

    pa = find(a)
    check("A 的 parent_id 为空（顶层）", pa.get("parent_id") is None, str(pa.get("parent_id")))
    check("A 的 child_count = 1", pa.get("child_count") == 1, str(pa.get("child_count")))
    pb = find(b)
    check("B 的 parent_id = A", pb.get("parent_id") == a, f"{pb.get('parent_id')} vs {a}")

    # ------------------------------------------------------------------
    section("2. 面包屑")
    detail_c = album_of(c)
    crumb = [x["id"] for x in detail_c.get("breadcrumb", [])]
    check("C 的面包屑是 A > B > C", crumb == [a, b, c], str(crumb))
    detail_a = album_of(a)
    crumb_a = [x["id"] for x in detail_a.get("breadcrumb", [])]
    check("A 的面包屑只有自己", crumb_a == [a], str(crumb_a))

    # ------------------------------------------------------------------
    section("3. 子相册列表")
    subs_a = [x["id"] for x in detail_a.get("sub_albums", [])]
    check("A 的子相册里有 B 且只有 B", subs_a == [b], str(subs_a))
    subs_b = [x["id"] for x in album_of(b).get("sub_albums", [])]
    check("B 的子相册里有 C", subs_b == [c], str(subs_b))
    check("C 没有子相册", album_of(c).get("sub_albums") == [], str(album_of(c).get("sub_albums")))

    # ------------------------------------------------------------------
    section("4. 照片计入合计：把一张照片放进最深的 C")
    payload = make_image((30, 140, 200), text="TREE")
    status, result = upload_bytes(payload, "e2e_树测试.jpg")
    asset = result.get("asset") if isinstance(result, dict) else None
    check("上传测试照片", status == 200 and asset, f"{status} {result}")
    if not asset:
        print("  上传失败，中止")
        return 1
    created_assets.append(asset["id"])

    status, _ = api("POST", f"/api/albums/{c}/items", {"ids": [asset["id"]], "remove": False})
    check("把照片加入最深层的 C", status == 200, str(status))

    check("C 直接 1 张", find(c).get("count") == 1, str(find(c).get("count")))
    check("B 直接 0 张但合计 1 张（聚合了子相册）",
          find(b).get("count") == 0 and find(b).get("total_count") == 1,
          f"count={find(b).get('count')} total={find(b).get('total_count')}")
    check("A 合计也是 1 张（跨两层聚合）", find(a).get("total_count") == 1,
          str(find(a).get("total_count")))

    # ------------------------------------------------------------------
    section("5. 移动相册")
    status, _ = api("POST", f"/api/albums/{c}/move", {"parent_id": a})
    check("把 C 从 B 移到 A（跨层）", status == 200 and find(c).get("parent_id") == a,
          f"{status} parent={find(c).get('parent_id')}")
    check("B 现在没有子相册", find(b).get("child_count") == 0, str(find(b).get("child_count")))

    status, _ = api("POST", f"/api/albums/{b}/move", {"parent_id": None})
    check("把 B 移回顶层", status == 200 and find(b).get("parent_id") is None,
          f"{status} parent={find(b).get('parent_id')}")

    # ------------------------------------------------------------------
    section("6. 成环保护")
    status, data = api("POST", f"/api/albums/{a}/move", {"parent_id": c})
    check("把 A 移进自己的子孙 C 里 -> 400 拒绝", status == 400, f"{status} {data}")
    check("拒绝后 A 仍在顶层（没被改坏）", find(a).get("parent_id") is None,
          str(find(a).get("parent_id")))

    status, data = api("POST", f"/api/albums/{a}/move", {"parent_id": a})
    check("把 A 移进它自己 -> 400 拒绝", status == 400, f"{status} {data}")

    # ------------------------------------------------------------------
    section("7. 错误处理")
    status, data = api("POST", "/api/albums", {"name": PREFIX + "孤儿", "parent_id": 99999999})
    check("父相册不存在 -> 404", status == 404, f"{status} {data}")

    status, data = api("POST", "/api/albums/99999999/move", {"parent_id": None})
    check("移动不存在的相册 -> 404", status == 404, f"{status} {data}")

    status, data = api("GET", "/api/albums/99999999")
    check("访问不存在的相册详情 -> 404", status == 404, f"{status} {data}")

    # ------------------------------------------------------------------
    section("8. 删除相册时子相册上移（不是级联删除）")
    # 现在结构：B 在顶层；A 在顶层，A 下面是 C
    status, data = api("DELETE", f"/api/albums/{a}")
    check("删除 A 成功", status == 200, f"{status} {data}")
    created_albums.remove(a)
    check("返回上移的子相册数 = 1", data.get("promoted_children") == 1, str(data))
    check("C 没有跟着被删（相册还在）", bool(find(c)), "C 不见了！")
    check("C 被上移到顶层", find(c).get("parent_id") is None, str(find(c).get("parent_id")))
    check("C 里的照片还在", find(c).get("count") == 1, str(find(c).get("count")))

    # ------------------------------------------------------------------
    section("9. 清理：只删自己建的")
    cleanup()
    print("    （清理：删除本次测试相册与资源；未动用全局清空回收站）")

    # ------------------------------------------------------------------
    section("10. 用户原有相册必须原样未动")
    status, after_all = api("GET", "/api/albums")
    after_map = {a["id"]: (a["name"], a["parent_id"], a["count"]) for a in after_all["albums"]}
    missing = [i for i in user_albums if i not in after_map]
    check("用户原有相册一个都没少", not missing, f"丢失 {missing}")
    changed = [i for i in user_albums if i in after_map and user_albums[i] != after_map[i]]
    check("用户原有相册的名称/父级/张数都没变", not changed,
          f"被改动 {[(i, user_albums[i], after_map[i]) for i in changed]}")
    leftovers = [a["name"] for a in after_all["albums"]
                 if str(a["name"]).startswith(PREFIX)]
    check("没有测试相册残留", not leftovers, str(leftovers))

    print(f"\n===== 结果：{base.PASSED} 项通过，{len(base.FAILED)} 项失败 =====")
    for name in base.FAILED:
        print(f"  FAILED: {name}")
    return 0 if not base.FAILED else 1


def cleanup() -> None:
    """只删本测试创建的相册与资源，删前再按名字前缀校验一遍。"""
    for aid in list(created_albums):
        info = find(aid)
        if not info:
            created_albums.remove(aid)
            continue
        if not str(info.get("name", "")).startswith(PREFIX):
            print(f"    ★ 跳过非本测试的相册 {aid} {info.get('name')}")
            continue
        api("DELETE", f"/api/albums/{aid}")
        created_albums.remove(aid)
    for asset_id in created_assets:
        api("DELETE", f"/api/assets/{asset_id}")
    created_assets.clear()


if __name__ == "__main__":
    # 无论正常结束还是中途抛异常，都要把测试相册清干净，
    # 否则会把一堆 e2e 空相册留在用户的相册列表里（上一版就踩到了）。
    code = 1
    try:
        code = main()
    finally:
        cleanup()
    raise SystemExit(code)
