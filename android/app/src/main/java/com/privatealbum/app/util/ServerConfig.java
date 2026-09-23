package com.privatealbum.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Locale;

/**
 * 本地保存服务器地址 / 端口 / 令牌 / 登录用户。
 *
 * 地址解析规则（填什么都能用）：
 *   "192.168.1.50"                -> http://192.168.1.50:8080
 *   "192.168.1.50:9000"           -> http://192.168.1.50:9000
 *   "http://pi.local"             -> http://pi.local:8080
 *   "https://album.example.com"   -> https://album.example.com:443
 */
public final class ServerConfig {

    private static final String PREFS = "photoalbum_prefs";

    private static final String KEY_HOST = "host";
    private static final String KEY_SCHEME = "scheme";
    private static final String KEY_PORT = "port";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_USER = "username";
    private static final String KEY_TOKEN_EXPIRES = "token_expires";
    private static final String KEY_LAST_UPLOAD_DIR = "last_upload_dir";
    private static final String KEY_GRID_COLUMNS = "grid_columns";
    private static final String KEY_KEEP_ORIGINAL = "keep_original";

    /** 默认留空：由使用者首次打开 App 时自己填服务端地址，仓库里不预设任何人的机器。 */
    public static final String DEFAULT_HOST = "";

    private ServerConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 地址
    public static String host(Context context) {
        return prefs(context).getString(KEY_HOST, DEFAULT_HOST);
    }

    public static String scheme(Context context) {
        return prefs(context).getString(KEY_SCHEME, "http");
    }

    public static int port(Context context) {
        return prefs(context).getInt(KEY_PORT, 8080);
    }

    public static String baseUrl(Context context) {
        return scheme(context) + "://" + host(context) + ":" + port(context);
    }

    /** 把用户随手输入的地址规范化后保存。返回规范化后的 baseUrl。 */
    public static String saveAddress(Context context, String rawInput, String rawPort) {
        String input = rawInput == null ? "" : rawInput.trim();
        String scheme = "http";
        int port = -1;

        if (input.startsWith("https://")) {
            scheme = "https";
            input = input.substring(8);
        } else if (input.startsWith("http://")) {
            input = input.substring(7);
        }
        // 去掉路径部分
        int slash = input.indexOf('/');
        if (slash >= 0) {
            input = input.substring(0, slash);
        }
        // 输入里自带的端口优先
        int colon = input.lastIndexOf(':');
        if (colon > 0 && input.indexOf(']') < colon) {
            try {
                port = Integer.parseInt(input.substring(colon + 1));
                input = input.substring(0, colon);
            } catch (NumberFormatException ignored) {
                port = -1;
            }
        }
        if (!TextUtils.isEmpty(rawPort)) {
            try {
                port = Integer.parseInt(rawPort.trim());
            } catch (NumberFormatException ignored) {
                // 保留输入框里的端口
            }
        }
        if (port <= 0 || port > 65535) {
            port = "https".equals(scheme) ? 443 : 8080;
        }
        if (input.isEmpty()) {
            input = DEFAULT_HOST;
        }

        prefs(context).edit()
                .putString(KEY_HOST, input)
                .putString(KEY_SCHEME, scheme)
                .putInt(KEY_PORT, port)
                .apply();
        return scheme + "://" + input + ":" + port;
    }

    // ---------------------------------------------------------------- 令牌
    public static String token(Context context) {
        return prefs(context).getString(KEY_TOKEN, null);
    }

    public static boolean isLoggedIn(Context context) {
        return !TextUtils.isEmpty(token(context));
    }

    public static void saveSession(Context context, String token, String username, String expiresAt) {
        prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER, username)
                .putString(KEY_TOKEN_EXPIRES, expiresAt)
                .apply();
    }

    public static String username(Context context) {
        return prefs(context).getString(KEY_USER, "");
    }

    public static void logout(Context context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRES).apply();
    }

    // ---------------------------------------------------------------- 其它
    public static int gridColumns(Context context) {
        return prefs(context).getInt(KEY_GRID_COLUMNS, 3);
    }

    public static void setGridColumns(Context context, int columns) {
        prefs(context).edit().putInt(KEY_GRID_COLUMNS, Math.max(2, Math.min(6, columns))).apply();
    }

    public static boolean keepOriginal(Context context) {
        return prefs(context).getBoolean(KEY_KEEP_ORIGINAL, true);
    }

    public static void setKeepOriginal(Context context, boolean keep) {
        prefs(context).edit().putBoolean(KEY_KEEP_ORIGINAL, keep).apply();
    }

    public static String lastUploadDir(Context context) {
        return prefs(context).getString(KEY_LAST_UPLOAD_DIR, null);
    }

    public static void setLastUploadDir(Context context, String dir) {
        prefs(context).edit().putString(KEY_LAST_UPLOAD_DIR, dir).apply();
    }

    public static String describe(Context context) {
        return String.format(Locale.US, "%s@%s:%d", username(context), host(context), port(context));
    }
}
