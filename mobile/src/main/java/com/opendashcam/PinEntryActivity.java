package com.opendashcam;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class PinEntryActivity extends AppCompatActivity {

    private final OverlayLifecycle overlayLifecycle = new OverlayLifecycle();
    private String mode;
    private String action;
    private EditText pinInput;
    private EditText pinConfirmInput;
    private boolean actionCompleted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RecordingOrientationHelper.applyActivityOrientation(this);
        super.onCreate(savedInstanceState);
        overlayLifecycle.onCreate(this);
        setContentView(R.layout.activity_pin_entry);

        mode = getIntent().getStringExtra(PinManager.EXTRA_MODE);
        action = getIntent().getStringExtra(PinManager.EXTRA_ACTION);

        TextView title = findViewById(R.id.pin_title);
        TextView message = findViewById(R.id.pin_message);
        pinInput = findViewById(R.id.pin_input);
        pinConfirmInput = findViewById(R.id.pin_confirm_input);
        Button cancelButton = findViewById(R.id.pin_cancel_button);
        Button okButton = findViewById(R.id.pin_ok_button);

        if (PinManager.MODE_SETUP.equals(mode)) {
            title.setText(R.string.pin_setup_title);
            message.setText(R.string.pin_setup_message);
            pinConfirmInput.setVisibility(View.VISIBLE);
        } else {
            title.setText(R.string.pin_enter_title);
            message.setText(getVerifyMessage(action));
        }

        cancelButton.setOnClickListener(v -> finish());

        okButton.setOnClickListener(v -> onSubmit());
    }

    @Override
    protected void onDestroy() {
        overlayLifecycle.onDestroy(this);
        super.onDestroy();
    }

    private void onSubmit() {
        String pin = pinInput.getText().toString().trim();
        if (PinManager.MODE_SETUP.equals(mode)) {
            String confirm = pinConfirmInput.getText().toString().trim();
            if (pin.length() < 4) {
                Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!pin.equals(confirm)) {
                Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show();
                return;
            }
            PinManager.setPin(this, pin);
            Toast.makeText(this, R.string.pin_setup_success, Toast.LENGTH_SHORT).show();
            actionCompleted = true;
            setResult(RESULT_OK);
            finish();
            return;
        }

        if (!PinManager.verify(this, pin)) {
            Toast.makeText(this, R.string.pin_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        handleVerifiedAction();
        actionCompleted = true;
        setResult(RESULT_OK);
        finish();
    }

    private void handleVerifiedAction() {
        if (TextUtils.isEmpty(action)) {
            return;
        }
        switch (action) {
            case PinManager.ACTION_STOP_RECORDING:
                stopService(new Intent(this, BackgroundVideoRecorder.class));
                stopService(new Intent(this, WidgetService.class));
                break;
            case PinManager.ACTION_DELETE_RECORDINGS:
                Util.deleteRecordings();
                break;
            case PinManager.ACTION_APP_START:
            default:
                break;
        }
    }

    private int getVerifyMessage(String verifyAction) {
        if (PinManager.ACTION_STOP_RECORDING.equals(verifyAction)) {
            return R.string.pin_message_stop;
        }
        if (PinManager.ACTION_DELETE_RECORDINGS.equals(verifyAction)) {
            return R.string.pin_message_delete;
        }
        return R.string.pin_enter_message;
    }
}
