package com.opendashcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.opendashcam.models.Recording;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Util {
    public static final String ACTION_UPDATE_RECORDINGS_LIST = "update.recordings.list";
    public static final String ACTION_HIDE_OVERLAY = "hide.overlay";
    public static final String ACTION_SHOW_OVERLAY = "show.overlay";
    public static final int RECORDER_NOTIFICATION_ID = 51288;
    public static final int WIDGET_NOTIFICATION_ID = 51289;

    private static final String NOTIFICATIONS_CHANNEL_ID = "open_dash_cam_main";

    private static String getNotificationChannelName(Context context) {
        return context.getString(R.string.notification_channel_name);
    }

    public static int getQuota() {
        return RecordingPreferences.getStorageQuotaMb(OpenDashApp.getAppContext());
    }

    public static int getQuotaWarningThreshold() {
        return RecordingPreferences.getQuotaWarningThresholdMb(OpenDashApp.getAppContext());
    }

    public static int getMaxDuration() {
        return (int) RecordingPreferences.getSegmentDurationMs(OpenDashApp.getAppContext());
    }

    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newSingleThreadExecutor();

    private Util() {
    }

    public static File getVideosDirectoryPath() {
        return StorageHelper.getVideosDirectoryPath(OpenDashApp.getAppContext());
    }

    public static long getFolderSizeMb(File file) {
        long sizeBytes = getFolderSizeBytes(file);
        return sizeBytes / (1024 * 1024);
    }

    private static long getFolderSizeBytes(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        long size = 0;
        File[] children = file.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            size += getFolderSizeBytes(child);
        }
        return size;
    }

    public static long getFreeSpaceMb(File storagePath) {
        if (storagePath == null || !storagePath.exists()) {
            return 0;
        }
        return storagePath.getFreeSpace() / (1024 * 1024);
    }

    public static void showToast(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }

    public static void showToastLong(Context context, String msg) {
        final Toast tag = Toast.makeText(context, msg, Toast.LENGTH_SHORT);
        tag.show();
        new CountDownTimer(9000, 1000) {
            public void onTick(long millisUntilFinished) {
                tag.show();
            }

            public void onFinish() {
                tag.show();
            }
        }.start();
    }

    public static void openFile(Context context, Uri file, String mimeType) {
        Intent openFile = new Intent(Intent.ACTION_VIEW);
        openFile.setDataAndType(file, mimeType);
        openFile.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        openFile.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(openFile);
        } catch (ActivityNotFoundException e) {
            Context appContext = context.getApplicationContext();
            Log.i("OpenDashCam", appContext.getString(R.string.cannot_open_file));
        }
    }

    public static void deleteRecordings() {
        BACKGROUND_EXECUTOR.execute(new DeleteRecordingsTask());
    }

    public static void updateStar(Recording recording) {
        BACKGROUND_EXECUTOR.execute(new UpdateStarTask(recording));
    }

    public static void deleteSingleRecording(Recording recording) {
        if (recording == null) {
            return;
        }
        StorageHelper.deleteRecordingAtPath(OpenDashApp.getAppContext(), recording.getFilePath());
        DBHelper.getInstance(OpenDashApp.getAppContext()).deleteRecording(recording);
        LocalBroadcastManager.getInstance(OpenDashApp.getAppContext()).sendBroadcast(
                new Intent(ACTION_UPDATE_RECORDINGS_LIST)
        );
    }

    public static void insertNewRecording(Recording recording) {
        if (recording == null) {
            return;
        }
        if (!DBHelper.getInstance(OpenDashApp.getAppContext()).insertNewRecording(recording)) {
            return;
        }
        LocalBroadcastManager.getInstance(OpenDashApp.getAppContext()).sendBroadcast(
                new Intent(ACTION_UPDATE_RECORDINGS_LIST)
        );
    }

    public static void restartBackgroundRecorder(Context context) {
        Context appContext = context.getApplicationContext();
        appContext.stopService(new Intent(appContext, BackgroundVideoRecorder.class));
        new Handler(Looper.getMainLooper()).post(() ->
                ContextCompat.startForegroundService(
                        appContext,
                        new Intent(appContext, BackgroundVideoRecorder.class)
                )
        );
    }

    public static void hideOverlay(Context context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
                new Intent(ACTION_HIDE_OVERLAY)
        );
    }

    public static void showOverlay(Context context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
                new Intent(ACTION_SHOW_OVERLAY)
        );
    }

    public static boolean isWidgetServiceRunning(Context context) {
        android.app.ActivityManager manager =
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (WidgetService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBackgroundRecorderRunning(Context context) {
        android.app.ActivityManager manager =
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (BackgroundVideoRecorder.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static int foregroundServiceTypeMicrophone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        return 0;
    }

    public static int foregroundServiceTypeMicrophoneAndLocation(Context context) {
        int type = foregroundServiceTypeMicrophone();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && hasLocationPermission(context)) {
            type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        return type;
    }

    public static int getRecorderForegroundServiceType(Context context) {
        if (RecordingPreferences.isAudioMode(context)) {
            return foregroundServiceTypeMicrophone();
        }
        if (RecordingPreferences.isAudioMarkerMode(context)) {
            return foregroundServiceTypeMicrophoneAndLocation(context);
        }
        return foregroundServiceTypeCameraMicrophoneAndLocation(context);
    }

    public static boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static Notification createStatusBarNotification(Context context) {
        int titleRes;
        int textRes;
        if (RecordingPreferences.isAudioMode(context)) {
            titleRes = R.string.notification_title_audio;
            textRes = R.string.notification_text_audio;
        } else if (RecordingPreferences.isAudioMarkerMode(context)) {
            titleRes = R.string.notification_title_audio_marker;
            textRes = R.string.notification_text_audio_marker;
        } else {
            titleRes = R.string.notification_title;
            textRes = R.string.notification_text;
        }
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(
                context,
                NOTIFICATIONS_CHANNEL_ID)
                .setContentTitle(context.getResources().getString(titleRes))
                .setContentText(context.getResources().getString(textRes))
                .setSmallIcon(R.drawable.ic_videocam_red_128dp)
                .setOngoing(true)
                .setAutoCancel(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATIONS_CHANNEL_ID,
                    getNotificationChannelName(context),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.enableVibration(false);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }

        return notificationBuilder.build();
    }

    public static int foregroundServiceTypeCameraAndMicrophone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        return 0;
    }

    public static int foregroundServiceTypeCameraMicrophoneAndLocation(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && hasLocationPermission(context)) {
                type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            return type;
        }
        return 0;
    }

    public static int foregroundServiceTypeSpecialUse() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        return 0;
    }

    public static void startForeground(Service service, int notificationId, Notification notification, int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            try {
                service.startForeground(notificationId, notification, type);
                return;
            } catch (RuntimeException e) {
                Log.w(Util.class.getSimpleName(), "startForeground failed with type flags, retrying", e);
                int fallbackType = type & ~android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                if (fallbackType != type && fallbackType != 0) {
                    try {
                        service.startForeground(notificationId, notification, fallbackType);
                        return;
                    } catch (RuntimeException retryError) {
                        Log.w(Util.class.getSimpleName(), "startForeground fallback failed", retryError);
                    }
                }
            }
        }
        service.startForeground(notificationId, notification);
    }

    public static void stopForeground(Service service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            service.stopForeground(true);
        }
    }

    private static File getAppPrivateVideosFolder(Context context) {
        try {
            File[] extAppFolders = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MOVIES);
            if (extAppFolders == null) {
                return null;
            }

            for (File file : extAppFolders) {
                if (file != null
                        && !file.getAbsolutePath().toLowerCase().contains("emulated")
                        && isStorageMounted(file)) {
                    return file;
                }
            }

            for (int i = extAppFolders.length - 1; i >= 0; i--) {
                File appFolder = extAppFolders[i];
                if (appFolder != null && isStorageMounted(appFolder)) {
                    return appFolder;
                }
            }
        } catch (Exception e) {
            Log.e(Util.class.getSimpleName(), "getAppPrivateVideosFolder failed", e);
        }
        return null;
    }

    private static boolean isStorageMounted(File storagePath) {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(storagePath));
    }

    private static boolean removeNonEmptyDirectory(File path) {
        if (path == null || !path.exists()) {
            return false;
        }
        File[] files = path.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    removeNonEmptyDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return path.delete();
    }

    private static class DeleteRecordingsTask implements Runnable {
        @Override
        public void run() {
            Context context = OpenDashApp.getAppContext();
            DBHelper dbHelper = DBHelper.getInstance(context);
            ArrayList<Recording> recordingsList = dbHelper.selectAllRecordingsList();
            boolean result = dbHelper.deleteAllRecordings();

            if (result) {
                for (Recording recording : recordingsList) {
                    StorageHelper.deleteRecordingAtPath(context, recording.getFilePath());
                }
            }

            Resources res = context.getResources();
            showToastLong(
                    context,
                    result
                            ? res.getString(R.string.pref_delete_recordings_confirmation)
                            : res.getString(R.string.recordings_list_empty_message_title)
            );
        }
    }

    private static class UpdateStarTask implements Runnable {
        private final Recording recording;

        UpdateStarTask(Recording recording) {
            this.recording = recording;
        }

        @Override
        public void run() {
            DBHelper.getInstance(OpenDashApp.getAppContext()).updateStar(recording);
            LocalBroadcastManager.getInstance(OpenDashApp.getAppContext()).sendBroadcast(
                    new Intent(ACTION_UPDATE_RECORDINGS_LIST)
            );
        }
    }
}
