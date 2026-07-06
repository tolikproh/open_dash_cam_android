package com.opendashcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;

/** Converts a recorded MP3 segment into a watermarked MP4 with a static waveform image. */
public final class AudioMarkerBurner {
    private static final String TAG = "AudioMarkerBurner";

    private AudioMarkerBurner() {
    }

    public static File burn(
            Context context,
            File mp3File,
            RecordingLocationTracker locationTracker,
            long segmentStartMs
    ) {
        return burn(context, mp3File, RecordingLocationSnapshot.from(locationTracker), segmentStartMs);
    }

    public static File burn(
            Context context,
            File mp3File,
            RecordingLocationSnapshot locationSnapshot,
            long segmentStartMs
    ) {
        if (mp3File == null || !mp3File.exists() || mp3File.length() == 0) {
            return mp3File;
        }

        Context appContext = context.getApplicationContext();
        RecordingLocationSnapshot snapshot = locationSnapshot != null
                ? locationSnapshot
                : RecordingLocationSnapshot.empty(appContext);
        Bitmap waveformBitmap = null;
        File composedFile = null;
        File watermarkedFile = null;

        try {
            WaveformAnalyzer.WaveformAnalysis analysis = WaveformAnalyzer.analyze(
                    mp3File,
                    WaveformRenderer.VIDEO_WIDTH
            );
            waveformBitmap = WaveformRenderer.createWaveformBitmap(appContext, analysis.peaks);
            composedFile = StorageHelper.createWaveformProcessingFile(
                    appContext,
                    segmentStartMs,
                    RecordingMediaType.AUDIO_MARKER
            );
            deleteQuietly(composedFile);
            WaveformMp4Composer.compose(mp3File, waveformBitmap, analysis, composedFile);

            watermarkedFile = StorageHelper.createProcessingFile(
                    appContext,
                    segmentStartMs,
                    RecordingMediaType.AUDIO_MARKER
            );
            deleteQuietly(watermarkedFile);

            File watermarked = VideoWatermarkBurner.burn(
                    appContext,
                    composedFile,
                    snapshot,
                    segmentStartMs,
                    RecordingMediaType.AUDIO_MARKER
            );
            deleteQuietly(composedFile);
            if (watermarked != null && watermarked.exists() && watermarked.length() > 0) {
                return watermarked;
            }
            Log.e(TAG, "Watermark step did not produce a valid MP4");
            return mp3File;
        } catch (Exception e) {
            Log.e(TAG, "Audio marker export failed", e);
            deleteQuietly(composedFile);
            deleteQuietly(watermarkedFile);
            return mp3File;
        } finally {
            if (waveformBitmap != null) {
                waveformBitmap.recycle();
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete temp file: " + file.getAbsolutePath());
        }
    }
}
