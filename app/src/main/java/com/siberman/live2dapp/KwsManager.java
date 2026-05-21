package com.siberman.live2dapp;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.k2fsa.sherpa.onnx.FeatureConfigKt;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterKt;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class KwsManager {
    private static final String TAG = "SiberManKws";
    private static final String MODEL_DIR = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01";
    private static final String DEFAULT_ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.onnx";
    private static final String DEFAULT_DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx";
    private static final String DEFAULT_JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.onnx";
    private static final String TOKENS_FILE = "tokens.txt";
    private static final String DEFAULT_KEYWORDS_FILE = "keywords.txt";
    private static final int MODEL_TYPE = 0;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int STREAM_BUFFER_SAMPLES = SAMPLE_RATE / 10;
    private static final String[] PINYIN_INITIALS = new String[]{
            "zh", "ch", "sh",
            "b", "p", "m", "f",
            "d", "t", "n", "l",
            "g", "k", "h",
            "j", "q", "x",
            "r", "z", "c", "s",
            "y", "w"
    };

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private KeywordSpotter keywordSpotter;
    private OnlineStream onlineStream;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler acousticEchoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl automaticGainControl;
    private Thread recordThread;
    private Listener listener;
    private volatile boolean listening;

    public interface Listener {
        void onWakeWordDetected(String wakeWord);

        void onError(String errorMessage);
    }

    public KwsManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public synchronized boolean start(@NonNull String wakeWordText, @NonNull Listener nextListener) {
        return start(wakeWordText, nextListener, false);
    }

    public synchronized boolean start(@NonNull String wakeWordText,
                                      @NonNull Listener nextListener,
                                      boolean interruptionMode) {
        stop();
        listener = nextListener;
        String validationError = validateWakeWordText(wakeWordText);
        if (validationError != null) {
            dispatchError(validationError);
            return false;
        }
        try {
            String modelIssue = resolveModelIssue(appContext.getAssets());
            if (modelIssue != null) {
                dispatchError(modelIssue);
                return false;
            }
            ArrayList<String> keywordLines = buildKeywordLines(wakeWordText);
            rebuildKeywordSpotter();
            String customKeywords = buildCustomKeywordsForStream(appContext.getAssets(), keywordLines);
            onlineStream = keywordSpotter.createStream(customKeywords);
            if (onlineStream == null || onlineStream.getPtr() == 0L) {
                String displayKeywords = customKeywords.isEmpty() ? describeWakeWord(wakeWordText) : customKeywords;
                dispatchError("KWS 初始化失败：无法创建检测流，请检查唤醒词格式或更换词语。当前关键词：" + displayKeywords);
                stop();
                return false;
            }

            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBufferSize <= 0) {
                dispatchError("KWS 初始化失败：无法获取麦克风缓冲区");
                stop();
                return false;
            }
            int audioBufferSize = Math.max(minBufferSize * 2, STREAM_BUFFER_SAMPLES * 2);
            audioRecord = new AudioRecord(
                    interruptionMode
                            ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            : MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    audioBufferSize
            );
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                dispatchError("KWS 初始化失败：麦克风不可用");
                stop();
                return false;
            }
            attachAudioEffects(audioRecord.getAudioSessionId());
            audioRecord.startRecording();
            Log.d(TAG, "KWS start success. interruptionMode=" + interruptionMode
                    + ", wakeWord=" + describeWakeWord(wakeWordText));
            listening = true;
            recordThread = new Thread(this::readLoop, "kws-audio-loop");
            recordThread.start();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "KWS start failed. interruptionMode=" + interruptionMode, t);
            dispatchError("KWS 启动失败：" + safeMessage(t));
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        listening = false;
        Thread thread = recordThread;
        recordThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(400L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        releaseRecorder();
        releaseStream();
        listener = null;
    }

    public synchronized void release() {
        stop();
        releaseKeywordSpotter();
    }

    public boolean isListening() {
        return listening;
    }

    @Nullable
    public static String validateWakeWordText(@Nullable String wakeWordText) {
        String normalized = normalizeWakeWordText(wakeWordText);
        if (normalized.isEmpty()) {
            return "已启用唤醒词，但还没有填写唤醒词内容";
        }
        try {
            buildKeywordLines(normalized);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Nullable
    public static String getSetupIssue(@NonNull Context context, @Nullable String wakeWordText) {
        String wakeWordIssue = validateWakeWordText(wakeWordText);
        if (wakeWordIssue != null) {
            return wakeWordIssue;
        }
        try {
            return resolveModelIssue(context.getAssets());
        } catch (IOException e) {
            return "无法检查 KWS 模型资源：" + safeMessage(e);
        }
    }

    @NonNull
    public static String describeWakeWord(@Nullable String wakeWordText) {
        String normalized = normalizeWakeWordText(wakeWordText);
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.replace("\n", " / ");
    }

    @NonNull
    private static String normalizeWakeWordText(@Nullable String wakeWordText) {
        if (wakeWordText == null) {
            return "";
        }
        String[] lines = wakeWordText.replace("\r", "\n").split("\n");
        ArrayList<String> normalized = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return String.join("\n", normalized);
    }

    @NonNull
    private static ArrayList<String> buildKeywordLines(@NonNull String wakeWordText) {
        String normalized = normalizeWakeWordText(wakeWordText);
        ArrayList<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains("@")) {
                lines.add(trimmed);
            } else {
                lines.add(convertPlainWakeWord(trimmed));
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("唤醒词不能为空");
        }
        return lines;
    }

    @NonNull
    private static String convertPlainWakeWord(@NonNull String wakeWord) {
        StringBuilder tokenBuilder = new StringBuilder();
        StringBuilder labelBuilder = new StringBuilder();
        for (int i = 0; i < wakeWord.length(); i++) {
            char ch = wakeWord.charAt(i);
            if (Character.isWhitespace(ch) || isIgnorablePunctuation(ch)) {
                continue;
            }
            if (!isChineseCharacter(ch)) {
                throw new IllegalArgumentException("当前唤醒词只支持中文，或直接填写 sherpa 关键词格式");
            }
            String syllable = toToneMarkedPinyin(ch);
            if (syllable == null || syllable.isEmpty()) {
                throw new IllegalArgumentException("无法为唤醒词生成拼音：" + wakeWord);
            }
            appendSyllableTokens(tokenBuilder, splitPinyinTokens(syllable));
            labelBuilder.append(ch);
        }
        if (tokenBuilder.length() == 0 || labelBuilder.length() == 0) {
            throw new IllegalArgumentException("唤醒词不能为空");
        }
        return tokenBuilder + " @" + labelBuilder;
    }

    private synchronized void rebuildKeywordSpotter() {
        releaseKeywordSpotter();
        OnlineModelConfig modelConfig = KeywordSpotterKt.getKwsModelConfig(MODEL_TYPE);
        if (modelConfig == null) {
            throw new IllegalStateException("KWS 模型配置不可用");
        }
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(FeatureConfigKt.getFeatureConfig(SAMPLE_RATE, 80));
        config.setModelConfig(modelConfig);
        config.setKeywordsFile(KeywordSpotterKt.getKeywordsFile(MODEL_TYPE));
        config.setKeywordsScore(1.0f);
        config.setKeywordsThreshold(0.25f);
        config.setMaxActivePaths(4);
        config.setNumTrailingBlanks(1);
        keywordSpotter = new KeywordSpotter(appContext.getAssets(), config);
    }

    private void readLoop() {
        short[] buffer = new short[STREAM_BUFFER_SAMPLES];
        try {
            while (listening) {
                AudioRecord currentRecord = audioRecord;
                OnlineStream currentStream = onlineStream;
                KeywordSpotter currentSpotter = keywordSpotter;
                if (currentRecord == null || currentStream == null || currentSpotter == null) {
                    break;
                }
                int read = currentRecord.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                float[] samples = new float[read];
                for (int i = 0; i < read; i++) {
                    samples[i] = buffer[i] / 32768.0f;
                }
                currentStream.acceptWaveform(samples, SAMPLE_RATE);
                while (listening && currentSpotter.isReady(currentStream)) {
                    currentSpotter.decode(currentStream);
                    KeywordSpotterResult result = currentSpotter.getResult(currentStream);
                    String keyword = result == null ? "" : result.getKeyword();
                    if (keyword != null && !keyword.trim().isEmpty()) {
                        Log.d(TAG, "KWS detected: " + keyword.trim());
                        listening = false;
                        currentSpotter.reset(currentStream);
                        dispatchWakeWordDetected(keyword.trim());
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            if (listening) {
                Log.e(TAG, "KWS read loop failed", t);
                dispatchError("KWS 监听失败：" + safeMessage(t));
            }
        } finally {
            listening = false;
            releaseRecorder();
            releaseStream();
            recordThread = null;
        }
    }

    private synchronized void releaseRecorder() {
        if (audioRecord == null) {
            releaseAudioEffects();
            return;
        }
        try {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
        } catch (Throwable ignored) {
        }
        audioRecord.release();
        audioRecord = null;
        releaseAudioEffects();
    }

    private synchronized void attachAudioEffects(int audioSessionId) {
        releaseAudioEffects();
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId);
                if (acousticEchoCanceler != null) {
                    acousticEchoCanceler.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId);
                if (automaticGainControl != null) {
                    automaticGainControl.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private synchronized void releaseAudioEffects() {
        if (acousticEchoCanceler != null) {
            try {
                acousticEchoCanceler.release();
            } catch (Throwable ignored) {
            }
            acousticEchoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.release();
            } catch (Throwable ignored) {
            }
            noiseSuppressor = null;
        }
        if (automaticGainControl != null) {
            try {
                automaticGainControl.release();
            } catch (Throwable ignored) {
            }
            automaticGainControl = null;
        }
    }

    private synchronized void releaseStream() {
        if (onlineStream == null) {
            return;
        }
        try {
            onlineStream.release();
        } catch (Throwable ignored) {
        }
        try {
            onlineStream.setPtr(0L);
        } catch (Throwable ignored) {
        }
        onlineStream = null;
    }

    private synchronized void releaseKeywordSpotter() {
        if (keywordSpotter == null) {
            return;
        }
        try {
            keywordSpotter.release();
        } catch (Throwable ignored) {
        }
        keywordSpotter = null;
    }

    private void dispatchWakeWordDetected(@NonNull String wakeWord) {
        Listener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        mainHandler.post(() -> currentListener.onWakeWordDetected(wakeWord));
    }

    private void dispatchError(@NonNull String errorMessage) {
        Listener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        mainHandler.post(() -> currentListener.onError(errorMessage));
    }

    @Nullable
    private static String resolveModelIssue(@NonNull AssetManager assetManager) throws IOException {
        String[] files = assetManager.list(MODEL_DIR);
        if (files == null || files.length == 0) {
            return "缺少 KWS 模型目录：" + MODEL_DIR;
        }
        ArrayList<String> missing = new ArrayList<>();
        if (!contains(files, DEFAULT_ENCODER)) {
            missing.add(DEFAULT_ENCODER);
        }
        if (!contains(files, DEFAULT_DECODER)) {
            missing.add(DEFAULT_DECODER);
        }
        if (!contains(files, DEFAULT_JOINER)) {
            missing.add(DEFAULT_JOINER);
        }
        if (!contains(files, TOKENS_FILE)) {
            missing.add(TOKENS_FILE);
        }
        if (!contains(files, DEFAULT_KEYWORDS_FILE)) {
            missing.add(DEFAULT_KEYWORDS_FILE);
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "KWS 模型资源不完整，缺少：" + String.join("、", missing);
    }

    @NonNull
    private static String buildCustomKeywordsForStream(@NonNull AssetManager assetManager,
                                                       @NonNull List<String> keywordLines) throws IOException {
        Set<String> defaultLabels = new HashSet<>();
        Set<String> defaultLines = new HashSet<>();
        for (String line : readDefaultKeywordLines(assetManager)) {
            String normalized = line.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            defaultLines.add(normalized);
            String label = extractKeywordLabel(normalized);
            if (!label.isEmpty()) {
                defaultLabels.add(label);
            }
        }
        ArrayList<String> customLines = new ArrayList<>();
        for (String line : keywordLines) {
            String normalized = line.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            String label = extractKeywordLabel(normalized);
            if (defaultLines.contains(normalized) || (!label.isEmpty() && defaultLabels.contains(label))) {
                continue;
            }
            customLines.add(normalized);
        }
        return joinWithSlash(customLines);
    }

    private static void validateKeywordTokens(@NonNull AssetManager assetManager,
                                              @NonNull List<String> keywordLines) throws IOException {
        Set<String> validTokens = loadSupportedTokens(assetManager);
        for (String line : keywordLines) {
            String normalized = line == null ? "" : line.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            String tokenPart = normalized;
            int atIndex = normalized.indexOf('@');
            if (atIndex >= 0) {
                tokenPart = normalized.substring(0, atIndex).trim();
            }
            for (String token : tokenPart.split("\\s+")) {
                String trimmed = normalizePinyinToken(token);
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!validTokens.contains(trimmed)) {
                    throw new IllegalArgumentException("当前唤醒词包含模型不支持的拼音片段：" + trimmed);
                }
            }
        }
    }

    @NonNull
    private static Set<String> loadSupportedTokens(@NonNull AssetManager assetManager) throws IOException {
        HashSet<String> tokens = new HashSet<>();
        try (InputStream inputStream = assetManager.open(MODEL_DIR + "/" + TOKENS_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int firstSpace = trimmed.indexOf(' ');
                String token = firstSpace > 0 ? trimmed.substring(0, firstSpace).trim() : trimmed;
                token = normalizePinyinToken(token);
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    @NonNull
    private static List<String> readDefaultKeywordLines(@NonNull AssetManager assetManager) throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        try (InputStream inputStream = assetManager.open(MODEL_DIR + "/" + DEFAULT_KEYWORDS_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        }
        return lines;
    }

    @NonNull
    private static String extractKeywordLabel(@NonNull String keywordLine) {
        int atIndex = keywordLine.indexOf('@');
        if (atIndex < 0 || atIndex >= keywordLine.length() - 1) {
            return "";
        }
        return keywordLine.substring(atIndex + 1).trim();
    }

    private static boolean contains(String[] files, String target) {
        for (String file : files) {
            if (target.equalsIgnoreCase(file)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String toToneMarkedPinyin(char ch) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
        format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
        try {
            String[] values = PinyinHelper.toHanyuPinyinStringArray(ch, format);
            if (values == null || values.length == 0) {
                return null;
            }
            return values[0];
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            return null;
        }
    }

    @NonNull
    private static List<String> splitPinyinTokens(@NonNull String syllable) {
        String normalized = normalizePinyinToken(syllable);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("唤醒词拼音为空");
        }
        for (String initial : PINYIN_INITIALS) {
            if (!normalized.startsWith(initial) || normalized.length() <= initial.length()) {
                continue;
            }
            String remaining = normalized.substring(initial.length());
            if (!remaining.isEmpty()) {
                ArrayList<String> tokens = new ArrayList<>(2);
                tokens.add(initial);
                tokens.add(remaining);
                return tokens;
            }
        }
        ArrayList<String> tokens = new ArrayList<>(1);
        tokens.add(normalized);
        return tokens;
    }

    private static void appendSyllableTokens(StringBuilder builder, List<String> tokens) {
        for (String token : tokens) {
            String normalized = normalizePinyinToken(token);
            if (normalized.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(normalized);
        }
    }

    @NonNull
    private static String normalizePinyinToken(@Nullable String token) {
        if (token == null) {
            return "";
        }
        String trimmed = token.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(trimmed, Normalizer.Form.NFC);
    }

    private static boolean isChineseCharacter(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    private static boolean isIgnorablePunctuation(char ch) {
        return ch == '，'
                || ch == '。'
                || ch == '、'
                || ch == '！'
                || ch == '？'
                || ch == ','
                || ch == '.'
                || ch == '!'
                || ch == '?';
    }

    @NonNull
    private static String joinWithSlash(@NonNull List<String> values) {
        return String.join("/", values);
    }

    @NonNull
    private static String safeMessage(@Nullable Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()) {
            return "未知错误";
        }
        return throwable.getMessage().trim();
    }
}
