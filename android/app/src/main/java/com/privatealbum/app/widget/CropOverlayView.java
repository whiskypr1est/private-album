package com.privatealbum.app.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.content.Context;

import androidx.annotation.Nullable;

/**
 * 裁剪框：四角可拖动，可整体平移；输出归一化坐标（0..1），服务器按比例裁剪。
 */
public class CropOverlayView extends View {

    private static final float HANDLE_RADIUS = 28f;

    private final RectF crop = new RectF();
    private final RectF imageRect = new RectF();
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int activeHandle = -1;
    private float lastX, lastY;
    private static final int NONE = -1, TL = 0, TR = 1, BL = 2, BR = 3, MOVE = 4;

    public interface OnCropChanged {
        void onCropChanged(float left, float top, float right, float bottom);
    }

    private OnCropChanged listener;

    public CropOverlayView(Context context) {
        this(context, null);
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dimPaint.setColor(0x99000000);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.FILL);
        gridPaint.setColor(0x55FFFFFF);
        gridPaint.setStrokeWidth(1f);
    }

    public void setListener(OnCropChanged listener) {
        this.listener = listener;
    }

    /** 图片在屏幕上的实际显示区域（fitCenter 之后）。 */
    public void setImageRect(RectF rect) {
        imageRect.set(rect);
        crop.set(rect);
        invalidate();
        notifyChanged();
    }

    public RectF getImageRect() {
        return imageRect;
    }

    public float[] normalizedCrop() {
        if (imageRect.width() <= 0 || imageRect.height() <= 0) return null;
        float left = (crop.left - imageRect.left) / imageRect.width();
        float top = (crop.top - imageRect.top) / imageRect.height();
        float right = (crop.right - imageRect.left) / imageRect.width();
        float bottom = (crop.bottom - imageRect.top) / imageRect.height();
        return new float[]{clamp(left), clamp(top), clamp(right), clamp(bottom)};
    }

    public void reset() {
        crop.set(imageRect);
        invalidate();
        notifyChanged();
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void notifyChanged() {
        if (listener != null) {
            float[] values = normalizedCrop();
            if (values != null) {
                listener.onCropChanged(values[0], values[1], values[2], values[3]);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageRect.width() <= 0) return;

        // 四个暗色遮罩
        canvas.drawRect(0, 0, getWidth(), crop.top, dimPaint);
        canvas.drawRect(0, crop.bottom, getWidth(), getHeight(), dimPaint);
        canvas.drawRect(0, crop.top, crop.left, crop.bottom, dimPaint);
        canvas.drawRect(crop.right, crop.top, getWidth(), crop.bottom, dimPaint);

        canvas.drawRect(crop, borderPaint);
        // 三分线
        for (int i = 1; i <= 2; i++) {
            float x = crop.left + crop.width() * i / 3f;
            float y = crop.top + crop.height() * i / 3f;
            canvas.drawLine(x, crop.top, x, crop.bottom, gridPaint);
            canvas.drawLine(crop.left, y, crop.right, y, gridPaint);
        }
        // 四角把手
        float radius = HANDLE_RADIUS / 2f;
        canvas.drawCircle(crop.left, crop.top, radius, handlePaint);
        canvas.drawCircle(crop.right, crop.top, radius, handlePaint);
        canvas.drawCircle(crop.left, crop.bottom, radius, handlePaint);
        canvas.drawCircle(crop.right, crop.bottom, radius, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeHandle = pickHandle(x, y);
                lastX = x;
                lastY = y;
                return activeHandle != NONE;
            case MotionEvent.ACTION_MOVE:
                if (activeHandle == NONE) return false;
                float dx = x - lastX;
                float dy = y - lastY;
                applyDrag(dx, dy);
                lastX = x;
                lastY = y;
                invalidate();
                notifyChanged();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeHandle = NONE;
                return true;
            default:
                return false;
        }
    }

    private int pickHandle(float x, float y) {
        float threshold = HANDLE_RADIUS;
        if (near(x, y, crop.left, crop.top, threshold)) return TL;
        if (near(x, y, crop.right, crop.top, threshold)) return TR;
        if (near(x, y, crop.left, crop.bottom, threshold)) return BL;
        if (near(x, y, crop.right, crop.bottom, threshold)) return BR;
        if (crop.contains(x, y)) return MOVE;
        return NONE;
    }

    private boolean near(float x, float y, float tx, float ty, float threshold) {
        return Math.hypot(x - tx, y - ty) <= threshold;
    }

    private void applyDrag(float dx, float dy) {
        float minSize = 48f;
        switch (activeHandle) {
            case TL:
                crop.left = Math.min(crop.right - minSize, Math.max(imageRect.left, crop.left + dx));
                crop.top = Math.min(crop.bottom - minSize, Math.max(imageRect.top, crop.top + dy));
                break;
            case TR:
                crop.right = Math.max(crop.left + minSize, Math.min(imageRect.right, crop.right + dx));
                crop.top = Math.min(crop.bottom - minSize, Math.max(imageRect.top, crop.top + dy));
                break;
            case BL:
                crop.left = Math.min(crop.right - minSize, Math.max(imageRect.left, crop.left + dx));
                crop.bottom = Math.max(crop.top + minSize, Math.min(imageRect.bottom, crop.bottom + dy));
                break;
            case BR:
                crop.right = Math.max(crop.left + minSize, Math.min(imageRect.right, crop.right + dx));
                crop.bottom = Math.max(crop.top + minSize, Math.min(imageRect.bottom, crop.bottom + dy));
                break;
            case MOVE:
                float newLeft = crop.left + dx;
                float newTop = crop.top + dy;
                if (newLeft < imageRect.left) newLeft = imageRect.left;
                if (newTop < imageRect.top) newTop = imageRect.top;
                if (newLeft + crop.width() > imageRect.right) newLeft = imageRect.right - crop.width();
                if (newTop + crop.height() > imageRect.bottom) newTop = imageRect.bottom - crop.height();
                crop.offsetTo(newLeft, newTop);
                break;
            default:
                break;
        }
    }
}
