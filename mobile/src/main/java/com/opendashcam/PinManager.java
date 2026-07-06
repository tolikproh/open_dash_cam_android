package com.opendashcam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public final class PinManager {
    public static final String EXTRA_ACTION = "pin_action";
    public static final String EXTRA_MODE = "pin_mode";

    public static final String ACTION_APP_START = "app_start";
    public static final String ACTION_STOP_RECORDING = "stop_recording";
    public static final String ACTION_DELETE_RECORDINGS = "delete_recordings";

    public static final String MODE_VERIFY = "verify";
    public static final String MODE_SETUP = "setup";

    private static final String PREFS_NAME = "pin_prefs";
    private static final String KEY_ENABLED = "pin_enabled";
    private static final String KEY_HASH = "pin_hash";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_REQUIRE_APP_START = "pin_require_app_start";
    private static final String KEY_REQUIRE_STOP = "pin_require_stop";
    private static final String KEY_REQUIRE_DELETE = "pin_require_delete";

    private PinManager() {
    }

    public static boolean isPinSet(Context context) {
        return !TextUtils.isEmpty(getPrefs(context).getString(KEY_HASH, ""));
    }

    public static boolean isEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_ENABLED, false) && isPinSet(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean isRequiredFor(Context context, String action) {
        if (!isEnabled(context)) {
            return false;
        }
        SharedPreferences prefs = getPrefs(context);
        switch (action) {
            case ACTION_APP_START:
                return prefs.getBoolean(KEY_REQUIRE_APP_START, true);
            case ACTION_STOP_RECORDING:
                return prefs.getBoolean(KEY_REQUIRE_STOP, true);
            case ACTION_DELETE_RECORDINGS:
                return prefs.getBoolean(KEY_REQUIRE_DELETE, true);
            default:
                return false;
        }
    }

    public static void setRequirement(Context context, String action, boolean required) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        switch (action) {
            case ACTION_APP_START:
                editor.putBoolean(KEY_REQUIRE_APP_START, required);
                break;
            case ACTION_STOP_RECORDING:
                editor.putBoolean(KEY_REQUIRE_STOP, required);
                break;
            case ACTION_DELETE_RECORDINGS:
                editor.putBoolean(KEY_REQUIRE_DELETE, required);
                break;
            default:
                return;
        }
        editor.apply();
    }

    public static boolean verify(Context context, String pin) {
        if (TextUtils.isEmpty(pin)) {
            return false;
        }
        SharedPreferences prefs = getPrefs(context);
        String salt = prefs.getString(KEY_SALT, "");
        String hash = prefs.getString(KEY_HASH, "");
        return !TextUtils.isEmpty(hash) && hash.equals(hashPin(pin, salt));
    }

    public static void setPin(Context context, String pin) {
        if (TextUtils.isEmpty(pin) || pin.length() < 4) {
            throw new IllegalArgumentException("PIN must be at least 4 digits");
        }
        String salt = generateSalt();
        getPrefs(context).edit()
                .putString(KEY_SALT, salt)
                .putString(KEY_HASH, hashPin(pin, salt))
                .putBoolean(KEY_ENABLED, true)
                .apply();
    }

    public static void clearPin(Context context) {
        getPrefs(context).edit()
                .remove(KEY_HASH)
                .remove(KEY_SALT)
                .putBoolean(KEY_ENABLED, false)
                .apply();
    }

    public static Intent createVerifyIntent(Context context, String action) {
        Intent intent = new Intent(context, PinEntryActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_VERIFY);
        intent.putExtra(EXTRA_ACTION, action);
        return intent;
    }

    public static Intent createSetupIntent(Context context) {
        Intent intent = new Intent(context, PinEntryActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_SETUP);
        return intent;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static String hashPin(String pin, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest(pin.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
