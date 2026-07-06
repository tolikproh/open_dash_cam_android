package com.opendashcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

public class SettingsActivity extends AppCompatActivity {

    private final OverlayLifecycle overlayLifecycle = new OverlayLifecycle();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RecordingOrientationHelper.applyActivityOrientation(this);
        super.onCreate(savedInstanceState);
        overlayLifecycle.onCreate(this);
        setupActionBar();
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new GeneralPreferenceFragment())
                    .commit();
        }
    }

    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        overlayLifecycle.onDestroy(this);
        super.onDestroy();
    }

    public static class GeneralPreferenceFragment extends PreferenceFragmentCompat {

        private ActivityResultLauncher<Intent> folderPickerLauncher;
        private ActivityResultLauncher<Intent> pinSetupLauncher;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            folderPickerLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            StorageHelper.setCustomFolderUri(requireContext(), result.getData().getData());
                            updateFolderSummary();
                            updateStorageQuotaPreference();
                        }
                    }
            );

            pinSetupLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> updatePinPreferences()
            );
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_general, rootKey);
            bindPreferences();
        }

        @Override
        public void onResume() {
            super.onResume();
            updateFolderSummary();
            updatePinPreferences();
            updateStorageQuotaPreference();
            updateRecordingPreferenceSummaries();
        }

        private void bindPreferences() {
            Preference chooseFolderPref = findPreference("choose_recordings_folder");
            if (chooseFolderPref != null) {
                chooseFolderPref.setOnPreferenceClickListener(preference -> {
                    folderPickerLauncher.launch(StorageHelper.createFolderPickerIntent());
                    return true;
                });
            }

            Preference resetFolderPref = findPreference("reset_recordings_folder");
            if (resetFolderPref != null) {
                resetFolderPref.setOnPreferenceClickListener(preference -> {
                    StorageHelper.clearCustomFolder(requireContext());
                    updateFolderSummary();
                    updateStorageQuotaPreference();
                    return true;
                });
            }

            Preference setupPinPref = findPreference("setup_pin");
            if (setupPinPref != null) {
                setupPinPref.setOnPreferenceClickListener(preference -> {
                    pinSetupLauncher.launch(PinManager.createSetupIntent(requireContext()));
                    return true;
                });
            }

            Preference deletePref = findPreference("delete_recordings");
            if (deletePref != null) {
                deletePref.setOnPreferenceClickListener(preference -> {
                    if (PinManager.isRequiredFor(requireContext(), PinManager.ACTION_DELETE_RECORDINGS)) {
                        requireActivity().startActivity(PinManager.createVerifyIntent(
                                requireContext(),
                                PinManager.ACTION_DELETE_RECORDINGS
                        ));
                    } else {
                        Util.deleteRecordings();
                    }
                    return true;
                });
            }

            bindPinRequirementSwitch("pin_require_app_start", PinManager.ACTION_APP_START);
            bindPinRequirementSwitch("pin_require_stop", PinManager.ACTION_STOP_RECORDING);
            bindPinRequirementSwitch("pin_require_delete", PinManager.ACTION_DELETE_RECORDINGS);

            SwitchPreferenceCompat pinEnabled = findPreference("pin_enabled");
            if (pinEnabled != null) {
                pinEnabled.setOnPreferenceChangeListener((preference, newValue) -> {
                    if ((Boolean) newValue && !PinManager.isPinSet(requireContext())) {
                        pinSetupLauncher.launch(PinManager.createSetupIntent(requireContext()));
                        return false;
                    }
                    PinManager.setEnabled(requireContext(), (Boolean) newValue);
                    updatePinPreferences();
                    return true;
                });
            }

            bindListPreferenceSummary(RecordingPreferences.KEY_SEGMENT_DURATION_MIN);
            bindStorageQuotaPreference();
            bindListPreferenceSummary(RecordingPreferences.KEY_VIDEO_RESOLUTION);
            bindListPreferenceSummary(RecordingPreferences.KEY_VIDEO_ORIENTATION);
            bindListPreferenceSummary(RecordingPreferences.KEY_APP_MODE);

            ListPreference appModePref = findPreference(RecordingPreferences.KEY_APP_MODE);
            if (appModePref != null) {
                appModePref.setOnPreferenceChangeListener((pref, newValue) -> {
                    // Restart after the new mode is persisted (listener runs before save).
                    requireView().post(() -> {
                        Util.restartBackgroundRecorder(requireContext());
                        updateRecordingPreferenceSummaries();
                        updateModeDependentPreferences();
                    });
                    return true;
                });
            }
        }

        private void updateModeDependentPreferences() {
            boolean videoMode = RecordingPreferences.isVideoMode(requireContext());

            SwitchPreferenceCompat videoAudioPref = findPreference(RecordingPreferences.KEY_VIDEO_RECORD_AUDIO);
            if (videoAudioPref != null) {
                videoAudioPref.setVisible(videoMode);
            }

            ListPreference resolutionPref = findPreference(RecordingPreferences.KEY_VIDEO_RESOLUTION);
            if (resolutionPref != null) {
                resolutionPref.setVisible(videoMode);
            }

            ListPreference orientationPref = findPreference(RecordingPreferences.KEY_VIDEO_ORIENTATION);
            if (orientationPref != null) {
                orientationPref.setVisible(videoMode);
            }
        }

        private void bindListPreferenceSummary(String key) {
            ListPreference preference = findPreference(key);
            if (preference == null) {
                return;
            }
            preference.setOnPreferenceChangeListener((pref, newValue) -> {
                updateRecordingPreferenceSummaries();
                return true;
            });
        }

        private void bindStorageQuotaPreference() {
            ListPreference quotaPref = findPreference(RecordingPreferences.KEY_STORAGE_QUOTA_MB);
            if (quotaPref == null) {
                return;
            }
            updateStorageQuotaPreference();
            quotaPref.setOnPreferenceChangeListener((pref, newValue) -> {
                updateRecordingPreferenceSummaries();
                return true;
            });
        }

        private void updateStorageQuotaPreference() {
            ListPreference quotaPref = findPreference(RecordingPreferences.KEY_STORAGE_QUOTA_MB);
            if (quotaPref == null) {
                return;
            }

            StorageQuotaHelper.QuotaOptionList options =
                    StorageQuotaHelper.buildOptions(requireContext());
            quotaPref.setEntries(options.entries);
            quotaPref.setEntryValues(options.values);

            String storedValue = RecordingPreferences.getStorageQuotaStoredValue(requireContext());
            String normalizedValue = StorageQuotaHelper.normalizeStoredValue(requireContext(), storedValue);
            if (!normalizedValue.equals(storedValue)) {
                quotaPref.setValue(normalizedValue);
            } else if (quotaPref.getValue() == null) {
                quotaPref.setValue(normalizedValue);
            }
        }

        private void updateRecordingPreferenceSummaries() {
            ListPreference appModePref = findPreference(RecordingPreferences.KEY_APP_MODE);
            if (appModePref != null) {
                appModePref.setSummary(RecordingPreferences.getAppModeSummary(requireContext()));
            }

            ListPreference segmentPref = findPreference(RecordingPreferences.KEY_SEGMENT_DURATION_MIN);
            if (segmentPref != null) {
                segmentPref.setSummary(RecordingPreferences.getSegmentDurationSummary(requireContext()));
            }

            ListPreference quotaPref = findPreference(RecordingPreferences.KEY_STORAGE_QUOTA_MB);
            if (quotaPref != null) {
                quotaPref.setSummary(RecordingPreferences.getStorageQuotaSummary(requireContext()));
            }

            ListPreference resolutionPref = findPreference(RecordingPreferences.KEY_VIDEO_RESOLUTION);
            if (resolutionPref != null) {
                resolutionPref.setSummary(RecordingPreferences.getVideoResolutionSummary(requireContext()));
            }

            ListPreference orientationPref = findPreference(RecordingPreferences.KEY_VIDEO_ORIENTATION);
            if (orientationPref != null) {
                orientationPref.setSummary(RecordingOrientationHelper.getSummary(requireContext()));
            }

            updateModeDependentPreferences();
        }

        private void updateFolderSummary() {
            Preference chooseFolderPref = findPreference("choose_recordings_folder");
            if (chooseFolderPref != null) {
                chooseFolderPref.setSummary(StorageHelper.getFolderSummary(requireContext()));
            }
        }

        private void bindPinRequirementSwitch(String key, String action) {
            SwitchPreferenceCompat pref = findPreference(key);
            if (pref == null) {
                return;
            }
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                PinManager.setRequirement(requireContext(), action, (Boolean) newValue);
                return true;
            });
        }

        private void updatePinPreferences() {
            boolean pinSet = PinManager.isPinSet(requireContext());
            SwitchPreferenceCompat pinEnabled = findPreference("pin_enabled");
            if (pinEnabled != null) {
                pinEnabled.setEnabled(pinSet);
                pinEnabled.setChecked(PinManager.isEnabled(requireContext()));
            }

            updatePinRequirementSwitch("pin_require_app_start", PinManager.ACTION_APP_START);
            updatePinRequirementSwitch("pin_require_stop", PinManager.ACTION_STOP_RECORDING);
            updatePinRequirementSwitch("pin_require_delete", PinManager.ACTION_DELETE_RECORDINGS);
        }

        private void updatePinRequirementSwitch(String key, String action) {
            SwitchPreferenceCompat pref = findPreference(key);
            if (pref != null) {
                pref.setEnabled(PinManager.isEnabled(requireContext()) && PinManager.isPinSet(requireContext()));
                pref.setChecked(PinManager.isRequiredFor(requireContext(), action));
            }
        }
    }
}
