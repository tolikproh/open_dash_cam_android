package com.opendashcam;

import android.content.Context;
import android.text.TextUtils;

/** Immutable GPS/address values captured for post-processing after the tracker is stopped. */
public final class RecordingLocationSnapshot {
    private final String coordinatesLine;
    private final String addressLine;

    public RecordingLocationSnapshot(String coordinatesLine, String addressLine) {
        this.coordinatesLine = coordinatesLine;
        this.addressLine = addressLine;
    }

    public static RecordingLocationSnapshot from(RecordingLocationTracker tracker) {
        if (tracker == null) {
            return empty(OpenDashApp.getAppContext());
        }
        return new RecordingLocationSnapshot(
                tracker.getCoordinatesLine(),
                tracker.getAddressLine()
        );
    }

    public static RecordingLocationSnapshot empty(Context context) {
        String unavailable = context != null
                ? context.getString(R.string.watermark_gps_unavailable)
                : "GPS: —";
        return new RecordingLocationSnapshot(unavailable, null);
    }

    public String getCoordinatesLine() {
        return coordinatesLine;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public boolean hasAddress() {
        return !TextUtils.isEmpty(addressLine);
    }
}
