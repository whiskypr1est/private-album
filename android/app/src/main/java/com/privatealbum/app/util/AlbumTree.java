package com.privatealbum.app.util;

import com.privatealbum.app.model.Album;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 相册树的纯计算工具：把服务器返回的「扁平相册列表」整理成层级关系。
 *
 * 服务端只给一张平表（每个相册带 parent_id），层级关系全部在客户端算，
 * 这样导航一层不需要额外请求。这里只做纯函数，方便单独推演正确性。
 */
public final class AlbumTree {

    /** 防御性深度上限，避免脏数据成环时死循环。 */
    private static final int MAX_DEPTH = 32;

    private AlbumTree() {
    }

    /** 只保留顶层相册（parent_id 为空），顺序不变。 */
    public static List<Album> topLevel(List<Album> all) {
        List<Album> out = new ArrayList<>();
        if (all == null) return out;
        for (Album a : all) {
            if (a.isTopLevel()) out.add(a);
        }
        return out;
    }

    /** 某个相册的直接子相册（不含更深层），顺序不变。 */
    public static List<Album> childrenOf(List<Album> all, long parentId) {
        List<Album> out = new ArrayList<>();
        if (all == null) return out;
        for (Album a : all) {
            if (a.parentId != null && a.parentId == parentId) out.add(a);
        }
        return out;
    }

    /** 某个相册的全部后代 id（不含自己）。 */
    public static Set<Long> descendantsOf(List<Album> all, long albumId) {
        Set<Long> out = new HashSet<>();
        List<Long> frontier = new ArrayList<>();
        frontier.add(albumId);
        int guard = 0;
        while (!frontier.isEmpty() && guard++ < MAX_DEPTH) {
            List<Long> next = new ArrayList<>();
            for (Long p : frontier) {
                for (Album a : childrenOf(all, p)) {
                    if (out.add(a.id)) next.add(a.id);
                }
            }
            frontier = next;
        }
        return out;
    }

    /**
     * 可以作为 albumId 新父级的候选相册。
     * 必须排除它自己和它的所有后代，否则会形成环。
     */
    public static List<Album> moveTargets(List<Album> all, long albumId) {
        Set<Long> forbidden = descendantsOf(all, albumId);
        forbidden.add(albumId);
        List<Album> out = new ArrayList<>();
        if (all == null) return out;
        for (Album a : all) {
            if (!forbidden.contains(a.id)) out.add(a);
        }
        return out;
    }

    /** 相册在树中的深度（顶层为 0）。 */
    public static int depthOf(List<Album> all, Album album) {
        int depth = 0;
        Long p = album == null ? null : album.parentId;
        Set<Long> seen = new HashSet<>();
        while (p != null && seen.add(p) && depth < MAX_DEPTH) {
            depth++;
            Album parent = find(all, p);
            p = parent == null ? null : parent.parentId;
        }
        return depth;
    }

    /** 带缩进的名字，让「移动到…」的候选列表一眼看出层级。 */
    public static String indentedName(List<Album> all, Album album) {
        StringBuilder sb = new StringBuilder();
        int depth = depthOf(all, album);
        for (int i = 0; i < depth; i++) sb.append("　　");
        return sb.append(album.name).toString();
    }

    public static Album find(List<Album> all, long id) {
        if (all == null) return null;
        for (Album a : all) {
            if (a.id == id) return a;
        }
        return null;
    }

    /** 把「根 -> 当前」的路径拼成一行，例如「Patreon / 小红（jw）」。 */
    public static String breadcrumbText(List<Album> all, long albumId) {
        List<String> names = new ArrayList<>();
        Long cur = albumId;
        Set<Long> seen = new HashSet<>();
        while (cur != null && seen.add(cur) && names.size() < MAX_DEPTH) {
            Album a = find(all, cur);
            if (a == null) break;
            names.add(a.name);
            cur = a.parentId;
        }
        if (names.size() <= 1) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = names.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
