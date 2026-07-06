package com.opendashcam;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;

/**
 * Maps the user-facing recording orientation preference to CameraX target rotation
 * and activity screen orientation.
 */
public final class RecordingOrientationHelper {
    public static final String ORIENTATION_AUTO = "auto";
    public static final String ORIENTATION_LANDSCAPE = "landscape";
    public static final String ORIENTATION_PORTRAIT = "portrait";

    private RecordingOrientationHelper() {
    }

    public static String getOrientationMode(Context context) {
        return RecordingPreferences.getVideoOrientationMode(context);
    }

    public static boolean isAutoMode(Context context) {
        return ORIENTATION_AUTO.equals(getOrientationMode(context));
    }

    public static void applyActivityOrientation(Activity activity) {
        activity.setRequestedOrientation(getActivityOrientation(activity));
    }

    public static int getActivityOrientation(Context context) {
        switch (getOrientationMode(context)) {
            case ORIENTATION_LANDSCAPE:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            case ORIENTATION_PORTRAIT:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            case ORIENTATION_AUTO:
            default:
                return ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        }
    }

    public static int getTargetRotation(Context context) {
        switch (getOrientationMode(context)) {
            case ORIENTATION_LANDSCAPE:
                return Surface.ROTATION_90;
            case ORIENTATION_PORTRAIT:
                return Surface.ROTATION_0;
            case ORIENTATION_AUTO:
            default:
                return getDisplayRotation(context);
        }
    }

    public static int mapDeviceOrientationToSurfaceRotation(int deviceOrientationDegrees) {
        int rounded = ((deviceOrientationDegrees + 45) / 90) * 90;
        switch (rounded % 360) {
            case 90:
                return Surface.ROTATION_90;
            case 180:
                return Surface.ROTATION_180;
            case 270:
                return Surface.ROTATION_270;
            case 0:
            default:
                return Surface.ROTATION_0;
        }
    }

    public static boolean isLandscapeRotation(int surfaceRotation) {
        return surfaceRotation == Surface.ROTATION_90 || surfaceRotation == Surface.ROTATION_270;
    }

    public static String getSummary(Context context) {
        String value = getOrientationMode(context);
        String[] values = context.getResources().getStringArray(R.array.pref_video_orientation_values);
        String[] entries = context.getResources().getStringArray(R.array.pref_video_orientation_entries);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return entries[i];
            }
        }
        return entries[0];
    }

    private static int getDisplayRotation(Context context) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                return display.getRotation();
            }
        }
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Surface.ROTATION_90
                : Surface.ROTATION_0;
    }

    /** Tracks physical device rotation for automatic recording orientation. */
    public static final class DeviceOrientationTracker {
        public interface Listener {
            void onRotationChanged(int surfaceRotation);
        }

        private final Context appContext;
        private final OrientationEventListener orientationListener;
        private Listener listener;
        private int surfaceRotation = Surface.ROTATION_0;
        private boolean started;

        public DeviceOrientationTracker(Context context) {
            appContext = context.getApplicationContext();
            surfaceRotation = getTargetRotation(appContext);
            orientationListener = new OrientationEventListener(appContext) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                        return;
                    }
                    int updatedRotation = mapDeviceOrientationToSurfaceRotation(orientation);
                    if (updatedRotation == surfaceRotation) {
                        return;
                    }
                    surfaceRotation = updatedRotation;
                    if (listener != null) {
                        listener.onRotationChanged(updatedRotation);
                    }
                }
            };
        }

        public void setListener(Listener listener) {
            this.listener = listener;
        }

        public void start() {
            if (started || !RecordingOrientationHelper.isAutoMode(appContext)) {
                return;
            }
            if (orientationListener.canDetectOrientation()) {
                orientationListener.enable();
                started = true;
            }
        }

        public void stop() {
            if (!started) {
                return;
            }
            orientationListener.disable();
            started = false;
        }

        public int getSurfaceRotation() {
            if (RecordingOrientationHelper.isAutoMode(appContext)) {
                return surfaceRotation;
            }
            return RecordingOrientationHelper.getTargetRotation(appContext);
        }
    }
}
