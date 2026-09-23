package com.privatealbum.app.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量启动日志：把关键步骤追加写到一个文件里。
 *
 * 用途：App 主要在内网使用，崩溃时不一定方便连电脑抓 logcat。
 * 这个日志能回答「崩在哪一行」——写完就能在手机上直接看到。
 *
 * 位置：Android/data/com.privatealbum.app/files/trace.log
 */
public final class Trace {

    private static final String TAG = "PhotoAlbumTrace";
    private static final String FILE_NAME = "trace.log";
    private static final long MAX_BYTES = 256 * 1024;

    private static Context appContext;
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Trace() {
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        reset();
    }

    /** 每次启动清空，避免只看到旧日志。 */
    public static synchronized void reset() {
        try {
            File file = resolveFile(false);
            if (file != null && file.exists()) {
                // 保留上一次的崩溃现场：如果上次是崩溃退出的，改名留档
                File previous = resolveFile(true);
                if (previous != null) {
                    file.renameTo(previous);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void log(String message) {
        String line = FORMAT.format(new Date()) + "  " + message;
        Log.i(TAG, message);
        try {
            File file = resolveFile(false);
            if (file == null) return;
            if (file.exists() && file.length() > MAX_BYTES) {
                // 简单截断，避免无限增长
                FileWriter truncate = new FileWriter(file, false);
                truncate.write("(日志过长，已截断)\n");
                truncate.close();
            }
            FileWriter writer = new FileWriter(file, true);
            writer.write(line);
            writer.write('\n');
            writer.close();
        } catch (Throwable ignored) {
            // 日志失败绝不能影响主流程
        }
    }

    /** 写一个「启动标记」：正常退出时会清掉，没清掉就说明上次异常结束。 */
    public static synchronized void writeMarker(String text) {
        try {
            File file = resolveMarker();
            if (file == null) return;
            FileWriter writer = new FileWriter(file, false);
            writer.write(text == null ? "" : text);
            writer.close();
        } catch (Throwable ignored) {
        }
    }

    public static String readMarker() {
        try {
            File file = resolveMarker();
            if (file == null || !file.exists()) return null;
            if (file.length() == 0) return null;
            byte[] raw = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = in.read(raw);
                if (read <= 0) return null;
                return new String(raw, 0, read, "UTF-8");
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized void clearMarker() {
        try {
            File file = resolveMarker();
            if (file != null) file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static File resolveMarker() {
        if (appContext == null) return null;
        try {
            File dir = appContext.getExternalFilesDir(null);
            if (dir == null) dir = appContext.getFilesDir();
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, "running.marker");
        } catch (Throwable t) {
            return null;
        }
    }

    public static String read(Context context) {
        try {
            File file = resolveFile(false);
            if (file == null || !file.exists()) return null;
            byte[] raw = new byte[(int) Math.min(file.length(), 512 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = in.read(raw);
                if (read <= 0) return null;
                return new String(raw, 0, read, "UTF-8");
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static String readPrevious() {
        try {
            File file = resolveFile(true);
            if (file == null || !file.exists()) return null;
            byte[] raw = new byte[(int) Math.min(file.length(), 512 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = in.read(raw);
                if (read <= 0) return null;
                return new String(raw, 0, read, "UTF-8");
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static File resolveFile(boolean previous) {
        if (appContext == null) return null;
        String name = previous ? FILE_NAME + ".prev" : FILE_NAME;
        try {
            File dir = appContext.getExternalFilesDir(null);
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                return new File(dir, name);
            }
        } catch (Throwable ignored) {
            // 外部存储不可用时退回内部存储
        }
        try {
            File dir = appContext.getFilesDir();
            if (dir != null) return new File(dir, name);
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static String externalRoot() {
        return Environment.getExternalStorageDirectory() == null ? "?" : "ok";
    }
}
