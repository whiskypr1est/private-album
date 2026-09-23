package com.privatealbum.app.util;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 系统栏（状态栏 / 导航栏）避让。
 *
 * Android 15（API 35）起，targetSdk >= 35 的应用**强制全屏（edge-to-edge）**，
 * 而且 setStatusBarColor / setNavigationBarColor 这类 API 直接失效。
 * 结果就是：内容会画到状态栏底下，出现「通知栏的时间和图标压住 App 顶栏」。
 *
 * 正确做法不是去关掉全屏，而是给顶部/底部的容器补上系统栏高度的内边距。
 *
 * 命名注意：不要叫 Insets —— 会和 androidx.core.graphics.Insets 撞名，
 * 同一个文件里就没法同时用了。
 */
public final class SystemBars {

    private SystemBars() {
    }

    private static int systemBarsType() {
        return WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
    }

    /** 让某个 View 自动避开状态栏（内容贴到状态栏下方）。 */
    public static void padTop(final View view) {
        if (view == null) return;
        final int initialTop = view.getPaddingTop();
        final int initialBottom = view.getPaddingBottom();
        final int initialLeft = view.getPaddingLeft();
        final int initialRight = view.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(systemBarsType());
            v.setPadding(initialLeft, initialTop + bars.top, initialRight, initialBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** 让某个 View 自动避开导航栏（内容抬到导航栏上方）。 */
    public static void padBottom(final View view) {
        if (view == null) return;
        final int initialBottom = view.getPaddingBottom();
        final int initialLeft = view.getPaddingLeft();
        final int initialRight = view.getPaddingRight();
        final int initialTop = view.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(systemBarsType());
            v.setPadding(initialLeft, initialTop, initialRight, initialBottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 用 margin 而不是 padding 避让底部导航栏。
     * 悬浮按钮（FAB）改 padding 会被撑变形，必须改 margin。
     */
    public static void marginBottom(final View view) {
        if (view == null) return;
        if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        final int initialBottom = ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(systemBarsType());
            ViewGroup.LayoutParams raw = v.getLayoutParams();
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
                params.bottomMargin = initialBottom + bars.bottom;
                v.setLayoutParams(params);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
