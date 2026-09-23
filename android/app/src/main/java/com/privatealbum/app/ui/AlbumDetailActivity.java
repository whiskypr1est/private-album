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
import com.privatealbum.app.util.AlbumTree;
import com.privatealbum.app.util.FolderScanner;
import com.privatealbum.app.util.ServerConfig;
import com.privatealbum.app.util.Ui;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 相册详情：子相册 + 照片，共用一个可滚动网格。
 *
 * 为什么不用「横向子相册条 + 下方照片网格」：那样一屏只放得下两张子相册，
 * 用户根本看不出还能横向滑（实际就被当成「只有两个子相册」）；
 * 而且子相册一大块，照片区就被挤没了。现在两者是同一条列表里的两种行，
 * 分组标题通过 spanSizeLookup 占满整行。
 */
public class AlbumDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ALBUM = "album";

    private static final int TYPE_SECTION = 0;    // 分组标题（占满整行）
    private static final int TYPE_SUBALBUM = 1;   // 子相册卡片（一格）
    private static final int TYPE_PHOTO = 2;      // 照片（一格）

    /** 列表里的分组标题行。 */
    private static class Section {
        final String title;
        final String action;   // 非空时右侧显示按钮
        final String hint;     // 非空时标题下方显示一行提示

        Section(String title, String action, String hint) {
            this.title = title;
            this.action = action;
            this.hint = hint;
        }
    }

    private Album album;
    private AlbumApi api;
    private com.google.android.material.appbar.MaterialToolbar toolbar;
    private RecyclerView recycler;
    private TextView breadcrumb;
    private View uploadBar;
    private TextView uploadText;
    private android.widget.ProgressBar uploadProgress;
    private ActivityResultLauncher<String> pickMedia;
    private ActivityResultLauncher<Uri> pickFolder;
    private Adapter adapter;

    private final List<Object> rows = new ArrayList<>();
    private final List<Asset> items = new ArrayList<>();
    private final List<Album> subAlbums = new ArrayList<>();
    /** 列表显示方式（每行一条，带文件名）。 */
    private boolean listMode = false;

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
            if (id == R.id.action_view_mode) {
                cycleViewMode();
                return true;
            }
            return false;
        });

        breadcrumb = findViewById(R.id.breadcrumb);
        recycler = findViewById(R.id.recycler);
        uploadBar = findViewById(R.id.uploadBar);
        uploadText = findViewById(R.id.uploadText);
        uploadProgress = findViewById(R.id.uploadProgress);

        adapter = new Adapter();
        applyViewMode(true);

        findViewById(R.id.fabAdd).setOnClickListener(v -> showFabChooser());

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

    /** 切换显示方式：3 列 -> 5 列 -> 列表 -> 3 列。 */
    private void cycleViewMode() {
        ServerConfig.setViewMode(this, ServerConfig.nextViewMode(this));
        applyViewMode(false);
        Ui.toast(this, ServerConfig.viewModeLabel(this));
    }

    /**
     * 把当前的显示方式套到列表上。
     *
     * @param initial true 表示首次安装（此时才需要 setAdapter）
     */
    private void applyViewMode(boolean initial) {
        listMode = ServerConfig.isListMode(this);
        // 列表模式每行一条；分组标题永远占满整行
        final int span = Math.max(1, ServerConfig.viewColumns(this));
        GridLayoutManager glm = new GridLayoutManager(this, span);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position >= 0 && position < rows.size()
                        && rows.get(position) instanceof Section ? span : 1;
            }
        });
        recycler.setLayoutManager(glm);
        if (initial) {
            recycler.setAdapter(adapter);
            recycler.setItemAnimator(null);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    /** 右下角「+」：上传文件 / 上传文件夹 / 新建子相册 —— 三个入口都放在这里。 */
    private void showFabChooser() {
        String[] options = {
                getString(R.string.upload_choose_files),
                getString(R.string.upload_choose_folder),
                getString(R.string.subalbum_new),
        };
        new AlertDialog.Builder(this)
                .setTitle(album.name)
                .setItems(options, (dlg, which) -> {
                    if (which == 0) {
                        if (pickMedia != null) pickMedia.launch("*/*");
                    } else if (which == 1) {
                        if (pickFolder != null) pickFolder.launch(null);
                    } else {
                        newSubAlbum();
                    }
                })
                .show();
    }

    private void startUpload(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        // 只有一张时排序没有意义
        if (uris.size() <= 1) {
            enqueueUpload(uris, UploadManager.SortMode.BY_NAME);
            return;
        }
        String[] labels = {
                getString(R.string.upload_sort_by_name),
                getString(R.string.upload_sort_by_source),
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.upload_sort_title))
                .setItems(labels, (dlg, which) -> enqueueUpload(uris,
                        which == 0 ? UploadManager.SortMode.BY_NAME
                                   : UploadManager.SortMode.SOURCE_ORDER))
                .show();
    }

    private void enqueueUpload(List<Uri> uris, UploadManager.SortMode mode) {
        Ui.toast(this, getString(R.string.upload_queued, uris.size()));
        // 关键：把 album.id 一起传下去，服务器入库时直接归入本相册
        UploadManager.get().enqueue(this, uris, album.id, mode);
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
                load();
            }
        });
    }

    // ------------------------------------------------------------ 数据
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

                    rebuildRows();

                    toolbar.setTitle(album.name);

                    // 面包屑：只有进到子相册里（层级 > 1）才有意义
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

    /** 把「子相册标题 + 子相册 + 照片标题 + 照片」拼成一条列表。 */
    private void rebuildRows() {
        rows.clear();

        String subHint = subAlbums.isEmpty()
                ? getString(R.string.subalbum_none) + "，点右边「" + getString(R.string.subalbum_new) + "」"
                : null;
        rows.add(new Section(getString(R.string.subalbum_section),
                getString(R.string.subalbum_new), subHint));
        rows.addAll(subAlbums);

        String photoHint = items.isEmpty()
                ? getString(R.string.album_photos_empty_hint)
                : null;
        rows.add(new Section(getString(R.string.album_photos_section), null, photoHint));
        rows.addAll(items);

        adapter.notifyDataSetChanged();
    }

    // -------------------------------------------------------- 子相册
    /** 进入子相册：直接再开一个详情页，系统返回键天然就是「上一层」。 */
    private void openSubAlbum(Album sub) {
        startActivity(AlbumDetailActivity.intent(this, sub));
    }

    private void newSubAlbum() {
        showAlbumNameDialog(null, getString(R.string.subalbum_in_album, album.name), (name, desc) -> {
            new Thread(() -> {
                try {
                    api.createAlbum(name, desc, album.id);
                    runOnUiThread(() -> {
                        Ui.toast(this, getString(R.string.subalbum_created, name));
                        load();
                    });
                } catch (ApiException e) {
                    runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
                }
            }).start();
        });
    }

    private void renameThisAlbum() {
        showAlbumNameDialog(album, getText(R.string.album_rename).toString(), (name, desc) -> {
            new Thread(() -> {
                try {
                    api.renameAlbum(album.id, name, desc);
                    runOnUiThread(this::load);
                } catch (ApiException e) {
                    runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
                }
            }).start();
        });
    }

    private void renameSubAlbum(Album sub) {
        showAlbumNameDialog(sub, getText(R.string.album_rename).toString(), (name, desc) -> {
            new Thread(() -> {
                try {
                    api.renameAlbum(sub.id, name, desc);
                    runOnUiThread(this::load);
                } catch (ApiException e) {
                    runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
                }
            }).start();
        });
    }

    /** 新建 / 重命名相册的输入框（existing 为空表示新建）。 */
    private interface OnAlbumName {
        void onOk(String name, String description);
    }

    private void showAlbumNameDialog(Album existing, String title, OnAlbumName callback) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_album_name, null);
        com.google.android.material.textfield.TextInputEditText name = view.findViewById(R.id.name);
        com.google.android.material.textfield.TextInputEditText desc = view.findViewById(R.id.description);
        if (existing != null) {
            name.setText(existing.name);
            desc.setText(existing.description);
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_save), (dlg, idx) -> {
                    String albumName = name.getText() == null ? "" : name.getText().toString().trim();
                    String description = desc.getText() == null ? "" : desc.getText().toString().trim();
                    if (albumName.isEmpty()) {
                        Ui.toast(this, getString(R.string.enter_album_name));
                        return;
                    }
                    callback.onOk(albumName, description);
                })
                .show();
    }

    private void moveThisAlbum() {
        moveAlbumFlow(album);
    }

    /** 把某个相册移动到别处（含移回顶层）。自己与自己的后代不会出现在候选里。 */
    private void moveAlbumFlow(Album target) {
        new Thread(() -> {
            try {
                Responses.AlbumList result = api.albums();
                final List<Album> all = result.albums == null ? new ArrayList<>() : result.albums;
                final List<Album> targets = AlbumTree.moveTargets(all, target.id);
                runOnUiThread(() -> showMovePicker(all, targets, target));
            } catch (ApiException e) {
                runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
            }
        }).start();
    }

    private void showMovePicker(List<Album> all, List<Album> targets, Album target) {
        final String[] labels = new String[targets.size() + 1];
        labels[0] = getString(R.string.move_to_top);
        for (int i = 0; i < targets.size(); i++) {
            labels[i + 1] = AlbumTree.indentedName(all, targets.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.move_title, target.name))
                .setItems(labels, (dlg, which) -> {
                    Long parentId = which == 0 ? null : targets.get(which - 1).id;
                    new Thread(() -> {
                        try {
                            api.moveAlbum(target.id, parentId);
                            runOnUiThread(() -> {
                                Ui.toast(this, getString(R.string.move_done));
                                load();
                            });
                        } catch (ApiException e) {
                            runOnUiThread(() -> Ui.toastLong(this, e.getMessage()));
                        }
                    }).start();
                })
                .show();
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
                        moveAlbumFlow(sub);
                    } else {
                        confirmDeleteSubAlbum(sub);
                    }
                })
                .show();
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

    // ------------------------------------------------------------ 分享
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
                            .setMessage(url + "\n\n" + getString(R.string.album_share_note))
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

    private void showItemMenu(Asset asset) {
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

    // ------------------------------------------------------------ 适配器
    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            Object row = rows.get(position);
            if (row instanceof Section) return TYPE_SECTION;
            if (row instanceof Album) return TYPE_SUBALBUM;
            return TYPE_PHOTO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_SECTION) {
                return new SectionHolder(inflater.inflate(R.layout.item_section_header, parent, false));
            }
            if (viewType == TYPE_SUBALBUM) {
                return new SubHolder(inflater.inflate(R.layout.item_subalbum, parent, false));
            }
            // 照片行：列表模式换成一行一条的布局（两个布局 id 一致，Holder 通用）
            return new PhotoHolder(inflater.inflate(
                    listMode ? R.layout.item_media_list : R.layout.item_media, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            if (holder instanceof SectionHolder) {
                bindSection((SectionHolder) holder, (Section) row);
            } else if (holder instanceof SubHolder) {
                bindSubAlbum((SubHolder) holder, (Album) row);
            } else {
                bindPhoto((PhotoHolder) holder, (Asset) row);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private void bindSection(SectionHolder holder, Section section) {
        holder.title.setText(section.title);
        if (section.action != null) {
            holder.action.setText(section.action);
            holder.action.setVisibility(View.VISIBLE);
            // 「子相册」这一组的按钮 = 新建子相册
            holder.action.setOnClickListener(v -> newSubAlbum());
        } else {
            holder.action.setVisibility(View.GONE);
            holder.action.setOnClickListener(null);
        }
        if (section.hint != null) {
            holder.hint.setText(section.hint);
            holder.hint.setVisibility(View.VISIBLE);
        } else {
            holder.hint.setVisibility(View.GONE);
        }
    }

    private void bindSubAlbum(SubHolder holder, Album sub) {
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

    private void bindPhoto(PhotoHolder holder, Asset asset) {
        Glide.with(AlbumDetailActivity.this)
                .load(api.thumbUrl(asset))
                .apply(AlbumApp.thumbOptions())
                .into(holder.thumb);
        holder.videoBadge.setVisibility(asset.isVideo() ? View.VISIBLE : View.GONE);
        holder.duration.setText(asset.durationText());
        holder.favBadge.setVisibility(asset.favorite ? View.VISIBLE : View.GONE);
        holder.overlay.setVisibility(View.GONE);
        holder.check.setVisibility(View.GONE);
        // 列表布局才有文件名与信息行（网格布局里这两个控件是 null）
        if (holder.name != null) {
            holder.name.setText(asset.fileName == null ? "" : asset.fileName);
        }
        if (holder.info != null) {
            StringBuilder info = new StringBuilder();
            if (asset.sizeText != null && !asset.sizeText.isEmpty()) info.append(asset.sizeText);
            String res = asset.resolutionText();
            if (res != null && !res.isEmpty()) {
                if (info.length() > 0) info.append(" · ");
                info.append(res);
            }
            if (asset.takenAt != null && !asset.takenAt.isEmpty()) {
                if (info.length() > 0) info.append(" · ");
                info.append(asset.takenAt.replace("T", " ").replace("Z", ""));
            }
            holder.info.setText(info.toString());
        }
        holder.itemView.setOnClickListener(v -> {
            int idx = items.indexOf(asset);
            if (idx >= 0) openViewer(idx);
        });
        holder.itemView.setOnLongClickListener(v -> {
            showItemMenu(asset);
            return true;
        });
    }

    static class SectionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView hint;
        final com.google.android.material.button.MaterialButton action;

        SectionHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.sectionTitle);
            hint = itemView.findViewById(R.id.sectionHint);
            action = itemView.findViewById(R.id.sectionAction);
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

    static class PhotoHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final View videoBadge;
        final TextView duration;
        final ImageView favBadge;
        final View overlay;
        final ImageView check;
        /** 只有列表布局才有，网格布局里是 null */
        final TextView name;
        final TextView info;

        PhotoHolder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            videoBadge = itemView.findViewById(R.id.videoBadge);
            duration = itemView.findViewById(R.id.duration);
            favBadge = itemView.findViewById(R.id.favBadge);
            overlay = itemView.findViewById(R.id.selectedOverlay);
            check = itemView.findViewById(R.id.check);
            name = itemView.findViewById(R.id.name);
            info = itemView.findViewById(R.id.info);
        }
    }
}
