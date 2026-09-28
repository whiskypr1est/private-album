package com.privatealbum.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.privatealbum.app.AlbumApp;
import com.privatealbum.app.R;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.util.MediaSaver;
import com.privatealbum.app.util.ServerConfig;
import com.privatealbum.app.util.Ui;
import com.privatealbum.app.widget.ZoomImageView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 全屏查看器：左右滑动切换、双指缩放、视频播放、编辑/详情/分享/删除。 */
public class PhotoViewerActivity extends AppCompatActivity {

    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_INDEX = "index";
    public static final int REQUEST_EDIT = 1001;

    private static final String STATE_INDEX = "viewer_index";
    private static final String STATE_VIDEO_ID = "viewer_video_id";
    private static final String STATE_VIDEO_POS = "viewer_video_pos";

    private ViewPager2 pager;
    private TextView title;
    private TextView counter;
    private View topBar;
    private View deleteButton;
    private ProgressBar loading;
    private AlbumApi api;
    private List<Asset> items = new ArrayList<>();
    private Adapter adapter;
    private boolean barsVisible = true;
    /** 删除悬浮按钮的预期可见性（XML 里默认是 gone，点一下画面才出来）。 */
    private boolean deleteVisible = false;
    /** 转屏前正在播的那个视频和位置，转回来接着播。 */
    private long pendingVideoId = -1;
    private int pendingVideoPos = 0;
    /** 当前展示的那一页（onPageSelected 时记下来，转屏存状态要用）。 */
    private Holder currentHolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        api = new AlbumApi(this);

        pager = findViewById(R.id.pager);
        title = findViewById(R.id.title);
        counter = findViewById(R.id.counter);
        topBar = findViewById(R.id.topBar);
        deleteButton = findViewById(R.id.btnDelete);
        // 全屏查看器：顶栏与底部信息都要避开系统栏
        com.privatealbum.app.util.SystemBars.padTop(topBar);
        com.privatealbum.app.util.SystemBars.marginBottom(deleteButton);
        com.privatealbum.app.util.SystemBars.marginBottom(counter);

        Serializable serializable = getIntent().getSerializableExtra(EXTRA_ITEMS);
        if (serializable instanceof List) {
            //noinspection unchecked
            items = new ArrayList<>((List<Asset>) serializable);
        }
        int start = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (savedInstanceState != null) {
            // 转屏重建：回到原来那一张，视频还要接着原来的位置播
            start = savedInstanceState.getInt(STATE_INDEX, start);
            pendingVideoId = savedInstanceState.getLong(STATE_VIDEO_ID, -1);
            pendingVideoPos = savedInstanceState.getInt(STATE_VIDEO_POS, 0);
        }

