package com.siberman.live2dapp;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.siberman.live2dapp.aliyun.AliyunGateway;
import com.siberman.live2dapp.audio.StreamAudioPlayer;
import com.siberman.live2dapp.config.AliyunConfigStore;

import java.io.InputStream;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static final int REQUEST_RECORD_AUDIO_FOR_KWS = 3101;
    private static final Option[] LLM_MODEL_OPTIONS = new Option[]{
            new Option("推荐: qwen-plus", "qwen-plus"),
            new Option("快速: qwen-turbo", "qwen-turbo"),
            new Option("高质量: qwen-max", "qwen-max")
    };

    private static final Option[] ASR_MODEL_OPTIONS = new Option[]{
            new Option("推荐: qwen3-asr-flash-realtime", "qwen3-asr-flash-realtime")
    };

    private static final Option[] TTS_MODEL_OPTIONS = new Option[]{
            new Option("推荐: cosyvoice-v3-flash", "cosyvoice-v3-flash")
    };

    private static final Option[] TTS_VOICE_OPTIONS = new Option[]{
            new Option("实测可用: longanyang", "longanyang")
    };

    private static final Option[] BASE_URL_OPTIONS = new Option[]{
            new Option("北京站: dashscope.aliyuncs.com", "https://dashscope.aliyuncs.com"),
            new Option("国际站: dashscope-intl.aliyuncs.com", "https://dashscope-intl.aliyuncs.com")
    };

    private AliyunConfigStore store;
    private final AliyunGateway gateway = new AliyunGateway();
    private final StreamAudioPlayer audioPlayer = new StreamAudioPlayer();
    private AliyunGateway.CancelableTask currentTtsTask;
    private KwsManager kwsManager;
    private android.widget.Button testKwsButton;
    private boolean pendingKwsTestStart;

    private EditText dashscopeApiKeyInput;
    private Spinner llmModelInput;
    private Spinner asrModelInput;
    private EditText systemPromptInput;
    private EditText openingMessageInput;
    private Spinner live2dModelInput;
    private Spinner ttsModelInput;
    private Spinner ttsVoiceInput;
    private Spinner baseUrlInput;
    private Switch wakeWordEnabledInput;
    private EditText wakeWordTextInput;
    private ImageView live2dPreviewImage;
    private TextView live2dPreviewEmptyText;
    private TextView statusText;
    private Option[] live2dModelOptions;
    private List<Live2dModelCatalog.ModelEntry> live2dModelEntries;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        store = new AliyunConfigStore(this);
        kwsManager = new KwsManager(this);
        bindViews();
        populateForm(store.load());
        bindActions();
    }

    private void bindViews() {
        dashscopeApiKeyInput = findViewById(R.id.input_dashscope_api_key);
        llmModelInput = findViewById(R.id.input_llm_model);
        asrModelInput = findViewById(R.id.input_asr_model);
        systemPromptInput = findViewById(R.id.input_system_prompt);
        openingMessageInput = findViewById(R.id.input_opening_message);
        live2dModelInput = findViewById(R.id.input_live2d_model);
        ttsModelInput = findViewById(R.id.input_tts_model);
        ttsVoiceInput = findViewById(R.id.input_tts_voice);
        baseUrlInput = findViewById(R.id.input_base_url);
        wakeWordEnabledInput = findViewById(R.id.input_wake_word_enabled);
        wakeWordTextInput = findViewById(R.id.input_wake_word_text);
        testKwsButton = findViewById(R.id.button_test_kws);
        live2dPreviewImage = findViewById(R.id.image_live2d_preview);
        live2dPreviewEmptyText = findViewById(R.id.text_live2d_preview_empty);
        statusText = findViewById(R.id.text_status);
        live2dModelEntries = Live2dModelCatalog.load(this);
        live2dModelOptions = buildLive2dModelOptions(live2dModelEntries);

        bindSpinner(llmModelInput, LLM_MODEL_OPTIONS);
        bindSpinner(asrModelInput, ASR_MODEL_OPTIONS);
        bindSpinner(live2dModelInput, live2dModelOptions);
        bindSpinner(ttsModelInput, TTS_MODEL_OPTIONS);
        bindSpinner(ttsVoiceInput, TTS_VOICE_OPTIONS);
        bindSpinner(baseUrlInput, BASE_URL_OPTIONS);
        wakeWordEnabledInput.setOnCheckedChangeListener((buttonView, isChecked) ->
                wakeWordTextInput.setEnabled(isChecked)
        );
        live2dModelInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateSelectedModelPreview(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateSelectedModelPreview(-1);
            }
        });
    }

    private void bindActions() {
        android.widget.Button saveButton = findViewById(R.id.button_save);
        android.widget.Button saveAndEnterButton = findViewById(R.id.button_save_enter);
        android.widget.Button skipButton = findViewById(R.id.button_skip);
        android.widget.Button testLlmButton = findViewById(R.id.button_test_llm);
        android.widget.Button testTtsButton = findViewById(R.id.button_test_tts);
        android.widget.Button openAsrDebugButton = findViewById(R.id.button_open_asr_debug);

        saveButton.setOnClickListener(v -> {
            saveConfig(false);
            Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
        });

        saveAndEnterButton.setOnClickListener(v -> {
            saveConfig(true);
            startActivity(new Intent(this, PlayerActivity.class));
            finish();
        });

        skipButton.setOnClickListener(v -> {
            AliyunConfigStore.Config config = store.load();
            config.onboardingCompleted = true;
            store.save(config);
            startActivity(new Intent(this, PlayerActivity.class));
            finish();
        });

        testLlmButton.setOnClickListener(v -> runLlmTest());
        testTtsButton.setOnClickListener(v -> runTtsTest());
        testKwsButton.setOnClickListener(v -> toggleKwsTest());
        openAsrDebugButton.setOnClickListener(v -> {
            saveConfig(false);
            startActivity(new Intent(this, AsrDebugActivity.class));
        });
    }

    private void populateForm(AliyunConfigStore.Config config) {
        dashscopeApiKeyInput.setText(config.dashscopeApiKey);
        selectSpinnerValue(llmModelInput, LLM_MODEL_OPTIONS, config.llmModel);
        selectSpinnerValue(asrModelInput, ASR_MODEL_OPTIONS, config.asrModel);
        systemPromptInput.setText(config.systemPrompt);
        openingMessageInput.setText(config.openingMessage);
        selectSpinnerValue(
                live2dModelInput,
                live2dModelOptions,
                Live2dModelCatalog.resolveModelPath(this, config.live2dModelPath)
        );
        selectSpinnerValue(ttsModelInput, TTS_MODEL_OPTIONS, config.ttsModel);
        selectSpinnerValue(ttsVoiceInput, TTS_VOICE_OPTIONS, config.ttsVoice);
        selectSpinnerValue(baseUrlInput, BASE_URL_OPTIONS, config.baseUrl);
        wakeWordEnabledInput.setChecked(config.wakeWordEnabled);
        wakeWordTextInput.setText(config.wakeWordText);
        wakeWordTextInput.setEnabled(config.wakeWordEnabled);
        updateSelectedModelPreview(live2dModelInput.getSelectedItemPosition());
        updateKwsTestButton();
        updateStatus(config);
    }

    private void saveConfig(boolean completeOnboarding) {
        AliyunConfigStore.Config config = new AliyunConfigStore.Config();
        config.dashscopeApiKey = textOf(dashscopeApiKeyInput);
        config.llmModel = getSelectedValue(llmModelInput, LLM_MODEL_OPTIONS);
        config.asrModel = getSelectedValue(asrModelInput, ASR_MODEL_OPTIONS);
        config.systemPrompt = textOf(systemPromptInput);
        config.openingMessage = textOf(openingMessageInput);
        config.live2dModelPath = getSelectedValue(live2dModelInput, live2dModelOptions);
        config.ttsModel = getSelectedValue(ttsModelInput, TTS_MODEL_OPTIONS);
        config.ttsVoice = getSelectedValue(ttsVoiceInput, TTS_VOICE_OPTIONS);
        config.baseUrl = getSelectedValue(baseUrlInput, BASE_URL_OPTIONS);
        config.wakeWordEnabled = wakeWordEnabledInput.isChecked();
        config.wakeWordText = textOf(wakeWordTextInput);
        config.onboardingCompleted = completeOnboarding || store.hasCompletedOnboarding();
        if (completeOnboarding) {
            config.onboardingCompleted = true;
        }
        store.save(config);
        updateStatus(config);
    }

    private void updateStatus(AliyunConfigStore.Config config) {
        String baseStatus;
        if (config.hasRequiredConfig()) {
            baseStatus = getString(R.string.config_status_ready);
        } else if (config.hasAnyConfig()) {
            baseStatus = getString(R.string.config_status_partial);
        } else {
            baseStatus = getString(R.string.config_status_empty);
        }
        if (!config.wakeWordEnabled) {
            statusText.setText(baseStatus + "\n\n" + getString(R.string.config_status_kws_disabled));
            return;
        }
        String issue = KwsManager.getSetupIssue(this, config.wakeWordText);
        String wakeWordSummary = KwsManager.describeWakeWord(config.wakeWordText);
        if (issue == null) {
            statusText.setText(baseStatus + "\n\n" + getString(R.string.config_status_kws_ready, wakeWordSummary));
            return;
        }
        statusText.setText(baseStatus + "\n\n" + getString(R.string.config_status_kws_problem, wakeWordSummary, issue));
    }

    private void runLlmTest() {
        AliyunConfigStore.Config config = currentConfig();
        if (!ensureApiKey(config)) {
            return;
        }
        statusText.setText(R.string.testing_llm);
        gateway.testLlm(config, new AliyunGateway.Callback<String>() {
            @Override
            public void onSuccess(String value) {
                runOnUiThread(() -> statusText.setText(getString(R.string.testing_llm_success, value)));
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> statusText.setText(getString(R.string.testing_error, errorMessage)));
            }
        });
    }

    private void runTtsTest() {
        AliyunConfigStore.Config config = currentConfig();
        if (!ensureApiKey(config)) {
            return;
        }
        statusText.setText(R.string.testing_tts);
        audioPlayer.stop();
        if (currentTtsTask != null) {
            currentTtsTask.cancel();
            currentTtsTask = null;
        }
        audioPlayer.startPcmStream(new StreamAudioPlayer.Callback() {
            @Override
            public void onStarted() {
                runOnUiThread(() -> statusText.setText(R.string.testing_tts_playing));
            }

            @Override
            public void onCompleted() {
                runOnUiThread(() -> {
                    currentTtsTask = null;
                    statusText.setText(R.string.testing_tts_success);
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    currentTtsTask = null;
                    statusText.setText(getString(R.string.testing_error, errorMessage));
                });
            }
        });
        currentTtsTask = gateway.streamSpeech(config,
                "你好，我是数字人测试语音，如果你听到这句话，说明 TTS 已经打通，而且现在已经是流式播放。",
                new AliyunGateway.AudioStreamCallback() {
                    @Override
                    public void onAudioChunk(byte[] data) {
                        audioPlayer.appendPcmChunk(data);
                    }

                    @Override
                    public void onComplete() {
                        audioPlayer.finishStream();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            audioPlayer.stop();
                            currentTtsTask = null;
                            statusText.setText(getString(R.string.testing_error, errorMessage));
                        });
                    }
                });
    }

    private void toggleKwsTest() {
        if (kwsManager != null && kwsManager.isListening()) {
            stopKwsTest(getString(R.string.testing_kws_stopped));
            return;
        }
        startKwsTest();
    }

    private void startKwsTest() {
        AliyunConfigStore.Config config = currentConfig();
        String issue = KwsManager.getSetupIssue(this, config.wakeWordText);
        if (issue != null) {
            statusText.setText(getString(R.string.testing_error, issue));
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingKwsTestStart = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_FOR_KWS);
            statusText.setText(R.string.testing_kws_need_permission);
            return;
        }
        String wakeWordSummary = KwsManager.describeWakeWord(config.wakeWordText);
        boolean started = kwsManager.start(config.wakeWordText, new KwsManager.Listener() {
            @Override
            public void onWakeWordDetected(String wakeWord) {
                runOnUiThread(() -> {
                    statusText.setText(getString(R.string.testing_kws_detected, wakeWord));
                    stopKwsTest(null);
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    statusText.setText(getString(R.string.testing_error, errorMessage));
                    stopKwsTest(null);
                });
            }
        });
        if (!started) {
            updateKwsTestButton();
            return;
        }
        statusText.setText(getString(R.string.testing_kws_waiting, wakeWordSummary));
        updateKwsTestButton();
    }

    private void stopKwsTest(@Nullable String statusMessage) {
        pendingKwsTestStart = false;
        if (kwsManager != null) {
            kwsManager.stop();
        }
        if (statusMessage != null && !statusMessage.isEmpty()) {
            statusText.setText(statusMessage);
        }
        updateKwsTestButton();
    }

    private void updateKwsTestButton() {
        if (testKwsButton == null) {
            return;
        }
        boolean listening = kwsManager != null && kwsManager.isListening();
        testKwsButton.setText(listening ? R.string.test_kws_stop : R.string.test_kws_start);
    }

    private boolean ensureApiKey(AliyunConfigStore.Config config) {
        if (config.dashscopeApiKey == null || config.dashscopeApiKey.trim().isEmpty()) {
            statusText.setText(R.string.testing_need_api_key);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO_FOR_KWS) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            pendingKwsTestStart = false;
            statusText.setText(R.string.testing_kws_need_permission);
            updateKwsTestButton();
            return;
        }
        if (pendingKwsTestStart) {
            pendingKwsTestStart = false;
            startKwsTest();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopKwsTest(null);
    }

    private AliyunConfigStore.Config currentConfig() {
        saveConfig(false);
        return store.load();
    }

    private String textOf(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void bindSpinner(Spinner spinner, Option[] options) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                buildLabels(options)
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private String[] buildLabels(Option[] options) {
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i].label;
        }
        return labels;
    }

    private void selectSpinnerValue(Spinner spinner, Option[] options, String value) {
        int targetIndex = 0;
        if (value != null) {
            for (int i = 0; i < options.length; i++) {
                if (value.trim().equalsIgnoreCase(options[i].value)) {
                    targetIndex = i;
                    break;
                }
            }
        }
        spinner.setSelection(targetIndex);
    }

    private String getSelectedValue(Spinner spinner, Option[] options) {
        int index = spinner.getSelectedItemPosition();
        if (index < 0 || index >= options.length) {
            return options[0].value;
        }
        return options[index].value;
    }

    private Option[] buildLive2dModelOptions(List<Live2dModelCatalog.ModelEntry> entries) {
        Option[] options = new Option[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Live2dModelCatalog.ModelEntry entry = entries.get(i);
            options[i] = new Option(entry.label, entry.path);
        }
        return options;
    }

    private void updateSelectedModelPreview(int position) {
        if (position < 0 || position >= live2dModelEntries.size()) {
            live2dPreviewImage.setImageDrawable(null);
            live2dPreviewEmptyText.setVisibility(View.VISIBLE);
            return;
        }
        Live2dModelCatalog.ModelEntry entry = live2dModelEntries.get(position);
        if (entry.previewAssetPath == null || entry.previewAssetPath.trim().isEmpty()) {
            live2dPreviewImage.setImageDrawable(null);
            live2dPreviewEmptyText.setVisibility(View.VISIBLE);
            return;
        }
        try (InputStream inputStream = getAssets().open(entry.previewAssetPath)) {
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) {
                live2dPreviewImage.setImageDrawable(null);
                live2dPreviewEmptyText.setVisibility(View.VISIBLE);
                return;
            }
            live2dPreviewImage.setImageBitmap(bitmap);
            live2dPreviewEmptyText.setVisibility(View.GONE);
        } catch (Exception ignored) {
            live2dPreviewImage.setImageDrawable(null);
            live2dPreviewEmptyText.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        if (currentTtsTask != null) {
            currentTtsTask.cancel();
            currentTtsTask = null;
        }
        audioPlayer.stop();
        super.onDestroy();
    }

    private static class Option {
        final String label;
        final String value;

        Option(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }
}
