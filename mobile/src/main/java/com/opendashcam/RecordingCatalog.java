package com.opendashcam;

import android.content.Context;
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.opendashcam.models.Recording;

import java.util.List;

/** Keeps the recordings database in sync with files on disk. */
public final class RecordingCatalog {
    private RecordingCatalog() {
    }

    public static void syncFromStorage(Context context) {
        Context appContext = context.getApplicationContext();
        StorageHelper.recoverOrphanedHiddenRecordings(appContext);
        DBHelper dbHelper = DBHelper.getInstance(appContext);
        boolean changed = false;

        for (String path : StorageHelper.listRecordingPaths(appContext)) {
            Recording recording = new Recording(path);
            if (!dbHelper.insertNewRecording(recording)) {
                continue;
            }
            changed = true;
        }

        if (changed) {
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(
                    new Intent(Util.ACTION_UPDATE_RECORDINGS_LIST)
            );
        }
    }
}
