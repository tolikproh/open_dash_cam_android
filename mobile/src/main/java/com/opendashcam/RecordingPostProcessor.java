package com.opendashcam;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Background executor for MP3→MP4 and watermark work; outlives the recording service. */
public final class RecordingPostProcessor {
    private static final String TAG = "RecordingPostProcessor";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger threadId = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "recording-post-" + threadId.incrementAndGet());
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    });

    private static final RecordingWakeLock WAKE_LOCK =
            new RecordingWakeLock("OpenDashCam:RecordingPostProcessor");

    private RecordingPostProcessor() {
    }

    public static void execute(Context context, Runnable task) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            WAKE_LOCK.acquire(appContext);
            try {
                task.run();
            } catch (Throwable t) {
                Log.e(TAG, "Post-processing task failed", t);
            } finally {
                WAKE_LOCK.release(appContext);
            }
        });
    }
}
