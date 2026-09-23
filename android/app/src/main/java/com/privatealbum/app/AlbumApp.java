package com.privatealbum.app;

import android.app.Application;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.privatealbum.app.util.CrashReporter;
import com.privatealbum.app.util.Trace;

/** 应用入口：全局配置 + 崩溃捕获。 */
public class AlbumApp extends Application {

    private static final String TAG = "PhotoAlbumApp";

    private static AlbumApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Trace.init(this);
        // 启动标记：如果下一次启动看到这个标记还在，说明上次是「没走到 Java 异常处理就没了」
        // —— 那多半是原生层崩溃（NDK / 图形驱动 / 系统限制），不是 Java 异常。
        String marker = Trace.readMarker();
        if (marker != null) {
            Trace.log("⚠ 上一次启动未正常结束（可能是原生崩溃或进程被杀）。标记内容：\n" + marker);
        }
        Trace.writeMarker("版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE
                + ") 于 " + new java.util.Date());
        // 第一个界面成功显示后清掉标记：之后的崩溃由 CrashReporter 负责记录
        registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            private boolean cleared = false;

            @Override
            public void onActivityResumed(android.app.Activity activity) {
                if (!cleared) {
                    cleared = true;
                    Trace.clearMarker();
                    Trace.log("首个界面已显示，启动完成");
                }
            }

            @Override
            public void onActivityCreated(android.app.Activity activity, android.os.Bundle bundle) {
            }

            @Override
            public void onActivityStarted(android.app.Activity activity) {
            }

            @Override
            public void onActivityPaused(android.app.Activity activity) {
            }

            @Override
            public void onActivityStopped(android.app.Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle bundle) {
            }

            @Override
            public void onActivityDestroyed(android.app.Activity activity) {
            }
        });
        Trace.log("Application.onCreate 开始");
        CrashReporter.install(this);
        Trace.log("崩溃捕获已安装，版本 " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
        Log.i(TAG, "启动 私有相册 " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
    }

    public static AlbumApp get() {
        return instance;
    }

    /** 缩略图一律走磁盘缓存，离线也能看已浏览过的照片。 */
    public static RequestOptions thumbOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(com.privatealbum.app.R.color.placeholder)
                .error(com.privatealbum.app.R.drawable.ic_photo);
    }

    public static RequestOptions previewOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .placeholder(com.privatealbum.app.R.color.placeholder);
    }

    public static void clearCaches() {
        Glide.get(instance).clearMemory();
        new Thread(() -> Glide.get(instance).clearDiskCache()).start();
    }
}
