package com.opendashcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.content.ContextCompat;

/**
 * Keeps the CPU awake while recording so camera and microphone continue with the screen off.
 */
public final class RecordingWakeLock {
    private static final long RENEW_INTERVAL_MS = 30 * 60 * 1000L;
    private static final long ACQUIRE_TIMEOUT_MS = 6 * 60 * 60 * 1000L;

    private final String tag;
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver screenReceiver;
    private final Handler renewHandler = new Handler(Looper.getMainLooper());
    private final Runnable renewRunnable = this::renew;

    public RecordingWakeLock(String tag) {
        this.tag = tag;
    }

    public void acquire(Context context) {
        Context appContext = context.getApplicationContext();
        PowerManager powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
            wakeLock.setReferenceCounted(false);
        }
        renew();
        registerScreenReceiver(appContext);
        scheduleRenewal();
    }

    public void release(Context context) {
        renewHandler.removeCallbacks(renewRunnable);
        unregisterScreenReceiver(context.getApplicationContext());
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void renew() {
        if (wakeLock == null) {
            return;
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock.acquire(ACQUIRE_TIMEOUT_MS);
    }

    private void scheduleRenewal() {
        renewHandler.removeCallbacks(renewRunnable);
        renewHandler.postDelayed(renewRunnable, RENEW_INTERVAL_MS);
    }

    private void registerScreenReceiver(Context context) {
        if (screenReceiver != null) {
            return;
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                switch (intent.getAction()) {
                    case Intent.ACTION_SCREEN_OFF:
                    case Intent.ACTION_SCREEN_ON:
                    case Intent.ACTION_USER_PRESENT:
                        renew();
                        scheduleRenewal();
                        break;
                    default:
                        break;
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterScreenReceiver(Context context) {
        if (screenReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        screenReceiver = null;
    }
}
