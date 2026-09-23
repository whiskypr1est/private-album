package com.privatealbum.app.net;

import android.content.Context;

import com.privatealbum.app.model.Album;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.model.Responses;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 服务端接口封装。所有方法都必须在后台线程调用。 */
public class AlbumApi {

    private final HttpClient http;
    private final Context context;

    public AlbumApi(Context context) {
        this.context = context.getApplicationContext();
        this.http = new HttpClient(context);
    }

    // ------------------------------------------------------------- 连接 / 登录
    public Responses.Health health(String baseUrlOverride) throws ApiException {
        String path = (baseUrlOverride == null ? "" : baseUrlOverride) + "/api/health";
        return HttpClient.gson().fromJson(http.requestText(path, "GET", null, false), Responses.Health.class);
    }

    public Responses.LoginResult login(String username, String password) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        return http.post("/api/login", body, Responses.LoginResult.class);
    }

    public Responses.LoginResult setup(String username, String password) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        return http.post("/api/setup", body, Responses.LoginResult.class);
    }

    public Responses.SimpleResult changePassword(String oldPassword, String newPassword) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("old_password", oldPassword);
        body.put("new_password", newPassword);
        return http.post("/api/password", body, Responses.SimpleResult.class);
    }

    // ------------------------------------------------------------- 时间线
    public Responses.Page listAssets(String cursor, int limit, String type, boolean favorite,
                                     boolean trashed, String search, String before, String after)
            throws ApiException {
        Map<String, String> params = new HashMap<>();
        params.put("limit", String.valueOf(limit));
        if (cursor != null) params.put("cursor", cursor);
        if (type != null) params.put("type", type);
        if (favorite) params.put("favorite", "true");
        if (trashed) params.put("trashed", "true");
        if (search != null && !search.isEmpty()) params.put("q", search);
        if (before != null) params.put("before", before);
        if (after != null) params.put("after", after);
        return http.get(HttpClient.query("/api/assets", params), Responses.Page.class);
    }

    public Responses.Timeline timeline() throws ApiException {
        return http.get("/api/timeline", Responses.Timeline.class);
    }

    public Asset assetDetail(long id) throws ApiException {
        return http.get("/api/assets/" + id, Asset.class);
    }

    // ------------------------------------------------------------- 编辑
    public Responses.AssetResult edit(long id, Map<String, Object> changes) throws ApiException {
        return http.post("/api/assets/" + id + "/edit", changes, Responses.AssetResult.class);
    }

    public Responses.AssetResult restoreOriginal(long id) throws ApiException {
        return http.post("/api/assets/" + id + "/restore-original", Collections.emptyMap(),
                Responses.AssetResult.class);
    }

    public Responses.AssetResult trimVideo(long id, long startMs, long endMs) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("start_ms", startMs);
        body.put("end_ms", endMs);
        return http.post("/api/assets/" + id + "/trim", body, Responses.AssetResult.class);
    }

    /** 快捷旋转（不改动 taken_at，直接覆盖原图）。 */
    public Responses.AssetResult rotate(long id, int degrees) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("rotate", degrees);
        body.put("keep_original", false);
        return edit(id, body);
    }

    // ------------------------------------------------------------- 状态变更
    public Responses.SimpleResult setFavorite(List<Long> ids, boolean value) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("value", value);
        return http.post("/api/assets/favorite", body, Responses.SimpleResult.class);
    }

    public Responses.SimpleResult setArchived(List<Long> ids, boolean value) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("value", value);
        return http.post("/api/assets/archive", body, Responses.SimpleResult.class);
    }

    public Responses.SimpleResult trash(List<Long> ids) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        return http.post("/api/assets/trash", body, Responses.SimpleResult.class);
    }

    public Responses.SimpleResult restore(List<Long> ids) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        return http.post("/api/assets/restore", body, Responses.SimpleResult.class);
    }

    public Responses.SimpleResult deleteForever(long id) throws ApiException {
        return http.delete("/api/assets/" + id, Responses.SimpleResult.class);
    }

    public Responses.SimpleResult purgeTrash() throws ApiException {
        return http.post("/api/trash/purge", Collections.emptyMap(), Responses.SimpleResult.class);
    }

    public Responses.SimpleResult addTag(List<Long> ids, String name, boolean remove) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("name", name);
        body.put("remove", remove);
        return http.post("/api/assets/tags", body, Responses.SimpleResult.class);
    }

    // ------------------------------------------------------------- 相册
    public Responses.AlbumList albums() throws ApiException {
        return http.get("/api/albums", Responses.AlbumList.class);
    }

    public Responses.SimpleResult createAlbum(String name, String description) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", description);
        return http.post("/api/albums", body, Responses.SimpleResult.class);
    }

    public Responses.SimpleResult renameAlbum(long id, String name, String description) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", description);
        return http.patch("/api/albums/" + id, body, Responses.SimpleResult.class);
    }

    public Responses.SimpleResult deleteAlbum(long id) throws ApiException {
        return http.delete("/api/albums/" + id, Responses.SimpleResult.class);
    }

    public Responses.AlbumDetail albumDetail(long id, String cursor) throws ApiException {
        Map<String, String> params = new HashMap<>();
        params.put("limit", "200");
        if (cursor != null) params.put("cursor", cursor);
        return http.get(HttpClient.query("/api/albums/" + id, params), Responses.AlbumDetail.class);
    }

    public Responses.SimpleResult albumItems(long albumId, List<Long> ids, boolean remove) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("remove", remove);
        return http.post("/api/albums/" + albumId + "/items", body, Responses.SimpleResult.class);
    }

    public Responses.SimpleResult setAlbumCover(long albumId, Long assetId) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("asset_id", assetId);
        return http.post("/api/albums/" + albumId + "/cover", body, Responses.SimpleResult.class);
    }

    // ------------------------------------------------------------- 分享
    public Responses.ShareResult createShare(String kind, Long targetId, String password, int days)
            throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        if (targetId != null) body.put("target_id", targetId);
        if (password != null && !password.isEmpty()) body.put("password", password);
        body.put("expires_in_days", days);
        return http.post("/api/shares", body, Responses.ShareResult.class);
    }

    // ------------------------------------------------------------- 上传
    public Responses.UploadInit initUpload(String fileName, long sizeBytes, String sha256,
                                           String takenAt, boolean force) throws ApiException {
        return initUpload(fileName, sizeBytes, sha256, takenAt, force, null, null);
    }

    /**
     * 开始一次上传。
     *
     * @param albumId 非空时，上传完成后自动进入该相册
     * @param batchId 同一批上传共用，服务器按批次分组、批内按文件名自然序排列
     */
    public Responses.UploadInit initUpload(String fileName, long sizeBytes, String sha256,
                                           String takenAt, boolean force,
                                           Long albumId, String batchId) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("file_name", fileName);
        body.put("size_bytes", sizeBytes);
        if (sha256 != null) body.put("sha256", sha256);
        if (takenAt != null) body.put("taken_at", takenAt);
        if (force) body.put("force", true);
        if (albumId != null) body.put("album_id", albumId);
        if (batchId != null) body.put("batch_id", batchId);
        return http.post("/api/uploads/init", body, Responses.UploadInit.class);
    }

    public Responses.AssetResult completeUpload(String uploadId, String fileName, String sha256,
                                                String takenAt) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("upload_id", uploadId);
        if (fileName != null) body.put("file_name", fileName);
        if (sha256 != null) body.put("sha256", sha256);
        if (takenAt != null) body.put("taken_at", takenAt);
        return http.post("/api/uploads/complete", body, Responses.AssetResult.class);
    }

    public long putChunk(String uploadId, byte[] buffer, int length, long offset,
                         HttpClient.ProgressListener listener) throws ApiException {
        return http.putChunk(uploadId, buffer, length, offset, listener);
    }

    // ------------------------------------------------------------- 维护
    public Responses.Stats stats() throws ApiException {
        return http.get("/api/library/stats", Responses.Stats.class);
    }

    public Responses.Settings settings() throws ApiException {
        return http.get("/api/settings", Responses.Settings.class);
    }

    public Responses.Settings updateSettings(Map<String, Object> body) throws ApiException {
        return http.put("/api/settings", body, Responses.Settings.class);
    }

    public Responses.SimpleResult scanLibrary() throws ApiException {
        return http.post("/api/library/scan?background=true", Collections.emptyMap(),
                Responses.SimpleResult.class);
    }

    public Responses.SimpleResult rebuildThumbs() throws ApiException {
        return http.post("/api/library/rebuild-thumbs", Collections.emptyMap(), Responses.SimpleResult.class);
    }

    public Map<String, Object> migratePlan(String targetRoot) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("target_root", targetRoot);
        String json = http.requestText("/api/library/migrate/plan", "POST", body, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = HttpClient.gson().fromJson(json, Map.class);
        return result;
    }

    public Responses.SimpleResult migrate(String targetRoot, boolean move) throws ApiException {
        Map<String, Object> body = new HashMap<>();
        body.put("target_root", targetRoot);
        body.put("move", move);
        return http.post("/api/library/migrate", body, Responses.SimpleResult.class);
    }

    public void download(String path, java.io.File target, HttpClient.ProgressListener listener)
            throws ApiException {
        http.downloadToFile(path, target, listener);
    }

    public String mediaUrl(String path) {
        return HttpClient.withToken(context, path);
    }

    public String thumbUrl(Asset asset) {
        return mediaUrl(asset.thumbUrl);
    }

    public String previewUrl(Asset asset) {
        return mediaUrl(asset.previewUrl);
    }

    public String streamUrl(Asset asset) {
        return mediaUrl(asset.streamUrl);
    }

    public String downloadUrl(Asset asset) {
        return mediaUrl(asset.downloadUrl);
    }

    public String albumCoverUrl(Album album) {
        return album.coverUrl == null ? null : mediaUrl(album.coverUrl);
    }
}
