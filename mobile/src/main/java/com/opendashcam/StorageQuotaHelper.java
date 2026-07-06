package com.opendashcam;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class StorageQuotaHelper {
    private static final int MIN_QUOTA_MB = 256;
    private static final int MIN_RESERVE_MB = 1024;
    private static final int RESERVE_PERCENT = 10;
    private static final int[] QUOTA_STEPS_MB = {
            256, 512, 1024, 2048, 5120, 10240, 20480, 51200, 102400
    };

    private StorageQuotaHelper() {
    }

    private static final class VolumeInfo {
        final long totalMb;
        final long freeMb;

        VolumeInfo(long totalMb, long freeMb) {
            this.totalMb = totalMb;
            this.freeMb = freeMb;
        }
    }

    public static final class QuotaOptionList {
        public final CharSequence[] entries;
        public final CharSequence[] values;
        public final int availableMb;

        QuotaOptionList(CharSequence[] entries, CharSequence[] values, int availableMb) {
            this.entries = entries;
            this.values = values;
            this.availableMb = availableMb;
        }
    }

    /** Space allocatable to recordings while keeping a disk reserve for the system. */
    public static int getAvailableRecordingSpaceMb(Context context) {
        long existingBytes = 0;
        for (String path : StorageHelper.listRecordingPaths(context)) {
            existingBytes += StorageHelper.getRecordingSizeBytes(context, path);
        }
        long existingMb = existingBytes / (1024 * 1024);

        VolumeInfo volume = getVolumeInfoForRecordings(context);
        long reserveMb = getRequiredFreeReserveMb(volume.totalMb);
        long usableFreeMb = Math.max(0, volume.freeMb - reserveMb);
        long available = existingMb + usableFreeMb;
        if (available > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0, available);
    }

    private static long getRequiredFreeReserveMb(long totalMb) {
        long percentReserveMb = (totalMb * RESERVE_PERCENT) / 100;
        return Math.max(percentReserveMb, MIN_RESERVE_MB);
    }

    public static QuotaOptionList buildOptions(Context context) {
        int availableMb = getAvailableRecordingSpaceMb(context);
        List<CharSequence> entries = new ArrayList<>();
        List<CharSequence> values = new ArrayList<>();

        if (availableMb >= MIN_QUOTA_MB) {
            entries.add(context.getString(
                    R.string.pref_storage_quota_max,
                    formatStorageSize(context, availableMb)
            ));
            values.add(RecordingPreferences.QUOTA_VALUE_MAX);
        }

        for (int stepMb : QUOTA_STEPS_MB) {
            if (stepMb <= availableMb) {
                entries.add(formatStorageSize(context, stepMb));
                values.add(String.valueOf(stepMb));
            }
        }

        if (entries.isEmpty()) {
            int fallback = Math.max(1, availableMb);
            entries.add(formatStorageSize(context, fallback));
            values.add(String.valueOf(fallback));
        }

        return new QuotaOptionList(
                entries.toArray(new CharSequence[0]),
                values.toArray(new CharSequence[0]),
                availableMb
        );
    }

    public static int resolveQuotaMb(Context context, String storedValue) {
        int availableMb = getAvailableRecordingSpaceMb(context);
        if (RecordingPreferences.QUOTA_VALUE_MAX.equals(storedValue)) {
            return Math.max(MIN_QUOTA_MB, availableMb);
        }
        try {
            int requested = Integer.parseInt(storedValue);
            if (availableMb <= 0) {
                return Math.max(MIN_QUOTA_MB, requested);
            }
            return Math.min(requested, availableMb);
        } catch (NumberFormatException e) {
            return Math.min(RecordingPreferences.DEFAULT_QUOTA_MB, Math.max(MIN_QUOTA_MB, availableMb));
        }
    }

    public static String formatStorageSize(Context context, int sizeMb) {
        if (sizeMb >= 1024) {
            int gb = sizeMb / 1024;
            int remainderMb = sizeMb % 1024;
            if (remainderMb >= 512) {
                return context.getString(R.string.pref_storage_size_gb, gb + 1);
            }
            return context.getString(R.string.pref_storage_size_gb, gb);
        }
        return context.getString(R.string.pref_storage_size_mb, sizeMb);
    }

    public static String normalizeStoredValue(Context context, String storedValue) {
        QuotaOptionList options = buildOptions(context);
        for (CharSequence value : options.values) {
            if (value.toString().equals(storedValue)) {
                return storedValue;
            }
        }
        if (RecordingPreferences.QUOTA_VALUE_MAX.equals(storedValue)
                && options.availableMb >= MIN_QUOTA_MB) {
            return storedValue;
        }
        if (options.values.length > 0) {
            return options.values[0].toString();
        }
        return String.valueOf(Math.max(MIN_QUOTA_MB, options.availableMb));
    }

    private static VolumeInfo getVolumeInfoForRecordings(Context context) {
        File path = getStoragePathForRecordings(context);
        return getVolumeInfoFromPath(path);
    }

    private static File getStoragePathForRecordings(Context context) {
        if (StorageHelper.hasCustomFolder(context)) {
            DocumentFile folder = DocumentFile.fromTreeUri(context, StorageHelper.getCustomFolderUri(context));
            if (folder != null && folder.getUri() != null) {
                File path = resolvePathFromUri(context, folder.getUri().getPath());
                if (path != null && path.exists()) {
                    return path;
                }
            }
        } else {
            File videoDir = StorageHelper.getVideoDirectory(context);
            if (videoDir != null) {
                File storageRoot = videoDir.getParentFile();
                if (storageRoot != null) {
                    return storageRoot;
                }
                return videoDir;
            }
        }
        return Environment.getExternalStorageDirectory();
    }

    private static VolumeInfo getVolumeInfoFromPath(File path) {
        if (path == null || !path.exists()) {
            return new VolumeInfo(0, 0);
        }
        try {
            StatFs statFs = new StatFs(path.getAbsolutePath());
            long totalMb = statFs.getTotalBytes() / (1024 * 1024);
            long freeMb = statFs.getAvailableBytes() / (1024 * 1024);
            return new VolumeInfo(totalMb, freeMb);
        } catch (IllegalArgumentException e) {
            return new VolumeInfo(0, 0);
        }
    }

    private static File resolvePathFromUri(Context context, String uriPath) {
        if (uriPath == null) {
            return null;
        }
        if (uriPath.startsWith("/tree/primary:")) {
            String relative = uriPath.substring("/tree/primary:".length());
            File base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (base != null) {
                File parent = base.getParentFile();
                if (parent != null) {
                    return new File(parent, relative.replace(':', '/'));
                }
            }
            return new File(Environment.getExternalStorageDirectory(), relative.replace(':', '/'));
        }
        if (uriPath.startsWith("/tree/")) {
            int colon = uriPath.indexOf(':');
            if (colon > 0 && colon + 1 < uriPath.length()) {
                return new File("/storage/" + uriPath.substring(colon + 1).replace(':', '/'));
            }
        }
        return null;
    }
}
