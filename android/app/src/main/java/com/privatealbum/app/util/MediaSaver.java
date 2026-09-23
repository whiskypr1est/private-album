package com.privatealbum.app.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.privatealbum.app.model.Asset;
import com.privatealbum.app.net.AlbumApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 「保存到手机」：把树莓派上的原图下载并写进系统相册。 */
public final class MediaSaver {

    private MediaSaver() {
    }

    public static void save(Context context, AlbumApi api, Asset asset) throws Exception {
        Context appContext = context.getApplicationContext();
        File temp = new File(appContext.getCacheDir(), "save_" + asset.id + "_" + asset.fileName);
        if (temp.exists()) temp.delete();

        api.download(asset.downloadUrl, temp, null);

        boolean isVideo = asset.isVideo();
        String mime = asset.mime != null ? asset.mime : (isVideo ? "video/mp4" : "image/jpeg");
        String relativeDir = isVideo
                ? Environment.DIRECTORY_MOVIES + "/私有相册"
                : Environment.DIRECTORY_PICTURES + "/私有相册";

        ContentResolver resolver = appContext.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, asset.fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

        Uri collection = isVideo
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        Uri target = resolver.insert(collection, values);
        if (target == null) {
            throw new Exception("无法在系统相册中创建文件");
        }
        try (InputStream in = new FileInputStream(temp);
             OutputStream out = resolver.openOutputStream(target)) {
            if (out == null) throw new Exception("无法写入系统相册");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(target, done, null, null);
        }
        temp.delete();
    }
}
