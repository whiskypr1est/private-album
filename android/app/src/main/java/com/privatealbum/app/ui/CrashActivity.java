package com.privatealbum.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 崩溃展示页：把堆栈直接显示出来，并支持复制，方便截图或粘贴反馈。
 * 写这个页面是因为 App 主要在内网使用，出问题时不一定方便连电脑抓 logcat。
 */
public class CrashActivity extends AppCompatActivity {

    public static final String EXTRA_REPORT = "report";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String report = getIntent().getStringExtra(EXTRA_REPORT);
        if (report == null) {
            report = com.privatealbum.app.util.CrashReporter.readLast(this);
        }
        if (report == null) report = "（没有读取到崩溃信息）";

        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF14161A);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("出错了（崩溃信息如下）");
        title.setTextColor(0xFFF28B82);
        title.setTextSize(18f);
        title.setPadding(0, 0, 0, pad / 2);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("请把这页截图发给开发者。也可以点「复制」后粘贴。\n"
                + "记录同时保存在：Android/data/com.privatealbum.app/files/crash-last.txt");
        hint.setTextColor(0xFF9AA0A6);
        hint.setTextSize(12f);
        hint.setPadding(0, 0, 0, pad / 2);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(report);
        body.setTextColor(0xFFECEEF1);
        body.setTextSize(11f);
        body.setTextIsSelectable(true);
        body.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        scroll.addView(body);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollParams);
        root.addView(scroll);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button copy = new Button(this);
        copy.setText("复制");
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", body.getText()));
                android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copy.setLayoutParams(buttonParams);
        buttons.addView(copy);

        Button restart = new Button(this);
        restart.setText("重新打开");
        restart.setOnClickListener(v -> {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
        restart.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(restart);

        root.addView(buttons);

        View container = root;
        setContentView(container);
    }
}
