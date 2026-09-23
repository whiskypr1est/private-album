package com.privatealbum.app.transfer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * 上传任务的宿主 Service。
 *
 * 目前上传由 {@link UploadManager} 的单线程队列驱动，界面通过 Observer 拿进度；
 * 这里保留一个 Service 壳子，是为了将来把上传改成前台服务、在锁屏/切后台时也继续跑。
 */
public class TransferService extends Service {

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }
}
