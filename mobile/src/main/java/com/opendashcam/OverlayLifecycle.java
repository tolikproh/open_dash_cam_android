package com.opendashcam;

import android.content.Context;

/**
 * Ensures each activity hides the overlay once on create and restores it once on destroy.
 * Avoids duplicate hide calls when onStart runs again after returning from sub-screens.
 */
public final class OverlayLifecycle {
    private boolean suppressed;

    public void onCreate(Context context) {
        if (!suppressed) {
            Util.hideOverlay(context);
            suppressed = true;
        }
    }

    public void onDestroy(Context context) {
        if (suppressed) {
            Util.showOverlay(context);
            suppressed = false;
        }
    }
}
