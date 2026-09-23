package com.privatealbum.app.util;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：把未捕获异常的堆栈写到文件，并跳到一个能「看见」它的页面。
 *
 * 为什么需要它：这个 App 主要在家里内网用，出问题时用户往往不方便连电脑抓 logcat。
 * 把堆栈落到本地文件 + 显示在屏幕上，用户截图就能定位问题。
 *
 * 崩溃记录位置：Android/data/com.privatealbum.app/files/crash-last.txt
 */
public final class CrashReporter implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "PhotoAlbumCrash";
    private static final String FILE_NAME = "crash-last.txt";

    private static CrashReporter instance;
    private static Application app;
    private Thread.UncaughtExceptionHandler previous;
    private Activity currentActivity;

    private CrashReporter() {
    }

    public static void install(Application application) {
        if (instance != null) return;
        app = application;
        instance = new CrashReporter();
        instance.previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(instance);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                instance.currentActivity = activity;
            }

            @Override
            public void onActivityStarted(Activity activity) {
                instance.currentActivity = activity;
            }

            @Override
            public void onActivityResumed(Activity activity) {
                instance.currentActivity = activity;
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (instance.currentActivity == activity) instance.currentActivity = null;
            }
        });
        Log.i(TAG, "崩溃捕获已安装");
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String report = buildReport(thread, throwable);
        Log.e(TAG, report);
        // 先同步落盘：进程随时可能被系统收走，必须保证记录已经写出去
        writeToFile(app, report);
        // 再补一份到启动日志里，这样「查看上次崩溃信息」一次就能看到全过程
        try {
            com.privatealbum.app.util.Trace.log("!!! 崩溃 !!!\n" + report);
        } catch (Throwable ignored) {
        }

        // 试着把堆栈直接展示给用户（失败也要保证进程正常结束，不能二次崩溃）
        try {
            Intent intent = new Intent(app, com.privatealbum.app.ui.CrashActivity.class);
            intent.putExtra(com.privatealbum.app.ui.CrashActivity.EXTRA_REPORT, report);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            app.startActivity(intent);
        } catch (Throwable ignored) {
            // 展示失败就退回默认处理
        }

        // 给 CrashActivity 一点时间起来，然后照常结束进程
        try {
            Thread.sleep(900);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        }
    }

    private String buildReport(Thread thread, Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        printer.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        printer.println("线程: " + thread.getName());
        printer.println("机型: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        printer.println("应用: 私有相册 " + com.privatealbum.app.BuildConfig.VERSION_NAME
                + " (versionCode " + com.privatealbum.app.BuildConfig.VERSION_CODE + ")");
        if (currentActivity != null) {
            printer.println("崩溃时界面: " + currentActivity.getClass().getName());
        }
        printer.println();
        throwable.printStackTrace(printer);
        printer.flush();
        return writer.toString();
    }

    private static void writeToFile(Context context, String report) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File file = new File(dir, FILE_NAME);
            try (FileWriter fileWriter = new FileWriter(file, false)) {
                fileWriter.write(report);
            }
            Log.i(TAG, "崩溃记录已写入 " + file.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "写崩溃记录失败", t);
        }
    }

    /** 读取上一次的崩溃记录（没有则返回 null）。 */
    public static String readLast(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File file = new File(dir, FILE_NAME);
            if (!file.exists()) return null;
            byte[] raw = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = in.read(raw);
                if (read <= 0) return null;
            }
            return new String(raw, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
