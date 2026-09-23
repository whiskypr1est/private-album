package com.privatealbum.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.privatealbum.app.R;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.util.ServerConfig;

import java.util.ArrayList;
import java.util.List;

/** 四个页签（照片 / 相册 / 收藏 / 更多）共同的骨架：加载、报错、下拉刷新、空状态。 */
public abstract class BaseFragment extends Fragment {

    protected AlbumApi api;
    protected RecyclerView recyclerView;
    /**
     * 空状态容器。
     *
     * 【踩坑记录】这里必须是 ViewGroup（fragment_grid.xml 里的 @+id/empty 是 LinearLayout），
     * 之前手滑写成 android.widget.TextView，findViewById 会抛
     * ClassCastException: LinearLayout cannot be cast to TextView，
     * 而且崩在 onCreateView 里——表现就是「一进主界面就闪退」，
     * 连 MainActivity.onCreate 的日志都可能来不及打全。
     */
    protected ViewGroup emptyView;
    protected android.widget.TextView emptyTextView;
    protected View loadingView;
    protected androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;

    protected final List<Object> rows = new ArrayList<>();
    protected boolean loading = false;
    protected String nextCursor;
    protected String searchKeyword;

    protected abstract int layoutId();

    protected abstract void onBindRows();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 不要在 onCreate 里碰 Context：某些恢复场景下 Fragment 还没 attach，
        // requireContext() 会直接抛 IllegalStateException。放到 onAttach 里最稳。
        if (getContext() != null) {
            api = new AlbumApi(getContext());
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (api == null) {
            api = new AlbumApi(context);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        com.privatealbum.app.util.Trace.log(getClass().getSimpleName() + ".onCreateView 开始，布局="
                + getResources().getResourceEntryName(layoutId()));
        View root = inflater.inflate(layoutId(), container, false);
        recyclerView = root.findViewById(R.id.recycler);
        emptyView = root.findViewById(R.id.empty);
        emptyTextView = root.findViewById(R.id.emptyText);
        loadingView = root.findViewById(R.id.loading);
        swipe = root.findViewById(R.id.swipe);
        com.privatealbum.app.util.Trace.log(getClass().getSimpleName()
                + ".onCreateView 控件绑定 recycler=" + (recyclerView != null)
                + " empty=" + (emptyView != null)
                + " emptyText=" + (emptyTextView != null)
                + " loading=" + (loadingView != null)
                + " swipe=" + (swipe != null));
        if (swipe != null) {
            swipe.setOnRefreshListener(this::refresh);
            swipe.setColorSchemeResources(R.color.accent);
        }
        setupRecycler();
        com.privatealbum.app.util.Trace.log(getClass().getSimpleName() + ".onCreateView 完成");
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        com.privatealbum.app.util.Trace.log(getClass().getSimpleName() + ".onViewCreated，开始加载数据");
        load(true);
    }

    /** 子类配置 LayoutManager / Adapter / 滚动监听。 */
    protected void setupRecycler() {
    }

    protected void load(boolean reset) {
        com.privatealbum.app.util.Guard.count(getClass().getSimpleName() + ".load");
        loading = true;
        if (reset) {
            nextCursor = null;
            rows.clear();
        }
        showLoading(rows.isEmpty());
        new Thread(() -> {
            try {
                List<Object> loaded = fetch(nextCursor);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (reset) rows.clear();
                    rows.addAll(loaded);
                    loading = false;
                    showLoading(false);
                    hideRefresh();
                    onBindRows();
                });
            } catch (ApiException e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    loading = false;
                    showLoading(false);
                    hideRefresh();
                    handleError(e);
                });
            }
        }).start();
    }

    public void refresh() {
        load(true);
    }

    public void loadMore() {
        if (loading || nextCursor == null) return;
        load(false);
    }

    /** 子类返回本页数据，rows 已经是「扁平化」的展示项（分组头 + 资源）。 */
    protected abstract List<Object> fetch(String cursor) throws ApiException;

    protected void handleError(ApiException e) {
        if (!isAdded()) return;
        if (e.isAuthError()) {
            ServerConfig.logout(requireContext());
            startActivity(new Intent(requireContext(), LoginActivity.class));
            requireActivity().finish();
            return;
        }
        if (rows.isEmpty()) {
            if (emptyView != null) {
                emptyView.setVisibility(View.VISIBLE);
                if (emptyTextView != null) emptyTextView.setText(e.getMessage());
            }
        } else {
            Snackbar.make(requireView(), e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }

    protected void showLoading(boolean show) {
        if (loadingView != null) loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    protected void hideRefresh() {
        if (swipe != null) swipe.setRefreshing(false);
    }

    /**
     * 设置空状态文字。
     *
     * 传进来的已经是解析好的字符串（调用方用 getText/getString 取好），
     * 内部只走 setText(CharSequence)，不再把资源 id 交给控件方法 ——
     * 避免在 MIUI 上触发系统的主题资源加载而递归爆栈（见 README 踩坑记录）。
     */
    protected void setEmptyText(CharSequence text) {
        if (emptyTextView != null && text != null) {
            emptyTextView.setText(text);
        }
    }

    protected void updateEmptyState() {
        if (emptyView == null) return;
        emptyView.setVisibility(rows.isEmpty() && !loading ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ 长按菜单
    protected interface OnAction {
        void run(List<Long> ids) throws ApiException;
    }

    protected void confirmTrash(List<Long> ids) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getText(R.string.action_delete))
                .setMessage(getText(R.string.viewer_delete_confirm))
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_delete), (dialog, which) -> {
                    runAction(ids, new OnAction() {
                        @Override
                        public void run(List<Long> targetIds) throws ApiException {
                            api.trash(targetIds);
                        }
                    });
                })
                .show();
    }

    protected void runAction(List<Long> ids, OnAction action) {
        new Thread(() -> {
            try {
                action.run(ids);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "已完成（" + ids.size() + " 项）", Toast.LENGTH_SHORT).show();
                    refresh();
                });
            } catch (ApiException e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    protected void openViewer(List<Asset> items, int index) {
        Intent intent = new Intent(requireContext(), PhotoViewerActivity.class);
        intent.putExtra(PhotoViewerActivity.EXTRA_ITEMS, new ArrayList<>(items));
        intent.putExtra(PhotoViewerActivity.EXTRA_INDEX, index);
        startActivity(intent);
    }
}
