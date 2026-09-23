package com.privatealbum.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.privatealbum.app.AlbumApp;
import com.privatealbum.app.R;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.model.Responses;
import com.privatealbum.app.util.ServerConfig;
import com.privatealbum.app.util.Ui;

/** 「更多」页：存储用量、媒体库维护、回收站、账号、缓存。 */
public class MoreFragment extends BaseFragment {

    private LinearLayout container;
    private TextView statsText;

    public static MoreFragment newInstance() {
        return new MoreFragment();
    }

    @Override
    protected int layoutId() {
        return R.layout.fragment_grid;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        // 这个页面不用网格，把 RecyclerView 换成竖排按钮列表
        recyclerView.setVisibility(View.GONE);
        if (emptyView != null) emptyView.setVisibility(View.GONE);
        if (loadingView != null) loadingView.setVisibility(View.GONE);

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(96));
        scroll.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((ViewGroup) view).addView(scroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statsText = new TextView(requireContext());
        statsText.setTextColor(0xFF9AA0A6);
        statsText.setTextSize(13f);
        statsText.setLineSpacing(dp(4), 1f);
        statsText.setPadding(dp(20), dp(8), dp(20), dp(16));
        statsText.setText(getText(R.string.more_loading));
        container.addView(statsText);

        addItem("服务器与账号", R.string.settings_server,
                v -> startActivity(new Intent(requireContext(), ServerSettingsActivity.class)));
        addItem("存储用量与媒体库", R.string.settings_stats, v -> refreshStats());
        addItem("重新扫描媒体库", R.string.settings_scan, v -> runScan());
        addItem("重建全部缩略图", R.string.settings_rebuild_thumbs, v -> rebuildThumbs());
        addItem("迁移到外接硬盘", R.string.settings_migrate, v -> migrateDialog());
        addItem("回收站", R.string.settings_trash, v -> openTrash());
        addItem("每行显示张数", R.string.grid_columns, v -> chooseColumns());
        addItem("清理本地缓存", R.string.settings_cache, v -> {
            AlbumApp.clearCaches();
            Ui.toast(requireContext(), getString(R.string.cache_cleared));
        });
        addItem("自检（连线与图片加载）", R.string.more_selftest, v -> openSelfTest());
        addItem("查看上次崩溃信息", R.string.more_last_crash, v -> showLastCrash());
        addItem("退出登录", R.string.settings_logout, v -> confirmLogout());

        refreshStats();
    }

    /** 打开自检页：分段跑关键代码路径，手机上直接看结果。 */
    private void openSelfTest() {
        startActivity(new Intent(requireContext(), SelfTestActivity.class));
    }

    /** 显示上一次的崩溃堆栈（如果有）。 */
    private void showLastCrash() {
        String crash = com.privatealbum.app.util.CrashReporter.readLast(requireContext());
        String trace = com.privatealbum.app.util.Trace.readPrevious();
        if (crash == null && trace == null) {
            Ui.toast(requireContext(), "没有崩溃记录，说明没崩过 👍");
            return;
        }
        StringBuilder report = new StringBuilder();
        if (crash != null) {
            report.append("===== 崩溃堆栈 =====\n").append(crash).append('\n');
        } else {
            report.append("（没有捕获到堆栈，可能是原生层崩溃）\n\n");
        }
        if (trace != null) {
            report.append("\n===== 上次启动的步骤日志 =====\n").append(trace);
        }
        Intent intent = new Intent(requireContext(), CrashActivity.class);
        intent.putExtra(CrashActivity.EXTRA_REPORT, report.toString());
        startActivity(intent);
    }

    @Override
    protected java.util.List<Object> fetch(String cursor) {
        return new java.util.ArrayList<>();
    }

    @Override
    protected void onBindRows() {
    }

    // ------------------------------------------------------------ 列表项
    private interface Click {
        void onClick(View view);
    }

    private void addItem(String title, int subtitleRes, Click click) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setOnClickListener(v -> click.onClick(v));

        TextView titleView = new TextView(requireContext());
        titleView.setText(title);
        titleView.setTextColor(0xFFECEEF1);
        titleView.setTextSize(15f);
        row.addView(titleView);

