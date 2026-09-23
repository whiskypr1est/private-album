package com.privatealbum.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.privatealbum.app.AlbumApp;
import com.privatealbum.app.R;
import com.privatealbum.app.model.Asset;
import com.privatealbum.app.net.AlbumApi;
import com.privatealbum.app.net.ApiException;
import com.privatealbum.app.util.ServerConfig;
import com.privatealbum.app.util.Ui;
import com.privatealbum.app.widget.CropOverlayView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 照片编辑器。设计取舍：
 *  - 旋转 / 翻转 / 裁剪 属于「几何操作」，在手机上做预览、保存时交给服务器（Pillow）落盘，
 *    这样原图质量不会被手机端的缩放破坏；
 *  - 亮度 / 对比度 / 饱和度 / 滤镜只有滑块和色阵预览，真正保存时同样由服务器处理；
 *  - 也就是说，App 只负责交互，图像处理 100% 在树莓派上完成，原图始终是原图。
 */
public class EditActivity extends AppCompatActivity {

    public static final String EXTRA_ASSET = "asset";
    public static final String EXTRA_RESULT = "result_asset";

    private Asset asset;
    private AlbumApi api;
    private ImageView preview;
    private CropOverlayView cropOverlay;
    private ProgressBar busy;
    private LinearLayout adjustPanel;
    private View filterStrip;
    private LinearLayout filterRow;
    private com.google.android.material.materialswitch.MaterialSwitch keepOriginalSwitch;

    private int pendingRotation;
    private boolean flipH;
    private boolean flipV;
    private boolean cropMode;
    private float[] cropBox;      // 归一化裁剪区域
    private float brightness = 1f;
    private float contrast = 1f;
    private float saturation = 1f;
    private String filter = null;

