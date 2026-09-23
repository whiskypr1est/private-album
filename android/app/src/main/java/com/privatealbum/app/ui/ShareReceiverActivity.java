package com.privatealbum.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.privatealbum.app.transfer.UploadManager;
import com.privatealbum.app.util.ServerConfig;

import java.util.ArrayList;

/**
 * 接收系统相册「分享」过来的照片，直接排队上传到树莓派，然后自己关掉。
 */
public class ShareReceiverActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ServerConfig.isLoggedIn(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        Intent intent = getIntent();
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null) uris.add(single);
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有可上传的内容", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        UploadManager.get().enqueue(this, uris);
        Toast.makeText(this, "已开始上传 " + uris.size() + " 个文件到树莓派", Toast.LENGTH_SHORT).show();
        finish();
    }
}