        TextView subtitle = new TextView(requireContext());
        subtitle.setText(subtitleRes);
        subtitle.setTextColor(0xFF9AA0A6);
        subtitle.setTextSize(12f);
        subtitle.setPadding(0, dp(3), 0, 0);
        row.addView(subtitle);

        container.addView(row);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 操作
    private void refreshStats() {
        new Thread(() -> {
            try {
                Responses.Stats stats = api.stats();
                Responses.Settings settings = api.settings();
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> statsText.setText(
                        "照片 " + stats.images + " 张 · 录像 " + stats.videos + " 段 · 收藏 " + stats.favorites + "\n"
                                + "占用空间 " + Ui.size(stats.bytes) + "（媒体库）\n"
                                + "磁盘剩余 " + (stats.diskFree == null ? "未知" : Ui.size(stats.diskFree))
                                + " / 共 " + (stats.diskTotal == null ? "未知" : Ui.size(stats.diskTotal)) + "\n"
                                + "媒体目录 " + stats.mediaRoot + "\n"
                                + "待生成缩略图 " + stats.pendingThumbs + " 项 · ffmpeg "
                                + (settings.ffmpeg ? "已安装" : "未安装") + "\n"
                                + "服务端版本 " + settings.version));
            } catch (ApiException e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> statsText.setText("读取服务器状态失败：" + e.getMessage()));
            }
        }).start();
    }

    private void runScan() {
        new Thread(() -> {
            try {
                api.scanLibrary();
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    Ui.toast(requireContext(), "已开始后台扫描，稍后刷新查看");
                    refreshStats();
                });
            } catch (ApiException e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Ui.toast(requireContext(), e.getMessage()));
            }
        }).start();
    }

    private void rebuildThumbs() {
        new Thread(() -> {
            try {
                Responses.SimpleResult result = api.rebuildThumbs();
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    Ui.toast(requireContext(), "已排队重建 " + result.queued + " 项缩略图");
                    refreshStats();
                });
            } catch (ApiException e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Ui.toast(requireContext(), e.getMessage()));
            }
        }).start();
    }

    private void openTrash() {
        MediaGridFragment fragment = MediaGridFragment.newInstance(MediaGridFragment.MODE_TRASH, null);
        requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(), fragment)
                .addToBackStack("trash")
                .commit();
    }

    private void chooseColumns() {
        final int[] options = {2, 3, 4, 5, 6};
        String[] labels = {"2 张", "3 张", "4 张", "5 张", "6 张"};
        new AlertDialog.Builder(requireContext())
                .setTitle(getText(R.string.grid_columns))
                .setItems(labels, (dialog, which) -> {
                    ServerConfig.setGridColumns(requireContext(), options[which]);
                    Ui.toast(requireContext(), "已设为每行 " + options[which] + " 张");
                })
                .show();
    }

    private void migrateDialog() {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("/mnt/usb/photoalbum/media");
        input.setText("/mnt/usb");
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(requireContext())
                .setTitle(getText(R.string.settings_migrate))
                .setMessage("把媒体库复制到外接硬盘，然后把服务指向新位置。\n"
                        + "建议先在树莓派上把硬盘挂载到固定路径（例如 /mnt/usb），再填入挂载后的目录。")
                .setView(input)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton("开始迁移", (dialog, which) -> doMigrate(input.getText().toString().trim()))
                .show();
    }

    private void doMigrate(String target) {
        if (target.isEmpty()) return;
        Ui.toastLong(requireContext(), "迁移已开始，大文件较多时会比较久，请保持 App 在前台");
        new Thread(() -> {
            try {
                api.migrate(target, true);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    Ui.toast(requireContext(), "迁移完成，媒体目录已切换到 " + target);
                    refreshStats();
                });
            } catch (ApiException e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Ui.toastLong(requireContext(), "迁移失败：" + e.getMessage()));
            }
        }).start();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(requireContext())
                .setTitle(getText(R.string.settings_logout))
                .setMessage(getText(R.string.logout_confirm))
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.settings_logout), (dialog, which) -> {
                    ServerConfig.logout(requireContext());
                    AlbumApp.clearCaches();
                    startActivity(new Intent(requireContext(), LoginActivity.class));
                    requireActivity().finish();
                })
                .show();
    }
}
