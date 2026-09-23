package com.privatealbum.app.ui;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.privatealbum.app.R;
import com.privatealbum.app.model.Responses;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.util.ServerConfig;
import com.privatealbum.app.util.Ui;

/** 服务器地址 / 账号密码 / 存储信息。 */
public class ServerSettingsActivity extends AppCompatActivity {

    private AlbumApi api;
    private EditText host;
    private EditText port;
    private EditText oldPassword;
    private EditText newPassword;
    private TextView stats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_settings);
        api = new AlbumApi(this);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        com.privatealbum.app.util.SystemBars.padTop(toolbar);

        host = findViewById(R.id.host);
        port = findViewById(R.id.port);
        oldPassword = findViewById(R.id.oldPassword);
        newPassword = findViewById(R.id.newPassword);
        stats = findViewById(R.id.statsText);

        host.setText(ServerConfig.host(this));
        port.setText(String.valueOf(ServerConfig.port(this)));

        findViewById(R.id.btnSaveAddress).setOnClickListener(v -> {
            String base = ServerConfig.saveAddress(this,
                    host.getText().toString(), port.getText().toString());
            Ui.toast(this, "已保存地址：" + base);
            loadStats();
        });
        findViewById(R.id.btnChangePassword).setOnClickListener(v -> changePassword());
        loadStats();
    }

    private void changePassword() {
        final String oldPwd = oldPassword.getText().toString();
        final String newPwd = newPassword.getText().toString();
        if (oldPwd.isEmpty() || newPwd.isEmpty()) {
            Ui.toast(this, "请填写原密码和新密码");
            return;
        }
        if (newPwd.length() < 6) {
            Ui.toast(this, "新密码至少 6 位");
            return;
        }
        new Thread(() -> {
            try {
                api.changePassword(oldPwd, newPwd);
                runOnUiThread(() -> {
                    oldPassword.setText("");
                    newPassword.setText("");
                    Ui.toast(this, "密码已修改，下次登录请使用新密码");
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
            }
        }).start();
    }

    private void loadStats() {
        stats.setText(getText(R.string.more_loading));
        new Thread(() -> {
            try {
                Responses.Health health = api.health(null);
                Responses.Stats detail = api.stats();
                Responses.Settings settings = api.settings();
                runOnUiThread(() -> stats.setText(
                        "服务端 " + health.app + " " + health.version + "\n"
                                + "当前账号 " + ServerConfig.username(this) + "\n"
                                + "服务器时间 " + health.serverTime + "\n\n"
                                + "照片 " + detail.images + " 张 · 录像 " + detail.videos + " 段\n"
                                + "媒体占用 " + Ui.size(detail.bytes) + "\n"
                                + "磁盘剩余 " + (detail.diskFree == null ? "未知" : Ui.size(detail.diskFree))
                                + " / " + (detail.diskTotal == null ? "未知" : Ui.size(detail.diskTotal)) + "\n"
                                + "媒体根目录 " + detail.mediaRoot + "\n"
                                + "缩略图目录 " + settings.thumbsDir + "\n"
                                + "视频转码 " + (settings.autoProxy ? "开启" : "关闭")
                                + "（最长边 " + settings.videoProxyHeight + "p）\n"
                                + "ffmpeg " + (settings.ffmpeg ? "已安装" : "未安装")));
            } catch (ApiException e) {
                runOnUiThread(() -> stats.setText("读取失败：" + e.getMessage()
                        + "\n当前地址：" + ServerConfig.baseUrl(this)));
            }
        }).start();
    }
}
