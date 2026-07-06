package com.opendashcam;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.view.WindowManager;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.opendashcam.models.Widget;

public class WidgetService extends Service {

    private WindowManager windowManager;
    private Widget overlayWidget;
    private BroadcastReceiver overlayReceiver;
    private int overlaySuppressionCount;
    private final RecordingWakeLock widgetWakeLock =
            new RecordingWakeLock("OpenDashCam:WidgetService");

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayWidget = new Widget(this, windowManager);
        overlayWidget.show();

        overlayReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (overlayWidget == null || intent == null || intent.getAction() == null) {
                    return;
                }
                switch (intent.getAction()) {
                    case Util.ACTION_HIDE_OVERLAY:
                        suppressOverlay();
                        break;
                    case Util.ACTION_SHOW_OVERLAY:
                        restoreOverlay();
                        break;
                    default:
                        break;
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Util.ACTION_HIDE_OVERLAY);
        filter.addAction(Util.ACTION_SHOW_OVERLAY);
        LocalBroadcastManager.getInstance(this).registerReceiver(overlayReceiver, filter);

        Util.startForeground(
                this,
                Util.WIDGET_NOTIFICATION_ID,
                Util.createStatusBarNotification(this),
                Util.foregroundServiceTypeSpecialUse()
        );

        widgetWakeLock.acquire(this);
    }

    @Override
    public void onDestroy() {
        if (overlayReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(overlayReceiver);
            overlayReceiver = null;
        }

        if (overlayWidget != null) {
            overlayWidget.hide();
        }

        DBHelper.getInstance(this).close();

        Intent startMain = new Intent(Intent.ACTION_MAIN);
        startMain.addCategory(Intent.CATEGORY_HOME);
        startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(startMain);

        widgetWakeLock.release(this);
        Util.stopForeground(this);
        super.onDestroy();
    }

    private void suppressOverlay() {
        overlaySuppressionCount++;
        if (overlaySuppressionCount == 1 && overlayWidget != null) {
            overlayWidget.hide();
        }
    }

    private void restoreOverlay() {
        if (overlaySuppressionCount > 0) {
            overlaySuppressionCount--;
        }
        if (overlaySuppressionCount <= 0) {
            overlaySuppressionCount = 0;
            if (overlayWidget != null) {
                overlayWidget.show();
            }
        }
    }
}
