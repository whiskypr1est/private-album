package com.privatealbum.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.privatealbum.app.R;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.model.Responses;
import com.privatealbum.app.net.AlbumApi;

import java.util.List;

/**
 * 自检页：在真机上一段一段地跑关键代码路径，把成功/失败直接显示出来。
 *
 * 目的是在没有电脑抓 logcat 的情况下定位崩溃点：
 * 每一步都 try/catch，出错就把堆栈显示在屏幕上。
 *
 * 入口：adb shell am start -n com.privatealbum.app/.ui.SelfTestActivity
 *      或者从「更多」页进入。
 */
public class SelfTestActivity extends AppCompatActivity {

    private LinearLayout container;
    private int passCount = 0;
    private int failCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        // 用 activity_main 作为根布局：里面已经有 R.id.content 这个容器，
        // 这样第 11b 步才能真正复刻 MainActivity 的「把 Fragment 装进 content」。
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF14161A);
        scroll.setId(R.id.selftest_scroll);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad, pad, pad);
        scroll.addView(container);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        android.widget.FrameLayout content = new android.widget.FrameLayout(this);
        content.setId(R.id.content);
        root.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        header("私有相册 自检 " + com.privatealbum.app.BuildConfig.VERSION_NAME
                + " (" + com.privatealbum.app.BuildConfig.VERSION_CODE + ")");

        step("1. 资源与主题", () -> {
            getString(R.string.app_name);
            getResources().getColor(R.color.accent);
            getDrawable(R.drawable.bg_thumb);
            getDrawable(R.drawable.ic_photo);
            getDrawable(R.drawable.ic_check);
            require(getTheme() != null, "主题为空");
            return "主题与图标资源可读";
        });

        step("2. 布局解析（activity_main）", () -> {
            View view = getLayoutInflater().inflate(R.layout.activity_main, null);
            require(view.findViewById(R.id.toolbar) != null, "缺 toolbar");
            require(view.findViewById(R.id.tabs) != null, "缺 tabs");
            require(view.findViewById(R.id.fab) != null, "缺 fab");
            require(view.findViewById(R.id.content) != null, "缺 content");
            return "activity_main 解析正常";
        });

        step("3. 布局解析（fragment_grid / item_media / item_header）", () -> {
            View grid = getLayoutInflater().inflate(R.layout.fragment_grid, null);
            require(grid.findViewById(R.id.recycler) != null, "缺 recycler");
            require(grid.findViewById(R.id.empty) != null, "缺 empty");
            View item = getLayoutInflater().inflate(R.layout.item_media, null);
            require(item.findViewById(R.id.thumb) != null, "缺 thumb");
            getLayoutInflater().inflate(R.layout.item_header, null);
            return "网格与条目布局解析正常";
        });

        step("4. 自定义控件构造", () -> {
            new com.privatealbum.app.widget.SquareImageView(this);
            new com.privatealbum.app.widget.ZoomImageView(this);
            new com.privatealbum.app.widget.CropOverlayView(this);
            return "SquareImageView / ZoomImageView / CropOverlayView 可构造";
        });

        step("5. 主题校验（AppCompat + Material3）", () -> {
            androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            require(getTheme() != null, "主题为空");
            com.google.android.material.tabs.TabLayout tabs = new com.google.android.material.tabs.TabLayout(this);
            tabs.addTab(tabs.newTab().setText("测试"));
            int count = tabs.getTabCount();
            require(count == 1, "TabLayout 异常，tabCount=" + count);
            return "TabLayout 可用（这是 MainActivity 里的第一处 Material 控件）";
        });

        step("6. Glide 初始化", () -> {
            com.bumptech.glide.RequestManager manager = com.bumptech.glide.Glide.with(this);
            require(manager != null, "Glide.with 返回 null");
            java.io.File cacheDir = com.bumptech.glide.Glide.getPhotoCacheDir(this);
            return "Glide 可用，缓存目录=" + (cacheDir == null ? "null" : cacheDir.getAbsolutePath());
        });

        step("7. 读取服务器地址配置", () -> {
            String url = com.privatealbum.app.util.ServerConfig.baseUrl(this);
            String token = com.privatealbum.app.util.ServerConfig.token(this);
            require(url != null && url.startsWith("http"), "地址异常: " + url);
            require(token != null && token.length() > 10, "没有登录令牌，请先登录");
            return "服务器=" + url + "，令牌长度=" + token.length();
        });

        step("8. 健康检查（网络）", () -> {
            Responses.Health health = new AlbumApi(this).health(null);
            require(health != null && health.ok, "服务器未就绪");
            return "服务端 " + health.app + " " + health.version
                    + "，照片 " + health.items + " 项，剩余 " + health.diskFreeText;
        });

        step("9. 拉取照片列表", () -> {
            Responses.Page page = new AlbumApi(this).listAssets(null, 20, null, false, false, null, null, null);
            require(page != null, "返回为空");
            return "拿到 " + page.items.size() + " 项，next_cursor=" + (page.nextCursor == null ? "无" : "有");
        });

        step("10. 适配器分组（MediaAdapter.groupByDay）", () -> {
            Responses.Page page = new AlbumApi(this).listAssets(null, 20, null, false, false, null, null, null);
            List<Object> rows = MediaAdapter.groupByDay(page.items, true);
            int headers = 0;
            int assets = 0;
            for (Object row : rows) {
                if (row instanceof MediaAdapter.Header) headers++;
                else assets++;
            }
            return "分组行数=" + rows.size() + "（日期头 " + headers + "，内容 " + assets + "）";
        });

        step("11. RecyclerView + GridLayoutManager 装配", () -> {
            androidx.recyclerview.widget.RecyclerView recycler =
                    new androidx.recyclerview.widget.RecyclerView(this);
            recycler.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 3));
            recycler.setItemAnimator(null);
            MediaAdapter adapter = new MediaAdapter(this, new MediaAdapter.Listener() {
                @Override
                public void onAssetClick(Asset asset, int position) {
                }

                @Override
                public void onAssetLongClick(Asset asset, int position) {
                }

                @Override
                public void onSelectionChanged(int count) {
                }

                @Override
                public void onLoadMore() {
                }
            });
            recycler.setAdapter(adapter);
            adapter.submit(new java.util.ArrayList<>());
            adapter.enterSelection(new Asset());
            adapter.clearSelection();
            return "适配器与网格装配正常";
        });

        step("11b. 真正把 MediaGridFragment 装进容器（复刻 MainActivity）", () -> {
            MediaGridFragment fragment = MediaGridFragment.newInstance(MediaGridFragment.MODE_TIMELINE, null);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content, fragment)
                    .commitNow();
            require(fragment.isAdded(), "Fragment 未装载");
            // 等它把数据加载并渲染出来
            Thread.sleep(2500);
            require(getSupportFragmentManager().findFragmentById(R.id.content) != null, "装载后找不到 Fragment");
            return "MediaGridFragment 装载并渲染完成（这一步复刻了 MainActivity 的首屏）";
        });

        step("12. 缩略图 URL 与下载", () -> {            Responses.Page page = new AlbumApi(this).listAssets(null, 5, null, false, false, null, null, null);
            AlbumApi api = new AlbumApi(this);
            String url = null;
            for (Asset asset : page.items) {
                if (!asset.isVideo()) {
                    url = api.thumbUrl(asset);
                    break;
                }
            }
            if (url == null) return "库里还没有照片，跳过（上传一张再测）";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            int length = conn.getContentLength();
            conn.disconnect();
            require(code == 200, "HTTP " + code);
            require(length > 100, "缩略图太小: " + length);
            return "缩略图 HTTP 200，" + length + " 字节";
        });

        step("13. 用 Glide 真正加载一张缩略图", () -> {
            Responses.Page page = new AlbumApi(this).listAssets(null, 5, null, false, false, null, null, null);
            AlbumApi api = new AlbumApi(this);
            String url = null;
            for (Asset asset : page.items) {
                url = api.thumbUrl(asset);
                if (url != null) break;
            }
            if (url == null) return "库里还没有照片，跳过";
            final android.widget.ImageView view = new android.widget.ImageView(this);
            final Object lock = new Object();
            final Throwable[] error = new Throwable[1];
            final boolean[] done = new boolean[1];
            com.bumptech.glide.Glide.with(this)
                    .load(url)
                    .apply(com.privatealbum.app.AlbumApp.thumbOptions())
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                                                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                    boolean isFirstResource) {
                            error[0] = e;
                            synchronized (lock) {
                                done[0] = true;
                                lock.notifyAll();
                            }
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                       com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                       com.bumptech.glide.load.DataSource dataSource,
                                                       boolean isFirstResource) {
                            synchronized (lock) {
                                done[0] = true;
                                lock.notifyAll();
                            }
                            return false;
                        }
                    })
                    .into(view);
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 30000;
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    lock.wait(500);
                }
            }
            require(done[0], "等待 Glide 超时");
            require(error[0] == null, "Glide 失败: " + error[0]);
            return "Glide 加载成功";
        });

        step("14. 复刻页签切换：反复 setAdapter + notifyDataSetChanged", () -> {
            // 这一段是专门用来复现「切换页签闪退」的：
            // 完全按 MediaGridFragment / AlbumsFragment 的顺序做一遍，
            // 并且反复来 20 轮，一旦有递归会立刻爆栈，被这里抓住。
            java.util.List<String> result = new java.util.ArrayList<>();
            final Throwable[] failure = new Throwable[1];

            Runnable job = () -> {
                try {
                    // 关键：RecyclerView 必须真的挂到窗口上、真的走一遍 measure/layout，
                    // 否则很多问题（尤其是布局相关的递归）复现不出来。
                    android.widget.FrameLayout testHost = new android.widget.FrameLayout(SelfTestActivity.this);
                    testHost.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            (int) (260 * getResources().getDisplayMetrics().density)));
                    android.widget.FrameLayout testContent =
                            findViewById(com.privatealbum.app.R.id.content);
                    if (testContent != null) {
                        testContent.addView(testHost);
                    }

                    androidx.recyclerview.widget.RecyclerView recycler =
                            new androidx.recyclerview.widget.RecyclerView(SelfTestActivity.this);
                    testHost.addView(recycler, new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

                    com.privatealbum.app.util.Guard.enter("SelfTest.页签切换");
                    for (int round = 0; round < 20; round++) {
                        // 1) 模拟 MediaGridFragment.setupRecycler()
                        MediaAdapter adapter = new MediaAdapter(SelfTestActivity.this,
                                new MediaAdapter.Listener() {
                                    @Override
                                    public void onAssetClick(Asset asset, int position) {
                                    }

                                    @Override
                                    public void onAssetLongClick(Asset asset, int position) {
                                    }

                                    @Override
                                    public void onSelectionChanged(int count) {
                                    }

                                    @Override
                                    public void onLoadMore() {
                                    }
                                });
                        recycler.setLayoutManager(
                                new androidx.recyclerview.widget.GridLayoutManager(
                                        SelfTestActivity.this, 3));
                        recycler.setAdapter(adapter);
                        recycler.setItemAnimator(null);

                        // 2) 模拟 onBindRows()：空 -> 有数据 -> 空，每次之间真的 layout 一遍
                        com.privatealbum.app.util.Guard.enter("notifyDataSetChanged");
                        try {
                            adapter.submit(new java.util.ArrayList<>());
                            forceLayout(recycler);
                            adapter.submit(new java.util.ArrayList<Object>(sampleRows()));
                            forceLayout(recycler);
                            adapter.submit(new java.util.ArrayList<>());
                            forceLayout(recycler);
                        } finally {
                            com.privatealbum.app.util.Guard.exit();
                        }

                        // 3) 模拟长按多选 + 取消
                        Asset first = firstAsset();
                        if (first != null) {
                            adapter.enterSelection(first);
                            forceLayout(recycler);
                            adapter.clearSelection();
                            forceLayout(recycler);
                        }
                    }
                    com.privatealbum.app.util.Guard.exit();
                    testHost.removeAllViews();
                    result.add("完成 20 轮（含真实 layout），无异常");
                } catch (Throwable t) {
                    failure[0] = t;
                }
            };

            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                job.run();
            } else {
                runOnUiThread(job);
                for (int i = 0; i < 100 && failure[0] == null && result.isEmpty(); i++) {
                    Thread.sleep(100);
                }
            }
            if (failure[0] != null) {
                com.privatealbum.app.util.Trace.log("!!! 页签切换复现失败：" + failure[0]);
                throw new IllegalStateException("复现出来了：" + failure[0]);
            }
            return result.isEmpty() ? "20 轮完成" : result.get(0);
        });

        footer();
    }

    /** 强制走一次 measure + layout，这样布局相关的递归才能被触发出来。 */
    private void forceLayout(android.view.View view) {
        int widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                Math.max(1, view.getWidth()), android.view.View.MeasureSpec.EXACTLY);
        int heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                Math.max(1, view.getHeight()), android.view.View.MeasureSpec.EXACTLY);
        // 先量父容器，再量自己，最后 layout，尽量贴近真实流程
        if (view.getParent() instanceof android.view.View) {
            android.view.View parent = (android.view.View) view.getParent();
            parent.measure(widthSpec, heightSpec);
            parent.layout(0, 0, parent.getMeasuredWidth(), parent.getMeasuredHeight());
        }
        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, widthSpec & 0x00FFFFFF, heightSpec & 0x00FFFFFF);
    }

    /** 造几行假数据，用来模拟「有照片」时的绑定。 */
    private java.util.List<Object> sampleRows() {
        java.util.List<Object> rows = new java.util.ArrayList<>();
        rows.add(new MediaAdapter.Header("今天", 0));
        for (long id = 1; id <= 9; id++) {
            Asset asset = new Asset();
            asset.id = id;
            asset.fileName = "selftest_" + id + ".jpg";
            asset.mediaType = "image";
            asset.mime = "image/jpeg";
            asset.sizeBytes = 1024 * id;
            asset.sizeText = id + " KB";
            asset.width = 800;
            asset.height = 600;
            asset.takenAt = "2026-09-22T12:00:00Z";
            asset.thumbUrl = "/api/assets/" + id + "/thumb?size=256";
            asset.previewUrl = "/api/assets/" + id + "/thumb?size=1024";
            asset.streamUrl = "/api/assets/" + id + "/stream";
            asset.downloadUrl = "/api/assets/" + id + "/download";
            rows.add(asset);
        }
        return rows;
    }

    private Asset firstAsset() {
        for (Object row : sampleRows()) {
            if (row instanceof Asset) return (Asset) row;
        }
        return null;
    }

    // ------------------------------------------------------------------ 工具
    private interface Step {
        String run() throws Exception;
    }

    private void step(String name, Step step) {
        long started = System.currentTimeMillis();
        try {
            String result = step.run();
            passCount++;
            addLine("✅ " + name, 0xFF81C995);
            addLine("     " + result + "  (" + (System.currentTimeMillis() - started) + " ms)", 0xFF9AA0A6);
        } catch (Throwable t) {
            failCount++;
            addLine("❌ " + name, 0xFFF28B82);
            addLine("     " + describe(t), 0xFFF28B82);
            if (t.getCause() != null) {
                addLine("     起因: " + describe(t.getCause()), 0xFFF28B82);
            }
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String trace = sw.toString();
            if (trace.length() > 1200) trace = trace.substring(0, 1200) + "\n…";
            addLine(trace, 0xFF8AB4F8);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void header(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFECEEF1);
        view.setTextSize(17f);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        view.setPadding(0, 0, 0, pad);
        container.addView(view);
    }

    private void footer() {
        TextView view = new TextView(this);
        view.setText("\n结果：" + passCount + " 项通过，" + failCount + " 项失败\n"
                + "请把这一页截图发给开发者。");
        view.setTextColor(failCount == 0 ? 0xFF81C995 : 0xFFF28B82);
        view.setTextSize(14f);
        view.setTextIsSelectable(true);
        container.addView(view);
    }

    private void addLine(String text, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(11f);
        view.setTextIsSelectable(true);
        container.addView(view);
    }
}
