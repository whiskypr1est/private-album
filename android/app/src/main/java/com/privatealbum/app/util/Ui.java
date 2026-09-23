package com.privatealbum.app.util;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 时间 / 体积 格式化与常用小工具。 */
public final class Ui {

    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

    static {
        ISO.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private Ui() {
    }

    // -------------------------------------------------------------- 时间
    public static Date parseIso(String iso) {
        if (iso == null) return null;
        String value = iso.endsWith("Z") ? iso.substring(0, iso.length() - 1) : iso;
        if (value.length() > 19) value = value.substring(0, 19);
        try {
            return ISO.parse(value);
        } catch (ParseException e) {
            return null;
        }
    }

    /** 分组标题：今天 / 昨天 / 8月14日 星期四 / 2024年8月14日 */
    public static String dayHeader(String iso) {
        Date date = parseIso(iso);
        if (date == null) return "未知时间";
        Calendar target = Calendar.getInstance();
        target.setTime(date);
        Calendar now = Calendar.getInstance();
        if (sameDay(target, now)) return "今天";
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(target, yesterday)) return "昨天";
        if (target.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            return new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(date);
        }
        return new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(date);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    public static String fullDateTime(String iso) {
        Date date = parseIso(iso);
        if (date == null) return "未知";
        return new SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.CHINA).format(date);
    }

    /** 只取 yyyy-MM-dd，用于分组 key。 */
    public static String dayKey(String iso) {
        return iso == null || iso.length() < 10 ? "0000-00-00" : iso.substring(0, 10);
    }

    // -------------------------------------------------------------- 体积
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int index = -1;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.US, index >= 2 ? "%.2f %s" : "%.1f %s", value, units[index]);
    }

    public static String progress(long done, long total) {
        if (total <= 0) return size(done);
        int percent = (int) Math.min(100, done * 100 / total);
        return percent + "%（" + size(done) + " / " + size(total) + "）";
    }

    // -------------------------------------------------------------- 其它
    public static void toast(Context context, String message) {
        if (context == null || message == null) return;
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    public static void toastLong(Context context, String message) {
        if (context == null || message == null) return;
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    public static void hideKeyboard(Context context, View view) {
        if (context == null || view == null) return;
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
