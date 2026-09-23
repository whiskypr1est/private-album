package com.privatealbum.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.privatealbum.app.R;
import com.privatealbum.app.model.Responses;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.util.ServerConfig;
import com.privatealbum.app.util.Ui;

/**
 * 登录页：填地址 -> 测试连接（顺便看服务器状态）-> 登录或初始化账号。
 */
public class LoginActivity extends AppCompatActivity {

    private EditText hostInput;
    private EditText portInput;
    private EditText userInput;
    private EditText passwordInput;
    private TextView statusText;
    private ProgressBar progress;
    private AlbumApi api;
    private boolean serverNeedsSetup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 已经登录过就直接进主界面
        if (ServerConfig.isLoggedIn(this) && getIntent().getBooleanExtra("force_login", false) == false) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);
        api = new AlbumApi(this);

        hostInput = findViewById(R.id.host);
        portInput = findViewById(R.id.port);
        userInput = findViewById(R.id.username);
        passwordInput = findViewById(R.id.password);
        statusText = findViewById(R.id.status);
        progress = findViewById(R.id.progress);

        hostInput.setText(ServerConfig.host(this));
        portInput.setText(String.valueOf(ServerConfig.port(this)));
        userInput.setText(ServerConfig.username(this));

        findViewById(R.id.btnTest).setOnClickListener(v -> testConnection());
        findViewById(R.id.btnLogin).setOnClickListener(v -> login());

        // 登录页也放一个诊断入口：主界面崩了也不至于完全没法自检
        findViewById(R.id.btnDiag).setOnClickListener(v ->
                startActivity(new Intent(this, BasicActivity.class)));

        // 页签切换复现：自动切一遍四个页签，把崩在哪一步直接显示出来
        findViewById(R.id.btnReproduce).setOnClickListener(v ->
                startActivity(new Intent(this, ReproduceActivity.class)));

        // 把版本号写在页面上，方便确认装的是哪一版
        TextView versionView = findViewById(R.id.version);
        versionView.setText("v" + com.privatealbum.app.BuildConfig.VERSION_NAME
                + " (" + com.privatealbum.app.BuildConfig.VERSION_CODE + ")"
                + "  ·  " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE);
    }

    private void saveAddress() {
        ServerConfig.saveAddress(this,
                hostInput.getText().toString(),
                portInput.getText().toString());
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnLogin).setEnabled(!busy);
        findViewById(R.id.btnTest).setEnabled(!busy);
    }

    private void testConnection() {
        saveAddress();
        setBusy(true);
        statusText.setText(getText(R.string.login_testing));
        new Thread(() -> {
            try {
                Responses.Health health = api.health(null);
                runOnUiThread(() -> {
                    setBusy(false);
                    serverNeedsSetup = health.needsSetup;
                    StringBuilder sb = new StringBuilder();
                    sb.append("✅ ").append(getString(R.string.login_ok)).append('\n');
                    sb.append("服务端 ").append(health.app).append(' ').append(health.version).append('\n');
                    sb.append("照片 ").append(health.items).append(" 项");
                    if (health.diskFreeText != null) {
                        sb.append(" · 剩余 ").append(health.diskFreeText);
                    }
                    sb.append('\n');
                    sb.append("媒体目录 ").append(health.mediaRoot).append('\n');
                    sb.append("ffmpeg ").append(health.ffmpeg ? "✔" : "✘（视频缩略图不可用）")
                            .append(" · HEIC ").append(health.pillowHeif ? "✔" : "✘");
                    if (health.needsSetup) {
                        sb.append("\n\n⚠️ 服务端还没有账号，登录时会直接创建你填写的账号。");
                    }
                    statusText.setText(sb);
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("❌ " + getString(R.string.login_failed) + "\n" + e.getMessage()
                            + "\n\n地址：" + ServerConfig.baseUrl(LoginActivity.this));
                });
            }
        }).start();
    }

    private void login() {
        saveAddress();
        final String username = userInput.getText().toString().trim();
        final String password = passwordInput.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            Ui.toast(this, "请填写账号和密码");
            return;
        }
        setBusy(true);
        statusText.setText(getText(R.string.login_testing));
        new Thread(() -> {
            try {
                Responses.LoginResult result;
                if (serverNeedsSetup) {
                    result = api.setup(username, password);
                } else {
                    try {
                        result = api.login(username, password);
                    } catch (ApiException e) {
                        // 服务器还没账号时，第一次登录直接初始化
                        if (e.statusCode == 401) {
                            Responses.Health health = api.health(null);
                            if (health.needsSetup) {
                                result = api.setup(username, password);
                            } else {
                                throw e;
                            }
                        } else {
                            throw e;
                        }
                    }
                }
                ServerConfig.saveSession(this, result.token, result.username, result.expiresAt);
                final String loggedInUser = result.username;
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("✅ 登录成功：" + loggedInUser);
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("❌ " + getString(R.string.login_failed) + "\n" + e.getMessage());
                    Ui.toast(LoginActivity.this, e.getMessage());
                });
            }
        }).start();
    }

    @SuppressWarnings("unused")
    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("地址怎么填？")
                .setMessage("· 同一个 WiFi：填树莓派的局域网地址，例如 192.168.1.50，端口 8080\n"
                        + "· 装了 Tailscale：填树莓派的 Tailscale 地址，外网也能用\n"
                        + "· 也可以直接粘 http://192.168.1.50:8080，App 会自动解析")
                .setPositiveButton("知道了", null)
                .show();
    }
}
