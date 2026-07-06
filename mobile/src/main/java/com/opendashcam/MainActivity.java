package com.opendashcam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.opendashcam.PinManager;
import com.opendashcam.StorageHelper;
import com.opendashcam.Util;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    public static final int MULTIPLE_PERMISSIONS_RESPONSE_CODE = 10;
    private static final int CODE_REQUEST_PERMISSION_DRAW_OVER_APPS = 10002;
    private static final int CODE_REQUEST_POST_NOTIFICATIONS = 10003;
    private static final int CODE_PIN_VERIFICATION = 10004;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case CODE_REQUEST_PERMISSION_DRAW_OVER_APPS:
                if (Settings.canDrawOverlays(this)) {
                    init();
                } else {
                    finish();
                }
                break;
            case CODE_REQUEST_POST_NOTIFICATIONS:
                init();
                break;
            case CODE_PIN_VERIFICATION:
                if (resultCode == RESULT_OK) {
                    startApp();
                } else {
                    finish();
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void init() {
        if (!checkDrawPermission()) {
            return;
        }
        if (!checkPostNotificationsPermission()) {
            return;
        }
        if (checkPermissions()) {
            if (PinManager.isRequiredFor(this, PinManager.ACTION_APP_START)) {
                startActivityForResult(
                        PinManager.createVerifyIntent(this, PinManager.ACTION_APP_START),
                        CODE_PIN_VERIFICATION
                );
            } else {
                startApp();
            }
        }
    }

    private void startApp() {
        if (!isEnoughStorage()) {
            Util.showToastLong(
                    getApplicationContext(),
                    getString(R.string.error_storage_not_enough, Util.getQuota())
            );
        } else {
            SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                    getString(R.string.db_first_launch_complete_flag),
                    Context.MODE_PRIVATE
            );
            String firstLaunchFlag = sharedPref.getString(
                    getString(R.string.db_first_launch_complete_flag),
                    null
            );

            if (TextUtils.isEmpty(firstLaunchFlag)) {
                startActivity(new Intent(getApplicationContext(), WelcomeActivity.class));
                finish();
                return;
            }

            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
            if (settings.getBoolean("start_maps_in_background", true)) {
                launchNavigation();
            }

            try {
                ContextCompat.startForegroundService(
                        getApplicationContext(),
                        new Intent(getApplicationContext(), BackgroundVideoRecorder.class)
                );
                ContextCompat.startForegroundService(
                        getApplicationContext(),
                        new Intent(getApplicationContext(), WidgetService.class)
                );
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to start recording services", e);
                Util.showToastLong(this, getString(R.string.error_service_start_failed));
            }
        }

        finish();
    }

    private boolean checkDrawPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, CODE_REQUEST_PERMISSION_DRAW_OVER_APPS);
            Toast.makeText(this, R.string.permission_overlay_needed, Toast.LENGTH_LONG).show();
            Toast.makeText(this, R.string.permission_overlay_back, Toast.LENGTH_LONG).show();
            Toast.makeText(this, R.string.permission_overlay_restart, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private boolean checkPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                CODE_REQUEST_POST_NOTIFICATIONS
        );
        return false;
    }

    private boolean checkPermissions() {
        List<String> missingRequired = getMissingRequiredPermissions();
        List<String> missingOptional = getMissingOptionalPermissions();

        List<String> toRequest = new ArrayList<>();
        toRequest.addAll(missingRequired);
        for (String permission : missingOptional) {
            if (!toRequest.contains(permission)) {
                toRequest.add(permission);
            }
        }

        if (toRequest.isEmpty()) {
            return true;
        }

        if (missingOptional.contains(Manifest.permission.ACCESS_FINE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                )) {
            Toast.makeText(this, R.string.permission_location_rationale, Toast.LENGTH_LONG).show();
        }

        ActivityCompat.requestPermissions(
                this,
                toRequest.toArray(new String[0]),
                MULTIPLE_PERMISSIONS_RESPONSE_CODE
        );
        return false;
    }

    private List<String> getMissingRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        if (RecordingPreferences.isVideoMode(this)) {
            addIfMissing(Manifest.permission.CAMERA, permissions);
        }
        addIfMissing(Manifest.permission.RECORD_AUDIO, permissions);
        return permissions;
    }

    private List<String> getMissingOptionalPermissions() {
        List<String> permissions = new ArrayList<>();
        if (RecordingPreferences.usesLocation(this) && !Util.hasLocationPermission(this)) {
            addIfMissing(Manifest.permission.ACCESS_FINE_LOCATION, permissions);
            addIfMissing(Manifest.permission.ACCESS_COARSE_LOCATION, permissions);
        }
        return permissions;
    }

    private void addIfMissing(String permission, List<String> target) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            target.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == MULTIPLE_PERMISSIONS_RESPONSE_CODE
                || requestCode == CODE_REQUEST_POST_NOTIFICATIONS) {
            if (areRequiredPermissionsGranted()) {
                if (RecordingPreferences.usesLocation(this) && !Util.hasLocationPermission(this)) {
                    Toast.makeText(this, R.string.permission_location_optional_denied, Toast.LENGTH_LONG).show();
                }
                init();
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
                Toast.makeText(this, R.string.permission_denied_restart, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private boolean areRequiredPermissionsGranted() {
        for (String permission : getMissingRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void launchNavigation() {
        String googleMapsPackage = "com.google.android.apps.maps";
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(googleMapsPackage);
            if (intent == null) {
                return;
            }
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("google.navigation:/?free=1&mode=d&entry=fnls"));
            startActivity(intent);
        } catch (Exception e) {
            // Maps not installed
        }
    }

    private boolean isEnoughStorage() {
        int availableMb = StorageQuotaHelper.getAvailableRecordingSpaceMb(this);
        return availableMb >= Util.getQuota();
    }
}
