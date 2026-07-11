package com.opendashcam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Size;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.AudioSpec;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackgroundVideoRecorder extends LifecycleService {
    private static final String TAG = "BackgroundVideoRecorder";
    /** Prepare the next segment file this long before the wall-clock boundary. */
    private static final long PRE_PREPARE_MS = 3_000L;
    /** Keep recording past the boundary so the encoder/muxer can flush trailing frames. */
    private static final long SEGMENT_STOP_BUFFER_MS = 2_500L;

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private SurfaceTexture previewSurfaceTexture;
    private Surface previewSurface;
    private RecordingLocationTracker locationTracker;
    /** Captured before the tracker is released so late Finalize/watermark still get GPS. */
    private RecordingLocationSnapshot pendingStopLocationSnapshot;
    private RecordingOrientationHelper.DeviceOrientationTracker deviceOrientationTracker;
    private int appliedTargetRotation = -1;
    private Recorder recorder;
    private VideoCapture<Recorder> videoCapture;
    private boolean videoPipelineReady;
    private static final boolean WATERMARK_ENABLED = true;
    private androidx.camera.video.Recording activeVideoRecording;
    private AudioMp3Recorder audioMp3Recorder;
    private Handler segmentHandler;
    private HandlerThread handlerThread;
    private String currentVideoFile = "null";
    private long currentSegmentStartMs;
    private RecordingMediaType currentMediaType;
    private SharedPreferences sharedPref;
    private SharedPreferences.Editor editor;
    private SharedPreferences settings;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private boolean isStopping;
    private String appMode;
    private boolean modeRestartPending;
    private File preparedNextHiddenFile;
    private long preparedNextSegmentStartMs;
    private PendingRecording preparedNextPendingRecording;
    private final RecordingWakeLock recordingWakeLock =
            new RecordingWakeLock("OpenDashCam:BackgroundVideoRecorder");
    private AudioFocusRequest audioFocusRequest;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            Log.w(TAG, "Permanent audio focus loss");
        }
    };

    private final Runnable segmentBoundaryRunnable = this::finishCurrentSegment;
    private final Runnable prePrepareRunnable = this::prePrepareNextSegment;

    @Override
    public void onCreate() {
        super.onCreate();
        isStopping = false;
        appMode = RecordingPreferences.getAppMode(this);
        currentMediaType = RecordingPreferences.getActiveMediaType(this);

        handlerThread = new HandlerThread("segment_timer_thread");
        handlerThread.start();
        segmentHandler = new Handler(handlerThread.getLooper());

        Util.startForeground(
                this,
                Util.RECORDER_NOTIFICATION_ID,
                Util.createStatusBarNotification(this),
                Util.getRecorderForegroundServiceType(this)
        );

        sharedPref = getApplicationContext().getSharedPreferences(
                getString(R.string.current_recordings_preferences_key),
                Context.MODE_PRIVATE);
        editor = sharedPref.edit();
        settings = PreferenceManager.getDefaultSharedPreferences(this);

        disableSound(editor);
        recordingWakeLock.acquire(this);
        requestRecordingAudioFocus();

        if (RecordingPreferences.usesLocation(this)) {
            ensureLocationTrackerStarted();
        }

        if (RecordingPreferences.isVideoMode(this)) {
            ensureDeviceOrientationTrackerStarted();
        }

        try {
            if (RecordingPreferences.usesMp3Capture(this)) {
                startNextSegment();
            } else {
                ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                        ProcessCameraProvider.getInstance(this);
                cameraProviderFuture.addListener(() -> {
                    try {
                        cameraProvider = cameraProviderFuture.get();
                        ensureVideoPipelineReady();
                        startNextSegment();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to initialize camera", e);
                        stopSelf();
                    }
                }, ContextCompat.getMainExecutor(this));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording pipeline", e);
            stopSelf();
        }
    }

    private void ensureDeviceOrientationTrackerStarted() {
        if (deviceOrientationTracker == null) {
            deviceOrientationTracker = new RecordingOrientationHelper.DeviceOrientationTracker(this);
            deviceOrientationTracker.setListener(rotation -> ContextCompat.getMainExecutor(this).execute(
                    () -> applyRecordingOrientation(rotation)
            ));
        }
        deviceOrientationTracker.start();
    }

    private void applyRecordingOrientation() {
        applyRecordingOrientation(getRecordingTargetRotation());
    }

    private void applyRecordingOrientation(int rotation) {
        if (rotation == appliedTargetRotation) {
            return;
        }
        appliedTargetRotation = rotation;
        if (preview != null) {
            preview.setTargetRotation(rotation);
        }
        if (videoCapture != null) {
            videoCapture.setTargetRotation(rotation);
        }
    }

    private int getRecordingTargetRotation() {
        if (deviceOrientationTracker != null
                && RecordingOrientationHelper.isAutoMode(this)) {
            return deviceOrientationTracker.getSurfaceRotation();
        }
        return RecordingOrientationHelper.getTargetRotation(this);
    }

    private void ensureLocationTrackerStarted() {
        if (locationTracker == null) {
            locationTracker = new RecordingLocationTracker(this);
        }
        locationTracker.start();
    }

    private RecordingLocationSnapshot captureLocationSnapshot() {
        if (pendingStopLocationSnapshot != null) {
            return pendingStopLocationSnapshot;
        }
        return RecordingLocationSnapshot.from(locationTracker);
    }

    private void releaseLocationTracker() {
        if (locationTracker != null) {
            locationTracker.release();
            locationTracker = null;
        }
    }

    private void releaseVideoPipeline() {
        if (activeVideoRecording != null) {
            activeVideoRecording.stop();
            activeVideoRecording = null;
        }
        discardPreparedNextSegment();

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }

        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (previewSurfaceTexture != null) {
            previewSurfaceTexture.release();
            previewSurfaceTexture = null;
        }

        if (deviceOrientationTracker != null) {
            deviceOrientationTracker.stop();
            deviceOrientationTracker = null;
        }

        videoCapture = null;
        recorder = null;
        preview = null;
        videoPipelineReady = false;
        appliedTargetRotation = -1;
    }

    private void ensureVideoPipelineReady() {
        if (videoPipelineReady || cameraProvider == null) {
            return;
        }

        ensureLocationTrackerStarted();
        ensureDeviceOrientationTrackerStarted();

        preview = new Preview.Builder()
                .setTargetRotation(getRecordingTargetRotation())
                .build();
        preview.setSurfaceProvider(request -> {
            Size resolution = request.getResolution();
            if (previewSurfaceTexture == null) {
                previewSurfaceTexture = new SurfaceTexture(0);
                previewSurface = new Surface(previewSurfaceTexture);
            }
            previewSurfaceTexture.setDefaultBufferSize(
                    resolution.getWidth(),
                    resolution.getHeight()
            );
            request.provideSurface(
                    previewSurface,
                    ContextCompat.getMainExecutor(this),
                    result -> {
                    }
            );
        });

        recorder = new Recorder.Builder()
                .setQualitySelector(RecordingPreferences.getQualitySelector(this))
                .setAudioSource(AudioSpec.SOURCE_CAMCORDER)
                .build();
        videoCapture = VideoCapture.withOutput(recorder);
        appliedTargetRotation = getRecordingTargetRotation();
        videoCapture.setTargetRotation(appliedTargetRotation);

        if (!bindVideoUseCases()) {
            Log.e(TAG, "Unable to bind video capture");
            stopSelf();
            return;
        }

        videoPipelineReady = true;
    }

    private boolean bindVideoUseCases() {
        cameraProvider.unbindAll();
        UseCaseGroup.Builder builder = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture);
        try {
            cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    builder.build()
            );
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind camera use cases", e);
            return false;
        }
    }

    private void startNextSegment() {
        if (isStopping) {
            return;
        }
        if (RecordingPreferences.isAudioMode(this)) {
            startNextAudioSegment();
        } else if (RecordingPreferences.isAudioMarkerMode(this)) {
            startNextAudioMarkerSegment();
        } else {
            startNextVideoSegment();
        }
    }

    private void startNextAudioSegment() {
        startNextMp3Segment(RecordingMediaType.AUDIO, false);
    }

    private void startNextAudioMarkerSegment() {
        startNextMp3Segment(RecordingMediaType.AUDIO_MARKER, true);
    }

    private void startNextMp3Segment(RecordingMediaType mediaType, boolean convertToMarkedMp4) {
        if (!RecordingPreferences.usesMp3Capture(this)) {
            return;
        }
        if (cameraProvider != null) {
            releaseVideoPipeline();
        }
        if (RecordingPreferences.usesLocation(this)) {
            ensureLocationTrackerStarted();
        }

        Context context = getApplicationContext();
        if (!isStorageAvailableForRecording(context)) {
            stopSelf();
            return;
        }

        if (RecordingPreferences.isCyclicRecordingEnabled(context)) {
            rotateRecordings(context, Util.getQuota());
        }

        int segmentMinutes = RecordingPreferences.getSegmentDurationMinutes(context);
        currentMediaType = mediaType;

        File hiddenFile;
        long completedSegmentStartMs = currentSegmentStartMs;
        if (preparedNextHiddenFile != null && preparedNextSegmentStartMs > 0) {
            currentSegmentStartMs = preparedNextSegmentStartMs;
            hiddenFile = preparedNextHiddenFile;
            preparedNextHiddenFile = null;
            preparedNextSegmentStartMs = 0;
        } else {
            currentSegmentStartMs = SegmentBoundaryScheduler.getSegmentStartMs(
                    System.currentTimeMillis(),
                    segmentMinutes
            );
            hiddenFile = StorageHelper.createHiddenRecordingFile(this, currentSegmentStartMs, mediaType);
        }

        if (hiddenFile == null) {
            Log.e(TAG, "Unable to create hidden recording file");
            stopSelf();
            return;
        }

        File parent = hiddenFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        if (audioMp3Recorder == null) {
            editor.putString(getString(R.string.previous_recording_preferences_key), currentVideoFile);
            editor.apply();
            currentVideoFile = hiddenFile.getAbsolutePath();
            editor.putString(getString(R.string.current_recording_preferences_key), currentVideoFile);
            editor.apply();

            audioMp3Recorder = new AudioMp3Recorder();
            try {
                audioMp3Recorder.start(hiddenFile);
            } catch (IOException e) {
                Log.e(TAG, "Failed to start MP3 recording", e);
                stopSelf();
                return;
            }
        } else {
            File completedHiddenFile = new File(currentVideoFile);
            try {
                audioMp3Recorder.rotateFileAndGetCompleted(hiddenFile);
            } catch (IOException e) {
                Log.e(TAG, "Failed to rotate MP3 segment", e);
                stopSelf();
                return;
            }

            if (convertToMarkedMp4) {
                processCompletedAudioMarkerSegment(completedHiddenFile, completedSegmentStartMs);
            } else {
                finalizeSegmentFile(completedHiddenFile, completedSegmentStartMs, RecordingMediaType.AUDIO);
            }

            editor.putString(getString(R.string.previous_recording_preferences_key), currentVideoFile);
            editor.apply();
            currentVideoFile = hiddenFile.getAbsolutePath();
            editor.putString(getString(R.string.current_recording_preferences_key), currentVideoFile);
            editor.apply();
        }

        scheduleSegmentBoundaries();
    }

    private void startNextVideoSegment() {
        if (cameraProvider == null) {
            return;
        }

        Context context = getApplicationContext();
        if (!isStorageAvailableForRecording(context)) {
            stopSelf();
            return;
        }

        try {
            ensureVideoPipelineReady();
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare video pipeline", e);
            stopSelf();
            return;
        }

        if (RecordingPreferences.isCyclicRecordingEnabled(context)) {
            rotateRecordings(context, Util.getQuota());
        }

        int segmentMinutes = RecordingPreferences.getSegmentDurationMinutes(context);
        currentMediaType = RecordingMediaType.VIDEO;

        File hiddenFile;
        if (preparedNextHiddenFile != null && preparedNextSegmentStartMs > 0) {
            currentSegmentStartMs = preparedNextSegmentStartMs;
            hiddenFile = preparedNextHiddenFile;
            preparedNextHiddenFile = null;
            preparedNextSegmentStartMs = 0;
        } else {
            currentSegmentStartMs = SegmentBoundaryScheduler.getSegmentStartMs(
                    System.currentTimeMillis(),
                    segmentMinutes
            );
            hiddenFile = StorageHelper.createHiddenRecordingFile(this, currentSegmentStartMs, currentMediaType);
        }

        File parent = hiddenFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        editor.putString(getString(R.string.previous_recording_preferences_key), currentVideoFile);
        editor.apply();
        currentVideoFile = hiddenFile.getAbsolutePath();
        editor.putString(getString(R.string.current_recording_preferences_key), currentVideoFile);
        editor.apply();

        if (activeVideoRecording != null) {
            activeVideoRecording.stop();
            activeVideoRecording = null;
            return;
        }

        PendingRecording pendingRecording = preparedNextPendingRecording;
        preparedNextPendingRecording = null;
        if (pendingRecording != null) {
            activeVideoRecording = pendingRecording.start(
                    ContextCompat.getMainExecutor(this),
                    this::handleVideoRecordEvent
            );
        } else {
            FileOutputOptions outputOptions = new FileOutputOptions.Builder(hiddenFile).build();
            pendingRecording = videoCapture.getOutput().prepareRecording(this, outputOptions);
            if (RecordingPreferences.isVideoRecordAudioEnabled(this)) {
                pendingRecording = pendingRecording.withAudioEnabled();
            }
            activeVideoRecording = pendingRecording.start(
                    ContextCompat.getMainExecutor(this),
                    this::handleVideoRecordEvent
            );
        }

        scheduleSegmentBoundaries();
    }

    private PendingRecording buildPendingRecording(File hiddenFile) {
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(hiddenFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, outputOptions);
        if (RecordingPreferences.isVideoRecordAudioEnabled(this)) {
            pending = pending.withAudioEnabled();
        }
        return pending;
    }

    private void discardPreparedNextSegment() {
        preparedNextHiddenFile = null;
        preparedNextSegmentStartMs = 0L;
        preparedNextPendingRecording = null;
    }

    private void scheduleSegmentBoundaries() {
        int segmentMinutes = RecordingPreferences.getSegmentDurationMinutes(getApplicationContext());
        long delayMs = SegmentBoundaryScheduler.getMsUntilNextBoundary(
                System.currentTimeMillis(),
                segmentMinutes
        );

        segmentHandler.removeCallbacks(segmentBoundaryRunnable);
        segmentHandler.removeCallbacks(prePrepareRunnable);

        long prePrepareDelay = Math.max(0L, delayMs - PRE_PREPARE_MS);
        if (prePrepareDelay > 0L) {
            segmentHandler.postDelayed(prePrepareRunnable, prePrepareDelay);
        } else {
            prePrepareNextSegment();
        }
        segmentHandler.postDelayed(segmentBoundaryRunnable, delayMs + SEGMENT_STOP_BUFFER_MS);
    }

    private void prePrepareNextSegment() {
        if (isStopping) {
            return;
        }
        int segmentMinutes = RecordingPreferences.getSegmentDurationMinutes(getApplicationContext());
        long nextBoundaryMs = SegmentBoundaryScheduler.getNextBoundaryMs(
                System.currentTimeMillis(),
                segmentMinutes
        );
        preparedNextSegmentStartMs = nextBoundaryMs;
        preparedNextHiddenFile = StorageHelper.createHiddenRecordingFile(
                this,
                preparedNextSegmentStartMs,
                currentMediaType
        );
        File parent = preparedNextHiddenFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        if (!RecordingPreferences.isVideoMode(this) && videoCapture != null && activeVideoRecording != null) {
            try {
                preparedNextPendingRecording = buildPendingRecording(preparedNextHiddenFile);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to pre-prepare next video recording", e);
                preparedNextPendingRecording = null;
            }
        }
    }

    private void finishCurrentSegment() {
        if (RecordingPreferences.usesMp3Capture(this)) {
            if (!isStorageAvailableForRecording(getApplicationContext())) {
                stopSelf();
                return;
            }
            startNextSegment();
        } else if (activeVideoRecording != null) {
            activeVideoRecording.stop();
        }
    }

    private void finishCurrentMp3Segment(RecordingMediaType mediaType, boolean convertToMarkedMp4) {
        if (audioMp3Recorder == null) {
            return;
        }
        audioMp3Recorder.stop();
        audioMp3Recorder = null;

        File completedFile = new File(currentVideoFile);
        if (convertToMarkedMp4) {
            processCompletedAudioMarkerSegment(completedFile, currentSegmentStartMs);
        } else {
            finalizeSegmentFile(completedFile, currentSegmentStartMs, mediaType);
        }
    }

    private void finishCurrentAudioSegment() {
        finishCurrentMp3Segment(RecordingMediaType.AUDIO, false);
    }

    private void finishCurrentAudioMarkerSegment() {
        finishCurrentMp3Segment(RecordingMediaType.AUDIO_MARKER, true);
    }

    private void processCompletedAudioMarkerSegment(File recordedFile, long segmentStartMs) {
        Context appContext = getApplicationContext();
        RecordingLocationSnapshot locationSnapshot = captureLocationSnapshot();

        RecordingPostProcessor.execute(appContext, () -> {
            File outputFile = recordedFile;
            try {
                outputFile = AudioMarkerBurner.burn(
                        appContext,
                        recordedFile,
                        locationSnapshot,
                        segmentStartMs
                );
            } catch (Throwable t) {
                Log.e(TAG, "Audio marker processing failed", t);
            }
            if (!outputFile.equals(recordedFile)) {
                StorageHelper.deleteTemporaryRecordingFile(recordedFile);
            }
            File finalOutput = outputFile;
            if (finalOutput.getName().toLowerCase().endsWith(".mp3")) {
                Log.e(TAG, "Audio marker export failed for: " + recordedFile.getName());
                return;
            }
            finalizeSegmentFile(appContext, finalOutput, segmentStartMs, RecordingMediaType.AUDIO_MARKER);
        });
    }

    private void finalizeSegmentFile(
            Context context,
            File hiddenFile,
            long segmentStartMs,
            RecordingMediaType type
    ) {
        if (!hiddenFile.exists() || hiddenFile.length() <= 0) {
            StorageHelper.deleteTemporaryRecordingFile(hiddenFile);
            return;
        }
        if (!hiddenFile.getName().toLowerCase().endsWith("." + type.getExtension())
                && !hiddenFile.getName().toLowerCase().contains(".processing.")) {
            Log.e(TAG, "Unexpected file type for finalize: " + hiddenFile.getName());
            return;
        }

        String finalPath = StorageHelper.finalizeRecording(context, hiddenFile, segmentStartMs, type);
        if (finalPath != null) {
            if (hiddenFile.exists()) {
                StorageHelper.deleteTemporaryRecordingFile(hiddenFile);
            }
            Util.insertNewRecording(new com.opendashcam.models.Recording(finalPath));
        } else {
            Log.e(TAG, "Finalize failed, keeping temp file: " + hiddenFile.getAbsolutePath());
        }
    }

    private void finalizeSegmentFile(File hiddenFile, long segmentStartMs, RecordingMediaType type) {
        finalizeSegmentFile(getApplicationContext(), hiddenFile, segmentStartMs, type);
    }

    private void handleVideoRecordEvent(@NonNull VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;
            activeVideoRecording = null;

            if (finalizeEvent.hasError()) {
                Log.e(TAG, "Recording error: " + finalizeEvent.getError());
                new File(currentVideoFile).delete();
                continueRecordingAfterSegment();
                return;
            }

            File recordedFile = new File(currentVideoFile);
            long segmentStartMs = currentSegmentStartMs;

            continueRecordingAfterSegment();
            processCompletedVideoSegment(recordedFile, segmentStartMs);
        }
    }

    private void processCompletedVideoSegment(File recordedFile, long segmentStartMs) {
        if (!WATERMARK_ENABLED) {
            finalizeSegmentFile(recordedFile, segmentStartMs, RecordingMediaType.VIDEO);
            return;
        }

        RecordingLocationSnapshot locationSnapshot = captureLocationSnapshot();

        Runnable watermarkTask = () -> {
            File outputFile = recordedFile;
            try {
                outputFile = VideoWatermarkBurner.burn(
                        getApplicationContext(),
                        recordedFile,
                        locationSnapshot,
                        segmentStartMs,
                        RecordingMediaType.VIDEO
                );
            } catch (Throwable t) {
                Log.e(TAG, "Watermark processing failed", t);
            }
            if (!outputFile.equals(recordedFile)) {
                StorageHelper.deleteTemporaryRecordingFile(recordedFile);
            }
            File finalOutput = outputFile;
            Context appContext = getApplicationContext();
            Runnable finalizeTask = () -> {
                if (!isStopping) {
                    finalizeSegmentFile(finalOutput, segmentStartMs, RecordingMediaType.VIDEO);
                    return;
                }
                finalizeSegmentFile(appContext, finalOutput, segmentStartMs, RecordingMediaType.VIDEO);
            };
            if (isStopping || cameraExecutor.isShutdown()) {
                finalizeTask.run();
            } else {
                ContextCompat.getMainExecutor(appContext).execute(finalizeTask);
            }
        };

        if (isStopping || cameraExecutor.isShutdown()) {
            RecordingPostProcessor.execute(getApplicationContext(), watermarkTask);
            return;
        }
        try {
            cameraExecutor.execute(watermarkTask);
        } catch (RuntimeException e) {
            Log.w(TAG, "Watermark executor unavailable, running inline", e);
            watermarkTask.run();
        }
    }

    private void continueRecordingAfterSegment() {
        if (!isStopping) {
            if (!isStorageAvailableForRecording(getApplicationContext())) {
                stopSelf();
            } else {
                startNextSegment();
            }
        }
    }

    @Override
    public void onDestroy() {
        isStopping = true;
        segmentHandler.removeCallbacks(segmentBoundaryRunnable);
        segmentHandler.removeCallbacks(prePrepareRunnable);
        discardPreparedNextSegment();

        // Freeze GPS/address before stopping capture or releasing the tracker.
        // CameraX Finalize and audio-marker post-process may run after this.
        pendingStopLocationSnapshot = RecordingLocationSnapshot.from(locationTracker);

        if (audioMp3Recorder != null) {
            if (RecordingPreferences.isAudioMarkerMode(this)) {
                finishCurrentAudioMarkerSegment();
            } else {
                finishCurrentAudioSegment();
            }
        }

        StorageHelper.clearActiveRecordingPaths(this);

        releaseVideoPipeline();
        releaseLocationTracker();

        cameraExecutor.shutdown();
        handlerThread.quitSafely();
        abandonRecordingAudioFocus();
        reEnableSound();
        recordingWakeLock.release(this);
        Util.stopForeground(this);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        recordingWakeLock.acquire(this);

        if (modeRestartPending) {
            modeRestartPending = false;
            return START_STICKY;
        }

        String desiredAppMode = RecordingPreferences.getAppMode(this);
        if (!desiredAppMode.equals(appMode)) {
            Log.i(TAG, "Recording mode changed, restarting service");
            modeRestartPending = true;
            stopSelf();
            ContextCompat.startForegroundService(
                    this,
                    new Intent(this, BackgroundVideoRecorder.class)
            );
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    private void requestRecordingAudioFocus() {
        if (!needsRecordingAudioFocus()) {
            return;
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(audioFocusListener, segmentHandler)
                    .setAcceptsDelayedFocusGain(true)
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }
    }

    private void abandonRecordingAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            audioManager.abandonAudioFocus(audioFocusListener);
        }
    }

    private boolean needsRecordingAudioFocus() {
        return RecordingPreferences.usesMp3Capture(this)
                || RecordingPreferences.isVideoRecordAudioEnabled(this);
    }

    private boolean isStorageAvailableForRecording(Context context) {
        if (getTotalRecordingsSizeMb(context) < Util.getQuota()) {
            return true;
        }
        if (RecordingPreferences.isCyclicRecordingEnabled(context)) {
            return true;
        }
        Util.showToastLong(context, context.getString(R.string.error_storage_quota_full));
        return false;
    }

    private void rotateRecordings(Context context, int quotaMb) {
        while (getTotalRecordingsSizeMb(context) >= quotaMb) {
            String oldestPath = findOldestRecordingPath(context);
            if (oldestPath == null) {
                return;
            }

            com.opendashcam.models.Recording recording = new com.opendashcam.models.Recording(oldestPath);
            if (recording.isStarred()) {
                Util.showToastLong(
                        context.getApplicationContext(),
                        context.getString(R.string.warning_low_quota));
                return;
            }

            Util.deleteSingleRecording(recording);
        }
    }

    private long getTotalRecordingsSizeMb(Context context) {
        long totalBytes = 0;
        for (String path : StorageHelper.listRecordingPaths(context)) {
            totalBytes += StorageHelper.getRecordingSizeBytes(context, path);
        }
        return totalBytes / (1024 * 1024);
    }

    private String findOldestRecordingPath(Context context) {
        List<String> paths = StorageHelper.listRecordingPaths(context);
        String oldestPath = null;
        long oldestTime = Long.MAX_VALUE;
        for (String path : paths) {
            long modified = StorageHelper.getRecordingLastModified(context, path);
            if (modified < oldestTime) {
                oldestTime = modified;
                oldestPath = path;
            }
        }
        return oldestPath;
    }

    private void disableSound(SharedPreferences.Editor editor) {
        if (settings.getBoolean("disable_sound", true)) {
            AudioManager audio = (AudioManager) getApplicationContext().getSystemService(AUDIO_SERVICE);
            if (audio == null) {
                return;
            }
            int volume = audio.getStreamVolume(AudioManager.STREAM_SYSTEM);
            editor.putInt(getString(R.string.pre_start_volume), volume);
            editor.apply();
            if (volume > 0) {
                audio.setStreamVolume(
                        AudioManager.STREAM_SYSTEM,
                        0,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                );
            }
        }
    }

    private void reEnableSound() {
        AudioManager audio = (AudioManager) getApplicationContext().getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        int volume = sharedPref.getInt(getString(R.string.pre_start_volume), 0);
        if (volume > 0) {
            audio.setStreamVolume(
                    AudioManager.STREAM_SYSTEM,
                    volume,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
            );
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
