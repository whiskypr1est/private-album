package com.privatealbum.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 极简诊断页：**完全不用 Material 组件、不用 Fragment、不读主题属性**，
 * 只用最朴素的原生 View。
 *
 * 用途：当主界面闪退时，用它来判断问题出在哪一层：
 *   - 这个页面能正常打开 => Activity 启动、主题、清单都没问题，
 *     那问题就在 Material 组件 / Fragment / RecyclerView / Glide 那一层；
 *   - 这个页面也打不开 => 问题在更底层（主题、Application、系统限制）。
 *
 * 入口：adb shell am start -n com.privatealbum.app/.ui.BasicActivity
 *      或者从登录页右上角的「诊断」按钮进入。
 */
public class BasicActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.privatealbum.app.util.Trace.log("BasicActivity.onCreate（极简页）");

        int pad = dp(16);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF14161A);

        TextView title = new TextView(this);
        title.setText("极简诊断页");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20f);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("这个页面不使用 Material 组件、不使用 Fragment、不读主题属性。\n"
                + "如果你能看到这一页，说明：\n"
                + "  · Activity 能正常启动\n"
                + "  · 清单文件与主题配置没问题\n"
                + "  · Application 初始化没问题\n"
                + "那么闪退的原因就在「Material 组件 / Fragment / RecyclerView / Glide」这一层。\n\n"
                + "设备：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n"
                + "系统：Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
                + "应用：私有相册 " + com.privatealbum.app.BuildConfig.VERSION_NAME
                + " (" + com.privatealbum.app.BuildConfig.VERSION_CODE + ")\n"
                + "当前界面：" + getClass().getName());
        info.setTextColor(0xFFAAAAAA);
        info.setTextSize(12f);
        info.setPadding(0, pad, 0, pad);
        info.setTextIsSelectable(true);
        root.addView(info);

        Button back = new Button(this);
        back.setText("返回登录页");
        back.setOnClickListener(v -> finish());
        root.addView(back);

        Button main = new Button(this);
        main.setText("尝试打开主界面（可能闪退）");
        main.setOnClickListener(v -> {
            com.privatealbum.app.util.Trace.log("BasicActivity: 尝试打开 MainActivity");
            try {
                startActivity(new android.content.Intent(this, MainActivity.class));
            } catch (Throwable t) {
                com.privatealbum.app.util.Trace.log("打开 MainActivity 失败: " + t);
                TextView error = new TextView(this);
                error.setText("打开主界面失败：" + t);
                error.setTextColor(0xFFFF8888);
                root.addView(error);
            }
        });
        root.addView(main);

        Button report = new Button(this);
        report.setText("查看上次崩溃/启动日志");
        report.setOnClickListener(v -> {
            String crash = com.privatealbum.app.util.CrashReporter.readLast(this);
            String trace = com.privatealbum.app.util.Trace.readPrevious();
            String current = com.privatealbum.app.util.Trace.read(this);
            StringBuilder sb = new StringBuilder();
            sb.append("===== 崩溃堆栈 =====\n").append(crash == null ? "（没有记录）" : crash);
            sb.append("\n===== 上次启动日志 =====\n").append(trace == null ? "（没有记录）" : trace);
            sb.append("\n===== 本次启动日志 =====\n").append(current == null ? "（没有记录）" : current);

            TextView view = new TextView(this);
            view.setText(sb.toString());
            view.setTextColor(0xFFCCCCCC);
            view.setTextSize(10f);
            view.setTextIsSelectable(true);
            root.addView(view);
        });
        root.addView(report);

        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        com.privatealbum.app.util.Trace.log("BasicActivity 显示完成（如果你能看到这一页，说明底层没问题）");
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
