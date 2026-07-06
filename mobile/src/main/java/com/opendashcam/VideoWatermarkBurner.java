package com.opendashcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Burns watermark strips along the long edge of a recorded MP4 using Media3 Transformer. */
@OptIn(markerClass = UnstableApi.class)
public final class VideoWatermarkBurner {
    private static final String TAG = "VideoWatermarkBurner";
    private static final long TIMEOUT_MINUTES = 10L;

    private VideoWatermarkBurner() {
    }

    public static File burn(
            Context context,
            File inputFile,
            RecordingLocationTracker locationTracker,
            long segmentStartMs
    ) {
        return burn(
                context,
                inputFile,
                RecordingLocationSnapshot.from(locationTracker),
                segmentStartMs,
                RecordingMediaType.VIDEO
        );
    }

    public static File burn(
            Context context,
            File inputFile,
            RecordingLocationTracker locationTracker,
            long segmentStartMs,
            RecordingMediaType outputType
    ) {
        return burn(
                context,
                inputFile,
                RecordingLocationSnapshot.from(locationTracker),
                segmentStartMs,
                outputType
        );
    }

    public static File burn(
            Context context,
            File inputFile,
            RecordingLocationSnapshot locationSnapshot,
            long segmentStartMs,
            RecordingMediaType outputType
    ) {
        if (inputFile == null || !inputFile.exists() || inputFile.length() == 0) {
            return inputFile;
        }

        WatermarkOrientation orientation = WatermarkOrientation.read(inputFile);
        if (orientation.stripSpan <= 0) {
            Log.e(TAG, "Unable to read video dimensions");
            return inputFile;
        }

        Context appContext = context.getApplicationContext();
        File outputFile = StorageHelper.createProcessingFile(
                appContext,
                segmentStartMs,
                outputType
        );
        deleteQuietly(outputFile);

        List<AnchoredStripOverlay> overlays = createOverlays(
                appContext,
                locationSnapshot,
                segmentStartMs,
                orientation
        );
        OverlayEffect overlayEffect = new OverlayEffect(ImmutableList.copyOf(overlays));
        Effects effects = new Effects(ImmutableList.of(), ImmutableList.of(overlayEffect));

        EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(inputFile))
        )
                .setRemoveAudio(false)
                .setEffects(effects)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ExportException> exportError = new AtomicReference<>();
        AtomicReference<RuntimeException> startError = new AtomicReference<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        Handler mainHandler = new Handler(Looper.getMainLooper());

        mainHandler.post(() -> {
            try {
                Transformer transformer = new Transformer.Builder(appContext)
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition, ExportResult exportResult) {
                                latch.countDown();
                            }

                            @Override
                            public void onError(
                                    Composition composition,
                                    ExportResult exportResult,
                                    ExportException exportException
                            ) {
                                exportError.set(exportException);
                                latch.countDown();
                            }
                        })
                        .build();
                transformer.start(editedMediaItem, outputFile.getAbsolutePath());
            } catch (RuntimeException e) {
                startError.set(e);
                latch.countDown();
            } finally {
                startLatch.countDown();
            }
        });

        try {
            if (!startLatch.await(30, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timed out waiting to start watermark export");
                deleteQuietly(outputFile);
                return inputFile;
            }
            if (startError.get() != null) {
                Log.e(TAG, "Failed to start watermark export", startError.get());
                deleteQuietly(outputFile);
                return inputFile;
            }
            if (!latch.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                Log.e(TAG, "Watermark export timed out");
                deleteQuietly(outputFile);
                return inputFile;
            }
            if (exportError.get() != null) {
                Log.e(TAG, "Watermark export failed", exportError.get());
                deleteQuietly(outputFile);
                return inputFile;
            }
            if (!outputFile.exists() || outputFile.length() == 0) {
                Log.e(TAG, "Watermark export produced empty file");
                deleteQuietly(outputFile);
                return inputFile;
            }
            return outputFile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(outputFile);
            return inputFile;
        } finally {
            for (AnchoredStripOverlay overlay : overlays) {
                overlay.dispose();
            }
        }
    }

    private static List<AnchoredStripOverlay> createOverlays(
            Context appContext,
            RecordingLocationSnapshot locationSnapshot,
            long segmentStartMs,
            WatermarkOrientation orientation
    ) {
        List<AnchoredStripOverlay> overlays = new ArrayList<>();
        if (orientation.stripLayout == WatermarkOrientation.StripLayout.HORIZONTAL) {
            overlays.add(new MetaStripOverlay(
                    appContext,
                    locationSnapshot,
                    segmentStartMs,
                    orientation.stripSpan,
                    true
            ));
            String address = locationSnapshot.getAddressLine();
            if (!TextUtils.isEmpty(address)) {
                overlays.add(new AddressStripOverlay(appContext, orientation.stripSpan, address, true));
            }
        } else {
            String address = locationSnapshot.getAddressLine();
            if (!TextUtils.isEmpty(address)) {
                overlays.add(new AddressStripOverlay(appContext, orientation.stripSpan, address, false));
            }
            overlays.add(new MetaStripOverlay(
                    appContext,
                    locationSnapshot,
                    segmentStartMs,
                    orientation.stripSpan,
                    false
            ));
        }
        return overlays;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete temporary watermark file: " + file.getAbsolutePath());
        }
    }

    private abstract static class AnchoredStripOverlay extends BitmapOverlay {
        private final int stripSpan;
        private final boolean horizontal;
        private Size outputSize;
        private Bitmap cachedBitmap;
        private long cachedSecond = -1L;

        AnchoredStripOverlay(int stripSpan, boolean horizontal) {
            this.stripSpan = stripSpan;
            this.horizontal = horizontal;
        }

        protected abstract float backgroundAnchorX();

        protected abstract float backgroundAnchorY();

        protected abstract float overlayAnchorX();

        protected abstract float overlayAnchorY();

        protected abstract Bitmap createStrip(long timestampMs);

        @Override
        public void configure(Size size) {
            outputSize = size;
        }

        @Override
        public Bitmap getBitmap(long presentationTimeUs) {
            long displaySecond = presentationTimeUs / 1_000_000L;
            if (cachedBitmap != null && cachedSecond == displaySecond) {
                return cachedBitmap;
            }

            disposeCachedBitmap();
            cachedSecond = displaySecond;
            cachedBitmap = createStrip(presentationTimeUs / 1_000L);
            return cachedBitmap;
        }

        @Override
        public OverlaySettings getOverlaySettings(long presentationTimeUs) {
            OverlaySettings.Builder builder = new OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(backgroundAnchorX(), backgroundAnchorY())
                    .setOverlayFrameAnchor(overlayAnchorX(), overlayAnchorY());
            if (outputSize == null) {
                return builder.build();
            }
            if (horizontal) {
                int bitmapWidth = cachedBitmap != null ? cachedBitmap.getWidth() : stripSpan;
                if (bitmapWidth > 0) {
                    builder.setScale((float) outputSize.getWidth() / bitmapWidth, 1f);
                }
            } else {
                int bitmapHeight = cachedBitmap != null ? cachedBitmap.getHeight() : stripSpan;
                if (bitmapHeight > 0) {
                    builder.setScale(1f, (float) outputSize.getHeight() / bitmapHeight);
                }
            }
            return builder.build();
        }

        void dispose() {
            disposeCachedBitmap();
        }

        private void disposeCachedBitmap() {
            if (cachedBitmap != null) {
                cachedBitmap.recycle();
                cachedBitmap = null;
            }
        }

        protected int getStripSpan() {
            return stripSpan;
        }

        protected boolean isHorizontal() {
            return horizontal;
        }
    }

    private static final class MetaStripOverlay extends AnchoredStripOverlay {
        private final Context appContext;
        private final RecordingLocationSnapshot locationSnapshot;
        private final long segmentStartMs;

        MetaStripOverlay(
                Context context,
                RecordingLocationSnapshot locationSnapshot,
                long segmentStartMs,
                int stripSpan,
                boolean horizontal
        ) {
            super(stripSpan, horizontal);
            this.appContext = context.getApplicationContext();
            this.locationSnapshot = locationSnapshot;
            this.segmentStartMs = segmentStartMs;
        }

        @Override
        protected float backgroundAnchorX() {
            return isHorizontal() ? -1f : 1f;
        }

        @Override
        protected float backgroundAnchorY() {
            return isHorizontal() ? -1f : 0f;
        }

        @Override
        protected float overlayAnchorX() {
            return isHorizontal() ? -1f : 1f;
        }

        @Override
        protected float overlayAnchorY() {
            return isHorizontal() ? -1f : 0f;
        }

        @Override
        protected Bitmap createStrip(long presentationMs) {
            long timestampMs = segmentStartMs + presentationMs;
            if (isHorizontal()) {
                return WatermarkRenderer.createMetaStripBitmap(
                        appContext,
                        getStripSpan(),
                        timestampMs,
                        locationSnapshot.getCoordinatesLine()
                );
            }
            return WatermarkRenderer.createVerticalMetaStripBitmap(
                    appContext,
                    getStripSpan(),
                    timestampMs,
                    locationSnapshot.getCoordinatesLine()
            );
        }
    }

    private static final class AddressStripOverlay extends AnchoredStripOverlay {
        private final Context appContext;
        private final String address;

        AddressStripOverlay(Context context, int stripSpan, String address, boolean horizontal) {
            super(stripSpan, horizontal);
            this.appContext = context.getApplicationContext();
            this.address = address;
        }

        @Override
        protected float backgroundAnchorX() {
            return isHorizontal() ? -1f : -1f;
        }

        @Override
        protected float backgroundAnchorY() {
            return isHorizontal() ? 1f : 0f;
        }

        @Override
        protected float overlayAnchorX() {
            return isHorizontal() ? -1f : -1f;
        }

        @Override
        protected float overlayAnchorY() {
            return isHorizontal() ? 1f : 0f;
        }

        @Override
        protected Bitmap createStrip(long presentationMs) {
            if (isHorizontal()) {
                return WatermarkRenderer.createAddressStripBitmap(appContext, getStripSpan(), address);
            }
            return WatermarkRenderer.createVerticalAddressStripBitmap(appContext, getStripSpan(), address);
        }
    }
}
