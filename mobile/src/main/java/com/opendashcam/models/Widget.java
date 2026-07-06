package com.opendashcam.models;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;

import com.opendashcam.BackgroundVideoRecorder;
import com.opendashcam.PinManager;
import com.opendashcam.R;
import com.opendashcam.SettingsActivity;
import com.opendashcam.Util;
import com.opendashcam.ViewRecordingsActivity;
import com.opendashcam.models.Recording;

public class Widget {
    protected Service service;
    protected WindowManager windowManager;
    private WidgetViewHolder viewHolder;

    private WindowManager.LayoutParams layoutParams;
    private int gravity = Gravity.CENTER_VERTICAL | Gravity.START;
    private int x = 0;
    private int y = 0;
    private boolean isShown = false;

    public Widget(Service service, WindowManager windowManager) {
        this.service = service;
        this.windowManager = windowManager;
        this.viewHolder = new WidgetViewHolder(service);
    }

    public void setPosition(int gravity, int x, int y) {
        this.gravity = gravity;
        this.x = x;
        this.y = y;
    }

    public void show() {
        if (isShown && viewHolder.rootView.getParent() != null) {
            return;
        }
        isShown = false;

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        layoutParams.gravity = this.gravity;
        layoutParams.x = this.x;
        layoutParams.y = this.y;

        windowManager.addView(viewHolder.rootViewMenu, layoutParams);
        windowManager.addView(viewHolder.rootView, layoutParams);
        isShown = true;
    }

    public void hide() {
        if (!isShown) {
            return;
        }
        windowManager.removeView(viewHolder.rootView);
        windowManager.removeView(viewHolder.rootViewMenu);
        isShown = false;
    }

    private void startActivityHidingOverlay(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        service.startActivity(intent);
    }

    private class WidgetViewHolder implements View.OnClickListener {
        View rootView;
        View rootViewMenu;
        View viewRecView;
        View saveRecView;
        View recView;
        View settingsView;
        View stopAndQuitView;
        View layoutMenu;
        boolean areSecondaryWidgetsShown = false;

        WidgetViewHolder(Context context) {
            rootView = LayoutInflater.from(context).inflate(R.layout.layout_widgets, null);
            recView = rootView.findViewById(R.id.rec_button);

            rootViewMenu = LayoutInflater.from(context).inflate(R.layout.layout_widget_menu, null);
            viewRecView = rootViewMenu.findViewById(R.id.view_recordings_button);
            saveRecView = rootViewMenu.findViewById(R.id.save_recording_button);
            settingsView = rootViewMenu.findViewById(R.id.settings_button);
            stopAndQuitView = rootViewMenu.findViewById(R.id.stop_and_quit_button);
            layoutMenu = rootViewMenu.findViewById(R.id.layout_menu);

            viewRecView.setOnClickListener(this);
            saveRecView.setOnClickListener(this);
            recView.setOnClickListener(this);
            settingsView.setOnClickListener(this);
            stopAndQuitView.setOnClickListener(this);
            hideSecondaryWidgets();
        }

        @Override
        public void onClick(View v) {
            int id = v.getId();
            if (id == R.id.view_recordings_button) {
                startActivityHidingOverlay(new Intent(service, ViewRecordingsActivity.class));
                hideSecondaryWidgets();
            } else if (id == R.id.save_recording_button) {
                SharedPreferences sharedPref = service.getApplicationContext().getSharedPreferences(
                        service.getString(R.string.current_recordings_preferences_key),
                        Context.MODE_PRIVATE
                );

                String currentVideoRecording = sharedPref.getString(
                        service.getString(R.string.current_recording_preferences_key),
                        "null"
                );
                if (!TextUtils.isEmpty(currentVideoRecording) && !"null".equals(currentVideoRecording)) {
                    new Recording(currentVideoRecording).toggleStar(true);
                }

                String previousVideoRecording = sharedPref.getString(
                        service.getString(R.string.previous_recording_preferences_key),
                        "null"
                );
                if (!TextUtils.isEmpty(previousVideoRecording) && !"null".equals(previousVideoRecording)) {
                    new Recording(0, previousVideoRecording).toggleStar(true);
                }

                Util.showToastLong(service, service.getString(R.string.save_recording_success_msg));
            } else if (id == R.id.rec_button) {
                toggleSecondaryWidgets();
            } else if (id == R.id.settings_button) {
                startActivityHidingOverlay(new Intent(service, SettingsActivity.class));
                hideSecondaryWidgets();
            } else if (id == R.id.stop_and_quit_button) {
                if (PinManager.isRequiredFor(service, PinManager.ACTION_STOP_RECORDING)) {
                    startActivityHidingOverlay(
                            PinManager.createVerifyIntent(service, PinManager.ACTION_STOP_RECORDING)
                    );
                } else {
                    service.stopService(new Intent(service, BackgroundVideoRecorder.class));
                    service.stopSelf();
                }
            }
        }

        private void toggleSecondaryWidgets() {
            if (areSecondaryWidgetsShown) {
                hideSecondaryWidgets();
            } else {
                showSecondaryWidgets();
            }
        }

        private void showSecondaryWidgets() {
            rootViewMenu.setVisibility(View.VISIBLE);

            Animation animation = new ScaleAnimation(
                    0f, 1f,
                    0f, 1f,
                    Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0.5f
            );
            animation.setFillAfter(true);
            animation.setDuration(200);
            layoutMenu.startAnimation(animation);

            areSecondaryWidgetsShown = true;
        }

        private void hideSecondaryWidgets() {
            Animation animation = new ScaleAnimation(
                    1f, 0f,
                    1f, 0f,
                    Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0.5f
            );
            animation.setDuration(areSecondaryWidgetsShown ? 200 : 0);
            animation.setFillAfter(true);
            animation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    rootViewMenu.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                }
            });
            layoutMenu.startAnimation(animation);

            areSecondaryWidgetsShown = false;
        }
    }
}
