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
    private com.google.android.material.appbar.MaterialToolbar toolbar;
    private RecyclerView recycler;
    private RecyclerView subRecycler;
    private View subSection;
    private TextView breadcrumb;
    private View empty;
    private View uploadBar;
    private TextView uploadText;
    private android.widget.ProgressBar uploadProgress;
    private ActivityResultLauncher<String> pickMedia;
    private ActivityResultLauncher<Uri> pickFolder;
    private Adapter adapter;
    private SubAdapter subAdapter;
    private final List<Asset> items = new ArrayList<>();
    /** 直接子相册（不含更深层） */
    private final List<Album> subAlbums = new ArrayList<>();

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

        toolbar = findViewById(R.id.toolbar);
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
            if (id == R.id.action_new_subalbum) {
                newSubAlbum();
                return true;
            }
            if (id == R.id.action_move_album) {
                moveThisAlbum();
                return true;
            }
            if (id == R.id.action_rename_album) {
                renameThisAlbum();
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

        // 子相册横向条：没子相册时整块隐藏
        breadcrumb = findViewById(R.id.breadcrumb);
        subSection = findViewById(R.id.subAlbumsSection);
        subRecycler = findViewById(R.id.subAlbumsRecycler);
        subAdapter = new SubAdapter();
        subRecycler.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(
                this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
        subRecycler.setAdapter(subAdapter);
        subRecycler.setItemAnimator(null);

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

                    subAlbums.clear();
                    if (detail.subAlbums != null) subAlbums.addAll(detail.subAlbums);

                    adapter.notifyDataSetChanged();
                    subAdapter.notifyDataSetChanged();

                    toolbar.setTitle(album.name);

                    // 面包屑：只有进到子相册里（层级 > 1）才显示，顶层相册显示它没意义
                    if (detail.breadcrumb != null && detail.breadcrumb.size() > 1) {
                        StringBuilder crumb = new StringBuilder();
                        for (Responses.Breadcrumb b : detail.breadcrumb) {
                            if (crumb.length() > 0) crumb.append(" / ");
                            crumb.append(b.name);
                        }
                        breadcrumb.setText(crumb.toString());
                        breadcrumb.setVisibility(View.VISIBLE);
                    } else {
                        breadcrumb.setVisibility(View.GONE);
                    }

                    subSection.setVisibility(subAlbums.isEmpty() ? View.GONE : View.VISIBLE);
                    // 既没有照片、也没有子相册，才算「空相册」
                    empty.setVisibility(items.isEmpty() && subAlbums.isEmpty()
                            ? View.VISIBLE : View.GONE);

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

    // -------------------------------------------------------- 子相册
    /** 进入子相册：直接再开一个详情页，系统返回键天然就是「上一层」。 */
    private void openSubAlbum(Album sub) {
        startActivity(AlbumDetailActivity.intent(this, sub));
    }

    private void newSubAlbum() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_album_name, null);
        com.google.android.material.textfield.TextInputEditText name = view.findViewById(R.id.name);
        com.google.android.material.textfield.TextInputEditText desc = view.findViewById(R.id.description);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.subalbum_in_album, album.name))
                .setView(view)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_save), (dlg, idx) -> {
                    String subName = name.getText() == null ? "" : name.getText().toString().trim();
                    String subDesc = desc.getText() == null ? "" : desc.getText().toString().trim();
                    if (subName.isEmpty()) {
                        Ui.toast(this, getString(R.string.enter_album_name));
                        return;
                    }
                    createSubAlbumOnServer(subName, subDesc);
                })
                .show();
    }

    private void createSubAlbumOnServer(String subName, String subDesc) {
        new Thread(() -> {
            try {
                api.createAlbum(subName, subDesc, album.id);
                runOnUiThread(this::load);
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
            }
        }).start();
    }

    private void renameThisAlbum() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_album_name, null);
        com.google.android.material.textfield.TextInputEditText name = view.findViewById(R.id.name);
        com.google.android.material.textfield.TextInputEditText desc = view.findViewById(R.id.description);
        name.setText(album.name);
        desc.setText(album.description);
        new AlertDialog.Builder(this)
                .setTitle(getText(R.string.album_rename))
                .setView(view)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_save), (dlg, idx) -> {
                    String newName = name.getText() == null ? "" : name.getText().toString().trim();
                    String newDesc = desc.getText() == null ? "" : desc.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            api.renameAlbum(album.id, newName, newDesc);
                            runOnUiThread(this::load);
                        } catch (ApiException e) {
                            runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
                        }
                    }).start();
                })
                .show();
    }

    /** 把「当前相册」移动到别处（含移回顶层）。 */
    private void moveThisAlbum() {
        new Thread(() -> {
            try {
                Responses.AlbumList result = api.albums();
                List<Album> all = result.albums == null ? new ArrayList<>() : result.albums;
                // 排除自己与自己的所有后代，避免让用户白点一个必然失败的选项
                final List<Album> targets = com.privatealbum.app.util.AlbumTree
                        .moveTargets(all, album.id);
                runOnUiThread(() -> showMovePicker(all, targets, album));
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
            }
        }).start();
    }

    private void showMovePicker(List<Album> all, List<Album> targets, Album target) {
        final String[] labels = new String[targets.size() + 1];
        labels[0] = getString(R.string.move_to_top);
        for (int i = 0; i < targets.size(); i++) {
            labels[i + 1] = com.privatealbum.app.util.AlbumTree.indentedName(all, targets.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.move_title, target.name))
                .setItems(labels, (dlg, which) -> {
                    Long parentId = which == 0 ? null : targets.get(which - 1).id;
                    moveAlbumOnServer(target, parentId);
                })
                .show();
    }

    private void moveAlbumOnServer(Album target, Long parentId) {
        new Thread(() -> {
            try {
                api.moveAlbum(target.id, parentId);
                runOnUiThread(() -> {
                    Ui.toast(this, getString(R.string.move_done));
                    if (target.id == album.id) {
                        load();          // 自己被动过，刷新面包屑
                    } else {
                        load();          // 子相册动过，刷新这一层
                    }
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
            }
        }).start();
    }

    private void showSubAlbumMenu(Album sub) {
        String[] actions = {
                getString(R.string.action_rename),
                getString(R.string.action_move),
                getString(R.string.action_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(sub.name)
                .setItems(actions, (dlg, which) -> {
                    if (which == 0) {
                        renameSubAlbum(sub);
                    } else if (which == 1) {
                        new Thread(() -> {
                            try {
                                Responses.AlbumList result = api.albums();
                                List<Album> all = result.albums == null ? new ArrayList<>() : result.albums;
                                final List<Album> targets = com.privatealbum.app.util.AlbumTree
                                        .moveTargets(all, sub.id);
                                runOnUiThread(() -> showMovePicker(all, targets, sub));
                            } catch (ApiException e) {
                                runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
                            }
                        }).start();
                    } else {
                        confirmDeleteSubAlbum(sub);
                    }
                })
                .show();
    }

    private void renameSubAlbum(Album sub) {
        renameAlbumDialog(sub, this::load);
    }

    private void confirmDeleteSubAlbum(Album sub) {
        String msg = sub.hasChildren()
                ? getString(R.string.album_delete_with_children, sub.childCount)
                : getString(R.string.album_delete_confirm);
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_delete), (dlg, idx) -> new Thread(() -> {
                    try {
                        api.deleteAlbum(sub.id);
                        runOnUiThread(this::load);
                    } catch (ApiException e) {
                        runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
                    }
                }).start())
                .show();
    }

    /** 重命名任意相册（供子相册菜单复用）。 */
    private void renameAlbumDialog(Album target, Runnable after) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_album_name, null);
        com.google.android.material.textfield.TextInputEditText name = view.findViewById(R.id.name);
        com.google.android.material.textfield.TextInputEditText desc = view.findViewById(R.id.description);
        name.setText(target.name);
        desc.setText(target.description);
        new AlertDialog.Builder(this)
                .setTitle(getText(R.string.album_rename))
                .setView(view)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_save), (dlg, idx) -> {
                    String newName = name.getText() == null ? "" : name.getText().toString().trim();
                    String newDesc = desc.getText() == null ? "" : desc.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            api.renameAlbum(target.id, newName, newDesc);
                            runOnUiThread(after);
                        } catch (ApiException e) {
                            runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
                        }
                    }).start();
                })
                .show();
    }

    /** 子相册横向条的适配器。 */
    private class SubAdapter extends RecyclerView.Adapter<SubHolder> {

        @NonNull
        @Override
        public SubHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new SubHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_subalbum, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull SubHolder holder, int position) {
            Album sub = subAlbums.get(position);
            holder.name.setText(sub.name);
            holder.subtitle.setText(sub.subtitle());

            String cover = api.albumCoverUrl(sub);
            if (cover == null) {
                holder.cover.setPadding(40, 40, 40, 40);
                holder.cover.setImageResource(R.drawable.ic_folder);
            } else {
                holder.cover.setPadding(0, 0, 0, 0);
                Glide.with(holder.cover.getContext())
                        .load(cover)
                        .apply(AlbumApp.thumbOptions())
                        .into(holder.cover);
            }

            holder.itemView.setOnClickListener(v -> openSubAlbum(sub));
            holder.itemView.setOnLongClickListener(v -> {
                showSubAlbumMenu(sub);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return subAlbums.size();
        }
    }

    static class SubHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView name;
        final TextView subtitle;

        SubHolder(View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.cover);
            name = itemView.findViewById(R.id.name);
            subtitle = itemView.findViewById(R.id.subtitle);
        }
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