        adapter = new Adapter();
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateHeader(position);
                // ★滑走的那一页必须停下来★
                // offscreenPageLimit=1，相邻页是活着的。要是不管，
                // 一行 7 个视频就会变成「滑到第 2 个，第 1 个还在后台响」。
                for (Holder h : adapter.liveHolders()) {
                    if (h.getBindingAdapterPosition() == position) {
                        currentHolder = h;
                        h.onBecameCurrent();
                    } else {
                        h.onBecameHidden();
                    }
                }
            }
        });
        if (start > 0 && start < items.size()) {
            pager.setCurrentItem(start, false);
        }
        updateHeader(Math.max(0, Math.min(start, items.size() - 1)));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnEdit).setOnClickListener(v -> editCurrent());
        findViewById(R.id.btnInfo).setOnClickListener(v -> showInfo());
        findViewById(R.id.btnMore).setOnClickListener(v -> showActions());
        deleteButton.setOnClickListener(v -> confirmDelete());
    }

    private int currentIndex() {
        return pager.getCurrentItem();
    }

    private Asset current() {
        int index = currentIndex();
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    private void updateHeader(int position) {
        if (items.isEmpty()) {
            title.setText("");
            counter.setText("");
            return;
        }
        Asset asset = items.get(position);
        title.setText(asset.fileName);
        StringBuilder sb = new StringBuilder();
        sb.append(position + 1).append(" / ").append(items.size());
        if (asset.isVideo() && asset.durationText() != null && !asset.durationText().isEmpty()) {
            sb.append(" · ").append(asset.durationText());
        }
        if (asset.resolutionText() != null && !asset.resolutionText().isEmpty()) {
            sb.append(" · ").append(asset.resolutionText());
        }
        counter.setText(sb);
        applyBottomBars(asset.isVideo());
    }

    /**
     * 底部那两样东西（张数药丸 + 删除悬浮按钮）要跟视频控件条抢位置。
     * 看视频的时候先让位：进度条和播放键比它们重要，而且张数在顶栏里已经写了。
     */
    private void applyBottomBars(boolean isVideo) {
        counter.setVisibility(isVideo || !barsVisible ? View.GONE : View.VISIBLE);
        deleteButton.setVisibility(isVideo || !deleteVisible ? View.GONE : View.VISIBLE);
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
        deleteVisible = barsVisible;
        applyBottomBars(current() != null && current().isVideo());
    }

    private void editCurrent() {
        Asset asset = current();
        if (asset == null) return;
        if (asset.isVideo()) {
            trimCurrent(asset);
            return;
        }
        Intent intent = new Intent(this, EditActivity.class);
        intent.putExtra(EditActivity.EXTRA_ASSET, asset);
        startActivityForResult(intent, REQUEST_EDIT);
    }

    private void trimCurrent(Asset asset) {
        final android.widget.EditText startInput = new android.widget.EditText(this);
        startInput.setHint("起始秒");
        startInput.setText("0");
        final android.widget.EditText endInput = new android.widget.EditText(this);
        endInput.setHint("结束秒");
        long totalSeconds = asset.durationMs == null ? 0 : asset.durationMs / 1000;
        endInput.setText(String.valueOf(totalSeconds));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(startInput);
        layout.addView(endInput);

        new AlertDialog.Builder(this)
                .setTitle(getText(R.string.action_trim))
                .setMessage("按秒裁剪视频时长（会覆盖服务器上的原视频，建议先「保存到手机」留个备份）")
                .setView(layout)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_trim), (dialog, which) -> {
                    long startMs;
                    long endMs;
                    try {
                        startMs = (long) (Double.parseDouble(startInput.getText().toString().trim()) * 1000);
                        endMs = (long) (Double.parseDouble(endInput.getText().toString().trim()) * 1000);
                    } catch (Exception e) {
                        Ui.toast(this, "请输入合法的时间");
                        return;
                    }
                    runTrim(asset, startMs, endMs);
                })
                .show();
    }

    private void runTrim(Asset asset, long startMs, long endMs) {
        Ui.toastLong(this, "正在裁剪，视频较大时需要一会儿…");
        new Thread(() -> {
            try {
                com.privatealbum.app.model.Responses.AssetResult result = api.trimVideo(asset.id, startMs, endMs);
                runOnUiThread(() -> {
                    if (result.asset != null) {
                        replaceAsset(result.asset);
                    }
                    Ui.toast(this, "裁剪完成");
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
            }
        }).start();
    }

    private void replaceAsset(Asset updated) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == updated.id) {
                items.set(i, updated);
                adapter.notifyItemChanged(i);
                break;
            }
        }
    }

    private void showInfo() {
        Asset asset = current();
        if (asset == null) return;
        Ui.toast(this, "正在读取详情…");
        new Thread(() -> {
            try {
                Asset detail = api.assetDetail(asset.id);
                runOnUiThread(() -> {
                    replaceAsset(detail);
                    new AlertDialog.Builder(this)
                            .setTitle(getText(R.string.info_title))
                            .setMessage(buildInfoText(detail))
                            .setPositiveButton("好", null)
                            .show();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toast(this, e.getMessage()));
            }
        }).start();
    }

    private String buildInfoText(Asset asset) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.info_file)).append("：").append(asset.fileName).append('\n');
        sb.append(getString(R.string.info_time)).append("：").append(Ui.fullDateTime(asset.takenAt));
        if (asset.takenSrc != null) {
            sb.append("（来源：").append(asset.takenSrc).append("）");
        }
        sb.append('\n');
        sb.append(getString(R.string.info_size)).append("：").append(asset.sizeText);
        if (asset.resolutionText() != null && !asset.resolutionText().isEmpty()) {
            sb.append('\n').append(getString(R.string.info_resolution)).append("：").append(asset.resolutionText());
        }
        if (asset.isVideo() && asset.durationText() != null && !asset.durationText().isEmpty()) {
            sb.append('\n').append("时长：").append(asset.durationText());
        }
        if (asset.cameraText() != null && !asset.cameraText().isEmpty()) {
            sb.append('\n').append(getString(R.string.info_camera)).append("：").append(asset.cameraText());
        }
        if (asset.exposureText() != null && !asset.exposureText().isEmpty()) {
            sb.append('\n').append(getString(R.string.info_exposure)).append("：").append(asset.exposureText());
        }
        if (asset.gpsLat != null && asset.gpsLon != null) {
            sb.append('\n').append(getString(R.string.info_location)).append("：")
                    .append(String.format(java.util.Locale.US, "%.5f, %.5f", asset.gpsLat, asset.gpsLon));
        }
        if (asset.albums != null && !asset.albums.isEmpty()) {
            sb.append('\n').append(getString(R.string.info_albums)).append("：");
            for (int i = 0; i < asset.albums.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(asset.albums.get(i).name);
            }
        }
        if (asset.relPath != null) {
            sb.append('\n').append(getString(R.string.info_path)).append("：").append(asset.relPath);
        }
        return sb.toString();
    }

    private void showActions() {
        final Asset asset = current();
        if (asset == null) return;
        final String[] actions = {
                getString(asset.favorite ? R.string.action_unfavorite : R.string.action_favorite),
                getString(R.string.action_save_to_phone),
                getString(R.string.action_share),
                getString(R.string.action_add_to_album),
                "复制服务器直链",
                getString(R.string.action_delete)
        };
        new AlertDialog.Builder(this)
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            toggleFavorite(asset);
                            break;
                        case 1:
                            saveToPhone(asset);
                            break;
                        case 2:
                            createShare(asset);
                            break;
                        case 3:
                            addToAlbum(asset);
                            break;
                        case 4:
                            copyDirectLink(asset);
                            break;
                        default:
                            confirmDelete();
                            break;
                    }
                })
                .show();
    }

    private void toggleFavorite(Asset asset) {
        new Thread(() -> {
            try {
                com.privatealbum.app.model.Responses.SimpleResult result =
                        api.setFavorite(java.util.Collections.singletonList(asset.id), !asset.favorite);
                runOnUiThread(() -> {
                    asset.favorite = !asset.favorite;
                    adapter.notifyItemChanged(currentIndex());
                    Ui.toast(this, result.ok ? "已更新收藏状态" : "更新失败");
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toast(this, e.getMessage()));
            }
        }).start();
    }

    private void saveToPhone(Asset asset) {
        Ui.toastLong(this, "正在下载到手机相册…");
        new Thread(() -> {
            try {
                MediaSaver.save(this, api, asset);
                runOnUiThread(() -> Ui.toast(this, "已保存到手机相册（Pictures/私有相册）"));
            } catch (Exception e) {
                runOnUiThread(() -> Ui.toastLong(this, "保存失败：" + e.getMessage()));
            }
        }).start();
    }

    private void createShare(Asset asset) {
        new Thread(() -> {
            try {
                com.privatealbum.app.model.Responses.ShareResult share =
                        api.createShare("asset", asset.id, null, 30);
                String url = ServerConfig.baseUrl(this) + share.url;
                runOnUiThread(() -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("share", url));
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(getText(R.string.share_photo))
                            .setMessage(url + "\n\n有效期 30 天，链接已复制")
                            .setPositiveButton("好", null)
                            .show();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toast(this, e.getMessage()));
            }
        }).start();
    }

    private void addToAlbum(Asset asset) {
        new Thread(() -> {
            try {
                List<com.privatealbum.app.model.Album> albums = api.albums().albums;
                List<String> names = new ArrayList<>();
                for (com.privatealbum.app.model.Album album : albums) {
                    names.add(album.name + "（" + album.count + "）");
                }
                runOnUiThread(() -> {
                    if (names.isEmpty()) {
                        Ui.toast(this, "还没有相册，先去「相册」页新建一个");
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(getText(R.string.action_add_to_album))
                            .setItems(names.toArray(new String[0]), (dialog, which) -> new Thread(() -> {
                                try {
                                    api.albumItems(albums.get(which).id,
                                            java.util.Collections.singletonList(asset.id), false);
                                    runOnUiThread(() -> Ui.toast(this, "已加入相册"));
                                } catch (ApiException e) {
                                    runOnUiThread(() -> Ui.toast(this, e.getMessage()));
                                }
                            }).start())
                            .show();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toast(this, e.getMessage()));
            }
        }).start();
    }

    private void copyDirectLink(Asset asset) {
        String url = api.downloadUrl(asset);
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("direct", url));
        }
        Ui.toast(this, "直链已复制（含访问令牌，请勿外发）");
    }

    private void confirmDelete() {
        final Asset asset = current();
        if (asset == null) return;
        new AlertDialog.Builder(this)
                .setTitle(getText(R.string.action_delete))
                .setMessage(getText(R.string.viewer_delete_confirm))
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_delete), (dialog, which) -> new Thread(() -> {
                    try {
                        api.trash(java.util.Collections.singletonList(asset.id));
                        runOnUiThread(() -> {
                            Ui.toast(this, "已移入回收站");
                            finish();
                        });
                    } catch (ApiException e) {
                        runOnUiThread(() -> Ui.toast(this, e.getMessage()));
                    }
                }).start())
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT && resultCode == RESULT_OK && data != null) {
            Serializable updated = data.getSerializableExtra(EditActivity.EXTRA_RESULT);
            if (updated instanceof Asset) {
                replaceAsset((Asset) updated);
            }
        }
    }

    // ------------------------------------------------------------ 适配器
    private class Adapter extends RecyclerView.Adapter<Holder> {

        /** 当前活着（已创建、还没被回收）的 Holder，用来控制「谁在播」。 */
        private final List<Holder> live = new ArrayList<>();

        List<Holder> liveHolders() {
            return live;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_viewer, parent, false);
            Holder holder = new Holder(view);
            live.add(holder);
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Asset asset = items.get(position);
            holder.bind(asset);
        }

        @Override
        public void onViewRecycled(@NonNull Holder holder) {
            super.onViewRecycled(holder);
            // 被回收的页必须释放播放器：否则解码器一直被占着，
            // 翻过几十张之后就会「播放失败」。
            live.remove(holder);
            holder.releasePlayer();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private class Holder extends RecyclerView.ViewHolder {
        final ZoomImageView image;
        final android.widget.VideoView video;
        final ImageButton playButton;
        final ProgressBar progress;

        // ---- 视频控件 ----
        final View videoLayer;
        final View videoControls;
        final android.widget.SeekBar seekBar;
        final ImageButton btnPlayPause;
        final ImageButton btnFullscreen;
        final TextView videoTime;
        final TextView btnRewind;
        final TextView btnForward;

        private final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        private Runnable ticker;
        /**
         * 播放中 4 秒后自动收起控件条。
         * 注意：只能在构造函数里赋值 —— 写成字段初始化
         * `private final Runnable autoHide = () -> { ... video ... }`
         * 会因为「video 这个 final 字段还没赋值」直接编译不过。
         */
        private final Runnable autoHide;
        /** 用户是不是正在拖进度条 —— 拖的时候别让 ticker 跟我们抢滑块。 */
        private boolean dragging;
        private boolean prepared;
        private boolean controlsVisible;
        /** 期望「正在播」—— 用来驱动进度表，见 tick() 的说明。 */
        private boolean ticking;

        Holder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.image);
            video = itemView.findViewById(R.id.video);
            playButton = itemView.findViewById(R.id.playButton);
            progress = itemView.findViewById(R.id.loading);
            videoLayer = itemView.findViewById(R.id.videoLayer);
            videoControls = itemView.findViewById(R.id.videoControls);
            seekBar = itemView.findViewById(R.id.seekBar);
            btnPlayPause = itemView.findViewById(R.id.btnPlayPause);
            btnFullscreen = itemView.findViewById(R.id.btnFullscreen);
            videoTime = itemView.findViewById(R.id.videoTime);
            btnRewind = itemView.findViewById(R.id.btnRewind);
            btnForward = itemView.findViewById(R.id.btnForward);
            autoHide = () -> {
                if (video.isPlaying()) {
                    setControlsVisible(false);
                }
            };
            // 控件条要避开导航栏（用 padding 而不是 margin：底衬渐变才能一直铺到屏幕边）
            com.privatealbum.app.util.SystemBars.padBottom(videoControls);
            itemView.findViewById(R.id.videoTapLayer).setOnClickListener(v -> toggleControls());
        }

        void bind(Asset asset) {
            progress.setVisibility(View.GONE);
            if (asset.isVideo()) {
                bindVideo(asset);
            } else {
                releasePlayer();
                image.setVisibility(View.VISIBLE);
                videoLayer.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
                Glide.with(PhotoViewerActivity.this)
                        .load(api.previewUrl(asset))
                        .apply(AlbumApp.previewOptions())
                        .addListener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                            @Override
                            public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                                        Object model,
                                                        @NonNull com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                        boolean isFirstResource) {
                                progress.setVisibility(View.GONE);
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(@NonNull android.graphics.drawable.Drawable resource,
                                                           @NonNull Object model,
                                                           com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                           @NonNull com.bumptech.glide.load.DataSource dataSource,
                                                           boolean isFirstResource) {
                                progress.setVisibility(View.GONE);
                                return false;
                            }
                        })
                        .into(image);
                image.setTapListener(new ZoomImageView.OnTapListener() {
                    @Override
                    public void onSingleTap() {
                        toggleBars();
                    }

                    @Override
                    public void onDoubleTap(float x, float y) {
                    }
                });
            }
        }

        // ------------------------------------------------------------ 视频
        private void bindVideo(Asset asset) {
            // 这个 Holder 可能刚被回收来放另一个视频，先把上一次的进度表和状态归零，
            // 否则会拿着上一页的 ticking 继续跑，图标和时间都不对
            stopTicker();
            image.setVisibility(View.GONE);
            videoLayer.setVisibility(View.VISIBLE);
            playButton.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            setControlsVisible(false);

            prepared = false;
            dragging = false;
            seekBar.setProgress(0);
            seekBar.setMax(1000);           // 还没拿到时长，先用个占位量程
            videoTime.setText("--:-- / --:--");
            syncPlayPauseIcon();
            updateFullscreenIcon();

            video.setVideoURI(android.net.Uri.parse(api.streamUrl(asset)));
            video.setOnPreparedListener(mp -> {
                prepared = true;
                progress.setVisibility(View.GONE);
                mp.setLooping(false);
                seekBar.setMax(Math.max(1, video.getDuration()));
                // 只有「当前这一页」才自动播 —— 见 onBecameCurrent 的说明
                long resume = pendingResumePosition(asset);
                if (resume > 0) {
                    video.seekTo((int) resume);
                }
                if (isCurrentPage()) {
                    video.start();
                    tick();                 // 立刻刷一次时间，别等半秒
                    setControlsVisible(true);
                } else {
                    syncPlayPauseIcon();
                }
            });
            video.setOnCompletionListener(mp -> {
                stopTicker();
                seekBar.setProgress(seekBar.getMax());
                videoTime.setText(fmt(video.getDuration()) + " / " + fmt(video.getDuration()));
                syncPlayPauseIcon();
                setControlsVisible(true);
                playButton.setVisibility(View.VISIBLE);
            });
            video.setOnErrorListener((mp, what, extra) -> {
                progress.setVisibility(View.GONE);
                stopTicker();
                Ui.toast(PhotoViewerActivity.this, getString(R.string.video_play_failed) + "（" + what + "）");
                playButton.setVisibility(View.VISIBLE);
                setControlsVisible(true);
                return true;
            });

            playButton.setOnClickListener(v -> {
                playButton.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
                togglePlay();
            });
            btnPlayPause.setOnClickListener(v -> togglePlay());
            btnRewind.setOnClickListener(v -> seekBy(-10_000));
            btnForward.setOnClickListener(v -> seekBy(10_000));
            btnFullscreen.setOnClickListener(v -> toggleFullscreen());

            seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar bar, int value, boolean fromUser) {
                    if (fromUser) {
                        videoTime.setText(fmt(value) + " / " + fmt(video.getDuration()));
                    }
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar bar) {
                    dragging = true;
                    handler.removeCallbacks(autoHide);
                }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar bar) {
                    dragging = false;
                    video.seekTo(bar.getProgress());
                    if (!video.isPlaying()) {
                        // 拖完顺手接着放，符合直觉
                        video.start();
                    }
                    tick();
                    scheduleAutoHide();
                }
            });
        }

        /** 这一页是不是当前正在展示的那一页。 */
        private boolean isCurrentPage() {
            int pos = getBindingAdapterPosition();
            return pos != RecyclerView.NO_POSITION && pos == currentIndex();
        }

        private void togglePlay() {
            if (!prepared) {
                progress.setVisibility(View.VISIBLE);
                video.start();
                return;
            }
            if (video.isPlaying()) {
                video.pause();
                setControlsVisible(true);   // 暂停了就把控件留在屏幕上
                stopTicker();
            } else {
                playButton.setVisibility(View.GONE);
                if (seekBar.getProgress() >= seekBar.getMax() && seekBar.getMax() > 0) {
                    video.seekTo(0);        // 放完了再点，从头开始
                }
                video.start();
                tick();
                scheduleAutoHide();
            }
            syncPlayPauseIcon();
        }

        private void seekBy(int deltaMs) {
            if (!prepared) {
                return;
            }
            int target = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + deltaMs));
            video.seekTo(target);
            seekBar.setProgress(target);
            videoTime.setText(fmt(target) + " / " + fmt(video.getDuration()));
            setControlsVisible(true);
        }

        private void toggleControls() {
            setControlsVisible(!controlsVisible);
        }

        private void setControlsVisible(boolean visible) {
            controlsVisible = visible;
            handler.removeCallbacks(autoHide);
            videoControls.setVisibility(visible ? View.VISIBLE : View.GONE);
            // 播放中且用户没在拖，才自动隐藏；暂停时控件一直留着
            if (visible && video.isPlaying() && !dragging) {
                scheduleAutoHide();
            }
        }

        private void scheduleAutoHide() {
            handler.removeCallbacks(autoHide);
            handler.postDelayed(autoHide, 4000);
        }

        /**
         * 图标跟的是 ticking（我们的意图），不是 isPlaying()：
         * 缓冲的那一两秒 isPlaying() 也是 false，用它会看到按钮在播/停之间反复横跳。
         */
        private void syncPlayPauseIcon() {
            btnPlayPause.setImageResource(ticking ? R.drawable.ic_pause : R.drawable.ic_play);
        }

        /** 每 500ms 刷一次进度。
         *
         * ★别用「isPlaying() 为假就停表」★
         * 缓冲、拖动、切后台时 isPlaying() 都会短暂变假，一停表就再也回不来 ——
         * 表现是网速慢的时候进度条走到一半卡住不动了。
         * 所以用 ticking 表示「我们期望它在播」，只有用户按暂停 / 滑走 / 播完 /
         * 被回收时才停。
         */
        private void tick() {
            handler.removeCallbacks(ticker);
            ticking = true;
            ticker = () -> {
                if (!ticking) {
                    return;
                }
                if (!dragging && prepared) {
                    int pos = video.getCurrentPosition();
                    seekBar.setProgress(pos);
                    videoTime.setText(fmt(pos) + " / " + fmt(video.getDuration()));
                }
                syncPlayPauseIcon();
                handler.postDelayed(ticker, 500);
            };
            handler.post(ticker);
        }

        private void stopTicker() {
            ticking = false;
            if (ticker != null) {
                handler.removeCallbacks(ticker);
                ticker = null;
            }
        }

        /** 滑到这一页：自动播。 */
        void onBecameCurrent() {
            if (videoLayer.getVisibility() != View.VISIBLE) {
                return;                     // 这一页是图片
            }
            if (!prepared) {
                return;                     // 还没准备好，onPrepared 里会接手
            }
            if (!video.isPlaying()) {
                playButton.setVisibility(View.GONE);
                if (seekBar.getProgress() >= seekBar.getMax() && seekBar.getMax() > 0) {
                    video.seekTo(0);
                }
                video.start();
            }
            tick();
            setControlsVisible(true);
        }

        /** 滑走了：必须停下，否则相邻页（offscreenPageLimit=1）会一起响。 */
        void onBecameHidden() {
            if (prepared && video.isPlaying()) {
                video.pause();
            }
            stopTicker();
            if (prepared) {
                int pos = video.getCurrentPosition();
                seekBar.setProgress(pos);
                videoTime.setText(fmt(pos) + " / " + fmt(video.getDuration()));
            }
            syncPlayPauseIcon();
            setControlsVisible(false);
        }

        /** 被回收：彻底释放播放器，别占着解码器不放。 */
        void releasePlayer() {
            stopTicker();
            handler.removeCallbacksAndMessages(null);
            try {
                video.stopPlayback();
            } catch (Exception ignored) {
            }
            prepared = false;
        }

        private void updateFullscreenIcon() {
            btnFullscreen.setImageResource(isLandscape()
                    ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
            btnFullscreen.setContentDescription(getString(
                    isLandscape() ? R.string.video_exit_fullscreen : R.string.video_fullscreen));
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private void toggleFullscreen() {
        setRequestedOrientation(isLandscape()
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private static String fmt(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        int total = ms / 1000;
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        return h > 0
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    /** 转屏后要接着播的位置（没有就返回 0）。 */
    private long pendingResumePosition(Asset asset) {
        if (pendingVideoId == asset.id && pendingVideoPos > 0) {
            long pos = pendingVideoPos;
            pendingVideoId = -1;
            pendingVideoPos = 0;
            return pos;
        }
        return 0;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 暂停后台视频播放，避免退出后还在响
        try {
            for (int i = 0; i < pager.getChildCount(); i++) {
                View child = pager.getChildAt(i);
                android.widget.VideoView view = child.findViewById(R.id.video);
                if (view != null && view.isPlaying()) {
                    view.pause();
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 转屏（全屏按钮）会重建 Activity，位置得自己记住，否则会跳回第一张
        outState.putInt(STATE_INDEX, currentIndex());
        Asset asset = current();
        // 用记下来的 currentHolder，而不是 getChildAt(0)：
        // 开着 offscreenPageLimit 时第 0 个子 View 未必是当前这一页
        if (currentHolder != null && asset != null && asset.isVideo()
                && currentHolder.videoLayer.getVisibility() == View.VISIBLE) {
            outState.putLong(STATE_VIDEO_ID, asset.id);
            outState.putInt(STATE_VIDEO_POS, currentHolder.video.getCurrentPosition());
        }
    }
}
