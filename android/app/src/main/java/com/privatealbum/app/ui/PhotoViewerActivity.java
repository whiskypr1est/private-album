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

        adapter = new Adapter();
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateHeader(position);
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
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
        counter.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
        deleteButton.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
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

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_viewer, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Asset asset = items.get(position);
            holder.bind(asset);
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

        Holder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.image);
            video = itemView.findViewById(R.id.video);
            playButton = itemView.findViewById(R.id.playButton);
            progress = itemView.findViewById(R.id.loading);
        }

        void bind(Asset asset) {
            progress.setVisibility(View.GONE);
            if (asset.isVideo()) {
                image.setVisibility(View.GONE);
                video.setVisibility(View.VISIBLE);
                playButton.setVisibility(View.VISIBLE);
                String url = api.streamUrl(asset);
                android.net.Uri uri = android.net.Uri.parse(url);
                video.setVideoURI(uri);
                video.setOnPreparedListener(mp -> {
                    progress.setVisibility(View.GONE);
                    playButton.setVisibility(View.GONE);
                    mp.setLooping(false);
                    video.start();
                });
                video.setOnErrorListener((mp, what, extra) -> {
                    progress.setVisibility(View.GONE);
                    Ui.toast(PhotoViewerActivity.this, "视频播放失败（what=" + what + "）");
                    playButton.setVisibility(View.VISIBLE);
                    return true;
                });
                playButton.setOnClickListener(v -> {
                    progress.setVisibility(View.VISIBLE);
                    video.start();
                });
            } else {
                video.setVisibility(View.GONE);
                playButton.setVisibility(View.GONE);
                image.setVisibility(View.VISIBLE);
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
}
