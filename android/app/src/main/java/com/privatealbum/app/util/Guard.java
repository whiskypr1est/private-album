package com.privatealbum.app.util;

/**
 * 递归 / 重复调用检测器。
 *
 * 用途：StackOverflowError 的堆栈有 8MB，手机上只显示了最上面几行，
 * 根本看不出「哪几个方法在互相调用」。这个检测器在关键方法入口记录调用，
 * 一旦发现同一个方法在**同一线程**上短时间内被重复进入很多次，
 * 就把当时的完整调用链（重复的那几帧）写进日志，直接指出循环在哪。
 *
 * 用法：
 *   Guard.enter("MediaAdapter.notifyDataSetChanged");
 *   try { ... 干活 ... } finally { Guard.exit(); }
 */
public final class Guard {

    private static final int WARN_THRESHOLD = 40;   // 同一条调用链里重复超过这个次数就报警
    private static final int MAX_REPORT = 3;        // 最多报几次，避免日志被刷爆

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    private static int reports = 0;

    private Guard() {
    }

    public static void enter(String tag) {
        int[] depth = DEPTH.get();
        depth[0]++;
        if (depth[0] == WARN_THRESHOLD && reports < MAX_REPORT) {
            reports++;
            Trace.log("!!! 检测到疑似递归 / 重复调用：" + tag
                    + " 在同一个调用链里已经进入 " + depth[0] + " 次\n"
                    + "调用链（只看本项目相关的帧）：\n" + relevantStack());
        }
    }

    public static void exit() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) depth[0]--;
    }

    /** 取当前线程堆栈里与本项目 / RecyclerView 相关的帧，去掉噪声。 */
    private static String relevantStack() {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        int shown = 0;
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.startsWith("com.privatealbum.")
                    || cls.startsWith("androidx.recyclerview.")
                    || cls.startsWith("androidx.fragment.")) {
                sb.append("    at ").append(frame).append('\n');
                shown++;
                if (shown >= 60) {
                    sb.append("    ...（更多帧省略）\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 入口调用计数：用来发现「某个方法被疯狂重复调用」（刷新死循环的特征）
    // ------------------------------------------------------------------
    private static final java.util.Map<String, Integer> COUNTS =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private static long windowStart = System.currentTimeMillis();

    /** 记录一次入口调用；同一秒内超过阈值就报警并把调用链写进日志。 */
    public static void count(String tag) {
        int value;
        synchronized (COUNTS) {
            Integer old = COUNTS.get(tag);
            value = (old == null ? 0 : old) + 1;
            COUNTS.put(tag, value);
        }
        if (value == 60 && reports < MAX_REPORT) {
            reports++;
            Trace.log("!!! " + tag + " 在一秒内被调用 60 次（疑似刷新死循环）\n调用链：\n"
                    + relevantStack());
        }
        long now = System.currentTimeMillis();
        if (now - windowStart > 1000) {
            windowStart = now;
            synchronized (COUNTS) {
                COUNTS.clear();
            }
        }
    }
}
