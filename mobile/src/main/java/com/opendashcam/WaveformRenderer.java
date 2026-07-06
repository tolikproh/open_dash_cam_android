package com.opendashcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Draws a static amplitude waveform used as the video background for audio-marker mode. */
public final class WaveformRenderer {
    public static final int VIDEO_WIDTH = 1280;
    public static final int VIDEO_HEIGHT = 720;
    private static final float SILENCE_THRESHOLD = 0.03f;

    private WaveformRenderer() {
    }

    public static Bitmap createWaveformBitmap(Context context, float[] peaks) {
        int topPadding = estimateStripThickness(VIDEO_WIDTH);
        int bottomPadding = topPadding;
        int waveformTop = topPadding;
        int waveformBottom = VIDEO_HEIGHT - bottomPadding;
        int waveformHeight = Math.max(1, waveformBottom - waveformTop);

        Bitmap bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(14, 14, 16));

        float midY = waveformTop + waveformHeight / 2f;
        drawTimeGrid(canvas, waveformTop, waveformBottom, midY);

        if (peaks == null || peaks.length == 0) {
            drawCenterLine(canvas, midY);
            return bitmap;
        }

        Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        float barWidth = (float) VIDEO_WIDTH / peaks.length;
        float strokeWidth = Math.max(1.5f, barWidth * 0.55f);
        wavePaint.setStrokeWidth(strokeWidth);

        for (int i = 0; i < peaks.length; i++) {
            float amplitude = peaks[i];
            if (amplitude < SILENCE_THRESHOLD) {
                continue;
            }

            float barHalfHeight = amplitude * waveformHeight * 0.42f;
            float x = i * barWidth + barWidth / 2f;
            float top = midY - barHalfHeight;
            float bottom = midY + barHalfHeight;

            int color = amplitudeColor(amplitude);
            fillPaint.setColor(colorWithAlpha(color, 0x55));
            wavePaint.setColor(color);

            float halfBar = Math.max(strokeWidth * 0.45f, barWidth * 0.38f);
            canvas.drawRoundRect(
                    x - halfBar,
                    top,
                    x + halfBar,
                    bottom,
                    halfBar,
                    halfBar,
                    fillPaint
            );
            canvas.drawLine(x, top, x, bottom, wavePaint);
        }

        drawCenterLine(canvas, midY);
        return bitmap;
    }

    private static void drawTimeGrid(Canvas canvas, int top, int bottom, float midY) {
        Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0x22FFFFFF);
        gridPaint.setStrokeWidth(1f);

        int verticalLines = 8;
        for (int i = 1; i < verticalLines; i++) {
            float x = VIDEO_WIDTH * i / (float) verticalLines;
            canvas.drawLine(x, top, x, bottom, gridPaint);
        }
        canvas.drawLine(0f, midY, VIDEO_WIDTH, midY, gridPaint);
    }

    private static void drawCenterLine(Canvas canvas, float midY) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x44FFFFFF);
        paint.setStrokeWidth(1f);
        canvas.drawLine(0f, midY, VIDEO_WIDTH, midY, paint);
    }

    /** Quiet → dim blue, loud → bright green/yellow. */
    private static int amplitudeColor(float amplitude) {
        float t = Math.max(0f, Math.min(1f, amplitude));
        int quiet = Color.parseColor("#546E7A");
        int mid = Color.parseColor("#29B6F6");
        int loud = Color.parseColor("#66BB6A");
        if (t < 0.55f) {
            return blend(quiet, mid, t / 0.55f);
        }
        return blend(mid, loud, (t - 0.55f) / 0.45f);
    }

    private static int blend(int from, int to, float ratio) {
        float t = Math.max(0f, Math.min(1f, ratio));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    private static int colorWithAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int estimateStripThickness(int stripSpan) {
        float textSize = Math.max(22f, stripSpan / 55f);
        return Math.round(textSize * 1.45f);
    }
}
