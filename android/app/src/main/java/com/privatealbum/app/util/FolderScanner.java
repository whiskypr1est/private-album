package com.privatealbum.app.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;

/**
 * 从「选择文件夹」返回的 tree URI 里，递归列出所有图片 / 视频。
 *
 * 系统给的 ACTION_OPEN_DOCUMENT_TREE 只返回一个目录 URI，
 * 里面的文件要自己用 DocumentsContract 枚举。这里做递归（含子文件夹），
 * 并顺手过滤出相册支持的扩展名，避免把 .txt / .pdf 也传上去。
 */
public final class FolderScanner {

    /** 支持的扩展名（和服务端 IMAGE_EXTENSIONS / VIDEO_EXTENSIONS 对齐） */
    private static final String[] EXTENSIONS = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp",
            ".heic", ".heif", ".avif", ".tif", ".tiff",
            ".mp4", ".mov", ".m4v", ".3gp", ".mkv", ".webm", ".avi",
    };

    private static final int MAX_FILES = 3000;   // 防止误选整个存储卡把上传队列撑爆
    private static final int MAX_DEPTH = 8;

    public interface Progress {
        void onScanning(int found);

        void onDone(List<Uri> files, int skipped);
    }

    private FolderScanner() {
    }

    public static boolean isSupported(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** 递归扫描；在后台线程调用。 */
    public static void scan(Context context, Uri treeUri, Progress progress) {
        List<Uri> found = new ArrayList<>();
        int[] skipped = new int[]{0};
        try {
            Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            walk(context, rootDoc, found, skipped, progress, 0);
        } catch (Exception e) {
            Trace.log("扫描文件夹失败: " + e);
        }
        if (progress != null) progress.onDone(found, skipped[0]);
    }

    private static void walk(Context context, Uri documentUri, List<Uri> out, int[] skipped,
                             Progress progress, int depth) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        String docId = DocumentsContract.getDocumentId(documentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(documentUri, docId);

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
            }, null, null, null);
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                if (out.size() >= MAX_FILES) return;
                String childId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(documentUri, childId);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(context, childUri, out, skipped, progress, depth + 1);
                } else if (isSupported(name)) {
                    out.add(childUri);
                    if (progress != null && out.size() % 20 == 0) progress.onScanning(out.size());
                } else {
                    skipped[0]++;
                }
            }
        } catch (Exception e) {
            Trace.log("遍历子目录失败: " + e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}
