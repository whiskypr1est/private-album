package com.privatealbum.app.net;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.privatealbum.app.util.ServerConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 轻量 HTTP 客户端：只用 HttpURLConnection，不引第三方网络库。
 * 所有请求自动带上 ?token=xxx 与 Bearer 头，方便 Glide / VideoView 直接加载 URL。
 */
public final class HttpClient {

    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 60000;

    private final Context appContext;

    public HttpClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static Gson gson() {
        return GSON;
    }

    public interface ProgressListener {
        void onProgress(long sent, long total);
    }

    // ------------------------------------------------------------- 基础请求
    private HttpURLConnection open(String pathOrUrl, String method, boolean withAuth) throws IOException {
        String url = pathOrUrl.startsWith("http")
                ? pathOrUrl
                : ServerConfig.baseUrl(appContext) + pathOrUrl;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "PrivateAlbum-Android/1.0");
        if (withAuth) {
            String token = ServerConfig.token(appContext);
            if (!TextUtils.isEmpty(token)) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
        }
        return conn;
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
            }
        }
        return sb.toString();
    }

    /** 把服务器返回的错误体（{"detail": "..."}）转成可读消息。 */
    private static String extractMessage(int code, String body) {
        if (!TextUtils.isEmpty(body)) {
            try {
                JSONObject json = new JSONObject(body);
                String detail = json.optString("detail", null);
                if (!TextUtils.isEmpty(detail)) return detail;
                String message = json.optString("message", null);
                if (!TextUtils.isEmpty(message)) return message;
            } catch (Exception ignored) {
                // 非 JSON，返回原文片段
                return body.length() > 200 ? body.substring(0, 200) : body;
            }
        }
        switch (code) {
            case 401: return "登录已过期，请重新登录";
            case 403: return "没有权限";
            case 404: return "服务器上找不到该资源";
            case 413: return "文件太大";
            case 415: return "服务器不支持该格式";
            case 500: return "服务器内部错误";
            default: return "请求失败（HTTP " + code + "）";
        }
    }

    public String getText(String path) throws ApiException {
        return requestText(path, "GET", null, true);
    }

    public String requestText(String path, String method, Object body, boolean withAuth) throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = open(path, method, withAuth);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }
            int code = conn.getResponseCode();
            String responseBody = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new ApiException(code, extractMessage(code, responseBody), responseBody);
            }
            return responseBody;
        } catch (SocketTimeoutException e) {
            throw new ApiException("连接超时：请检查手机是否连着 Tailscale / 同一个 WiFi", e);
        } catch (IOException e) {
            throw new ApiException("网络不可用：" + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public <T> T get(String path, Class<T> type) throws ApiException {
        return parse(getText(path), type);
    }

    public <T> T post(String path, Object body, Class<T> type) throws ApiException {
        return parse(requestText(path, "POST", body, true), type);
    }

    public <T> T patch(String path, Object body, Class<T> type) throws ApiException {
        return parse(requestText(path, "PATCH", body, true), type);
    }

    public <T> T put(String path, Object body, Class<T> type) throws ApiException {
        return parse(requestText(path, "PUT", body, true), type);
    }

    public <T> T delete(String path, Class<T> type) throws ApiException {
        return parse(requestText(path, "DELETE", null, true), type);
    }

    private <T> T parse(String json, Class<T> type) throws ApiException {
        try {
            return GSON.fromJson(json, type);
        } catch (JsonSyntaxException e) {
            throw new ApiException("服务器返回内容无法解析", e);
        }
    }

    // ------------------------------------------------------------- 上传分片
    /**
     * 上传一个分片。返回服务器已确认的字节数（用于断点续传）。
     */
    public long putChunk(String uploadId, byte[] data, int length, long offset,
                         ProgressListener listener) throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = open("/api/uploads/" + uploadId + "/chunk", "PUT", true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("X-Chunk-Offset", String.valueOf(offset));
            conn.setFixedLengthStreamingMode(length);

            try (OutputStream out = conn.getOutputStream()) {
                int written = 0;
                int blockSize = 64 * 1024;
                while (written < length) {
                    int chunk = Math.min(blockSize, length - written);
                    out.write(data, written, chunk);
                    written += chunk;
                    if (listener != null) listener.onProgress(offset + written, offset + length);
                }
                out.flush();
            }

            int code = conn.getResponseCode();
            String body = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new ApiException(code, extractMessage(code, body), body);
            }
            com.privatealbum.app.model.Responses.UploadChunk result =
                    GSON.fromJson(body, com.privatealbum.app.model.Responses.UploadChunk.class);
            if (result != null && result.expectedOffset != null) {
                return result.expectedOffset;
            }
            return result == null ? offset + length : result.received;
        } catch (SocketTimeoutException e) {
            throw new ApiException("上传超时（网络较慢，可重试续传）", e);
        } catch (IOException e) {
            throw new ApiException("上传中断：" + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 下载一个 URL 到本地文件，带进度回调。用于「保存到手机」。 */
    public void downloadToFile(String path, java.io.File target, ProgressListener listener)
            throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "GET", true);
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new ApiException(code, extractMessage(code, readBody(conn.getErrorStream())), null);
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[128 * 1024];
                long done = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (listener != null) listener.onProgress(done, total);
                }
                out.flush();
            }
        } catch (SocketTimeoutException e) {
            throw new ApiException("下载超时", e);
        } catch (IOException e) {
            throw new ApiException("下载失败：" + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 需要 token 的媒体 URL（Glide / VideoView 都能直接吃）。 */
    public static String withToken(Context context, String path) {
        String token = ServerConfig.token(context);
        String base = ServerConfig.baseUrl(context);
        String url = path.startsWith("http") ? path : base + path;
        if (TextUtils.isEmpty(token)) return url;
        return url + (url.contains("?") ? "&" : "?") + "token=" + Uri.encode(token);
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /** 组装查询字符串。 */
    public static String query(String path, Map<String, String> params) {
        if (params == null || params.isEmpty()) return path;
        StringBuilder sb = new StringBuilder(path);
        char sep = path.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null) continue;
            sb.append(sep).append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    public static Map<String, String> params(String... keysAndValues) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            out.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }
}
