package com.siberman.live2dapp;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.siberman.live2dapp.aliyun.AliyunGateway;
import com.siberman.live2dapp.audio.WavRecorder;
import com.siberman.live2dapp.config.AliyunConfigStore;

import java.io.File;
import java.util.Locale;

public class AsrDebugActivity extends AppCompatActivity {
    private final AliyunGateway gateway = new AliyunGateway();
    private final WavRecorder wavRecorder = new WavRecorder();

    private TextView debugSummaryText;
    private TextView debugLevelText;
    private TextView debugNoiseText;
    private TextView debugThresholdText;
    private TextView debugDurationText;
    private TextView debugResultText;
    private Button testAsrButton;
    private boolean pendingAsrPermissionRequest;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_asr_debug);

        debugSummaryText = findViewById(R.id.text_asr_debug_summary);
        debugLevelText = findViewById(R.id.text_asr_debug_level);
        debugNoiseText = findViewById(R.id.text_asr_debug_noise);
        debugThresholdText = findViewById(R.id.text_asr_debug_threshold);
        debugDurationText = findViewById(R.id.text_asr_debug_duration);
        debugResultText = findViewById(R.id.text_asr_debug_result);
        testAsrButton = findViewById(R.id.button_test_asr);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        testAsrButton.setOnClickListener(v -> toggleAsrTest());
        resetAsrDebugPanel();
    }

    private void toggleAsrTest() {
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        if (config.dashscopeApiKey == null || config.dashscopeApiKey.trim().isEmpty()) {
            debugResultText.setText(R.string.testing_need_api_key);
            return;
        }
        if (!wavRecorder.isRecording()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingAsrPermissionRequest = true;
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1001);
                return;
            }
            startAsrDebugRecording();
            return;
        }
        stopAsrDebugRecording(config);
    }

    private void startAsrDebugRecording() {
        try {
            File file = new File(getCacheDir(), "settings_asr_test.wav");
            wavRecorder.start(file, new WavRecorder.Listener() {
                @Override
                public void onVadDebug(
                        float level,
                        float noiseFloor,
                        float startThreshold,
                        float continueThreshold,
                        boolean speaking,
                        long totalRecordedMs,
                        long speechRecordedMs
                ) {
                    runOnUiThread(() -> updateAsrDebugPanel(
                            level,
                            noiseFloor,
                            startThreshold,
                            continueThreshold,
                            speaking,
                            totalRecordedMs,
                            speechRecordedMs
                    ));
                }

                @Override
                public void onSpeechDetected() {
                    runOnUiThread(() -> debugSummaryText.setText(R.string.asr_debug_detected));
                }

                @Override
                public void onSilenceTimeout() {
                    runOnUiThread(() -> debugSummaryText.setText(R.string.asr_debug_detected_pause));
                }

                @Override
                public void onMaxDurationReached() {
                    runOnUiThread(() -> {
                        debugSummaryText.setText(R.string.asr_debug_max_duration);
                        stopAsrDebugRecording(new AliyunConfigStore(AsrDebugActivity.this).load());
                    });
                }
            });
            testAsrButton.setText(R.string.stop_asr_test);
            debugSummaryText.setText(R.string.asr_debug_recording);
            debugResultText.setText(R.string.asr_debug_waiting_result);
        } catch (Exception e) {
            debugResultText.setText(getString(R.string.testing_error, e.getMessage()));
        }
    }

    private void stopAsrDebugRecording(AliyunConfigStore.Config config) {
        testAsrButton.setEnabled(false);
        debugResultText.setText(R.string.testing_asr_recognizing);
        try {
            File audioFile = wavRecorder.stop();
            gateway.transcribeAudio(config, audioFile, new AliyunGateway.Callback<String>() {
                @Override
                public void onSuccess(String value) {
                    runOnUiThread(() -> {
                        testAsrButton.setEnabled(true);
                        testAsrButton.setText(R.string.test_asr);
                        debugResultText.setText(getString(R.string.testing_asr_success, value));
                        debugSummaryText.setText(getString(R.string.asr_debug_success, value));
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        testAsrButton.setEnabled(true);
                        testAsrButton.setText(R.string.test_asr);
                        debugResultText.setText(getString(R.string.testing_error, errorMessage));
                        debugSummaryText.setText(getString(R.string.asr_debug_error, errorMessage));
                    });
                }
            });
        } catch (Exception e) {
            testAsrButton.setEnabled(true);
            testAsrButton.setText(R.string.test_asr);
            debugResultText.setText(getString(R.string.testing_error, e.getMessage()));
            debugSummaryText.setText(getString(R.string.asr_debug_error, e.getMessage()));
        }
    }

    private void resetAsrDebugPanel() {
        debugSummaryText.setText(R.string.asr_debug_idle);
        debugLevelText.setText(getString(R.string.asr_debug_level, "0.000"));
        debugNoiseText.setText(getString(R.string.asr_debug_noise, "0.000"));
        debugThresholdText.setText(getString(R.string.asr_debug_thresholds, "0.000", "0.000"));
        debugDurationText.setText(getString(
                R.string.asr_debug_duration,
                0L,
                0L,
                getString(R.string.asr_debug_state_silence)
        ));
        debugResultText.setText(R.string.asr_debug_waiting_result);
    }

    private void updateAsrDebugPanel(
            float level,
            float noiseFloor,
            float startThreshold,
            float continueThreshold,
            boolean speaking,
            long totalRecordedMs,
            long speechRecordedMs
    ) {
        debugLevelText.setText(getString(R.string.asr_debug_level, formatFloat(level)));
        debugNoiseText.setText(getString(R.string.asr_debug_noise, formatFloat(noiseFloor)));
        debugThresholdText.setText(getString(
                R.string.asr_debug_thresholds,
                formatFloat(startThreshold),
                formatFloat(continueThreshold)
        ));
        debugDurationText.setText(getString(
                R.string.asr_debug_duration,
                totalRecordedMs,
                speechRecordedMs,
                getString(speaking ? R.string.asr_debug_state_speaking : R.string.asr_debug_state_silence)
        ));
    }

    private String formatFloat(float value) {
        return String.format(Locale.US, "%.3f", value);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1001) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (granted && pendingAsrPermissionRequest) {
            pendingAsrPermissionRequest = false;
            startAsrDebugRecording();
            return;
        }
        pendingAsrPermissionRequest = false;
        debugResultText.setText(R.string.player_state_need_permission);
        debugSummaryText.setText(R.string.asr_debug_need_permission);
    }

    @Override
    protected void onDestroy() {
        wavRecorder.stopSilently();
        super.onDestroy();
    }
}
