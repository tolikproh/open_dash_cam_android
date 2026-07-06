package com.opendashcam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class StorageHelper {
    private static final String TAG = "StorageHelper";
    private static final String PREF_CUSTOM_FOLDER_URI = "recordings_folder_uri";
    private static final String DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss";

    private StorageHelper() {
    }

    public static File getVideoDirectory(Context context) {
        return ensureDirectory(getMediaDirectory(context, RecordingMediaType.VIDEO));
    }

    public static File getAudioDirectory(Context context) {
        return ensureDirectory(getMediaDirectory(context, RecordingMediaType.AUDIO));
    }

    public static File getAudioMarkerDirectory(Context context) {
        return ensureDirectory(getMediaDirectory(context, RecordingMediaType.AUDIO_MARKER));
    }

    public static File getDirectoryForType(Context context, RecordingMediaType type) {
        return ensureDirectory(getMediaDirectory(context, type));
    }

    /** @deprecated use {@link #getVideoDirectory(Context)} */
    public static File getVideosDirectoryPath(Context context) {
        return getVideoDirectory(context);
    }

    public static File getDefaultVideosDirectory(Context context) {
        return getVideoDirectory(context);
    }

    public static boolean hasCustomFolder(Context context) {
        return !TextUtils.isEmpty(getCustomFolderUriString(context));
    }

    public static Uri getCustomFolderUri(Context context) {
        String uri = getCustomFolderUriString(context);
        return TextUtils.isEmpty(uri) ? null : Uri.parse(uri);
    }

    public static void setCustomFolderUri(Context context, Uri uri) {
        if (uri == null) {
            clearCustomFolder(context);
            return;
        }
        final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        context.getContentResolver().takePersistableUriPermission(uri, flags);
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_CUSTOM_FOLDER_URI, uri.toString())
                .apply();
    }

    public static void clearCustomFolder(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove(PREF_CUSTOM_FOLDER_URI)
                .apply();
    }

    public static String getFolderSummary(Context context) {
        if (hasCustomFolder(context)) {
            DocumentFile folder = DocumentFile.fromTreeUri(context, getCustomFolderUri(context));
            if (folder != null && folder.getName() != null) {
                return folder.getName() + " (Video / Audio / AudioMarker)";
            }
            return context.getString(R.string.pref_recordings_folder_custom);
        }
        File videoDir = getVideoDirectory(context);
        File audioDir = getAudioDirectory(context);
        File audioMarkerDir = getAudioMarkerDirectory(context);
        if (videoDir != null && audioDir != null && audioMarkerDir != null) {
            return videoDir.getParent() + "/Video, Audio, AudioMarker";
        }
        return context.getString(R.string.pref_recordings_folder_default);
    }

    public static String formatRecordingStartTime(long timeMs) {
        return new SimpleDateFormat(DATE_FORMAT, Locale.US).format(new Date(timeMs));
    }

    public static String buildHiddenFileName(long startTimeMs, RecordingMediaType type) {
        return "." + formatRecordingStartTime(startTimeMs) + "." + type.getHiddenExtension();
    }

    public static String buildProcessingHiddenFileName(long startTimeMs, RecordingMediaType type) {
        return ".processing." + formatRecordingStartTime(startTimeMs) + "." + type.getExtension();
    }

    public static String buildVisibleFileName(long startTimeMs, RecordingMediaType type) {
        return formatRecordingStartTime(startTimeMs) + "." + type.getExtension();
    }

    public static File createProcessingFile(Context context, long startTimeMs, RecordingMediaType type) {
        return new File(context.getCacheDir(), buildProcessingHiddenFileName(startTimeMs, type));
    }

    public static File createWaveformProcessingFile(Context context, long startTimeMs, RecordingMediaType type) {
        String name = ".processing.waveform."
                + formatRecordingStartTime(startTimeMs)
                + "."
                + type.getExtension();
        return new File(context.getCacheDir(), name);
    }

    public static void clearActiveRecordingPaths(Context context) {
        SharedPreferences sharedPref = context.getApplicationContext().getSharedPreferences(
                context.getString(R.string.current_recordings_preferences_key),
                Context.MODE_PRIVATE
        );
        sharedPref.edit()
                .putString(context.getString(R.string.current_recording_preferences_key), "null")
                .putString(context.getString(R.string.previous_recording_preferences_key), "null")
                .apply();
    }

    public static boolean isHiddenRecordingFileName(String name) {
        return !TextUtils.isEmpty(name) && name.startsWith(".");
    }

    public static File createHiddenRecordingFile(Context context, long startTimeMs, RecordingMediaType type) {
        String hiddenName = buildHiddenFileName(startTimeMs, type);
        if (hasCustomFolder(context)) {
            return new File(context.getCacheDir(), hiddenName);
        }
        File dir = getDirectoryForType(context, type);
        return new File(dir, hiddenName);
    }

    public static String finalizeRecording(
            Context context,
            File hiddenFile,
            long startTimeMs,
            RecordingMediaType type
    ) {
        if (hiddenFile == null || !hiddenFile.exists()) {
            return null;
        }
        String visibleName = buildVisibleFileName(startTimeMs, type);
        if (hasCustomFolder(context)) {
            String path = copyToCustomFolder(context, hiddenFile, visibleName, type);
            if (path != null && path.startsWith("content://")) {
                return path;
            }
            Log.e(TAG, "Failed to copy recording to custom folder");
            return null;
        }

        File destDir = getDirectoryForType(context, type);
        if (destDir == null) {
            return null;
        }

        File visibleFile = new File(destDir, visibleName);
        if (visibleFile.exists() && !visibleFile.delete()) {
            Log.w(TAG, "Could not replace existing visible recording: " + visibleName);
        }

        if (moveFile(hiddenFile, visibleFile)) {
            return visibleFile.getAbsolutePath();
        }

        Log.e(TAG, "Failed to finalize recording, keeping hidden file: " + hiddenFile.getAbsolutePath());
        return null;
    }

    public static List<String> listRecordingPaths(Context context) {
        List<String> paths = new ArrayList<>();
        paths.addAll(listPathsForType(context, RecordingMediaType.VIDEO));
        paths.addAll(listPathsForType(context, RecordingMediaType.AUDIO));
        paths.addAll(listPathsForType(context, RecordingMediaType.AUDIO_MARKER));
        return paths;
    }

    public static void recoverOrphanedHiddenRecordings(Context context) {
        recoverOrphanedHiddenRecordings(context, RecordingMediaType.VIDEO);
        recoverOrphanedHiddenRecordings(context, RecordingMediaType.AUDIO);
        recoverOrphanedHiddenRecordings(context, RecordingMediaType.AUDIO_MARKER);
        if (hasCustomFolder(context)) {
            recoverOrphanedHiddenRecordings(context.getCacheDir(), context, RecordingMediaType.VIDEO);
            recoverOrphanedHiddenRecordings(context.getCacheDir(), context, RecordingMediaType.AUDIO);
            recoverOrphanedHiddenRecordings(context.getCacheDir(), context, RecordingMediaType.AUDIO_MARKER);
        }
    }

    private static void recoverOrphanedHiddenRecordings(Context context, RecordingMediaType type) {
        recoverOrphanedHiddenRecordings(getDirectoryForType(context, type), context, type);
    }

    private static void recoverOrphanedHiddenRecordings(
            File directory,
            Context context,
            RecordingMediaType type
    ) {
        if (directory == null || !directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        String activeRecordingPath = getActiveRecordingPath(context);
        for (File hiddenFile : files) {
            if (!hiddenFile.isFile() || !isHiddenRecordingFileName(hiddenFile.getName())) {
                continue;
            }
            if (hiddenFile.getName().contains(".processing.")) {
                if (type == RecordingMediaType.AUDIO_MARKER) {
                    recoverOrphanedAudioMarkerProcessingFile(context, hiddenFile, activeRecordingPath);
                }
                continue;
            }
            if (type == RecordingMediaType.AUDIO_MARKER) {
                recoverOrphanedAudioMarkerFile(context, hiddenFile, activeRecordingPath);
                continue;
            }
            if (!hiddenFile.getName().endsWith("." + type.getHiddenExtension())) {
                continue;
            }
            if (activeRecordingPath != null
                    && activeRecordingPath.equals(hiddenFile.getAbsolutePath())
                    && Util.isBackgroundRecorderRunning(context)) {
                continue;
            }
            if (hiddenFile.length() <= 0) {
                deleteQuietly(hiddenFile);
                continue;
            }
            long segmentStartMs = parseRecordingStartMs(hiddenFile.getName(), type);
            if (segmentStartMs <= 0) {
                continue;
            }
            String finalPath = finalizeRecording(context, hiddenFile, segmentStartMs, type);
            if (finalPath != null) {
                Util.insertNewRecording(new com.opendashcam.models.Recording(finalPath));
            }
        }
    }

    private static void recoverOrphanedAudioMarkerFile(
            Context context,
            File hiddenFile,
            String activeRecordingPath
    ) {
        boolean isMp3 = hiddenFile.getName().endsWith("." + RecordingMediaType.AUDIO_MARKER.getHiddenExtension());
        boolean isMp4 = hiddenFile.getName().endsWith("." + RecordingMediaType.AUDIO_MARKER.getExtension());
        if (!isMp3 && !isMp4) {
            return;
        }
        if (activeRecordingPath != null
                && activeRecordingPath.equals(hiddenFile.getAbsolutePath())
                && Util.isBackgroundRecorderRunning(context)) {
            return;
        }
        if (hiddenFile.length() <= 0) {
            deleteQuietly(hiddenFile);
            return;
        }

        RecordingMediaType type = RecordingMediaType.AUDIO_MARKER;
        long segmentStartMs = parseRecordingStartMs(
                hiddenFile.getName(),
                isMp3 ? type.getHiddenExtension() : type.getExtension()
        );
        if (segmentStartMs <= 0) {
            return;
        }

        File toFinalize = hiddenFile;
        if (isMp3) {
            RecordingPostProcessor.execute(context, () ->
                    finalizeRecoveredAudioMarkerMp3(context, hiddenFile, segmentStartMs)
            );
            return;
        }

        String finalPath = finalizeRecording(context, toFinalize, segmentStartMs, type);
        if (finalPath != null) {
            Util.insertNewRecording(new com.opendashcam.models.Recording(finalPath));
        }
    }

    private static void finalizeRecoveredAudioMarkerMp3(
            Context context,
            File hiddenMp3,
            long segmentStartMs
    ) {
        RecordingLocationSnapshot snapshot = RecordingLocationSnapshot.from(
                new RecordingLocationTracker(context)
        );
        File processed = AudioMarkerBurner.burn(context, hiddenMp3, snapshot, segmentStartMs);
        if (processed == null || !processed.exists()
                || processed.getName().toLowerCase().endsWith(".mp3")) {
            Log.e(TAG, "Recovery failed for audio marker MP3: " + hiddenMp3.getName());
            return;
        }
        if (!processed.equals(hiddenMp3)) {
            deleteTemporaryRecordingFile(hiddenMp3);
        }
        String finalPath = finalizeRecording(
                context,
                processed,
                segmentStartMs,
                RecordingMediaType.AUDIO_MARKER
        );
        if (finalPath != null) {
            Util.insertNewRecording(new com.opendashcam.models.Recording(finalPath));
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(context.getApplicationContext())
                    .sendBroadcast(new android.content.Intent(Util.ACTION_UPDATE_RECORDINGS_LIST));
        }
    }

    private static void recoverOrphanedAudioMarkerProcessingFile(
            Context context,
            File hiddenFile,
            String activeRecordingPath
    ) {
        if (!hiddenFile.getName().endsWith("." + RecordingMediaType.AUDIO_MARKER.getExtension())) {
            return;
        }
        if (activeRecordingPath != null
                && activeRecordingPath.equals(hiddenFile.getAbsolutePath())
                && Util.isBackgroundRecorderRunning(context)) {
            return;
        }
        if (hiddenFile.length() <= 0) {
            deleteQuietly(hiddenFile);
            return;
        }

        long segmentStartMs = parseProcessingRecordingStartMs(hiddenFile.getName());
        if (segmentStartMs <= 0) {
            return;
        }

        boolean isWaveform = hiddenFile.getName().contains(".processing.waveform.");
        if (isWaveform) {
            RecordingPostProcessor.execute(context, () ->
                    finalizeRecoveredAudioMarkerWaveform(context, hiddenFile, segmentStartMs)
            );
            return;
        }

        String finalPath = finalizeRecording(
                context,
                hiddenFile,
                segmentStartMs,
                RecordingMediaType.AUDIO_MARKER
        );
        if (finalPath != null) {
            Util.insertNewRecording(new com.opendashcam.models.Recording(finalPath));
        }
    }

    private static void finalizeRecoveredAudioMarkerWaveform(
            Context context,
            File waveformFile,
            long segmentStartMs
    ) {
        RecordingLocationSnapshot snapshot = RecordingLocationSnapshot.from(
                new RecordingLocationTracker(context)
        );
        File watermarked = VideoWatermarkBurner.burn(
                context,
                waveformFile,
                snapshot,
                segmentStartMs,
                RecordingMediaType.AUDIO_MARKER
        );
        if (watermarked == null || !watermarked.exists() || watermarked.equals(waveformFile)) {
            Log.e(TAG, "Recovery watermark failed for: " + waveformFile.getName());
            return;
        }
        deleteTemporaryRecordingFile(waveformFile);
        String finalPath = finalizeRecording(
                context,
                watermarked,
                segmentStartMs,
                RecordingMediaType.AUDIO_MARKER
        );
        if (finalPath != null) {
            Util.insertNewRecording(new com.opendashcam.models.Recording(finalPath));
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(context.getApplicationContext())
                    .sendBroadcast(new android.content.Intent(Util.ACTION_UPDATE_RECORDINGS_LIST));
        }
    }

    private static long parseProcessingRecordingStartMs(String fileName) {
        String baseName = fileName;
        if (baseName.startsWith(".")) {
            baseName = baseName.substring(1);
        }
        String prefix = "processing.waveform.";
        if (baseName.startsWith(prefix)) {
            baseName = baseName.substring(prefix.length());
        } else if (baseName.startsWith("processing.")) {
            baseName = baseName.substring("processing.".length());
        } else {
            return 0;
        }
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return 0;
        }
        String timestamp = baseName.substring(0, dotIndex);
        try {
            Date parsed = new SimpleDateFormat(DATE_FORMAT, Locale.US).parse(timestamp);
            return parsed != null ? parsed.getTime() : 0;
        } catch (ParseException e) {
            return 0;
        }
    }

    private static long parseRecordingStartMs(String fileName, RecordingMediaType type) {
        return parseRecordingStartMs(fileName, type.getHiddenExtension());
    }

    private static long parseRecordingStartMs(String fileName, String extension) {
        String baseName = fileName;
        if (baseName.startsWith(".")) {
            baseName = baseName.substring(1);
        }
        if (baseName.startsWith("processing.")) {
            return 0;
        }
        String suffix = "." + extension;
        if (!baseName.endsWith(suffix)) {
            return 0;
        }
        String timestamp = baseName.substring(0, baseName.length() - suffix.length());
        try {
            Date parsed = new SimpleDateFormat(DATE_FORMAT, Locale.US).parse(timestamp);
            return parsed != null ? parsed.getTime() : 0;
        } catch (ParseException e) {
            return 0;
        }
    }

    public static boolean deleteRecordingAtPath(Context context, String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        if (path.startsWith("content://")) {
            DocumentFile file = DocumentFile.fromSingleUri(context, Uri.parse(path));
            return file != null && file.delete();
        }
        return new File(path).delete();
    }

    public static Uri toContentUri(Context context, String path) {
        if (path.startsWith("content://")) {
            return Uri.parse(path);
        }
        return androidx.core.content.FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".provider",
                new File(path)
        );
    }

    public static long getRecordingLastModified(Context context, String path) {
        if (TextUtils.isEmpty(path)) {
            return 0;
        }
        if (path.startsWith("content://")) {
            DocumentFile file = DocumentFile.fromSingleUri(context, Uri.parse(path));
            return file != null ? file.lastModified() : 0;
        }
        return new File(path).lastModified();
    }

    public static long getRecordingSizeBytes(Context context, String path) {
        if (TextUtils.isEmpty(path)) {
            return 0;
        }
        if (path.startsWith("content://")) {
            DocumentFile file = DocumentFile.fromSingleUri(context, Uri.parse(path));
            return file != null ? file.length() : 0;
        }
        return new File(path).length();
    }

    public static Intent createFolderPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    private static List<String> listPathsForType(Context context, RecordingMediaType type) {
        List<String> paths = new ArrayList<>();
        if (hasCustomFolder(context)) {
            DocumentFile subfolder = getCustomSubfolder(context, type, true);
            if (subfolder == null) {
                return paths;
            }
            DocumentFile[] files = subfolder.listFiles();
            if (files == null) {
                return paths;
            }
            for (DocumentFile file : files) {
                if (file.isFile() && isVisibleRecording(file.getName(), type)) {
                    paths.add(file.getUri().toString());
                }
            }
            return paths;
        }

        File dir = getDirectoryForType(context, type);
        if (dir == null) {
            return paths;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return paths;
        }
        for (File file : files) {
            if (file.isFile() && isVisibleRecording(file.getName(), type)) {
                paths.add(file.getAbsolutePath());
            }
        }
        return paths;
    }

    private static String copyToCustomFolder(
            Context context,
            File hiddenFile,
            String visibleName,
            RecordingMediaType type
    ) {
        DocumentFile subfolder = getCustomSubfolder(context, type, true);
        if (subfolder == null) {
            Log.e(TAG, "Custom subfolder unavailable: " + type.getFolderName());
            return null;
        }

        DocumentFile existing = subfolder.findFile(visibleName);
        if (existing != null) {
            existing.delete();
        }

        DocumentFile target = subfolder.createFile(type.getMimeType(), visibleName);
        if (target == null) {
            Log.e(TAG, "Failed to create file in custom folder: " + visibleName);
            return null;
        }

        try {
            copyStreams(new FileInputStream(hiddenFile), context.getContentResolver().openOutputStream(target.getUri()));
            if (!hiddenFile.delete()) {
                Log.w(TAG, "Unable to delete temp file after copy: " + hiddenFile.getAbsolutePath());
            }
            return target.getUri().toString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy recording to custom folder", e);
            return null;
        }
    }

    private static DocumentFile getCustomSubfolder(Context context, RecordingMediaType type, boolean create) {
        DocumentFile root = DocumentFile.fromTreeUri(context, getCustomFolderUri(context));
        if (root == null) {
            return null;
        }
        DocumentFile sub = root.findFile(type.getFolderName());
        if (sub != null && sub.isDirectory()) {
            return sub;
        }
        if (!create) {
            return null;
        }
        return root.createDirectory(type.getFolderName());
    }

    private static File getMediaDirectory(Context context, RecordingMediaType type) {
        if (hasCustomFolder(context)) {
            return new File(context.getCacheDir(), type.getFolderName());
        }
        File root = getAppPrivateRootFolder(context);
        if (root == null) {
            return null;
        }
        return new File(root, type.getFolderName());
    }

    private static File ensureDirectory(File dir) {
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static void copyStreams(InputStream in, OutputStream out) throws IOException {
        if (out == null) {
            throw new IOException("Output stream is null");
        }
        try (InputStream input = in; OutputStream output = out) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    public static void deleteTemporaryRecordingFile(File file) {
        deleteQuietly(file);
    }

    static String getActiveRecordingPath(Context context) {
        SharedPreferences sharedPref = context.getApplicationContext().getSharedPreferences(
                context.getString(R.string.current_recordings_preferences_key),
                Context.MODE_PRIVATE
        );
        String path = sharedPref.getString(
                context.getString(R.string.current_recording_preferences_key),
                null
        );
        if (TextUtils.isEmpty(path) || "null".equals(path)) {
            return null;
        }
        return path;
    }

    private static boolean isVisibleRecording(String name, RecordingMediaType type) {
        return !TextUtils.isEmpty(name)
                && name.endsWith("." + type.getExtension())
                && !isHiddenRecordingFileName(name);
    }

    private static boolean moveFile(File source, File target) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        if (source.renameTo(target)) {
            return true;
        }
        try {
            copyStreams(new FileInputStream(source), new FileOutputStream(target));
            deleteQuietly(source);
            return target.exists() && target.length() > 0;
        } catch (IOException e) {
            Log.e(TAG, "Failed to move recording file", e);
            deleteQuietly(target);
            return false;
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete temporary recording file: " + file.getAbsolutePath());
        }
    }

    private static String getCustomFolderUriString(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_CUSTOM_FOLDER_URI, "");
    }

    private static File getAppPrivateRootFolder(Context context) {
        File[] extAppFolders = androidx.core.content.ContextCompat.getExternalFilesDirs(
                context, Environment.DIRECTORY_MOVIES);
        if (extAppFolders == null) {
            return null;
        }
        for (File file : extAppFolders) {
            if (file != null
                    && !file.getAbsolutePath().toLowerCase(Locale.US).contains("emulated")
                    && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(file))) {
                return file;
            }
        }
        for (int i = extAppFolders.length - 1; i >= 0; i--) {
            File appFolder = extAppFolders[i];
            if (appFolder != null
                    && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(appFolder))) {
                return appFolder;
            }
        }
        return null;
    }
}
