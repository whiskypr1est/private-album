package com.privatealbum.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;
import com.privatealbum.app.R;
import com.privatealbum.app.model.Album;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.model.Responses;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.transfer.UploadManager;
import com.privatealbum.app.util.FolderScanner;
import com.privatealbum.app.util.MediaSaver;
import com.privatealbum.app.util.SystemBars;
import com.privatealbum.app.util.ServerConfig;
import com.privatealbum.app.util.Trace;
import com.privatealbum.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：四个页签（照片 / 相册 / 收藏 / 更多）+ 上传入口 + 多选操作条。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private Toolbar toolbar;
    private TabLayout tabs;
    private View fab;
    private View uploadBar;
    private TextView uploadText;
    private android.widget.ProgressBar uploadProgress;

    private AlbumsFragment albumsFragment;
    private MediaGridFragment favoritesFragment;
    private MoreFragment moreFragment;
    private Fragment current;

    private boolean selecting = false;

    /**
     * 选择照片用的启动器。
     *
     * 【踩坑记录】不能在字段初始化器里直接 registerForActivityResult：
     * 字段初始化发生在构造函数里、Activity 基类还没完全准备好，
     * 某些机型/系统版本上这里抛异常时，异常发生在 super.onCreate() 之前，
     * 表现为「连 onCreate 第一行日志都打不出来就闪退」，极难定位。
     * 正确做法是在 onCreate 里注册。
     */
    private ActivityResultLauncher<String> pickMedia;

    /** 选择文件夹用的启动器（ACTION_OPEN_DOCUMENT_TREE）。 */
    private ActivityResultLauncher<Uri> pickFolder;

    /** 上传进度条容器，扫描文件夹时复用。 */
    private volatile boolean scanningFolder = false;

    /** 当前这次「选文件 / 选文件夹」要传进哪个相册（null = 只进总库）。 */
    private Long pendingAlbumId = null;

    public MainActivity() {
        // 构造函数里也留一条日志：能确认 Activity 对象到底有没有被创建出来
        try {
            Trace.log("MainActivity 构造函数被执行（说明 Activity 已被系统实例化）");
        } catch (Throwable ignored) {
        }
    }

    private void registerPickMedia() {
        try {
            pickMedia = registerForActivityResult(
                    new ActivityResultContracts.GetMultipleContents(), uris -> {
                        if (uris == null || uris.isEmpty()) return;
                        // pendingAlbumId 由「上传到此相册」按钮设置
                        startUpload(new ArrayList<>(uris), pendingAlbumId);
                    });
            Trace.log("registerForActivityResult（选文件）注册成功");
        } catch (Throwable t) {
            Trace.log("registerForActivityResult（选文件）注册失败: " + t);
        }
        try {
            pickFolder = registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(), uri -> {
                        if (uri == null) return;
                        startFolderUpload(uri);
                    });
            Trace.log("registerForActivityResult（选文件夹）注册成功");
        } catch (Throwable t) {
            Trace.log("registerForActivityResult（选文件夹）注册失败: " + t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Trace.log("MainActivity.onCreate 开始");
        registerPickMedia();
        if (!ServerConfig.isLoggedIn(this)) {
            Trace.log("未登录，回到登录页");
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        Trace.log("activity_main 布局已加载");

        // Android 15+ 强制全屏：必须自己给系统栏留出空间，否则顶栏会被状态栏压住
        SystemBars.padTop(findViewById(R.id.appBar));
        SystemBars.marginBottom(findViewById(R.id.fab));
        SystemBars.padBottom(findViewById(R.id.uploadBar));
        Trace.log("系统栏内边距已应用");

        toolbar = findViewById(R.id.toolbar);
        tabs = findViewById(R.id.tabs);
        fab = findViewById(R.id.fab);
        uploadBar = findViewById(R.id.uploadBar);
        uploadText = findViewById(R.id.uploadText);
        uploadProgress = findViewById(R.id.uploadProgress);
        Trace.log("控件绑定完成 toolbar=" + (toolbar != null) + " tabs=" + (tabs != null)
                + " fab=" + (fab != null) + " uploadBar=" + (uploadBar != null));

        setSupportActionBar(toolbar);
        Trace.log("setSupportActionBar 完成");

        // 页签：相册 / 收藏 / 更多（照片总览页已按需求去掉）
        albumsFragment = AlbumsFragment.newInstance();
        Trace.log("相册 Fragment 创建完成");
        favoritesFragment = MediaGridFragment.newInstance(MediaGridFragment.MODE_FAVORITES, null);
        Trace.log("收藏 Fragment 创建完成");
        moreFragment = MoreFragment.newInstance();
        Trace.log("「更多」Fragment 创建完成");

        tabs.addTab(tabs.newTab().setText(getText(R.string.tab_albums)));
        tabs.addTab(tabs.newTab().setText(getText(R.string.tab_favorites)));
        tabs.addTab(tabs.newTab().setText(getText(R.string.tab_more)));
        Trace.log("页签已添加，共 " + tabs.getTabCount() + " 个");
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                Trace.log("页签被选中 -> " + tab.getPosition());
                try {
                    exitSelection();
                    switchTab(tab.getPosition());
                } catch (Throwable t) {
                    Trace.log("切换页签失败 position=" + tab.getPosition() + " : " + t);
                    throw t;
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (current instanceof MediaGridFragment) {
                    ((MediaGridFragment) current).refresh();
                } else if (current instanceof AlbumsFragment) {
                    ((AlbumsFragment) current).refresh();
                }
            }
        });

        fab.setOnClickListener(v -> {
            if (current instanceof AlbumsFragment) {
                // 相册页的 FAB 也提供上传入口（上传到这里 / 新建相册）
                showAlbumTabChooser();
            } else {
                showUploadChoice(null);
            }
        });

        UploadManager.get().observe(this, new UploadManager.Observer() {
            @Override
            public void onProgress(String fileName, int index, int total, long sent, long size) {
                uploadBar.setVisibility(View.VISIBLE);
                uploadText.setText(String.format("%s（%d/%d）\n%s", fileName, index, total,
                        Ui.progress(sent, size)));
                int percent = size <= 0 ? 0 : (int) (sent * 100 / size);
                uploadProgress.setProgress(percent);
            }

            @Override
            public void onFinished(int uploaded, int duplicates, int failed, String lastError) {
                uploadBar.postDelayed(() -> uploadBar.setVisibility(View.GONE), 2500);
                StringBuilder message = new StringBuilder();
                message.append("上传完成：成功 ").append(uploaded).append(" 个");
                if (duplicates > 0) message.append("，跳过重复 ").append(duplicates).append(" 个");
                if (failed > 0) message.append("，失败 ").append(failed).append(" 个");
                if (lastError != null) message.append("\n").append(lastError);
                uploadText.setText(message);
                // 上传结束后刷新当前页，让新照片立刻出现
                if (current instanceof AlbumsFragment) {
                    ((AlbumsFragment) current).refresh();
                } else if (current instanceof MediaGridFragment) {
                    ((MediaGridFragment) current).refresh();
                }
            }
        });

        switchTab(0);
        Trace.log("首屏 Fragment 已装载，onCreate 结束");
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Trace.log("MainActivity.onNewIntent 收到新 Intent");
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Trace.log("MainActivity.onResume");
    }

    @Override
    protected void onDestroy() {
        Trace.log("MainActivity.onDestroy");
        super.onDestroy();
    }

    /** 从系统相册「分享」过来的图片，直接排队上传。 */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null) uris.add(single);
        }
        if (!uris.isEmpty()) {
            // 从系统相册「分享」进来的一批，也算一个批次（批内按文件名自然序）
            startUpload(uris, null);
        }
    }

    /**
     * 开始上传一批文件。
     *
     * @param albumId 非空时上传完成后自动进这个相册；为 null 则只进总库
     */
    void startUpload(List<Uri> uris, Long albumId) {
        if (uris == null || uris.isEmpty()) return;
        Ui.toast(this, getString(R.string.upload_queued, uris.size()));
        UploadManager.get().enqueue(this, uris, albumId);
        uploadBar.setVisibility(View.VISIBLE);
        uploadBar.setTranslationY(0f);
        uploadText.setText(albumId == null
                ? getText(R.string.upload_running)
                : getString(R.string.upload_running_to_album, uris.size()));
        uploadProgress.setProgress(0);
    }

    /** 在「相册」页里打开「全部照片」网格（压在返回栈上，可返回相册列表）。 */
    void showAllPhotos() {
        MediaGridFragment fragment = MediaGridFragment.newInstance(MediaGridFragment.MODE_ALL, null);
        current = fragment;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content, fragment)
                .addToBackStack("all")
                .commitAllowingStateLoss();
        toolbar.setTitle(getText(R.string.all_photos));
        invalidateOptionsMenu();
    }

    /** 相册页的悬浮按钮：先问用户是要上传还是新建相册。 */
    private void showAlbumTabChooser() {
        String[] options = {
                getString(R.string.upload_choose_files),
                getString(R.string.upload_choose_folder),
                getString(R.string.album_new),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.upload_title)
                .setItems(options, (dlg, which) -> {
                    if (which == 0) {
                        launchPicker(null);
                    } else if (which == 1) {
                        launchFolderPicker(null);
                    } else {
                        ((AlbumsFragment) current).createAlbum();
                    }
                })
                .show();
    }

    /**
     * 统一的「上传方式」选择：选文件 / 选文件夹。
     *
     * @param albumId 非空表示上传到指定相册
     */
    void showUploadChoice(Long albumId) {
        String[] options = {
                getString(R.string.upload_choose_files),
                getString(R.string.upload_choose_folder),
        };
        new AlertDialog.Builder(this)
                .setTitle(albumId == null
                        ? getString(R.string.upload_title)
                        : getString(R.string.upload_to_album_title))
                .setItems(options, (dlg, which) -> {
                    if (which == 0) {
                        launchPicker(albumId);
                    } else {
                        launchFolderPicker(albumId);
                    }
                })
                .show();
    }

    /** 打开系统选择器（选多个文件）；注册失败时给出提示而不是直接抛 NPE。 */
    private void launchPicker(Long albumId) {
        if (pickMedia == null) {
            registerPickMedia();
        }
        if (pickMedia == null) {
            Ui.toastLong(this, "系统选择器不可用，请重启 App 后重试");
            return;
        }
        pendingAlbumId = albumId;
        try {
            pickMedia.launch("*/*");
        } catch (Throwable t) {
            Trace.log("打开选择器失败: " + t);
            Ui.toastLong(this, "打开选择器失败：" + t.getMessage());
        }
    }

    /** 打开「选择文件夹」，选完后递归扫描里面的图片/视频再上传。 */
    void launchFolderPicker(Long albumId) {
        if (pickFolder == null) {
            registerPickMedia();
        }
        if (pickFolder == null) {
            Ui.toastLong(this, "系统选择器不可用，请重启 App 后重试");
            return;
        }
        pendingAlbumId = albumId;
        try {
            pickFolder.launch(null);
        } catch (Throwable t) {
            Trace.log("打开文件夹选择器失败: " + t);
            Ui.toastLong(this, "打开文件夹选择器失败：" + t.getMessage());
        }
    }

    /** 扫描选中的文件夹，把所有图片/视频排进上传队列。 */
    private void startFolderUpload(Uri treeUri) {
        if (scanningFolder) {
            Ui.toast(this, "正在扫描上一个文件夹，请稍候");
            return;
        }
        scanningFolder = true;
        uploadBar.setVisibility(View.VISIBLE);
        uploadText.setText(getText(R.string.upload_scanning_folder));
        uploadProgress.setProgress(0);
        final Long albumId = pendingAlbumId;
        final Context appContext = getApplicationContext();
        Trace.log("开始扫描文件夹: " + treeUri);
        new Thread(() -> {
            final List<Uri> files = new ArrayList<>();
            final int[] skipped = new int[]{0};
            FolderScanner.scan(appContext, treeUri, new FolderScanner.Progress() {
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
            Trace.log("文件夹扫描完成：找到 " + files.size() + " 个，跳过 " + skipped[0] + " 个");
            runOnUiThread(() -> {
                scanningFolder = false;
                if (files.isEmpty()) {
                    uploadBar.setVisibility(View.GONE);
                    Ui.toastLong(MainActivity.this, getString(R.string.upload_folder_empty));
                    return;
                }
                Ui.toastLong(MainActivity.this,
                        getString(R.string.upload_folder_found, files.size(), skipped[0]));
                startUpload(files, albumId);
            });
        }).start();
    }

    private void switchTab(int position) {
        Trace.log("switchTab -> " + position);
        Fragment target;
        switch (position) {
            case 0:
                target = albumsFragment;
                break;
            case 1:
                target = favoritesFragment;
                break;
            case 2:
                target = moreFragment;
                break;
            default:
                target = albumsFragment;
                break;
        }
        current = target;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content, target)
                .commitAllowingStateLoss();
        // 「更多」页不需要右下角悬浮按钮
        fab.setVisibility(position == 2 ? View.GONE : View.VISIBLE);
        Trace.log("switchTab 完成 position=" + position + " target=" + target.getClass().getSimpleName());
    }

    // ------------------------------------------------------------ 菜单
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem search = menu.findItem(R.id.action_search);
        MenuItem selectAll = menu.findItem(R.id.action_select_all);
        MenuItem closeSelection = menu.findItem(R.id.action_close_selection);
        MenuItem upload = menu.findItem(R.id.action_upload);
        MenuItem refresh = menu.findItem(R.id.action_refresh);
        MenuItem favorite = menu.findItem(R.id.action_favorite);
        MenuItem album = menu.findItem(R.id.action_album);
        MenuItem save = menu.findItem(R.id.action_save_phone);
        MenuItem share = menu.findItem(R.id.action_share);
        MenuItem delete = menu.findItem(R.id.action_delete);

        boolean gridMode = current instanceof MediaGridFragment
                && ((MediaGridFragment) current).getMode() != MediaGridFragment.MODE_TRASH;
        // 「相册」页也允许搜索：照片页签去掉后，搜索是找照片的主要入口之一
        boolean searchable = gridMode || current instanceof AlbumsFragment;

        if (search != null) search.setVisible(searchable && !selecting);
        if (selectAll != null) selectAll.setVisible(selecting);
        if (closeSelection != null) closeSelection.setVisible(selecting);
        if (upload != null) upload.setVisible(!selecting);
        if (refresh != null) refresh.setVisible(!selecting
                && (current instanceof MediaGridFragment || current instanceof AlbumsFragment));
        if (favorite != null) favorite.setVisible(selecting);
        if (album != null) album.setVisible(selecting);
        if (save != null) save.setVisible(selecting);
        if (share != null) share.setVisible(selecting);
        if (delete != null) delete.setVisible(selecting);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_search) {
            showSearchDialog();
            return true;
        }
        if (id == R.id.action_select_all) {
            selectAllInCurrent();
            return true;
        }
        if (id == R.id.action_upload) {
            showUploadChoice(null);
            return true;
        }
        if (id == R.id.action_upload_folder) {
            launchFolderPicker(null);
            return true;
        }
        if (id == R.id.action_refresh) {
            if (current instanceof MediaGridFragment) ((MediaGridFragment) current).refresh();
            if (current instanceof AlbumsFragment) ((AlbumsFragment) current).refresh();
            return true;
        }
        if (id == R.id.action_close_selection) {
            exitSelection();
            return true;
        }
        if (id == R.id.action_favorite) {
            if (current instanceof MediaGridFragment) ((MediaGridFragment) current).favoriteSelected();
            exitSelection();
            return true;
        }
        if (id == R.id.action_album) {
            addSelectionToAlbum();
            return true;
        }
        if (id == R.id.action_save_phone) {
            saveSelectionToPhone();
            return true;
        }
        if (id == R.id.action_share) {
            shareSelection();
            return true;
        }
        if (id == R.id.action_delete) {
            if (current instanceof MediaGridFragment) ((MediaGridFragment) current).trashSelected();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSearchDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_search, null);
        EditText keyword = view.findViewById(R.id.keyword);
        new AlertDialog.Builder(this)
                .setTitle(getText(R.string.action_search))
                .setView(view)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_search), (dlgSearch, idxSearch) -> {
                    String query = keyword.getText().toString().trim();
                    MediaGridFragment fragment = MediaGridFragment.newInstance(
                            MediaGridFragment.MODE_SEARCH, query.isEmpty() ? null : query);
                    current = fragment;
                    getSupportFragmentManager().beginTransaction()
                            .replace(R.id.content, fragment)
                            .addToBackStack("search")
                            .commitAllowingStateLoss();
                    toolbar.setTitle(query.isEmpty() ? getString(R.string.action_search) : query);
                })
                .show();
    }

    // ------------------------------------------------------------ 多选
    public void showSelectionBar(boolean show) {
        selecting = show;
        toolbar.setTitle(show ? getString(R.string.selected_count, 1) : getString(R.string.app_name));
        invalidateOptionsMenu();
    }

    /**
     * 适配器通知「选中数变化」。
     *
     * 【踩坑记录】这里原来是 `if (count <= 0) { exitSelection(); return; }`，
     * 而 exitSelection() 又会回调 adapter.clearSelection() → onSelectionChanged(0)
     * → 再次进到这个方法 —— 形成无限循环。
     * 现在改成：count<=0 时**只复位界面状态**，绝不反过来再去动适配器/切换页面。
     */
    public void updateSelectionCount(int count) {
        if (count <= 0) {
            selecting = false;
            toolbar.setTitle(getText(R.string.app_name));
            invalidateOptionsMenu();
            return;
        }
        selecting = true;
        toolbar.setTitle(getString(R.string.selected_count, count));
        invalidateOptionsMenu();
    }

    /** 退出多选（唯一入口）：先让 Fragment 清掉选中，再复位工具栏。 */
    private void exitSelection() {
        selecting = false;
        if (current instanceof MediaGridFragment) ((MediaGridFragment) current).exitSelection();
        toolbar.setTitle(getText(R.string.app_name));
        invalidateOptionsMenu();
    }

    private void selectAllInCurrent() {
        if (current instanceof MediaGridFragment) {
            ((MediaGridFragment) current).selectAll();
        }
    }

    private List<Long> currentSelection() {
        if (current instanceof MediaGridFragment) {
            return ((MediaGridFragment) current).getSelectedIds();
        }
        return new ArrayList<>();
    }

    private void addSelectionToAlbum() {
        final List<Long> ids = currentSelection();
        if (ids.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Album> albums = new AlbumApi(MainActivity.this).albums().albums;
                    final List<String> names = new ArrayList<>();
                    for (Album album : albums) {
                        names.add(album.name + "（" + album.count + "）");
                    }
                    names.add("＋ 新建相册");
                    runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getText(R.string.action_add_to_album))
                            .setItems(names.toArray(new String[0]), (dlgAlbum, idxAlbum) -> {
                                if (idxAlbum == albums.size()) {
                                    createAlbumWith(ids);
                                } else {
                                    addToAlbum(albums.get(idxAlbum).id, ids);
                                }
                            })
                            .show());
                } catch (ApiException e) {
                    runOnUiThread(() -> Ui.toast(MainActivity.this, e.getMessage()));
                }
            }
        }).start();
    }

    private void createAlbumWith(List<Long> ids) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_album_name, null);
        final EditText name = view.findViewById(R.id.name);
        new AlertDialog.Builder(this)
                .setTitle(getText(R.string.album_new))
                .setView(view)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_save), (dlgNew, idxNew) -> {
                    final String albumName = name.getText().toString().trim();
                    if (albumName.isEmpty()) return;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                AlbumApi api = new AlbumApi(MainActivity.this);
                                Responses.SimpleResult created = api.createAlbum(albumName, null);
                                api.albumItems(created.id, ids, false);
                                runOnUiThread(() -> {
                                    Ui.toast(MainActivity.this, "已加入相册「" + albumName + "」");
                                    exitSelection();
                                });
                            } catch (ApiException e) {
                                runOnUiThread(() -> Ui.toast(MainActivity.this, e.getMessage()));
                            }
                        }
                    }).start();
                })
                .show();
    }

    private void addToAlbum(long albumId, List<Long> ids) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new AlbumApi(MainActivity.this).albumItems(albumId, ids, false);
                    runOnUiThread(() -> {
                        Ui.toast(MainActivity.this, "已加入相册");
                        exitSelection();
                    });
                } catch (ApiException e) {
                    runOnUiThread(() -> Ui.toast(MainActivity.this, e.getMessage()));
                }
            }
        }).start();
    }

    private void shareSelection() {
        final List<Long> ids = currentSelection();
        if (ids.size() != 1) {
            Ui.toast(this, getString(R.string.share_one_only));
            return;
        }
        final EditText input = new EditText(this);
        input.setHint(getText(R.string.share_days));
        input.setText("30");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(getText(R.string.share_photo))
                .setMessage(getText(R.string.share_hint))
                .setView(input)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.share_create), (dlgShare, idxShare) -> {
                    int days = 30;
                    try {
                        days = Integer.parseInt(input.getText().toString().trim());
                    } catch (Exception ignored) {
                        // 用默认 30 天
                    }
                    createShare(ids.get(0), days);
                })
                .show();
    }

    private void createShare(long assetId, int days) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Responses.ShareResult share =
                            new AlbumApi(MainActivity.this).createShare("asset", assetId, null, days);
                    final String url = ServerConfig.baseUrl(MainActivity.this) + share.url;
                    runOnUiThread(() -> {
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("share", url));
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(getText(R.string.share_photo))
                                .setMessage(url + "\n\n" + getString(R.string.share_copied))
                                .setPositiveButton("好", null)
                                .show();
                        exitSelection();
                    });
                } catch (ApiException e) {
                    runOnUiThread(() -> Ui.toast(MainActivity.this, e.getMessage()));
                }
            }
        }).start();
    }

    private void saveSelectionToPhone() {
        final List<Long> ids = currentSelection();
        if (ids.isEmpty()) return;
        Ui.toastLong(this, getString(R.string.downloading));
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                int fail = 0;
                AlbumApi api = new AlbumApi(MainActivity.this);
                for (long id : ids) {
                    try {
                        Asset asset = api.assetDetail(id);
                        MediaSaver.save(MainActivity.this, api, asset);
                        ok++;
                    } catch (Exception e) {
                        fail++;
                    }
                }
                final int okCount = ok;
                final int failCount = fail;
                runOnUiThread(() -> {
                    String message = "已保存 " + okCount + " 个到手机相册";
                    if (failCount > 0) message += "，失败 " + failCount + " 个";
                    Ui.toastLong(MainActivity.this, message);
                    exitSelection();
                });
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            exitSelection();
            return;
        }
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            // 搜索页是通过 addToBackStack 压进去的；返回后回到「相册」页
            tabs.selectTab(tabs.getTabAt(0));
            switchTab(0);
            return;
        }
        super.onBackPressed();
    }
}
