package com.privatealbum.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.privatealbum.app.AlbumApp;
import com.privatealbum.app.R;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.model.Responses;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.util.ServerConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 网格页：照片时间线、收藏、回收站、搜索结果都用它，只是查询参数不同。
 */
public class MediaGridFragment extends BaseFragment implements MediaAdapter.Listener {

    public static final int MODE_TIMELINE = 0;
    public static final int MODE_FAVORITES = 1;
    public static final int MODE_TRASH = 2;
    public static final int MODE_SEARCH = 3;
    /** 全部照片（含不属于任何相册的）。入口在「相册」页的第一行。 */
    public static final int MODE_ALL = 4;

    private static final String ARG_MODE = "mode";
    private static final String ARG_QUERY = "query";

    private MediaAdapter adapter;
    private int mode = MODE_TIMELINE;

    public static MediaGridFragment newInstance(int mode, String query) {
        MediaGridFragment fragment = new MediaGridFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_MODE, mode);
        args.putString(ARG_QUERY, query);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected int layoutId() {
        return R.layout.fragment_grid;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mode = getArguments().getInt(ARG_MODE, MODE_TIMELINE);
            searchKeyword = getArguments().getString(ARG_QUERY);
        }
    }

    @Override
    protected void setupRecycler() {
        com.privatealbum.app.util.Trace.log("MediaGridFragment.setupRecycler 开始 mode=" + mode
                + " recycler=" + (recyclerView != null));
        adapter = new MediaAdapter(requireContext(), this);
        adapter.setListMode(ServerConfig.isListMode(requireContext()));
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(),
                ServerConfig.viewColumns(requireContext())));
        recyclerView.setAdapter(adapter);
        recyclerView.setItemAnimator(null);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy <= 0) return;
                GridLayoutManager manager = (GridLayoutManager) view.getLayoutManager();
                if (manager == null) return;
                int last = manager.findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 8) {
                    loadMore();
                }
            }
        });
        com.privatealbum.app.util.Trace.log("MediaGridFragment.setupRecycler 完成 mode=" + mode);
    }

    @Override
    protected List<Object> fetch(String cursor) throws ApiException {
        boolean favorite = mode == MODE_FAVORITES;
        boolean trashed = mode == MODE_TRASH;
        String query = mode == MODE_SEARCH ? searchKeyword : null;
        Responses.Page page = api.listAssets(cursor, 120, null, favorite, trashed, query, null, null);
        nextCursor = page.nextCursor;
        List<Asset> assets = page.items == null ? new ArrayList<>() : page.items;
        // 不再插入「日期分组标题」：现在排序是按「上传批次 + 文件名自然序」，
        // 按拍摄日期分组会导致显示顺序看起来是乱的（比如去年拍的照片排在今天上传的后面）。
        List<Object> grouped = MediaAdapter.groupByDay(assets, false);
        if (nextCursor != null) {
            grouped.add(new MediaAdapter.LoadMore());
        }
        return grouped;
    }

    @Override
    protected void onBindRows() {
        com.privatealbum.app.util.Trace.log("MediaGridFragment.onBindRows mode=" + mode
                + " 展示行数=" + rows.size() + " adapter=" + (adapter != null));
        if (adapter == null) {
            com.privatealbum.app.util.Trace.log("MediaGridFragment.onBindRows: adapter 为 null，跳过");
            return;
        }
        com.privatealbum.app.util.Guard.count("MediaGridFragment.onBindRows");
        adapter.submit(new ArrayList<>(rows));
        switch (mode) {
            case MODE_FAVORITES:
                setEmptyText(getText(R.string.empty_favorites));
                break;
            case MODE_SEARCH:
                setEmptyText(getText(R.string.empty_search));
                break;
            case MODE_TRASH:
                setEmptyText(getText(R.string.empty_trash));
                break;
            case MODE_ALL:
                setEmptyText(getText(R.string.empty_all_photos));
                break;
            default:
                setEmptyText(getText(R.string.empty_library));
                break;
        }
        updateEmptyState();
    }

    // ------------------------------------------------------------ 适配器回调
    @Override
    public void onAssetClick(Asset asset, int position) {
        List<Asset> assets = adapter.assets();
        int index = 0;
        for (int i = 0; i < assets.size(); i++) {
            if (assets.get(i).id == asset.id) {
                index = i;
                break;
            }
        }
        openViewer(assets, index);
    }

    @Override
    public void onAssetLongClick(Asset asset, int position) {
        if (mode == MODE_TRASH) {
            confirmForeverDelete(asset);
            return;
        }
        adapter.enterSelection(asset);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showSelectionBar(true);
        }
    }

    @Override
    public void onSelectionChanged(int count) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateSelectionCount(count);
        }
    }

    @Override
    public void onLoadMore() {
        loadMore();
    }

    public int getMode() {
        return mode;
    }

    private void confirmForeverDelete(Asset asset) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getText(R.string.action_delete_forever))
                .setMessage(getText(R.string.delete_forever_confirm))
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_delete_forever), (dialog, which) -> new Thread(() -> {
                    try {
                        api.deleteForever(asset.id);
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(this::refresh);
                    } catch (ApiException e) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() ->
                                android.widget.Toast.makeText(requireContext(), e.getMessage(),
                                        android.widget.Toast.LENGTH_LONG).show());
                    }
                }).start())
                .show();
    }

    // ------------------------------------------------------------ 多选操作
    public List<Long> getSelectedIds() {
        return adapter == null ? new ArrayList<>() : adapter.selectedIds();
    }

    public void exitSelection() {
        if (adapter != null) adapter.clearSelection();
    }

    public void selectAll() {
        if (adapter != null) adapter.selectAll();
    }

    public boolean isSelectionMode() {
        return adapter != null && adapter.isSelectionMode();
    }

    public void favoriteSelected() {
        List<Long> ids = getSelectedIds();
        if (ids.isEmpty()) return;
        runAction(ids, new OnAction() {
            @Override
            public void run(List<Long> targetIds) throws ApiException {
                api.setFavorite(targetIds, true);
            }
        });
    }

    public void trashSelected() {
        List<Long> ids = getSelectedIds();
        if (ids.isEmpty()) return;
        if (mode == MODE_TRASH) {
            restoreSelected();
            return;
        }
        confirmTrash(ids);
    }

    public void restoreSelected() {
        List<Long> ids = getSelectedIds();
        if (ids.isEmpty()) return;
        runAction(ids, new OnAction() {
            @Override
            public void run(List<Long> targetIds) throws ApiException {
                api.restore(targetIds);
            }
        });
    }

    public void archiveSelected() {
        List<Long> ids = getSelectedIds();
        if (ids.isEmpty()) return;
        runAction(ids, new OnAction() {
            @Override
            public void run(List<Long> targetIds) throws ApiException {
                api.setArchived(targetIds, true);
            }
        });
    }

    /** 编辑后刷新单个格子，不整页重载。 */
    public void onAssetUpdated(Asset asset) {
        if (adapter != null) adapter.updateAsset(asset);
    }

    public void recreateGrid() {
        if (recyclerView == null) return;
        // 列表模式要换行的布局，所以先让适配器知道，再换列数（列表时列数为 1）
        if (adapter != null) {
            adapter.setListMode(ServerConfig.isListMode(requireContext()));
        }
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(),
                ServerConfig.viewColumns(requireContext())));
    }

    /** 切换显示方式：3 列 -> 5 列 -> 列表 -> 3 列。 */
    public void cycleViewMode() {
        ServerConfig.setViewMode(requireContext(), ServerConfig.nextViewMode(requireContext()));
        recreateGrid();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        adapter = null;
    }
}
