package com.opendashcam;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Continuous microphone capture with gapless MP3 file rotation at segment boundaries.
 */
public final class AudioMp3Recorder {
    private static final String TAG = "AudioMp3Recorder";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 1;
    private static final int BIT_RATE = 128;
    private static final long ROTATE_TIMEOUT_MS = 3_000L;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean rotateRequested = new AtomicBoolean(false);
    private final AtomicReference<File> pendingNextFile = new AtomicReference<>();
    private final Object streamLock = new Object();

    private Thread workerThread;
    private File outputFile;

    public void start(File targetFile) throws IOException {
        if (recording.get()) {
            throw new IllegalStateException("Already recording");
        }
        ensureParentExists(targetFile);
        outputFile = targetFile;
        recording.set(true);
        workerThread = new Thread(this::recordLoop, "mp3-recorder");
        workerThread.start();
    }

    public File rotateFileAndGetCompleted(File nextFile) throws IOException {
        if (!recording.get()) {
            throw new IllegalStateException("Not recording");
        }
        ensureParentExists(nextFile);
        File completedFile = outputFile;
        pendingNextFile.set(nextFile);
        rotateRequested.set(true);
        waitForRotation();
        return completedFile;
    }

    public void stop() {
        recording.set(false);
        rotateRequested.set(false);
        pendingNextFile.set(null);
        if (workerThread != null) {
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    private void waitForRotation() throws IOException {
        long deadline = System.currentTimeMillis() + ROTATE_TIMEOUT_MS;
        while (rotateRequested.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while rotating MP3 file", e);
            }
        }
        if (rotateRequested.get()) {
            throw new IOException("Timed out waiting for MP3 file rotation");
        }
    }

    private void recordLoop() {
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size");
            return;
        }

        AudioRecord audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
        );

        short[] pcmBuffer = new short[bufferSize];
        byte[] mp3Buffer = new byte[bufferSize];

        try {
            synchronized (streamLock) {
                openEncoderStreams(outputFile);
            }
            audioRecord.startRecording();

            while (recording.get()) {
                ensureRecordingActive(audioRecord);

                int read = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                if (read > 0) {
                    synchronized (streamLock) {
                        writeEncoded(pcmBuffer, read, mp3Buffer);
                    }
                }

                if (rotateRequested.get()) {
                    File nextFile = pendingNextFile.getAndSet(null);
                    if (nextFile != null) {
                        try {
                            synchronized (streamLock) {
                                closeEncoderStreams();
                                outputFile = nextFile;
                                openEncoderStreams(nextFile);
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to rotate MP3 output file", e);
                            recording.set(false);
                        } finally {
                            rotateRequested.set(false);
                        }
                    } else {
                        rotateRequested.set(false);
                    }
                }
            }

            synchronized (streamLock) {
                closeEncoderStreams();
            }
        } catch (IOException e) {
            Log.e(TAG, "MP3 recording failed", e);
        } finally {
            try {
                audioRecord.stop();
            } catch (RuntimeException ignored) {
            }
            audioRecord.release();
        }
    }

    private static void ensureRecordingActive(AudioRecord audioRecord) {
        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            return;
        }
        Log.w(TAG, "AudioRecord paused, restarting capture");
        try {
            audioRecord.stop();
        } catch (RuntimeException ignored) {
        }
        audioRecord.startRecording();
    }

    private AndroidLame lame;
    private FileOutputStream outputStream;

    private void openEncoderStreams(File targetFile) throws IOException {
        lame = new LameBuilder()
                .setInSampleRate(SAMPLE_RATE)
                .setOutChannels(CHANNELS)
                .setOutBitrate(BIT_RATE)
                .setOutSampleRate(SAMPLE_RATE)
                .build();
        outputStream = new FileOutputStream(targetFile);
    }

    private void writeEncoded(short[] pcmBuffer, int read, byte[] mp3Buffer) throws IOException {
        if (lame == null || outputStream == null) {
            return;
        }
        int encoded = lame.encode(pcmBuffer, pcmBuffer, read, mp3Buffer);
        if (encoded > 0) {
            outputStream.write(mp3Buffer, 0, encoded);
        }
    }

    private void closeEncoderStreams() throws IOException {
        if (lame != null && outputStream != null) {
            byte[] mp3Buffer = new byte[8192];
            int flushBytes = lame.flush(mp3Buffer);
            if (flushBytes > 0) {
                outputStream.write(mp3Buffer, 0, flushBytes);
            }
            outputStream.flush();
        }
        if (lame != null) {
            lame.close();
            lame = null;
        }
        if (outputStream != null) {
            outputStream.close();
            outputStream = null;
        }
    }

    private static void ensureParentExists(File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent);
        }
    }
}
