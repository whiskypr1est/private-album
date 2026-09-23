package com.privatealbum.app.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import com.privatealbum.app.model.Responses;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.net.ApiException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 上传队列：把一个或多个 Uri 分片上传到树莓派。
 *
 * 流程：
 *   1. 查名字/大小 -> 算 SHA-256（本地算好，服务器可用于秒传去重）
 *   2. POST /api/uploads/init      （已存在同哈希文件则直接判定重复）
 *   3. PUT  /api/uploads/{id}/chunk 按 4MB 分片，带 X-Chunk-Offset，可断点续传
 *   4. POST /api/uploads/complete   （服务器入库、生成缩略图）
 */
public final class UploadManager {

    private static final int CHUNK = 4 * 1024 * 1024;
    private static final long HASH_LIMIT = 8L * 1024 * 1024 * 1024L; // 超过 8GB 就不再本地算哈希

    public interface Observer {
        void onProgress(String fileName, int index, int total, long sent, long size);

        void onFinished(int uploaded, int duplicates, int failed, String lastError);
    }

    /** 上传时的排序方式。 */
    public enum SortMode {
        /** 按文件名自然排序：1.png &lt; 2.png &lt; 10.png（默认，也是以前的行为） */
        BY_NAME,
        /** 保留源文件夹里的先后顺序，文件名不参与排序 */
        SOURCE_ORDER
    }

    private static final UploadManager INSTANCE = new UploadManager();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;

    private UploadManager() {
    }

    public static UploadManager get() {
        return INSTANCE;
    }

    public void observe(Context context, Observer observer) {
        observers.add(observer);
    }

    public boolean isRunning() {
        return running;
    }

    public void enqueue(Context context, List<Uri> uris) {
        enqueue(context, uris, null, SortMode.BY_NAME);
    }

    public void enqueue(Context context, List<Uri> uris, Long albumId) {
        enqueue(context, uris, albumId, SortMode.BY_NAME);
    }

    /**
     * 上传一批文件。
     *
     * @param albumId  非空时，上传完成的文件会自动进入这个相册
     *                 （相册页里的「上传到此相册」用它）
     * @param sortMode 这批文件的排序方式：按文件名，或保留源文件夹顺序
     */
    public void enqueue(Context context, List<Uri> uris, Long albumId, SortMode sortMode) {
        if (uris == null || uris.isEmpty()) return;
        final Context appContext = context.getApplicationContext();
        final List<Uri> queue = new ArrayList<>(uris);
        final SortMode mode = sortMode == null ? SortMode.BY_NAME : sortMode;
        // 这一批共用一个 batch_id：服务器按「批次」分组，
        // 同一批内按排序键排列（默认是文件名自然序 1 < 2 < 10）
        final String batchId = "b" + System.currentTimeMillis();
        executor.execute(() -> runQueue(appContext, queue, albumId, batchId, mode));
    }

    private void runQueue(Context context, List<Uri> queue, Long albumId, String batchId,
                          SortMode sortMode) {
        running = true;
        AlbumApi api = new AlbumApi(context);
        int uploaded = 0;
        int duplicates = 0;
        int failed = 0;
        String lastError = null;
        int index = 0;

        for (Uri uri : queue) {
            index++;
            File temp = null;
            try {
                FileMeta meta = readMeta(context, uri);
                temp = File.createTempFile("upload_", ".part", context.getCacheDir());
                String sha = null;
                try {
                    sha = copyAndHash(context, uri, temp, meta.size);
                } catch (Exception e) {
                    // 算哈希失败不致命，服务器端完成入库时还会再算一次
                    sha = null;
                }
                long size = temp.length();

                Responses.UploadInit init = api.initUpload(meta.name, size, sha, meta.takenAt,
                        false, albumId, batchId);
                if (init.duplicate) {
                    duplicates++;
                    temp.delete();
                    continue;
                }
                if (init.uploadId == null) {
                    throw new ApiException("服务器未返回上传会话", null);
                }

                uploadChunks(api, init.uploadId, temp, meta.name, index, queue.size());

                // 「保留源文件夹顺序」时把这一批里的先后位置报给服务器，
                // 服务器会拿它当排序键并加锁（重扫也不会被文件名重排）。
                // 用 index-1 而不是跳过失败项后的计数：源顺序就是 queue 里的位置。
                Integer orderIndex = sortMode == SortMode.SOURCE_ORDER ? (index - 1) : null;
                Responses.AssetResult result = api.completeUpload(
                        init.uploadId, meta.name, sha, meta.takenAt, orderIndex);
                if (Boolean.TRUE.equals(result.duplicate)) {
                    duplicates++;
                } else {
                    uploaded++;
                }
            } catch (ApiException e) {
                failed++;
                lastError = e.getMessage();
            } catch (Exception e) {
                failed++;
                lastError = e.toString();
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        }

        running = false;
        final int up = uploaded;
        final int dup = duplicates;
        final int fail = failed;
        final String error = lastError;
        main.post(() -> {
            for (Observer observer : observers) {
                observer.onFinished(up, dup, fail, error);
            }
        });
    }

    private void uploadChunks(AlbumApi api, String uploadId, File file, String name,
                              int index, int total) throws Exception {
        long totalSize = file.length();
        long offset = 0;
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] buffer = new byte[CHUNK];
            while (offset < totalSize) {
                int read = raf.read(buffer, 0, (int) Math.min(CHUNK, totalSize - offset));
                if (read <= 0) break;
                final long currentOffset = offset;
                final int length = read;
                long confirmed = api.putChunk(uploadId, buffer, length, currentOffset,
                        (sent, size) -> notifyProgress(name, index, total, sent, totalSize));
                if (confirmed != currentOffset + length) {
                    // 服务器说没收到这么多，按它给的偏移重试
                    offset = confirmed;
                    raf.seek(offset);
                } else {
                    offset += length;
                }
                notifyProgress(name, index, total, offset, totalSize);
            }
        } finally {
            raf.close();
        }
    }

    private void notifyProgress(String name, int index, int total, long sent, long size) {
        main.post(() -> {
            for (Observer observer : observers) {
                observer.onProgress(name, index, total, sent, size);
            }
        });
    }

    // ------------------------------------------------------------ 本地读取
    static class FileMeta {
        String name;
        long size;
        String takenAt;
    }

    private FileMeta readMeta(Context context, Uri uri) {
        FileMeta meta = new FileMeta();
        meta.name = "upload_" + System.currentTimeMillis() + ".jpg";
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    meta.name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    meta.size = cursor.getLong(sizeIndex);
                }
                int dateIndex = cursor.getColumnIndex("datetaken");
                if (dateIndex >= 0 && !cursor.isNull(dateIndex)) {
                    long taken = cursor.getLong(dateIndex);
                    if (taken > 0) {
                        java.text.SimpleDateFormat format =
                                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        meta.takenAt = format.format(new java.util.Date(taken));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (meta.name == null || meta.name.trim().isEmpty()) {
            meta.name = "upload_" + System.currentTimeMillis() + ".jpg";
        }
        return meta;
    }

    /** 边复制边算 SHA-256，顺便得到真实大小。 */
    private String copyAndHash(Context context, Uri uri, File target, long declaredSize) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        boolean hash = declaredSize <= 0 || declaredSize <= HASH_LIMIT;
        long total = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new ApiException("无法读取所选文件", null);
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (hash) digest.update(buffer, 0, read);
                total += read;
            }
            out.flush();
        }
        if (total <= 0) throw new ApiException("所选文件是空的", null);
        return hash ? toHex(digest.digest()) : null;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
