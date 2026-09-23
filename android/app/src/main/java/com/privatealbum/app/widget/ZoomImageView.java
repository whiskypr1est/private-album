package com.privatealbum.app.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * 支持双指缩放 / 拖动 / 双击放大的 ImageView（自己实现，不引第三方库）。
 * 同时把「是否已放大」告诉父容器，方便查看器决定要不要拦截滑动翻页。
 */
public class ZoomImageView extends AppCompatImageView {

    private static final float MAX_SCALE = 6f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final PointF lastTouch = new PointF();
    private final RectF displayRect = new RectF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private float baseScale = 1f;
    private float currentScale = 1f;
    private float minScale = 1f;

    private int lastAction = MotionEvent.ACTION_UP;
    private float downX, downY;

    private OnTapListener tapListener;
    private OnZoomChangedListener zoomChangedListener;

    public interface OnTapListener {
        void onSingleTap();

        void onDoubleTap(float x, float y);
    }

    public interface OnZoomChangedListener {
        /** true = 图片已放大到超出屏幕，父容器不应再处理横向滑动。 */
        void onZoomChanged(boolean zoomedIn);
    }

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);
        initDetectors(context);
    }

    private void initDetectors(Context context) {
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (getDrawable() == null) return false;
                float factor = detector.getScaleFactor();
                float target = currentScale * factor;
                target = Math.max(minScale, Math.min(MAX_SCALE, target));
                float applied = target / currentScale;
                currentScale = target;
                matrix.postScale(applied, applied, detector.getFocusX(), detector.getFocusY());
                fixTranslation();
                setImageMatrix(matrix);
                notifyZoom();
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null) {
                    tapListener.onSingleTap();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (currentScale > minScale * 1.05f) {
                    resetZoom(true);
                } else {
                    zoomTo(DOUBLE_TAP_SCALE, e.getX(), e.getY());
                }
                if (tapListener != null) {
                    tapListener.onDoubleTap(e.getX(), e.getY());
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                return false; // 交给 onTouchEvent 统一处理
            }
        });
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetMatrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetMatrix();
    }

    /** 按 fitCenter 计算初始矩阵。 */
    public void resetMatrix() {
        Drawable drawable = getDrawable();
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (drawable == null || width <= 0 || height <= 0) return;

        float drawableWidth = drawable.getIntrinsicWidth();
        float drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) return;

        float scale = Math.min(width / drawableWidth, height / drawableHeight);
        baseScale = scale;
        minScale = scale;
        currentScale = scale;

        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((width - drawableWidth * scale) / 2f + getPaddingLeft(),
                (height - drawableHeight * scale) / 2f + getPaddingTop());
        setImageMatrix(matrix);
        notifyZoom();
    }

    private void zoomTo(float targetScale, float focusX, float focusY) {
        float target = Math.max(minScale, Math.min(MAX_SCALE, targetScale));
        float factor = target / currentScale;
        currentScale = target;
        matrix.postScale(factor, factor, focusX, focusY);
        fixTranslation();
        setImageMatrix(matrix);
        notifyZoom();
    }

    /** 缩放后把图片拉回可视区域，避免拖出屏幕外空白。 */
    private void fixTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        matrix.getValues(values);
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float scaleX = values[Matrix.MSCALE_X];
        float scaleY = values[Matrix.MSCALE_Y];

        float viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float drawableWidth = drawable.getIntrinsicWidth() * scaleX;
        float drawableHeight = drawable.getIntrinsicHeight() * scaleY;

        float deltaX;
        if (drawableWidth <= viewWidth) {
            deltaX = (viewWidth - drawableWidth) / 2f + getPaddingLeft() - transX;
        } else {
            deltaX = Math.min(getPaddingLeft() - transX, 0);
            if (transX + drawableWidth < viewWidth + getPaddingLeft()) {
                deltaX = viewWidth + getPaddingLeft() - transX - drawableWidth;
            }
        }

        float deltaY;
        if (drawableHeight <= viewHeight) {
            deltaY = (viewHeight - drawableHeight) / 2f + getPaddingTop() - transY;
        } else {
            deltaY = Math.min(getPaddingTop() - transY, 0);
            if (transY + drawableHeight < viewHeight + getPaddingTop()) {
                deltaY = viewHeight + getPaddingTop() - transY - drawableHeight;
            }
        }
        matrix.postTranslate(deltaX, deltaY);
    }

    private void notifyZoom() {
        if (zoomChangedListener != null) {
            zoomChangedListener.onZoomChanged(currentScale > minScale * 1.02f);
        }
    }

    public boolean isZoomedIn() {
        return currentScale > minScale * 1.02f;
    }

    public void resetZoom(boolean animate) {
        if (!animate) {
            resetMatrix();
            return;
        }
        matrix.getValues(values);
        final float startScale = currentScale;
        final float startX = values[Matrix.MTRANS_X];
        final float startY = values[Matrix.MTRANS_Y];
        matrix.reset();
        matrix.postScale(minScale, minScale);
        Drawable drawable = getDrawable();
        float targetX = 0, targetY = 0;
        if (drawable != null) {
            targetX = (getWidth() - drawable.getIntrinsicWidth() * minScale) / 2f;
            targetY = (getHeight() - drawable.getIntrinsicHeight() * minScale) / 2f;
        }
        final float endX = targetX, endY = targetY;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(180);
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            float scale = startScale + (minScale - startScale) * t;
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(startX + (endX - startX) * t, startY + (endY - startY) * t);
            setImageMatrix(matrix);
            currentScale = scale;
        });
        animator.start();
    }

    public void setTapListener(OnTapListener listener) {
        this.tapListener = listener;
    }

    public void setZoomChangedListener(OnZoomChangedListener listener) {
        this.zoomChangedListener = listener;
    }

    public RectF getDisplayRect() {
        matrix.getValues(values);
        Drawable drawable = getDrawable();
        if (drawable != null) {
            displayRect.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            matrix.mapRect(displayRect);
        }
        return displayRect;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) {
            return super.onTouchEvent(event);
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouch.set(event.getX(), event.getY());
                downX = event.getX();
                downY = event.getY();
                lastAction = MotionEvent.ACTION_DOWN;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(isZoomedIn());
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && isZoomedIn()) {
                    float dx = event.getX() - lastTouch.x;
                    float dy = event.getY() - lastTouch.y;
                    matrix.postTranslate(dx, dy);
                    fixTranslation();
                    setImageMatrix(matrix);
                    lastTouch.set(event.getX(), event.getY());
                    lastAction = MotionEvent.ACTION_MOVE;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastAction = MotionEvent.ACTION_UP;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                break;
            default:
                break;
        }
        return true;
    }
}
