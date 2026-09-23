package com.privatealbum.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;
import com.privatealbum.app.R;
import com.privatealbum.app.util.Trace;

/**
 * 页签切换复现页。
 *
 * 存在的原因：「点击相册 / 收藏 / 更多都会闪退」，但单独看每个 Fragment 的代码都没问题，
 * 而「更多」页根本不用 RecyclerView，所以病根一定是**四个页签共用的那条路径**
 * （Activity 初始化 + Fragment 事务 + 页签切换）。
 *
 * 这个页面把 MainActivity.onCreate 逐字复刻一遍（同样的布局、同样的四个 Fragment、
 * 同样的页签监听、同样的切换顺序），然后在 onResume 之后**自动依次切一遍**，
 * 每一步都记日志、每步之后强制走一次 measure/layout。
 * 哪一步炸了，日志里最后一行就是凶手；完整的重复调用链由 Guard 写出来。
 */
public class ReproduceActivity extends AppCompatActivity {

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Trace.log("ReproduceActivity.onCreate 开始");
        setContentView(R.layout.activity_main);
        com.privatealbum.app.util.SystemBars.padTop(findViewById(R.id.appBar));
        com.privatealbum.app.util.SystemBars.marginBottom(findViewById(R.id.fab));
        Trace.log("ReproduceActivity 布局就绪");

        toolbar = findViewById(R.id.toolbar);
        tabs = findViewById(R.id.tabs);
        fab = findViewById(R.id.fab);
        uploadBar = findViewById(R.id.uploadBar);
        uploadText = findViewById(R.id.uploadText);
        uploadProgress = findViewById(R.id.uploadProgress);
        setSupportActionBar(toolbar);

        albumsFragment = AlbumsFragment.newInstance();
        favoritesFragment = MediaGridFragment.newInstance(MediaGridFragment.MODE_FAVORITES, null);
        moreFragment = MoreFragment.newInstance();

        // 和 MainActivity 保持一致：相册 / 收藏 / 更多
        tabs.addTab(tabs.newTab().setText(getText(R.string.tab_albums)));
        tabs.addTab(tabs.newTab().setText(getText(R.string.tab_favorites)));
        tabs.addTab(tabs.newTab().setText(getText(R.string.tab_more)));
        Trace.log("ReproduceActivity 页签添加完成（共 " + tabs.getTabCount() + " 个）");

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                Trace.log("ReproduceActivity 页签选中 -> " + tab.getPosition());
                exitSelectionSilently();
                switchTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        switchTab(0);
        Trace.log("ReproduceActivity.onCreate 结束（首屏已装载）");
    }

    /** 复刻 MainActivity.exitSelection()，但去掉 adapter 相关调用，单独验证 toolbar.setTitle 这条路径。 */
    private void exitSelectionSilently() {
        Trace.log("ReproduceActivity.exitSelection 开始");
        try {
            toolbar.setTitle(getText(R.string.app_name));
            Trace.log("toolbar.setTitle(已经解析好的字符串) 成功");
        } catch (Throwable t) {
            Trace.log("toolbar.setTitle 失败: " + t);
        }
        try {
            invalidateOptionsMenu();
            Trace.log("invalidateOptionsMenu 成功");
        } catch (Throwable t) {
            Trace.log("invalidateOptionsMenu 失败: " + t);
        }
        Trace.log("ReproduceActivity.exitSelection 结束");
    }

    private void switchTab(int position) {
        Trace.log("ReproduceActivity.switchTab -> " + position);
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
        fab.setVisibility(position == 3 ? View.GONE : View.VISIBLE);
        Trace.log("ReproduceActivity.switchTab 完成 -> " + target.getClass().getSimpleName());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Trace.log("ReproduceActivity.onResume，准备自动跑一遍页签切换");
        // 等首屏真正 layout 完成再开始，避免「还没画完就切」这种不真实的情况
        tabs.postDelayed(this::runAllTabs, 1500);
    }

    /** 依次切一遍四个页签，每一步都强制 layout，并记录结果。 */
    private void runAllTabs() {
        StringBuilder report = new StringBuilder();
        Trace.log("===== 开始自动复现：依次切换四个页签 =====");
        int[] order = {1, 2, 0};
        String[] names = {"收藏", "更多", "相册"};
        for (int i = 0; i < order.length; i++) {
            final int position = order[i];
            String name = names[i];
            try {
                Trace.log("---- 切到「" + name + "」----");
                tabs.selectTab(tabs.getTabAt(position));
                getSupportFragmentManager().executePendingTransactions();
                forceFullLayout();
                report.append("✅ 切到「").append(name).append("」正常\n");
                Trace.log("✅ 切到「" + name + "」正常");
            } catch (Throwable t) {
                report.append("❌ 切到「").append(name).append("」失败：").append(t).append('\n');
                Trace.log("❌ 切到「" + name + "」失败：" + t
                        + "\n完整堆栈：\n" + stack(t));
                break;
            }
        }
        showResult(report.toString());
    }

    /** 强制整个内容区走一遍 measure + layout。 */
    private void forceFullLayout() {
        View content = findViewById(R.id.content);
        if (content == null) return;
        int width = Math.max(1, content.getWidth());
        int height = Math.max(1, content.getHeight());
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        content.measure(widthSpec, heightSpec);
        content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
    }

    private static String stack(Throwable t) {
        java.io.StringWriter writer = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    private void showResult(String text) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF14161A);

        TextView title = new TextView(this);
        title.setText("页签切换复现结果");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18f);
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(text
                + "\n\n详细日志见「更多 → 查看上次崩溃信息」，"
                + "或文件 Android/data/com.privatealbum.app/files/trace.log");
        body.setTextColor(0xFFCCCCCC);
        body.setTextSize(12f);
        body.setTextIsSelectable(true);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }
}
