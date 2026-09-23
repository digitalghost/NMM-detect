package com.digitalghost.nmmprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Direct-manipulation original/NMM wipe comparison. */
final class ComparisonView extends View {
    interface OnComparisonChangedListener {
        void onComparisonChanged(float value);
    }

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint uiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private Bitmap original;
    private Bitmap nmm;
    private float comparison = 0.5f;
    private OnComparisonChangedListener comparisonListener;

    ComparisonView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setBitmaps(Bitmap original, Bitmap nmm) {
        this.original = original;
        this.nmm = nmm;
        invalidate();
    }

    void setComparison(float value) {
        comparison = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    void setOnComparisonChangedListener(OnComparisonChangedListener listener) {
        comparisonListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff11151d);
        if (original == null) {
            uiPaint.setColor(0xff626b7c);
            uiPaint.setTextSize(sp(15));
            uiPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("选择一张微缩模型照片", getWidth() / 2f, getHeight() / 2f, uiPaint);
            return;
        }
        calculateDestination(original);
        if (nmm == null) {
            canvas.drawBitmap(original, null, destination, imagePaint);
            return;
        }
        canvas.drawBitmap(nmm, null, destination, imagePaint);
        float divider = destination.left + destination.width() * comparison;
        canvas.save();
        canvas.clipRect(destination.left, destination.top, divider, destination.bottom);
        canvas.drawBitmap(original, null, destination, imagePaint);
        canvas.restore();

        uiPaint.setColor(0xffd9ff52);
        uiPaint.setStrokeWidth(dp(2));
        canvas.drawLine(divider, destination.top, divider, destination.bottom, uiPaint);
        canvas.drawCircle(divider, destination.centerY(), dp(15), uiPaint);
        uiPaint.setColor(0xff11151d);
        uiPaint.setStrokeWidth(dp(2));
        canvas.drawLine(divider - dp(5), destination.centerY(), divider + dp(5), destination.centerY(), uiPaint);

        uiPaint.setTextSize(sp(11));
        uiPaint.setTextAlign(Paint.Align.LEFT);
        uiPaint.setColor(0xccffffff);
        canvas.drawText("原图", destination.left + dp(10), destination.top + dp(22), uiPaint);
        uiPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("NMM 光影", destination.right - dp(10), destination.top + dp(22), uiPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (nmm == null || destination.width() <= 0) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN
                || event.getAction() == MotionEvent.ACTION_MOVE
                || event.getAction() == MotionEvent.ACTION_UP) {
            setComparison((event.getX() - destination.left) / destination.width());
            if (comparisonListener != null) comparisonListener.onComparisonChanged(comparison);
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void calculateDestination(Bitmap bitmap) {
        float scale = Math.min(getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (getWidth() - width) * 0.5f;
        float top = (getHeight() - height) * 0.5f;
        destination.set(left, top, left + width, top + height);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
