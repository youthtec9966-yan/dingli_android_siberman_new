package com.siberman.live2dapp.config;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class AliyunConfigStore {
    private static final String PREFS_NAME = "aliyun_config";
    private static final String KEY_ONBOARDING_COMPLETED = "onboarding_completed";
    private static final String KEY_DASHSCOPE_API_KEY = "dashscope_api_key";
    private static final String KEY_LLM_MODEL = "llm_model";
    private static final String KEY_ASR_MODEL = "asr_model";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_OPENING_MESSAGE = "opening_message";
    private static final String KEY_LIVE2D_MODEL_PATH = "live2d_model_path";
    private static final String KEY_TTS_MODEL = "tts_model";
    private static final String KEY_TTS_VOICE = "tts_voice";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_WAKE_WORD_ENABLED = "wake_word_enabled";
    private static final String KEY_WAKE_WORD_TEXT = "wake_word_text";
    private static final String LEGACY_KEY_ASR_APP_KEY = "asr_app_key";
    private static final String LEGACY_KEY_ASR_TOKEN = "asr_token";
    private static final String LEGACY_KEY_TTS_ENDPOINT = "tts_endpoint";

    private final SharedPreferences preferences;

    public AliyunConfigStore(Context context) {
        this.preferences = createPreferences(context.getApplicationContext());
    }

    private SharedPreferences createPreferences(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception ignored) {
            // Fallback keeps the app usable if device security services are unavailable.
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public Config load() {
        Config config = new Config();
        config.onboardingCompleted = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false);
        config.dashscopeApiKey = preferences.getString(KEY_DASHSCOPE_API_KEY, "a0aff0");
        config.llmModel = preferences.getString(KEY_LLM_MODEL, "qwen-plus");
        config.asrModel = normalizeAsrModel(firstNonBlank(
                preferences.getString(KEY_ASR_MODEL, ""),
                preferences.getString(LEGACY_KEY_ASR_APP_KEY, ""),
                "qwen3-asr-flash-realtime"
        ));
        config.systemPrompt = firstNonBlank(
                preferences.getString(KEY_SYSTEM_PROMPT, ""),
                preferences.getString(LEGACY_KEY_ASR_TOKEN, ""),
                "你是包头市公安局的数字人民警，负责为群众解答警务、法律法规、安全风险提示等方面的问题。你的回答专业、严谨且亲切，每次回答篇幅不超过150字，回答内容必须是纯文本不能有表情。"
        );
        config.openingMessage = preferences.getString(
                KEY_OPENING_MESSAGE,
                "你好！请问有什么可以帮你的？"
        );
        config.live2dModelPath = preferences.getString(KEY_LIVE2D_MODEL_PATH, "");
        config.ttsModel = normalizeTtsModel(firstNonBlank(
                preferences.getString(KEY_TTS_MODEL, ""),
                "cosyvoice-v3-flash"
        ));
        config.ttsVoice = normalizeTtsVoice(preferences.getString(KEY_TTS_VOICE, "longanyang"));
        config.baseUrl = firstNonBlank(
                preferences.getString(KEY_BASE_URL, ""),
                preferences.getString(LEGACY_KEY_TTS_ENDPOINT, ""),
                "https://dashscope.aliyuncs.com"
        );
        config.wakeWordEnabled = preferences.getBoolean(KEY_WAKE_WORD_ENABLED, false);
        config.wakeWordText = preferences.getString(KEY_WAKE_WORD_TEXT, "");
        return config;
    }

    public void save(Config config) {
        preferences.edit()
                .putBoolean(KEY_ONBOARDING_COMPLETED, config.onboardingCompleted)
                .putString(KEY_DASHSCOPE_API_KEY, nullToEmpty(config.dashscopeApiKey))
                .putString(KEY_LLM_MODEL, nullToEmpty(config.llmModel))
                .putString(KEY_ASR_MODEL, nullToEmpty(config.asrModel))
                .putString(KEY_SYSTEM_PROMPT, nullToEmpty(config.systemPrompt))
                .putString(KEY_OPENING_MESSAGE, nullToEmpty(config.openingMessage))
                .putString(KEY_LIVE2D_MODEL_PATH, nullToEmpty(config.live2dModelPath))
                .putString(KEY_TTS_MODEL, nullToEmpty(config.ttsModel))
                .putString(KEY_TTS_VOICE, nullToEmpty(config.ttsVoice))
                .putString(KEY_BASE_URL, nullToEmpty(config.baseUrl))
                .putBoolean(KEY_WAKE_WORD_ENABLED, config.wakeWordEnabled)
                .putString(KEY_WAKE_WORD_TEXT, nullToEmpty(config.wakeWordText))
                .apply();
    }

    public boolean hasCompletedOnboarding() {
        return preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeAsrModel(String value) {
        if ("qwen3.5-omni-flash".equalsIgnoreCase(value)
                || "paraformer-realtime-v1".equalsIgnoreCase(value)) {
            return "qwen3-asr-flash-realtime";
        }
        return value;
    }

    private String normalizeTtsModel(String value) {
        if ("qwen3-tts-flash".equalsIgnoreCase(value)) {
            return "cosyvoice-v3-flash";
        }
        return value;
    }

    private String normalizeTtsVoice(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "longanyang";
        }
        String normalized = value.trim();
        if ("Cherry".equalsIgnoreCase(normalized)) {
            return "longanyang";
        }
        return normalized;
    }

    public static class Config {
        public boolean onboardingCompleted;
        public String dashscopeApiKey;
        public String llmModel;
        public String asrModel;
        public String systemPrompt;
        public String openingMessage;
        public String live2dModelPath;
        public String ttsModel;
        public String ttsVoice;
        public String baseUrl;
        public boolean wakeWordEnabled;
        public String wakeWordText;

        public boolean hasAnyConfig() {
            return !isBlank(dashscopeApiKey)
                    || !isBlank(asrModel)
                    || !isBlank(ttsModel)
                    || !isBlank(ttsVoice)
                    || !isBlank(baseUrl);
        }

        public boolean hasRequiredConfig() {
            return !isBlank(dashscopeApiKey)
                    && !isBlank(llmModel)
                    && !isBlank(asrModel)
                    && !isBlank(ttsModel)
                    && !isBlank(ttsVoice);
        }

        public boolean hasWakeWordConfig() {
            return wakeWordEnabled && !isBlank(wakeWordText);
        }

        public String resolveBaseUrl() {
            if (isBlank(baseUrl)) {
                return "https://dashscope.aliyuncs.com";
            }
            if (baseUrl.endsWith("/")) {
                return baseUrl.substring(0, baseUrl.length() - 1);
            }
            return baseUrl;
        }

        private boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}
