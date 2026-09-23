package com.privatealbum.app.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** 服务器各种响应的封装。 */
public final class Responses {

    private Responses() {
    }

    public static class Health {
        @SerializedName("ok") public boolean ok;
        @SerializedName("app") public String app;
        @SerializedName("version") public String version;
        @SerializedName("needs_setup") public boolean needsSetup;
        @SerializedName("server_time") public String serverTime;
        @SerializedName("media_root") public String mediaRoot;
        @SerializedName("items") public long items;
        @SerializedName("disk_free") public Long diskFree;
        @SerializedName("disk_free_text") public String diskFreeText;
        @SerializedName("ffmpeg") public boolean ffmpeg;
        @SerializedName("pillow_heif") public boolean pillowHeif;
    }

    public static class LoginResult {
        @SerializedName("ok") public boolean ok;
        @SerializedName("token") public String token;
        @SerializedName("expires_at") public String expiresAt;
        @SerializedName("username") public String username;
    }

    public static class Page {
        @SerializedName("items") public List<Asset> items = new ArrayList<>();
        @SerializedName("next_cursor") public String nextCursor;
        @SerializedName("count") public int count;
    }

    public static class AssetResult {
        @SerializedName("ok") public boolean ok;
        @SerializedName("changed") public boolean changed;
        @SerializedName("asset") public Asset asset;
        @SerializedName("duplicate") public Boolean duplicate;
    }

    public static class AlbumList {
        @SerializedName("albums") public List<Album> albums = new ArrayList<>();
    }

    public static class AlbumDetail {
        @SerializedName("album") public Album album;
        @SerializedName("items") public List<Asset> items = new ArrayList<>();
        @SerializedName("next_cursor") public String nextCursor;
    }

    public static class Stats {
        @SerializedName("total") public long total;
        @SerializedName("images") public long images;
        @SerializedName("videos") public long videos;
        @SerializedName("favorites") public long favorites;
        @SerializedName("trashed") public long trashed;
        @SerializedName("bytes") public long bytes;
        @SerializedName("media_root") public String mediaRoot;
        @SerializedName("disk_total") public Long diskTotal;
        @SerializedName("disk_free") public Long diskFree;
        @SerializedName("disk_used") public Long diskUsed;
        @SerializedName("pending_thumbs") public int pendingThumbs;
        @SerializedName("queue_depth") public int queueDepth;
    }

    public static class MonthBucket {
        @SerializedName("month") public String month;
        @SerializedName("count") public int count;
        @SerializedName("videos") public int videos;
    }

    public static class Timeline {
        @SerializedName("months") public List<MonthBucket> months = new ArrayList<>();
    }

    public static class Settings {
        @SerializedName("media_root") public String mediaRoot;
        @SerializedName("thumbs_dir") public String thumbsDir;
        @SerializedName("cache_dir") public String cacheDir;
        @SerializedName("auto_proxy") public boolean autoProxy;
        @SerializedName("organize_by_date") public boolean organizeByDate;
        @SerializedName("max_upload_bytes") public long maxUploadBytes;
        @SerializedName("version") public String version;
        @SerializedName("ffmpeg") public boolean ffmpeg;
        @SerializedName("video_proxy_height") public int videoProxyHeight;
    }

    public static class SimpleResult {
        @SerializedName("ok") public boolean ok;
        @SerializedName("count") public int count;
        @SerializedName("id") public long id;
        @SerializedName("token") public String token;
        @SerializedName("removed") public int removed;
        @SerializedName("queued") public int queued;
        @SerializedName("moved") public boolean moved;
        @SerializedName("media_root") public String mediaRoot;
        @SerializedName("message") public String message;
    }

    public static class UploadInit {
        @SerializedName("ok") public boolean ok;
        @SerializedName("upload_id") public String uploadId;
        @SerializedName("received") public long received;
        @SerializedName("chunk_size") public int chunkSize;
        @SerializedName("duplicate") public boolean duplicate;
        @SerializedName("asset") public Asset asset;
    }

    public static class UploadChunk {
        @SerializedName("ok") public boolean ok;
        @SerializedName("received") public long received;
        @SerializedName("complete") public boolean complete;
        @SerializedName("expected_offset") public Long expectedOffset;
    }

    public static class Tag {
        @SerializedName("id") public long id;
        @SerializedName("name") public String name;
        @SerializedName("kind") public String kind;
        @SerializedName("count") public int count;
    }

    public static class TagList {
        @SerializedName("tags") public List<Tag> tags = new ArrayList<>();
    }

    public static class ShareResult {
        @SerializedName("id") public long id;
        @SerializedName("token") public String token;
        @SerializedName("url") public String url;
        @SerializedName("expires_at") public String expiresAt;
    }
}