    private static final String[] FILTERS = {"original", "grayscale", "sepia", "warm", "cool", "vivid", "blur", "sharpen"};
    private static final String[] FILTER_LABELS = {"原图", "黑白", "复古", "暖色", "冷色", "鲜艳", "柔化", "锐化"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        api = new AlbumApi(this);

        asset = (Asset) getIntent().getSerializableExtra(EXTRA_ASSET);
        if (asset == null) {
            finish();
            return;
        }

        preview = findViewById(R.id.preview);
        cropOverlay = findViewById(R.id.cropOverlay);
        busy = findViewById(R.id.busy);
        adjustPanel = findViewById(R.id.adjustPanel);
        filterStrip = findViewById(R.id.filterStrip);
        filterRow = findViewById(R.id.filterRow);
        keepOriginalSwitch = findViewById(R.id.switchKeepOriginal);
        keepOriginalSwitch.setChecked(ServerConfig.keepOriginal(this));

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        com.privatealbum.app.util.SystemBars.padTop(toolbar);

        setupAdjustSliders();
        buildFilterStrip();
        loadPreview();

        findViewById(R.id.btnRotateLeft).setOnClickListener(v -> rotate(-90));
        findViewById(R.id.btnRotateRight).setOnClickListener(v -> rotate(90));
        findViewById(R.id.btnFlipH).setOnClickListener(v -> {
            flipH = !flipH;
            renderPreview();
        });
        findViewById(R.id.btnFlipV).setOnClickListener(v -> {
            flipV = !flipV;
            renderPreview();
        });
        findViewById(R.id.btnCrop).setOnClickListener(v -> toggleCrop());
        findViewById(R.id.btnAdjust).setOnClickListener(v -> {
            boolean show = adjustPanel.getVisibility() != View.VISIBLE;
            adjustPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) filterStrip.setVisibility(View.GONE);
        });
        findViewById(R.id.btnFilter).setOnClickListener(v -> {
            boolean show = filterStrip.getVisibility() != View.VISIBLE;
            filterStrip.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) adjustPanel.setVisibility(View.GONE);
        });
        findViewById(R.id.btnReset).setOnClickListener(v -> {
            pendingRotation = 0;
            flipH = false;
            flipV = false;
            brightness = contrast = saturation = 1f;
            filter = null;
            cropBox = null;
            cropMode = false;
            cropOverlay.setVisibility(View.GONE);
            setSliders(1f, 1f, 1f);
            renderPreview();
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
    }

    private void loadPreview() {
        busy.setVisibility(View.VISIBLE);
        Glide.with(this)
                .asBitmap()
                .load(api.previewUrl(asset))
                .apply(AlbumApp.previewOptions())
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        busy.setVisibility(View.GONE);
                        preview.setImageBitmap(resource);
                        previewContainerReady();
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        busy.setVisibility(View.GONE);
                        Ui.toast(EditActivity.this, "预览加载失败，请检查网络");
                    }
                });
    }

    /** 计算图片在预览框里的实际显示矩形，给裁剪框用。 */
    private void previewContainerReady() {
        preview.post(() -> {
            Drawable drawable = preview.getDrawable();
            if (drawable == null || preview.getWidth() == 0) return;
            float viewWidth = preview.getWidth();
            float viewHeight = preview.getHeight();
            float drawableWidth = drawable.getIntrinsicWidth();
            float drawableHeight = drawable.getIntrinsicHeight();
            float scale = Math.min(viewWidth / drawableWidth, viewHeight / drawableHeight);
            float displayWidth = drawableWidth * scale;
            float displayHeight = drawableHeight * scale;
            float left = (viewWidth - displayWidth) / 2f;
            float top = (viewHeight - displayHeight) / 2f;
            cropOverlay.setImageRect(new RectF(left, top, left + displayWidth, top + displayHeight));
        });
    }

    private void setupAdjustSliders() {
        SeekBar brightnessBar = findViewById(R.id.seekBrightness);
        SeekBar contrastBar = findViewById(R.id.seekContrast);
        SeekBar saturationBar = findViewById(R.id.seekSaturation);
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                brightness = brightnessBar.getProgress() / 50f;
                contrast = contrastBar.getProgress() / 50f;
                saturation = saturationBar.getProgress() / 50f;
                renderPreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        brightnessBar.setOnSeekBarChangeListener(listener);
        contrastBar.setOnSeekBarChangeListener(listener);
        saturationBar.setOnSeekBarChangeListener(listener);
        setSliders(1f, 1f, 1f);
    }

    private void setSliders(float b, float c, float s) {
        SeekBar brightnessBar = findViewById(R.id.seekBrightness);
        SeekBar contrastBar = findViewById(R.id.seekContrast);
        SeekBar saturationBar = findViewById(R.id.seekSaturation);
        brightnessBar.setProgress((int) (b * 50));
        contrastBar.setProgress((int) (c * 50));
        saturationBar.setProgress((int) (s * 50));
    }

    /** 用 ColorMatrix 在本地模拟服务器端的调节效果，所见接近所得。 */
    private void renderPreview() {
        Matrix matrix = new Matrix();
        matrix.postRotate(pendingRotation);
        if (flipH) matrix.postScale(-1, 1);
        if (flipV) matrix.postScale(1, -1);
        preview.setImageMatrix(matrix);

        ColorMatrix colorMatrix = new ColorMatrix();
        float translate = (brightness - 1f) * 100f;
        colorMatrix.postConcat(new ColorMatrix(new float[]{
                1, 0, 0, 0, translate,
                0, 1, 0, 0, translate,
                0, 0, 1, 0, translate,
                0, 0, 0, 1, 0
        }));
        float c = contrast;
        float t = (1f - c) * 127.5f;
        colorMatrix.postConcat(new ColorMatrix(new float[]{
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t,
                0, 0, 0, 1, 0
        }));
        float s = saturation;
        float sr = (1f - s) * 0.213f;
        float sg = (1f - s) * 0.715f;
        float sb = (1f - s) * 0.072f;
        colorMatrix.postConcat(new ColorMatrix(new float[]{
                sr + s, sg, sb, 0, 0,
                sr, sg + s, sb, 0, 0,
                sr, sg, sb + s, 0, 0,
                0, 0, 0, 1, 0
        }));
        if ("grayscale".equals(filter)) {
            colorMatrix.setSaturation(0f);
        } else if ("vivid".equals(filter)) {
            colorMatrix.postConcat(new ColorMatrix(new float[]{
                    1.35f, 0, 0, 0, 0,
                    0, 1.35f, 0, 0, 0,
                    0, 0, 1.35f, 0, 0,
                    0, 0, 0, 1, 0
            }));
        } else if ("sepia".equals(filter)) {
            colorMatrix.postConcat(new ColorMatrix(new float[]{
                    0.393f, 0.769f, 0.189f, 0, 0,
                    0.349f, 0.686f, 0.168f, 0, 0,
                    0.272f, 0.534f, 0.131f, 0, 0,
                    0, 0, 0, 1, 0
            }));
        }
        preview.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
    }

    private void rotate(int degrees) {
        pendingRotation = ((pendingRotation + degrees) % 360 + 360) % 360;
        renderPreview();
        previewContainerReady();
    }

    private void toggleCrop() {
        cropMode = !cropMode;
        cropOverlay.setVisibility(cropMode ? View.VISIBLE : View.GONE);
        if (cropMode) {
            cropOverlay.reset();
            Ui.toast(this, "拖动四角调整裁剪范围，再点一次「裁剪」确认");
        } else {
            cropBox = cropOverlay.normalizedCrop();
            if (cropBox != null) {
                Ui.toast(this, String.format(java.util.Locale.US,
                        "裁剪区域：%.0f%% × %.0f%%", (cropBox[2] - cropBox[0]) * 100,
                        (cropBox[3] - cropBox[1]) * 100));
            }
        }
    }

    private void buildFilterStrip() {
        filterRow.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < FILTERS.length; i++) {
            final String name = FILTERS[i];
            View item = inflater.inflate(R.layout.item_filter, filterRow, false);
            ImageView swatch = item.findViewById(R.id.swatch);
            TextView label = item.findViewById(R.id.label);
            label.setText(FILTER_LABELS[i]);
            Glide.with(this)
                    .load(api.thumbUrl(asset))
                    .apply(AlbumApp.thumbOptions())
                    .into(swatch);
            applySwatchFilter(swatch, name);
            item.setOnClickListener(v -> {
                filter = "original".equals(name) ? null : name;
                renderPreview();
                Ui.toast(this, "已选择滤镜：" + label.getText());
            });
            filterRow.addView(item);
        }
    }

    private void applySwatchFilter(ImageView view, String name) {
        ColorMatrix matrix = new ColorMatrix();
        switch (name) {
            case "grayscale":
                matrix.setSaturation(0f);
                break;
            case "sepia":
                matrix.set(new float[]{
                        0.393f, 0.769f, 0.189f, 0, 0,
                        0.349f, 0.686f, 0.168f, 0, 0,
                        0.272f, 0.534f, 0.131f, 0, 0,
                        0, 0, 0, 1, 0
                });
                break;
            case "warm":
                matrix.set(new float[]{
                        1.08f, 0, 0, 0, 8,
                        0, 1.0f, 0, 0, 0,
                        0, 0, 0.94f, 0, -6,
                        0, 0, 0, 1, 0
                });
                break;
            case "cool":
                matrix.set(new float[]{
                        0.94f, 0, 0, 0, -6,
                        0, 1.0f, 0, 0, 0,
                        0, 0, 1.08f, 0, 8,
                        0, 0, 0, 1, 0
                });
                break;
            case "vivid":
                matrix.setSaturation(1.35f);
                break;
            case "blur":
            case "sharpen":
            default:
                break;
        }
        view.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    // ------------------------------------------------------------ 保存
    private void save() {
        boolean keep = keepOriginalSwitch.isChecked();
        ServerConfig.setKeepOriginal(this, keep);
        BusyGuard(true);
        Map<String, Object> body = new HashMap<>();
        body.put("rotate", pendingRotation);
        body.put("flip_h", flipH);
        body.put("flip_v", flipV);
        body.put("brightness", brightness);
        body.put("contrast", contrast);
        body.put("saturation", saturation);
        if (filter != null) body.put("filter", filter);
        if (cropBox != null) {
            List<Double> box = new ArrayList<>();
            for (float value : cropBox) box.add((double) value);
            body.put("crop", box);
        }
        body.put("keep_original", keep);

        new Thread(() -> {
            try {
                com.privatealbum.app.model.Responses.AssetResult result = api.edit(asset.id, body);
                runOnUiThread(() -> {
                    BusyGuard(false);
                    Ui.toast(this, "已保存到树莓派");
                    Intent data = new Intent();
                    data.putExtra(EXTRA_RESULT, result.asset);
                    setResult(RESULT_OK, data);
                    finish();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BusyGuard(false);
                    Ui.toastLong(this, "保存失败：" + e.getMessage());
                });
            }
        }).start();
    }

    private void BusyGuard(boolean isBusy) {
        busy.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnSave).setEnabled(!isBusy);
        findViewById(R.id.btnReset).setEnabled(!isBusy);
    }
}
