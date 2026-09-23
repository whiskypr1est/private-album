package com.privatealbum.app.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 一张照片 / 一段录像。字段与服务器 /api/assets 返回一一对应。 */
public class Asset implements Serializable {

    @SerializedName("id") public long id;
    @SerializedName("file_name") public String fileName;
    @SerializedName("media_type") public String mediaType;      // image | video
    @SerializedName("mime") public String mime;
    @SerializedName("size_bytes") public long sizeBytes;
    @SerializedName("size_text") public String sizeText;
    @SerializedName("width") public Integer width;
    @SerializedName("height") public Integer height;
    @SerializedName("duration_ms") public Long durationMs;
    @SerializedName("taken_at") public String takenAt;
    @SerializedName("taken_src") public String takenSrc;
    @SerializedName("is_favorite") public boolean favorite;
    @SerializedName("is_archived") public boolean archived;
    @SerializedName("is_trashed") public boolean trashed;
    @SerializedName("thumb_state") public String thumbState;
    @SerializedName("thumb_error") public String thumbError;
    @SerializedName("has_proxy") public boolean hasProxy;
    @SerializedName("edit_version") public int editVersion;

    @SerializedName("thumb_url") public String thumbUrl;
    @SerializedName("preview_url") public String previewUrl;
    @SerializedName("raw_url") public String rawUrl;
    @SerializedName("stream_url") public String streamUrl;
    @SerializedName("download_url") public String downloadUrl;

    @SerializedName("tags") public List<String> tags = new ArrayList<>();

    // 详情接口才返回
    @SerializedName("camera_make") public String cameraMake;
    @SerializedName("camera_model") public String cameraModel;
    @SerializedName("lens") public String lens;
    @SerializedName("iso") public Integer iso;
    @SerializedName("f_number") public String fNumber;
    @SerializedName("exposure") public String exposure;
    @SerializedName("focal_length") public String focalLength;
    @SerializedName("gps_lat") public Double gpsLat;
    @SerializedName("gps_lon") public Double gpsLon;
    @SerializedName("rel_path") public String relPath;
    @SerializedName("sha256") public String sha256;
    @SerializedName("albums") public List<AlbumRef> albums = new ArrayList<>();

    public boolean isVideo() {
        return "video".equals(mediaType);
    }

    public String resolutionText() {
        if (width == null || height == null) return "";
        return width + " × " + height;
    }

    public String durationText() {
        if (durationMs == null || durationMs <= 0) return "";
        long totalSeconds = durationMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    public String cameraText() {
        StringBuilder sb = new StringBuilder();
        if (cameraMake != null && !cameraMake.isEmpty()) {
            sb.append(cameraMake.trim());
        }
        if (cameraModel != null && !cameraModel.isEmpty()) {
            if (sb.length() > 0 && !cameraModel.toLowerCase().startsWith(sb.toString().toLowerCase())) {
                sb.append(' ');
            }
            sb.append(cameraModel.trim());
        }
        return sb.toString().trim();
    }

    public String exposureText() {
        StringBuilder sb = new StringBuilder();
        if (fNumber != null && !fNumber.isEmpty()) sb.append(fNumber);
        if (exposure != null && !exposure.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(exposure);
        }
        if (iso != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("ISO ").append(iso);
        }
        if (focalLength != null && !focalLength.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(focalLength);
        }
        return sb.toString();
    }

    public static class AlbumRef implements Serializable {
        @SerializedName("id") public long id;
        @SerializedName("name") public String name;
    }
}
