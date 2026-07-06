package com.opendashcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Draws watermark strips along the long edge of a video frame. */
public final class WatermarkRenderer {
    private static final SimpleDateFormat DATE_TIME_FORMAT =
            new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", new Locale("ru", "RU"));

    private WatermarkRenderer() {
    }

    public static Bitmap createMetaStripBitmap(
            Context context,
            int stripSpan,
            long timestampMs,
            RecordingLocationTracker locationTracker
    ) {
        return createMetaStripBitmap(
                context,
                stripSpan,
                timestampMs,
                locationTracker != null
                        ? locationTracker.getCoordinatesLine()
                        : context.getString(R.string.watermark_gps_unavailable)
        );
    }

    public static Bitmap createMetaStripBitmap(
            Context context,
            int stripSpan,
            long timestampMs,
            String gpsLine
    ) {
        StripLayout layout = createStripLayout(stripSpan);
        int thickness = Math.round(layout.stripHeight);
        Bitmap bitmap = Bitmap.createBitmap(stripSpan, thickness, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawRect(0f, 0f, stripSpan, thickness, layout.backgroundPaint);

        String timeText = DATE_TIME_FORMAT.format(new Date(timestampMs));
        String gpsText = gpsLine != null ? gpsLine : "";
        canvas.drawText(timeText, layout.horizontalPadding, layout.textBaseline, layout.textPaint);

        float gpsWidth = layout.textPaint.measureText(gpsText);
        canvas.drawText(
                gpsText,
                stripSpan - layout.horizontalPadding - gpsWidth,
                layout.textBaseline,
                layout.textPaint
        );
        return bitmap;
    }

    public static Bitmap createAddressStripBitmap(Context context, int stripSpan, String address) {
        if (TextUtils.isEmpty(address)) {
            return null;
        }

        StripLayout layout = createStripLayout(stripSpan);
        int thickness = Math.round(layout.stripHeight);
        Bitmap bitmap = Bitmap.createBitmap(stripSpan, thickness, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawRect(0f, 0f, stripSpan, thickness, layout.backgroundPaint);
        canvas.drawText(
                ellipsize(layout.textPaint, address, stripSpan - layout.horizontalPadding * 2f),
                layout.horizontalPadding,
                layout.textBaseline,
                layout.textPaint
        );
        return bitmap;
    }

    public static Bitmap createVerticalMetaStripBitmap(
            Context context,
            int stripSpan,
            long timestampMs,
            RecordingLocationTracker locationTracker
    ) {
        return createVerticalMetaStripBitmap(
                context,
                stripSpan,
                timestampMs,
                locationTracker != null
                        ? locationTracker.getCoordinatesLine()
                        : context.getString(R.string.watermark_gps_unavailable)
        );
    }

    public static Bitmap createVerticalMetaStripBitmap(
            Context context,
            int stripSpan,
            long timestampMs,
            String gpsLine
    ) {
        StripLayout layout = createStripLayout(stripSpan);
        int thickness = Math.round(layout.stripHeight);
        Bitmap bitmap = Bitmap.createBitmap(thickness, stripSpan, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(thickness, 0f);
        canvas.rotate(90f);
        drawMetaStripContents(canvas, layout, stripSpan, timestampMs, gpsLine);
        return bitmap;
    }

    public static Bitmap createVerticalAddressStripBitmap(Context context, int stripSpan, String address) {
        if (TextUtils.isEmpty(address)) {
            return null;
        }

        StripLayout layout = createStripLayout(stripSpan);
        int thickness = Math.round(layout.stripHeight);
        Bitmap bitmap = Bitmap.createBitmap(thickness, stripSpan, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(thickness, 0f);
        canvas.rotate(90f);
        canvas.drawRect(0f, 0f, stripSpan, thickness, layout.backgroundPaint);
        canvas.drawText(
                ellipsize(layout.textPaint, address, stripSpan - layout.horizontalPadding * 2f),
                layout.horizontalPadding,
                layout.textBaseline,
                layout.textPaint
        );
        return bitmap;
    }

    private static void drawMetaStripContents(
            Canvas canvas,
            StripLayout layout,
            int stripSpan,
            long timestampMs,
            RecordingLocationTracker locationTracker
    ) {
        drawMetaStripContents(
                canvas,
                layout,
                stripSpan,
                timestampMs,
                locationTracker != null ? locationTracker.getCoordinatesLine() : ""
        );
    }

    private static void drawMetaStripContents(
            Canvas canvas,
            StripLayout layout,
            int stripSpan,
            long timestampMs,
            String gpsLine
    ) {
        int thickness = Math.round(layout.stripHeight);
        canvas.drawRect(0f, 0f, stripSpan, thickness, layout.backgroundPaint);

        String timeText = DATE_TIME_FORMAT.format(new Date(timestampMs));
        String gpsText = gpsLine != null ? gpsLine : "";
        canvas.drawText(timeText, layout.horizontalPadding, layout.textBaseline, layout.textPaint);

        float gpsWidth = layout.textPaint.measureText(gpsText);
        canvas.drawText(
                gpsText,
                stripSpan - layout.horizontalPadding - gpsWidth,
                layout.textBaseline,
                layout.textPaint
        );
    }

    private static StripLayout createStripLayout(int stripSpan) {
        float textSize = Math.max(22f, stripSpan / 55f);
        float horizontalPadding = textSize * 0.45f;
        float verticalPadding = textSize * 0.22f;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setShadowLayer(textSize * 0.1f, 0f, textSize * 0.05f, Color.BLACK);

        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(0xCC000000);

        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float stripHeight = (metrics.bottom - metrics.top) + verticalPadding * 2f;
        float textBaseline = verticalPadding - metrics.top;

        return new StripLayout(
                textPaint,
                backgroundPaint,
                stripHeight,
                horizontalPadding,
                textBaseline
        );
    }

    private static String ellipsize(Paint paint, String text, float maxWidth) {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        float ellipsisWidth = paint.measureText(ellipsis);
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) {
            end--;
        }
        if (end <= 0) {
            return ellipsis;
        }
        return text.substring(0, end).trim() + ellipsis;
    }

    private static final class StripLayout {
        private final Paint textPaint;
        private final Paint backgroundPaint;
        private final float stripHeight;
        private final float horizontalPadding;
        private final float textBaseline;

        private StripLayout(
                Paint textPaint,
                Paint backgroundPaint,
                float stripHeight,
                float horizontalPadding,
                float textBaseline
        ) {
            this.textPaint = textPaint;
            this.backgroundPaint = backgroundPaint;
            this.stripHeight = stripHeight;
            this.horizontalPadding = horizontalPadding;
            this.textBaseline = textBaseline;
        }
    }
}
