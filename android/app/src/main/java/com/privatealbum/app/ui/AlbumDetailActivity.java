package com.privatealbum.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.privatealbum.app.AlbumApp;
import com.privatealbum.app.R;
import com.privatealbum.app.model.Album;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.model.Responses;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.transfer.UploadManager;
import com.privatealbum.app.util.FolderScanner;
import com.privatealbum.app.util.ServerConfig;
import com.privatealbum.app.util.Ui;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 相册详情：看相册里的照片，可以移出、设为封面、生成分享链接。 */
public class AlbumDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ALBUM = "album";

    private Album album;
    private AlbumApi api;
    private RecyclerView recycler;
    private View empty;
    private View uploadBar;
    private TextView uploadText;
    private android.widget.ProgressBar uploadProgress;
    private ActivityResultLauncher<String> pickMedia;
    private ActivityResultLauncher<Uri> pickFolder;
    private Adapter adapter;
    private final List<Asset> items = new ArrayList<>();

    public static Intent intent(Context context, Album album) {
        Intent intent = new Intent(context, AlbumDetailActivity.class);
        intent.putExtra(EXTRA_ALBUM, album);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album_detail);
        api = new AlbumApi(this);

        album = (Album) getIntent().getSerializableExtra(EXTRA_ALBUM);
        if (album == null) {
            finish();
            return;
        }

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(album.name);
        toolbar.setNavigationOnClickListener(v -> finish());
        com.privatealbum.app.util.SystemBars.padTop(toolbar);
        com.privatealbum.app.util.SystemBars.marginBottom(findViewById(R.id.fabAdd));
        toolbar.inflateMenu(R.menu.menu_album);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_share_album) {
                shareAlbum();
                return true;
            }
            if (id == R.id.action_rename_album) {
                Ui.toast(this, "请在「相册」页长按相册重命名");
                return true;
            }
            return false;
        });

        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        uploadBar = findViewById(R.id.uploadBar);
        uploadText = findViewById(R.id.uploadText);
        uploadProgress = findViewById(R.id.uploadProgress);
        adapter = new Adapter();
        recycler.setLayoutManager(new GridLayoutManager(this, ServerConfig.gridColumns(this)));
        recycler.setAdapter(adapter);
        recycler.setItemAnimator(null);

        findViewById(R.id.fabAdd).setOnClickListener(v -> showUploadChooser());

        androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(this::load);
        swipe.setColorSchemeResources(R.color.accent);
        registerPickers();
        observeUploads();
        load();
    }

    // ------------------------------------------------------------ 上传
    private void registerPickers() {
        try {
            pickMedia = registerForActivityResult(
                    new ActivityResultContracts.GetMultipleContents(), uris -> {
                        if (uris == null || uris.isEmpty()) return;
                        startUpload(new ArrayList<>(uris));
                    });
        } catch (Throwable t) {
            com.privatealbum.app.util.Trace.log("相册详情页注册选文件失败: " + t);
        }
        try {
            pickFolder = registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(), uri -> {
                        if (uri == null) return;
                        scanFolder(uri);
                    });
        } catch (Throwable t) {
            com.privatealbum.app.util.Trace.log("相册详情页注册选文件夹失败: " + t);
        }
    }

    private void showUploadChooser() {
        String[] options = {
                getString(R.string.upload_choose_files),
                getString(R.string.upload_choose_folder),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.upload_to_album_title)
                .setItems(options, (dlg, which) -> {
                    if (which == 0) {
                        if (pickMedia != null) pickMedia.launch("*/*");
                    } else {
                        if (pickFolder != null) pickFolder.launch(null);
                    }
                })
                .show();
    }

    private void startUpload(List<Uri> uris) {
        Ui.toast(this, getString(R.string.upload_queued, uris.size()));
        // 关键：把 album.id 一起传下去，服务器入库时直接归入本相册
        UploadManager.get().enqueue(this, uris, album.id);
        uploadBar.setVisibility(View.VISIBLE);
        uploadText.setText(getString(R.string.upload_running_to_album, uris.size()));
        uploadProgress.setProgress(0);
    }

    private void scanFolder(Uri treeUri) {
        uploadBar.setVisibility(View.VISIBLE);
        uploadText.setText(getText(R.string.upload_scanning_folder));
        new Thread(() -> {
            final List<Uri> files = new ArrayList<>();
            final int[] skipped = new int[]{0};
            FolderScanner.scan(getApplicationContext(), treeUri, new FolderScanner.Progress() {
                @Override
                public void onScanning(int found) {
                    runOnUiThread(() -> uploadText.setText(
                            getString(R.string.upload_scanning_progress, found)));
                }

                @Override
                public void onDone(List<Uri> result, int skippedCount) {
                    files.addAll(result);
                    skipped[0] = skippedCount;
                }
            });
            runOnUiThread(() -> {
                if (files.isEmpty()) {
                    uploadBar.setVisibility(View.GONE);
                    Ui.toastLong(this, getString(R.string.upload_folder_empty));
                    return;
                }
                Ui.toastLong(this, getString(R.string.upload_folder_found, files.size(), skipped[0]));
                startUpload(files);
            });
        }).start();
    }

    private void observeUploads() {
        UploadManager.get().observe(this, new UploadManager.Observer() {
            @Override
            public void onProgress(String fileName, int index, int total, long sent, long size) {
                uploadBar.setVisibility(View.VISIBLE);
                uploadText.setText(String.format("%s（%d/%d）\n%s", fileName, index, total,
                        Ui.progress(sent, size)));
                uploadProgress.setProgress(size <= 0 ? 0 : (int) (sent * 100 / size));
            }

            @Override
            public void onFinished(int uploaded, int duplicates, int failed, String lastError) {
                uploadBar.postDelayed(() -> uploadBar.setVisibility(View.GONE), 2500);
                StringBuilder message = new StringBuilder();
                message.append("上传完成：成功 ").append(uploaded).append(" 个");
                if (duplicates > 0) message.append("，跳过重复 ").append(duplicates).append(" 个");
                if (failed > 0) message.append("，失败 ").append(failed).append(" 个");
                uploadText.setText(message);
                load();   // 刷新相册内容，新照片立刻出现
            }
        });
    }

    private void load() {
        new Thread(() -> {
            try {
                Responses.AlbumDetail detail = api.albumDetail(album.id, null);
                runOnUiThread(() -> {
                    items.clear();
                    if (detail.items != null) items.addAll(detail.items);
                    if (detail.album != null) album = detail.album;
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe = findViewById(R.id.swipe);
                    swipe.setRefreshing(false);
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe = findViewById(R.id.swipe);
                    swipe.setRefreshing(false);
                    Ui.toastLong(this, e.getMessage());
                });
            }
        }).start();
    }

    private void shareAlbum() {
        new Thread(() -> {
            try {
                Responses.ShareResult share = api.createShare("album", album.id, null, 30);
                String url = ServerConfig.baseUrl(this) + share.url;
                runOnUiThread(() -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("share", url));
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(getText(R.string.share_album))
                            .setMessage(url + "\n\n有效期 30 天，浏览器打开即可浏览（链接已复制）")
                            .setPositiveButton("好", null)
                            .show();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
            }
        }).start();
    }

    private void openViewer(int index) {
        Intent intent = new Intent(this, PhotoViewerActivity.class);
        intent.putExtra(PhotoViewerActivity.EXTRA_ITEMS, (Serializable) new ArrayList<>(items));
        intent.putExtra(PhotoViewerActivity.EXTRA_INDEX, index);
        startActivity(intent);
    }

    private class Adapter extends RecyclerView.Adapter<Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_media, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Asset asset = items.get(position);
            Glide.with(AlbumDetailActivity.this)
                    .load(api.thumbUrl(asset))
                    .apply(AlbumApp.thumbOptions())
                    .into(holder.thumb);
            holder.videoBadge.setVisibility(asset.isVideo() ? View.VISIBLE : View.GONE);
            holder.duration.setText(asset.durationText());
            holder.favBadge.setVisibility(asset.favorite ? View.VISIBLE : View.GONE);
            holder.overlay.setVisibility(View.GONE);
            holder.check.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(v -> openViewer(holder.getBindingAdapterPosition()));
            holder.itemView.setOnLongClickListener(v -> {
                showItemMenu(asset, holder.getBindingAdapterPosition());
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private void showItemMenu(Asset asset, int position) {
        String[] actions = {"设为相册封面", "从相册移出"};
        new AlertDialog.Builder(this)
                .setItems(actions, (dialog, which) -> new Thread(() -> {
                    try {
                        if (which == 0) {
                            api.setAlbumCover(album.id, asset.id);
                        } else {
                            api.albumItems(album.id, java.util.Collections.singletonList(asset.id), true);
                        }
                        runOnUiThread(this::load);
                    } catch (ApiException e) {
                        runOnUiThread(() -> Ui.toast(this, e.getMessage()));
                    }
                }).start())
                .show();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final View videoBadge;
        final TextView duration;
        final ImageView favBadge;
        final View overlay;
        final ImageView check;

        Holder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            videoBadge = itemView.findViewById(R.id.videoBadge);
            duration = itemView.findViewById(R.id.duration);
            favBadge = itemView.findViewById(R.id.favBadge);
            overlay = itemView.findViewById(R.id.selectedOverlay);
            check = itemView.findViewById(R.id.check);
        }
    }
}
