package com.privatealbum.app.net;

/** 服务器返回的错误，带上 HTTP 状态码方便上层区分 401 / 404 等。 */
public class ApiException extends Exception {

    public final int statusCode;
    public final String body;

    public ApiException(int statusCode, String message, String body) {
        super(message == null || message.isEmpty() ? ("HTTP " + statusCode) : message);
        this.statusCode = statusCode;
        this.body = body;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.body = null;
    }

    public boolean isAuthError() {
        return statusCode == 401 || statusCode == 403;
    }

    public boolean isOffline() {
        return statusCode == -1;
    }
}
