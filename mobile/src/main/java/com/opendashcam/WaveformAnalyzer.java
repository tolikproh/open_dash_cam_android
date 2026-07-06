package com.opendashcam;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Decodes an MP3 file and extracts RMS energy per time window for waveform rendering. */
public final class WaveformAnalyzer {
    private static final long TIMEOUT_US = 10_000L;
    private static final int WINDOW_MS = 50;

    private WaveformAnalyzer() {
    }

    public static WaveformAnalysis analyze(File mp3File, int peakCount) throws IOException {
        if (mp3File == null || !mp3File.exists() || mp3File.length() == 0) {
            throw new IOException("MP3 file is missing or empty");
        }

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(mp3File.getAbsolutePath());
        int audioTrack = selectAudioTrack(extractor);
        if (audioTrack < 0) {
            extractor.release();
            throw new IOException("No audio track in MP3 file");
        }

        extractor.selectTrack(audioTrack);
        MediaFormat inputFormat = extractor.getTrackFormat(audioTrack);
        long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                ? inputFormat.getLong(MediaFormat.KEY_DURATION)
                : 0L;
        String mime = inputFormat.getString(MediaFormat.KEY_MIME);
        if (mime == null) {
            extractor.release();
            throw new IOException("Unknown audio mime type");
        }

        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();

        int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                : 44100;
        int channelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                : 1;
        int windowSizeSamples = Math.max(1, sampleRate * WINDOW_MS / 1000);

        List<Float> windowRms = new ArrayList<>();
        WindowAccumulator window = new WindowAccumulator(windowSizeSamples);
        long totalSamples = 0L;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        try {
            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            throw new IOException("Decoder input buffer is null");
                        }
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    0
                            );
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                }
                if (outputIndex < 0) {
                    continue;
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }

                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                if (outputBuffer != null && bufferInfo.size > 0) {
                    totalSamples += accumulateWindowRms(
                            outputBuffer,
                            bufferInfo.offset,
                            bufferInfo.size,
                            channelCount,
                            window,
                            windowRms
                    );
                }
                decoder.releaseOutputBuffer(outputIndex, false);
            }
        } finally {
            decoder.stop();
            decoder.release();
            extractor.release();
        }

        window.flush(windowRms);

        if (durationUs <= 0 && sampleRate > 0 && totalSamples > 0) {
            durationUs = totalSamples * 1_000_000L / sampleRate;
        }

        float[] peaks = resampleToPeaks(windowRms, Math.max(peakCount, 1));
        smoothPeaks(peaks, 3);
        applyNoiseGateAndNormalize(peaks);
        return new WaveformAnalysis(durationUs, sampleRate, channelCount, peaks);
    }

    private static long accumulateWindowRms(
            ByteBuffer buffer,
            int offset,
            int size,
            int channelCount,
            WindowAccumulator window,
            List<Float> windowRms
    ) {
        buffer.position(offset);
        buffer.limit(offset + size);
        ShortBuffer shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        long samplesSeen = 0L;

        while (shortBuffer.hasRemaining()) {
            float sample = 0f;
            for (int channel = 0; channel < channelCount && shortBuffer.hasRemaining(); channel++) {
                sample = Math.max(sample, Math.abs(shortBuffer.get()) / 32768f);
            }
            window.addSample(sample * sample, windowRms);
            samplesSeen++;
        }
        return samplesSeen;
    }

    private static float[] resampleToPeaks(List<Float> windowRms, int peakCount) {
        float[] peaks = new float[peakCount];
        if (windowRms.isEmpty()) {
            return peaks;
        }
        if (windowRms.size() == 1) {
            peaks[0] = windowRms.get(0);
            return peaks;
        }

        for (int i = 0; i < peakCount; i++) {
            int start = i * windowRms.size() / peakCount;
            int end = Math.max(start + 1, (i + 1) * windowRms.size() / peakCount);
            float max = 0f;
            for (int j = start; j < end && j < windowRms.size(); j++) {
                max = Math.max(max, windowRms.get(j));
            }
            peaks[i] = max;
        }
        return peaks;
    }

    /** Light smoothing so short speech bursts read clearly without flattening silence. */
    private static void smoothPeaks(float[] peaks, int radius) {
        if (peaks.length < 3 || radius <= 0) {
            return;
        }
        float[] smoothed = new float[peaks.length];
        for (int i = 0; i < peaks.length; i++) {
            float sum = 0f;
            int count = 0;
            for (int j = Math.max(0, i - radius); j <= Math.min(peaks.length - 1, i + radius); j++) {
                sum += peaks[j];
                count++;
            }
            smoothed[i] = count > 0 ? sum / count : peaks[i];
        }
        System.arraycopy(smoothed, 0, peaks, 0, peaks.length);
    }

    /**
     * Suppresses constant background noise and normalizes by a high percentile so
     * speech/music stands out instead of filling the whole strip.
     */
    private static void applyNoiseGateAndNormalize(float[] peaks) {
        if (peaks.length == 0) {
            return;
        }

        float[] sorted = peaks.clone();
        Arrays.sort(sorted);
        int count = sorted.length;
        float noiseFloor = sorted[Math.min(count - 1, count * 15 / 100)];
        float gateThreshold = noiseFloor * 2.2f;

        float maxAfterGate = 0f;
        for (int i = 0; i < peaks.length; i++) {
            if (peaks[i] <= gateThreshold) {
                peaks[i] = 0f;
            } else {
                peaks[i] = peaks[i] - gateThreshold;
                maxAfterGate = Math.max(maxAfterGate, peaks[i]);
            }
        }
        if (maxAfterGate <= 0f) {
            return;
        }

        float[] gatedSorted = peaks.clone();
        Arrays.sort(gatedSorted);
        float displayMax = percentile(gatedSorted, 0.92f);
        if (displayMax <= 0f) {
            displayMax = maxAfterGate;
        }

        for (int i = 0; i < peaks.length; i++) {
            if (peaks[i] <= 0f) {
                continue;
            }
            float normalized = peaks[i] / displayMax;
            peaks[i] = (float) Math.min(1.0, Math.sqrt(normalized));
        }
    }

    private static float percentile(float[] sortedAsc, float fraction) {
        if (sortedAsc.length == 0) {
            return 0f;
        }
        int index = Math.min(sortedAsc.length - 1, Math.round(sortedAsc.length * fraction));
        return sortedAsc[index];
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static final class WindowAccumulator {
        private final int windowSizeSamples;
        private float sumSquares;
        private int sampleCount;

        WindowAccumulator(int windowSizeSamples) {
            this.windowSizeSamples = windowSizeSamples;
        }

        void addSample(float sampleSquare, List<Float> windowRms) {
            sumSquares += sampleSquare;
            sampleCount++;
            if (sampleCount >= windowSizeSamples) {
                windowRms.add((float) Math.sqrt(sumSquares / sampleCount));
                sumSquares = 0f;
                sampleCount = 0;
            }
        }

        void flush(List<Float> windowRms) {
            if (sampleCount > 0) {
                windowRms.add((float) Math.sqrt(sumSquares / sampleCount));
                sumSquares = 0f;
                sampleCount = 0;
            }
        }
    }

    public static final class WaveformAnalysis {
        public final long durationUs;
        public final int sampleRate;
        public final int channelCount;
        public final float[] peaks;

        WaveformAnalysis(long durationUs, int sampleRate, int channelCount, float[] peaks) {
            this.durationUs = durationUs;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.peaks = peaks;
        }
    }
}
