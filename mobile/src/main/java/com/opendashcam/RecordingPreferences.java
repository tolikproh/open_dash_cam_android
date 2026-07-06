package com.opendashcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;

import java.util.Arrays;
import java.util.List;

public final class RecordingPreferences {
    public static final String KEY_SEGMENT_DURATION_MIN = "segment_duration_minutes";
    public static final String KEY_STORAGE_QUOTA_MB = "storage_quota_mb";
    public static final String QUOTA_VALUE_MAX = "max";
    public static final String KEY_CYCLIC_RECORDING = "cyclic_recording";
    public static final String KEY_VIDEO_RESOLUTION = "video_resolution";
    public static final String KEY_APP_MODE = "app_mode";
    public static final String KEY_VIDEO_RECORD_AUDIO = "video_record_audio";
    public static final String KEY_VIDEO_ORIENTATION = "video_orientation";

    public static final String MODE_VIDEO = "video";
    public static final String MODE_AUDIO = "audio";
    public static final String MODE_AUDIO_MARKER = "audio_marker";

    private static final int DEFAULT_SEGMENT_MINUTES = 1;
    static final int DEFAULT_QUOTA_MB = 1024;
    private static final String DEFAULT_RESOLUTION = "720";

    private RecordingPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static int getSegmentDurationMinutes(Context context) {
        try {
            return Integer.parseInt(prefs(context).getString(
                    KEY_SEGMENT_DURATION_MIN,
                    String.valueOf(DEFAULT_SEGMENT_MINUTES)
            ));
        } catch (NumberFormatException e) {
            return DEFAULT_SEGMENT_MINUTES;
        }
    }

    public static long getSegmentDurationMs(Context context) {
        return getSegmentDurationMinutes(context) * 60L * 1000L;
    }

    public static int getStorageQuotaMb(Context context) {
        String storedValue = prefs(context).getString(KEY_STORAGE_QUOTA_MB, QUOTA_VALUE_MAX);
        return StorageQuotaHelper.resolveQuotaMb(context, storedValue);
    }

    public static String getStorageQuotaStoredValue(Context context) {
        return prefs(context).getString(KEY_STORAGE_QUOTA_MB, QUOTA_VALUE_MAX);
    }

    public static boolean isMaxStorageQuota(Context context) {
        return QUOTA_VALUE_MAX.equals(getStorageQuotaStoredValue(context));
    }

    public static int getQuotaWarningThresholdMb(Context context) {
        return Math.max(50, getStorageQuotaMb(context) / 5);
    }

    public static boolean isCyclicRecordingEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CYCLIC_RECORDING, true);
    }

    public static String getAppMode(Context context) {
        return prefs(context).getString(KEY_APP_MODE, MODE_VIDEO);
    }

    public static boolean isVideoMode(Context context) {
        return MODE_VIDEO.equals(getAppMode(context));
    }

    public static boolean isAudioMode(Context context) {
        return MODE_AUDIO.equals(getAppMode(context));
    }

    public static boolean isAudioMarkerMode(Context context) {
        return MODE_AUDIO_MARKER.equals(getAppMode(context));
    }

    public static boolean usesMp3Capture(Context context) {
        return isAudioMode(context) || isAudioMarkerMode(context);
    }

    public static boolean usesLocation(Context context) {
        return isVideoMode(context) || isAudioMarkerMode(context);
    }

    public static boolean isVideoRecordAudioEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VIDEO_RECORD_AUDIO, true);
    }

    public static String getVideoOrientationMode(Context context) {
        return prefs(context).getString(
                KEY_VIDEO_ORIENTATION,
                RecordingOrientationHelper.ORIENTATION_AUTO
        );
    }

    public static RecordingMediaType getActiveMediaType(Context context) {
        String mode = getAppMode(context);
        if (MODE_AUDIO.equals(mode)) {
            return RecordingMediaType.AUDIO;
        }
        if (MODE_AUDIO_MARKER.equals(mode)) {
            return RecordingMediaType.AUDIO_MARKER;
        }
        return RecordingMediaType.VIDEO;
    }

    public static String getAppModeSummary(Context context) {
        String value = prefs(context).getString(KEY_APP_MODE, MODE_VIDEO);
        String[] values = context.getResources().getStringArray(R.array.pref_app_mode_values);
        String[] entries = context.getResources().getStringArray(R.array.pref_app_mode_entries);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return entries[i];
            }
        }
        return entries[0];
    }

    public static QualitySelector getQualitySelector(Context context) {
        Quality preferred = mapResolutionToQuality(
                prefs(context).getString(KEY_VIDEO_RESOLUTION, DEFAULT_RESOLUTION)
        );
        List<Quality> qualities = Arrays.asList(
                preferred,
                Quality.FHD,
                Quality.HD,
                Quality.SD
        );
        return QualitySelector.fromOrderedList(
                qualities,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        );
    }

    public static String getSegmentDurationSummary(Context context) {
        int minutes = getSegmentDurationMinutes(context);
        if (minutes >= 60 && minutes % 60 == 0) {
            return context.getString(R.string.pref_summary_segment_duration_hours, minutes / 60);
        }
        return context.getString(R.string.pref_summary_segment_duration, minutes);
    }

    public static String getStorageQuotaSummary(Context context) {
        if (isMaxStorageQuota(context)) {
            int availableMb = StorageQuotaHelper.getAvailableRecordingSpaceMb(context);
            return context.getString(
                    R.string.pref_storage_quota_max,
                    StorageQuotaHelper.formatStorageSize(context, availableMb)
            );
        }
        return StorageQuotaHelper.formatStorageSize(context, getStorageQuotaMb(context));
    }

    public static String getVideoResolutionSummary(Context context) {
        String value = prefs(context).getString(KEY_VIDEO_RESOLUTION, DEFAULT_RESOLUTION);
        String[] values = context.getResources().getStringArray(R.array.pref_video_resolution_values);
        String[] entries = context.getResources().getStringArray(R.array.pref_video_resolution_entries);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return entries[i];
            }
        }
        return entries[1];
    }

    private static Quality mapResolutionToQuality(String value) {
        if (value == null) {
            return Quality.HD;
        }
        switch (value) {
            case "480":
                return Quality.SD;
            case "1080":
                return Quality.FHD;
            case "2160":
                return Quality.UHD;
            case "720":
            default:
                return Quality.HD;
        }
    }

    private static String formatStorageSize(Context context, int sizeMb) {
        return StorageQuotaHelper.formatStorageSize(context, sizeMb);
    }
}
