package com.opendashcam.presenters;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.opendashcam.DBHelper;
import com.opendashcam.OpenDashApp;
import com.opendashcam.RecordingCatalog;
import com.opendashcam.RecordingMediaType;
import com.opendashcam.StorageHelper;
import com.opendashcam.Util;
import com.opendashcam.models.Recording;

import java.util.ArrayList;

public class ViewRecordingsPresenter implements IViewRecordings.Presenter {

    private static final String LOG_TAG = ViewRecordingsPresenter.class.getSimpleName();

    private final IViewRecordings.View view;
    private Handler updateListHandler;
    private BroadcastReceiver broadcastReceiver;

    public ViewRecordingsPresenter(IViewRecordings.View view) {
        this.view = view;
    }

    @Override
    public void onStartView() {
        updateListHandler = new Handler(Looper.getMainLooper());
        RecordingCatalog.syncFromStorage(view.getActivity());
        view.updateRecordingsList(getDataSet());
        registerBroadcastReceiver();
    }

    @Override
    public void onStopView() {
        stopUpdateList();
        unRegisterBroadcastReceiver();
    }

    @Override
    public void onRecordingsItemPressed(Recording recordingItem) {
        if (recordingItem == null) {
            return;
        }

        Util.showToast(
                view.getActivity(),
                recordingItem.getDateSaved() + " - " + recordingItem.getTimeSaved()
        );

        Uri fileUri = StorageHelper.toContentUri(
                view.getActivity(),
                recordingItem.getFilePath()
        );

        Util.openFile(view.getActivity(), fileUri, RecordingMediaType.fromPath(recordingItem.getFilePath()).getMimeType());
    }

    private void stopUpdateList() {
        if (updateListHandler != null) {
            updateListHandler.removeCallbacksAndMessages(null);
            updateListHandler = null;
        }
    }

    private ArrayList<Recording> getDataSet() {
        return DBHelper.getInstance(OpenDashApp.getAppContext()).selectAllRecordingsList();
    }

    private void registerBroadcastReceiver() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (Util.ACTION_UPDATE_RECORDINGS_LIST.equals(action)) {
                    view.updateRecordingsList(getDataSet());
                    Log.d(LOG_TAG, "Recordings list updated");
                }
            }
        };
        LocalBroadcastManager.getInstance(view.getActivity()).registerReceiver(
                broadcastReceiver,
                new IntentFilter(Util.ACTION_UPDATE_RECORDINGS_LIST)
        );
    }

    private void unRegisterBroadcastReceiver() {
        if (broadcastReceiver != null) {
            LocalBroadcastManager.getInstance(view.getActivity()).unregisterReceiver(broadcastReceiver);
            broadcastReceiver = null;
        }
    }
}
