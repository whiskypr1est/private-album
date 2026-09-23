package com.privatealbum.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.privatealbum.app.AlbumApp;
import com.privatealbum.app.R;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.util.Ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 网格适配器。rows 里放：
 *  - Header（日期分组标题）
 *  - Asset（一张照片 / 一段视频）
 *  - LoadMore（底部加载指示）
 *
 * 长按进入多选模式，多选状态保存在 selected 里，跨页面刷新不会丢。
 */
public class MediaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_ASSET = 1;
    public static final int TYPE_FOOTER = 2;

    public static final class Header {
        public final String title;
        public final int count;

        public Header(String title, int count) {
            this.title = title;
            this.count = count;
        }
    }

    public static final class LoadMore {
    }

    public interface Listener {
        void onAssetClick(Asset asset, int position);

        void onAssetLongClick(Asset asset, int position);

        void onSelectionChanged(int count);

        void onLoadMore();
    }

    private final AlbumApi api;
    private final Listener listener;
    private final List<Object> rows = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private boolean selectionMode = false;
    /** 列表显示方式：每行一条，带文件名和信息（网格模式为 false）。 */
    private boolean listMode = false;

    public void setListMode(boolean list) {
        if (this.listMode == list) return;
        this.listMode = list;
        notifyDataSetChanged();
    }

    public boolean isListMode() {
        return listMode;
    }

    /**
     * 回调重入锁。
     *
     * 【踩坑记录 · 真凶就在这里】
     * 之前 clearSelection() 会回调 listener.onSelectionChanged(0)，
     * 而 MainActivity.updateSelectionCount(0) 里又调用了 exitSelection()，
     * exitSelection() 反过来又调 clearSelection()：
     *
     *   clearSelection → onSelectionChanged → updateSelectionCount(0)
     *     → exitSelection → clearSelection → …… 无限循环 → StackOverflowError
     *
     * 崩溃堆栈顶部看起来是 RecyclerView 内部
     * （notifyDataSetChanged → processDataSetCompletelyChanged → markKnownViewsInvalid），
     * 所以我一开始一直在 RecyclerView 的回收机制里找递归，方向完全错了。
     * 真正的原因是「适配器和 Activity 互相回调」，RecyclerView 只是最先被撑爆的那一层。
     *
     * 现在统一用这个锁把回调做成「不可重入」：任何回调里再触发回调都会被忽略。
     */
    private boolean notifyingListener = false;

    public MediaAdapter(Context context, Listener listener) {
        this.api = new AlbumApi(context);
        this.listener = listener;
        setHasStableIds(false);
    }

    /** 重新提交整份数据。 */
    public void submit(List<Object> newRows) {
        com.privatealbum.app.util.Guard.enter("MediaAdapter.submit");
        com.privatealbum.app.util.Guard.enter("MediaAdapter.notifyDataSetChanged");
        try {
            rows.clear();
            rows.addAll(newRows);
            // 已被删除的项要从选中集合里清掉
            Set<Long> alive = new HashSet<>();
            for (Object row : rows) {
                if (row instanceof Asset) alive.add(((Asset) row).id);
            }
            selected.retainAll(alive);
            if (selected.isEmpty()) selectionMode = false;
            notifyDataSetChanged();
        } finally {
            com.privatealbum.app.util.Guard.exit();
            com.privatealbum.app.util.Guard.exit();
        }
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public List<Long> selectedIds() {
        return new ArrayList<>(selected);
    }

    public int selectedCount() {
        return selected.size();
    }

    /**
     * 退出多选。
     *
     * 两点关键修改：
     *  1) 没有选中项时直接返回 —— 不会有任何状态变化，也就不该回调通知任何人；
     *  2) 回调走 notifySelectionChanged()，它带重入锁，杜绝「适配器 ↔ Activity」互相回调成环。
     */
    public void clearSelection() {
        if (selected.isEmpty() && !selectionMode) {
            return;
        }
        selected.clear();
        selectionMode = false;
        notifyDataSetChanged();
        notifySelectionChanged(0);
    }

    public void selectAll() {
        for (Object row : rows) {
            if (row instanceof Asset) selected.add(((Asset) row).id);
        }
        selectionMode = !selected.isEmpty();
        notifyDataSetChanged();
        notifySelectionChanged(selected.size());
    }

    /** 唯一对外通知选中数变化的出口，带重入保护。 */
    private void notifySelectionChanged(int count) {
        if (notifyingListener) {
            com.privatealbum.app.util.Trace.log("MediaAdapter.notifySelectionChanged(" + count
                    + ") 被重入，已忽略（防止回调成环）");
            return;
        }
        notifyingListener = true;
        try {
            listener.onSelectionChanged(count);
        } finally {
            notifyingListener = false;
        }
    }

    public List<Asset> assets() {
        List<Asset> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Asset) out.add((Asset) row);
        }
        return out;
    }

    /** 用服务器返回的最新对象替换本地对象（编辑 / 收藏后调用）。 */
    public void updateAsset(Asset updated) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof Asset && ((Asset) row).id == updated.id) {
                rows.set(i, updated);
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        Object row = rows.get(position);
        if (row instanceof Header) return TYPE_HEADER;
        if (row instanceof LoadMore) return TYPE_FOOTER;
        return TYPE_ASSET;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        com.privatealbum.app.util.Trace.log("MediaAdapter.onCreateViewHolder viewType=" + viewType);
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_header, parent, false));
        }
        if (viewType == TYPE_FOOTER) {
            View view = new android.widget.ProgressBar(parent.getContext());
            int size = (int) (36 * parent.getResources().getDisplayMetrics().density);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, size);
            params.topMargin = size / 3;
            view.setLayoutParams(params);
            return new RecyclerView.ViewHolder(view) {
            };
        }
        // 列表模式换成「一行一条」的布局；两个布局的 id 完全一致，
        // 所以下面的 AssetHolder 不用改就能通用
        return new AssetHolder(inflater.inflate(
                listMode ? R.layout.item_media_list : R.layout.item_media, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof HeaderHolder && row instanceof Header) {
            Header header = (Header) row;
            ((HeaderHolder) holder).text.setText(
                    header.count > 0 ? header.title + "  ·  " + header.count : header.title);
        } else if (holder instanceof AssetHolder && row instanceof Asset) {
            try {
                bindAsset((AssetHolder) holder, (Asset) row, position);
            } catch (Throwable t) {
                com.privatealbum.app.util.Trace.log("MediaAdapter.bindAsset 失败 position=" + position
                        + " asset=" + ((Asset) row).fileName + " : " + t);
                throw t;
            }
        }
    }

    private void bindAsset(AssetHolder holder, Asset asset, int position) {
        Context context = holder.itemView.getContext();

        Glide.with(context)
                .load(api.thumbUrl(asset))
                .apply(AlbumApp.thumbOptions())
                .into(holder.thumb);

        holder.videoBadge.setVisibility(asset.isVideo() ? View.VISIBLE : View.GONE);
        holder.duration.setText(asset.durationText());
        holder.favBadge.setVisibility(asset.favorite ? View.VISIBLE : View.GONE);

        // 列表模式才有文件名与信息行（网格布局里这两个控件是 null）
        if (holder.name != null) {
            holder.name.setText(asset.fileName == null ? "" : asset.fileName);
        }
        if (holder.info != null) {
            holder.info.setText(listInfo(asset));
        }

        boolean isSelected = selected.contains(asset.id);
        holder.overlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        holder.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.check.setImageResource(isSelected ? R.drawable.ic_check : R.drawable.ic_photo);
        holder.check.setAlpha(isSelected ? 1f : 0.35f);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggle(asset);
            } else {
                listener.onAssetClick(asset, position);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            listener.onAssetLongClick(asset, position);
            return true;
        });
    }

    /** 列表模式下第二行的小字：拍摄时间 · 分辨率 · 大小。 */
    private String listInfo(Asset asset) {
        StringBuilder sb = new StringBuilder();
        try {
            String day = Ui.dayHeader(asset.takenAt);
            if (day != null && !day.isEmpty()) sb.append(day);
        } catch (Throwable ignored) {
            // 时间解析失败就不显示时间，不影响其它信息
        }
        String res = asset.resolutionText();
        if (res != null && !res.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(res);
        }
        if (asset.sizeText != null && !asset.sizeText.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(asset.sizeText);
        }
        return sb.toString();
    }

    private void toggle(Asset asset) {
        if (selected.contains(asset.id)) {
            selected.remove(asset.id);
        } else {
            selected.add(asset.id);
        }
        selectionMode = !selected.isEmpty();
        notifyItemChanged(indexOf(asset.id));
        notifySelectionChanged(selected.size());
    }

    /** 长按进入多选并选中该项。 */
    public void enterSelection(Asset asset) {
        selectionMode = true;
        selected.add(asset.id);
        notifyDataSetChanged();
        notifySelectionChanged(selected.size());
    }

    private int indexOf(long assetId) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof Asset && ((Asset) row).id == assetId) return i;
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.headerText);
        }
    }

    static class AssetHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final View videoBadge;
        final TextView duration;
        final ImageView favBadge;
        final View overlay;
        final ImageView check;
        /** 只有列表布局才有，网格布局里是 null */
        final TextView name;
        final TextView info;

        AssetHolder(View itemView) {
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

    /** 把服务器返回的分页资源转成带日期分组标题的展示行。 */
    public static List<Object> groupByDay(List<Asset> assets, boolean withHeaders) {
        List<Object> out = new ArrayList<>();
        String lastDay = null;
        for (Asset asset : assets) {
            if (withHeaders) {
                String day = Ui.dayKey(asset.takenAt);
                if (!day.equals(lastDay)) {
                    out.add(new Header(Ui.dayHeader(asset.takenAt), 0));
                    lastDay = day;
                }
            }
            out.add(asset);
        }
        return out;
    }
}
