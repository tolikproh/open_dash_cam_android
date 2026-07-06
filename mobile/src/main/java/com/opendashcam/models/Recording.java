package com.opendashcam.models;

import android.net.Uri;
import android.text.TextUtils;

import com.opendashcam.DBHelper;
import com.opendashcam.OpenDashApp;
import com.opendashcam.R;
import com.opendashcam.StorageHelper;
import com.opendashcam.Util;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Model class for video recording
 */

public class Recording {
    private int id;
    private String filePath;
    private String filename;
    private String dateSaved;
    private String timeSaved;
    private DBHelper dbHelper;

    private static final Locale RU_LOCALE = Locale.forLanguageTag("ru");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("d MMMM yyyy", RU_LOCALE);
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", RU_LOCALE);

    /**
     * Constructor for selecting rows from SQLite
     *
     * @param id       Unique id
     * @param filePath String
     */
    public Recording(int id, String filePath) {
        dbHelper = DBHelper.getInstance(OpenDashApp.getAppContext());
        this.id = id;
        this.filePath = filePath;
        if (filePath != null && filePath.startsWith("content://")) {
            String segment = Uri.parse(filePath).getLastPathSegment();
            this.filename = segment != null ? segment : "recording.mp4";
        } else {
            this.filename = new File(filePath).getName();
        }
        getDatesFromFile();
    }

    /**
     * Constructor for create a new recording from Video Recorder
     *
     * @param filePath String
     */
    public Recording(String filePath) {
        this(-1, filePath);
    }

    public String getFilePath() {
        return !TextUtils.isEmpty(filePath) ? filePath : "";
    }

    public String getFileName() {
        return !TextUtils.isEmpty(filename) ? filename : "";
    }

    public String getDateSaved() {
        return dateSaved;
    }

    public String getTimeSaved() {
        return timeSaved;
    }

    public boolean isStarred() {
        return dbHelper.isRecordingStarred(this);
    }

    /**
     * Checks/unchecks a recording as starred in DB. Intended to be called by
     * OnCheckedChangeListener when video is starred/unstarred by the user.
     *
     * @param isChecked Whether or not checkbox was marked as checked
     * @return True when marked as checked in DB, False otherwise
     */
    public boolean toggleStar(boolean isChecked) {
        //this item will be updated in the UI when asynctask will be finished
        Util.updateStar(this);
        return true;
    }

    private void getDatesFromFile() {
        if (filePath != null && !filePath.isEmpty()) {
            long lastModified = StorageHelper.getRecordingLastModified(OpenDashApp.getAppContext(), filePath);
            Date lastModDate = new Date(lastModified);
            dateSaved = DATE_FORMAT.format(lastModDate);
            timeSaved = TIME_FORMAT.format(lastModDate);
        } else {
            dateSaved = OpenDashApp.getAppContext().getString(R.string.recording_fallback_name, id);
            timeSaved = "";
        }
    }

    public int getId() {
        return id;
    }

}
