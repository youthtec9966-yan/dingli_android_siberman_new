package com.siberman.live2dapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import com.siberman.live2dapp.aliyun.AliyunGateway;
import com.siberman.live2dapp.audio.StreamAudioPlayer;
import com.siberman.live2dapp.audio.WavRecorder;
import com.siberman.live2dapp.config.AliyunConfigStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity {
    private static final String TAG = "SiberManLive2D";
    private static final String ENTRY_PAGE = "https://appassets.androidplatform.net/player/live2d_android.html";
    private static final int REQUEST_RECORD_AUDIO = 2001;
    private static final long AUTO_LISTEN_DELAY_MS = 120L;
    private static final long MIN_AUTO_ASR_TOTAL_MS = 700L;
    private static final long MIN_AUTO_ASR_SPEECH_MS = 220L;
    private static final long ASR_FINAL_FALLBACK_MS = 550L;
    private static final long PARTIAL_STABLE_COMMIT_MS = 420L;
    private static final long KWS_SESSION_TIMEOUT_MS = 30_000L;
    private static final String[] FILLER_ONLY_TERMS = new String[]{
            "嗯", "嗯嗯", "嗯哼", "哦", "哦哦", "噢", "啊", "啊啊", "呃", "额", "唉", "诶", "哎", "哈", "欸"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AliyunGateway gateway = new AliyunGateway();
    private final WavRecorder wavRecorder = new WavRecorder();
    private final StreamAudioPlayer audioPlayer = new StreamAudioPlayer();
    private final List<AliyunGateway.ChatMessage> history = new ArrayList<>();
    private final Runnable autoListenRunnable = this::startAutoListening;
    private final Runnable wakeWordStandbyRunnable = this::startWakeWordStandby;
    private final Runnable asrFinalFallbackRunnable = this::deliverPendingRecognitionFromFallback;
    private final Runnable stablePartialCommitRunnable = this::tryCommitStablePartialTranscript;
    private final Runnable kwsSessionTimeoutRunnable = this::handleKwsSessionTimeout;

    private WebView webView;
    private TextView emptyConversationText;
    private TextView wakeWordHintView;
    private LinearLayout conversationContainer;
    private ScrollView conversationScroll;
    private AliyunGateway.CancelableTask currentLlmTask;
    private AliyunGateway.CancelableTask currentTtsTask;
    private AliyunGateway.RealtimeAsrSession currentAsrSession;
    private KwsManager kwsManager;
    private boolean openingPlayed;
    private boolean pendingDirectListenAfterSpeech;
    private boolean pendingListenAfterPermission;
    private boolean recognitionInFlight;
    private boolean kwsConversationActive;
    private boolean kwsInterruptListening;
    private boolean kwsFallbackToDirectListening;
    private boolean wakeWordInterruptPromptActive;
    private boolean destroyed;
    private TextView pendingUserBubbleView;
    private String pendingUserTranscript = "";
    private AliyunConfigStore.Config activeAsrConfig;
    private boolean recognitionResultHandled;
    private volatile boolean latestVadSpeaking;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        webView = findViewById(R.id.web_view);
        emptyConversationText = findViewById(R.id.text_conversation_empty);
        wakeWordHintView = findViewById(R.id.text_wake_word_hint);
        conversationContainer = findViewById(R.id.conversation_container);
        conversationScroll = findViewById(R.id.scroll_conversation);
        kwsManager = new KwsManager(this);

        webView.setKeepScreenOn(true);
        enterImmersiveMode();
        registerBackHandler();
        bindTopBarActions();

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setBackgroundColor(0xFF000000);
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(@NonNull ConsoleMessage consoleMessage) {
                Log.d(TAG, consoleMessage.message() + " @" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber());
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(@NonNull WebView view, @NonNull WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageStarted(@NonNull WebView view, @NonNull String url, @Nullable Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d(TAG, "Loading page: " + url);
            }

            @Override
            public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page finished: " + url);
                initializeConversationFlow();
            }
        });

        String targetUrl = buildTargetUrl();
        Log.d(TAG, "Launching Live2D URL: " + targetUrl);
        webView.loadUrl(targetUrl);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    private void bindTopBarActions() {
        findViewById(R.id.button_open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class))
        );
    }

    private void enterImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private void registerBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                finish();
            }
        });
    }

    private String buildTargetUrl() {
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null && data.getQueryParameter("model") != null) {
            return ENTRY_PAGE + "?model=" + Uri.encode(data.getQueryParameter("model"), "/%");
        }
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        String modelPath = Live2dModelCatalog.resolveModelPath(this, config.live2dModelPath);
        return ENTRY_PAGE + "?model=" + Uri.encode(modelPath, "/%");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(autoListenRunnable);
        mainHandler.removeCallbacks(wakeWordStandbyRunnable);
        mainHandler.removeCallbacks(asrFinalFallbackRunnable);
        mainHandler.removeCallbacks(stablePartialCommitRunnable);
        mainHandler.removeCallbacks(kwsSessionTimeoutRunnable);
        stopSpeaking();
        cancelRealtimeAsrSession();
        clearPendingUserBubble();
        wavRecorder.stopSilently();
        if (kwsManager != null) {
            kwsManager.release();
            kwsManager = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void scheduleAutoListening() {
        scheduleAutoListening(AUTO_LISTEN_DELAY_MS);
    }

    private void scheduleAutoListening(long delayMs) {
        if (destroyed) {
            return;
        }
        mainHandler.removeCallbacks(autoListenRunnable);
        mainHandler.removeCallbacks(wakeWordStandbyRunnable);
        Runnable nextStep = shouldRouteToWakeWordStandby() ? wakeWordStandbyRunnable : autoListenRunnable;
        mainHandler.postDelayed(nextStep, Math.max(0L, delayMs));
    }

    private void scheduleDirectAutoListening(long delayMs) {
        if (destroyed) {
            return;
        }
        mainHandler.removeCallbacks(autoListenRunnable);
        mainHandler.removeCallbacks(wakeWordStandbyRunnable);
        mainHandler.postDelayed(autoListenRunnable, Math.max(0L, delayMs));
    }

    private void initializeConversationFlow() {
        kwsFallbackToDirectListening = false;
        pendingDirectListenAfterSpeech = false;
        kwsConversationActive = false;
        mainHandler.removeCallbacks(kwsSessionTimeoutRunnable);
        updateWakeWordOverlay(false);
        if (shouldUseWakeWord()) {
            openingPlayed = false;
            enterWakeWordStandbyUi();
            scheduleAutoListening(0L);
            return;
        }
        maybePlayOpeningMessage();
    }

    private boolean ensureConversationReady(boolean requestPermission) {
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        if (config.dashscopeApiKey == null || config.dashscopeApiKey.trim().isEmpty()) {
            setSessionState(getString(R.string.testing_need_api_key));
            sendLive2dHint("请先去设置页填写百炼 API Key");
            return false;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (requestPermission) {
                pendingListenAfterPermission = true;
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            } else {
                setSessionState(getString(R.string.player_state_need_permission));
            }
            return false;
        }
        return true;
    }

    private boolean shouldUseWakeWord() {
        if (kwsFallbackToDirectListening) {
            return false;
        }
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        return config.hasWakeWordConfig();
    }

    private boolean shouldRouteToWakeWordStandby() {
        return shouldUseWakeWord() && !kwsConversationActive;
    }

    private void startWakeWordStandby() {
        if (destroyed) {
            return;
        }
        kwsInterruptListening = false;
        if (!shouldUseWakeWord()) {
            startAutoListening();
            return;
        }
        if (!ensureConversationReady(true)) {
            return;
        }
        if (wavRecorder.isRecording() || recognitionInFlight || currentLlmTask != null || currentTtsTask != null) {
            return;
        }
        if (kwsManager == null || kwsManager.isListening()) {
            return;
        }
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        boolean started = kwsManager.start(config.wakeWordText, new KwsManager.Listener() {
            @Override
            public void onWakeWordDetected(String wakeWord) {
                runOnUiThread(() -> handleWakeWordDetected(wakeWord));
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    kwsConversationActive = false;
                    mainHandler.removeCallbacks(kwsSessionTimeoutRunnable);
                    stopWakeWordStandby();
                    enterWakeWordStandbyUi();
                    sendLive2dHint("唤醒监听失败：" + errorMessage);
                });
            }
        }, false);
        if (started) {
            enterWakeWordStandbyUi();
        } else {
            enterWakeWordStandbyUi();
        }
    }

    private void stopWakeWordStandby() {
        kwsInterruptListening = false;
        updateInterruptPlaybackGain();
        if (kwsManager != null) {
            kwsManager.stop();
        }
    }

    private void updateInterruptPlaybackGain() {
        audioPlayer.setPlaybackGain(kwsInterruptListening ? 0.35f : 1.0f);
    }

    private void handleWakeWordDetected(String wakeWord) {
        if (destroyed) {
            return;
        }
        stopWakeWordStandby();
        beginKwsConversationSession();
        updateWakeWordOverlay(false);
        sendLive2dStandby(false);
        setSessionState(getString(R.string.player_state_idle));
        sendLive2dHint("已唤醒：" + wakeWord);
        replayOpeningMessage(true);
    }

    private void enterWakeWordStandbyUi() {
        kwsConversationActive = false;
        mainHandler.removeCallbacks(kwsSessionTimeoutRunnable);
        setSessionState(getString(R.string.player_state_waiting_wake_word));
        sendLive2dHint("待机中，说出唤醒词即可开始对话");
        sendLive2dTalking(false);
        sendLive2dMouthLevel(0);
        sendLive2dStandby(true);
        updateWakeWordOverlay(true);
    }

    private void beginKwsConversationSession() {
        if (!shouldUseWakeWord()) {
            return;
        }
        kwsConversationActive = true;
        refreshKwsConversationTimeout();
    }

    private void startWakeWordInterruptListening() {
        if (destroyed || !shouldUseWakeWord() || wakeWordInterruptPromptActive) {
            return;
        }
        if (wavRecorder.isRecording() || recognitionInFlight || currentTtsTask == null) {
            return;
        }
        if (kwsManager == null || kwsManager.isListening()) {
            return;
        }
        mainHandler.removeCallbacks(autoListenRunnable);
        mainHandler.removeCallbacks(wakeWordStandbyRunnable);
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        kwsInterruptListening = true;
        updateInterruptPlaybackGain();
        boolean started = kwsManager.start(config.wakeWordText, new KwsManager.Listener() {
            @Override
            public void onWakeWordDetected(String wakeWord) {
                runOnUiThread(() -> handleWakeWordInterruptDetected(wakeWord));
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    kwsInterruptListening = false;
                    updateInterruptPlaybackGain();
                });
            }
        }, true);
        if (!started) {
            kwsInterruptListening = false;
            updateInterruptPlaybackGain();
        }
    }

    private void handleWakeWordInterruptDetected(String wakeWord) {
        if (destroyed || !shouldUseWakeWord()) {
            return;
        }
        stopWakeWordStandby();
        beginKwsConversationSession();
        updateWakeWordOverlay(false);
        sendLive2dStandby(false);
        pendingDirectListenAfterSpeech = false;
        if (currentLlmTask != null) {
            currentLlmTask.cancel();
            currentLlmTask = null;
        }
        if (currentTtsTask != null) {
            currentTtsTask.cancel();
            currentTtsTask = null;
        }
        audioPlayer.stop();
        sendLive2dTalking(false);
        sendLive2dMouthLevel(0);
        setSessionState(getString(R.string.player_state_idle));
        playWakeWordInterruptPrompt();
    }

    private void playWakeWordInterruptPrompt() {
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        wakeWordInterruptPromptActive = true;
        pendingDirectListenAfterSpeech = true;
        if (config.dashscopeApiKey != null && !config.dashscopeApiKey.trim().isEmpty()) {
            speakText(config, "请讲");
            return;
        }
        wakeWordInterruptPromptActive = false;
        scheduleDirectAutoListening(120L);
    }

    private void refreshKwsConversationTimeout() {
        if (destroyed || !shouldUseWakeWord() || !kwsConversationActive) {
            return;
        }
        mainHandler.removeCallbacks(kwsSessionTimeoutRunnable);
        mainHandler.postDelayed(kwsSessionTimeoutRunnable, KWS_SESSION_TIMEOUT_MS);
    }

    private void markKwsValidReply() {
        if (!shouldUseWakeWord()) {
            return;
        }
        kwsConversationActive = true;
        refreshKwsConversationTimeout();
    }

    private void handleKwsSessionTimeout() {
        if (destroyed || !shouldUseWakeWord() || !kwsConversationActive) {
            return;
        }
        returnToWakeWordStandby("超过 30 秒没有有效回复，已回到唤醒待机");
    }

    private void returnToWakeWordStandby(String hintText) {
        mainHandler.removeCallbacks(autoListenRunnable);
        mainHandler.removeCallbacks(wakeWordStandbyRunnable);
        mainHandler.removeCallbacks(asrFinalFallbackRunnable);
        mainHandler.removeCallbacks(stablePartialCommitRunnable);
        mainHandler.removeCallbacks(kwsSessionTimeoutRunnable);
        stopWakeWordStandby();
        kwsConversationActive = false;
        pendingDirectListenAfterSpeech = false;
        if (currentLlmTask != null) {
            currentLlmTask.cancel();
            currentLlmTask = null;
        }
        if (currentTtsTask != null) {
            currentTtsTask.cancel();
            currentTtsTask = null;
        }
        audioPlayer.stop();
        cancelRealtimeAsrSession();
        clearPendingUserBubble();
        wavRecorder.stopSilently();
        recognitionInFlight = false;
        sendLive2dTalking(false);
        sendLive2dMouthLevel(0);
        enterWakeWordStandbyUi();
        if (hintText != null && !hintText.trim().isEmpty()) {
            sendLive2dHint(hintText);
        }
        scheduleAutoListening(0L);
    }

    private void startAutoListening() {
        if (destroyed) {
            return;
        }
        stopWakeWordStandby();
        updateWakeWordOverlay(false);
        sendLive2dStandby(false);
        if (!ensureConversationReady(true)) {
            return;
        }
        if (wavRecorder.isRecording() || recognitionInFlight || currentLlmTask != null || currentTtsTask != null) {
            return;
        }
        try {
            activeAsrConfig = new AliyunConfigStore(this).load();
            latestVadSpeaking = false;
            startRealtimeAsrSession(activeAsrConfig);
            File file = new File(getCacheDir(), "player_input.wav");
            wavRecorder.start(file, new WavRecorder.Listener() {
                @Override
                public void onPcmChunk(byte[] data, int size) {
                    AliyunGateway.RealtimeAsrSession session = currentAsrSession;
                    if (session != null) {
                        session.appendAudio(data, size);
                    }
                }

                @Override
                public void onSpeechDetected() {
                    runOnUiThread(() -> {
                        setSessionState(getString(R.string.player_state_recording));
                        sendLive2dHint("我听到你开始说话了…");
                    });
                }

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
                    latestVadSpeaking = speaking;
                    if (speaking) {
                        mainHandler.removeCallbacks(stablePartialCommitRunnable);
                    } else {
                        scheduleStablePartialCommitIfNeeded();
                    }
                }

                @Override
                public void onSilenceTimeout() {
                    runOnUiThread(PlayerActivity.this::finishListeningAndRecognize);
                }

                @Override
                public void onMaxDurationReached() {
                    runOnUiThread(() -> {
                        sendLive2dHint("我先按这段内容去识别，你也可以继续下一句");
                        finishListeningAndRecognize();
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        setSessionState(getString(R.string.testing_error, errorMessage));
                        sendLive2dHint("录音出错了，请再试一次");
                        scheduleAutoListening(1200L);
                    });
                }
            });
            setSessionState(getString(R.string.player_state_listening));
            sendLive2dHint("正在自动聆听，直接说话就可以");
            sendLive2dTalking(false);
            sendLive2dMouthLevel(0);
        } catch (Exception e) {
            setSessionState(getString(R.string.testing_error, e.getMessage()));
            sendLive2dHint("启动聆听失败");
        }
    }

    private void stopListeningSilently() {
        mainHandler.removeCallbacks(autoListenRunnable);
        mainHandler.removeCallbacks(wakeWordStandbyRunnable);
        mainHandler.removeCallbacks(asrFinalFallbackRunnable);
        mainHandler.removeCallbacks(stablePartialCommitRunnable);
        stopWakeWordStandby();
        if (wavRecorder.isRecording()) {
            wavRecorder.stopSilently();
        }
        cancelRealtimeAsrSession();
    }

    private void finishListeningAndRecognize() {
        if (!wavRecorder.isRecording() || recognitionInFlight) {
            return;
        }
        recognitionInFlight = true;
        setSessionState(getString(R.string.player_state_recognizing));
        sendLive2dHint("正在识别语音…");

        try {
            wavRecorder.stop();
            long totalRecordedMs = wavRecorder.getTotalRecordedMs();
            long speechRecordedMs = wavRecorder.getSpeechRecordedMs();
            if (!wavRecorder.hasCapturedSpeech()) {
                recognitionInFlight = false;
                recognitionResultHandled = true;
                cancelRealtimeAsrSession();
                clearPendingUserBubble();
                setSessionState(getString(R.string.player_state_listening));
                sendLive2dHint("没听清楚，你可以直接再说一遍");
                scheduleAutoListening(300L);
                return;
            }
            if (totalRecordedMs < MIN_AUTO_ASR_TOTAL_MS || speechRecordedMs < MIN_AUTO_ASR_SPEECH_MS) {
                recognitionInFlight = false;
                recognitionResultHandled = true;
                cancelRealtimeAsrSession();
                clearPendingUserBubble();
                setSessionState(getString(R.string.player_state_listening));
                sendLive2dHint("这句有点短，你可以连续说完整一句，我会再听");
                scheduleAutoListening(250L);
                return;
            }
            AliyunGateway.RealtimeAsrSession session = currentAsrSession;
            if (session == null) {
                completeRecognitionResult(pendingUserTranscript);
                return;
            }
            session.finishInput();
            mainHandler.removeCallbacks(asrFinalFallbackRunnable);
            mainHandler.postDelayed(asrFinalFallbackRunnable, ASR_FINAL_FALLBACK_MS);
        } catch (Exception e) {
            recognitionInFlight = false;
            recognitionResultHandled = true;
            cancelRealtimeAsrSession();
            clearPendingUserBubble();
            setSessionState(getString(R.string.testing_error, e.getMessage()));
            scheduleAutoListening(1000L);
        }
    }

    private void startRealtimeAsrSession(AliyunConfigStore.Config config) {
        cancelRealtimeAsrSession();
        clearPendingUserBubble();
        recognitionResultHandled = false;
        mainHandler.removeCallbacks(stablePartialCommitRunnable);
        currentAsrSession = gateway.openRealtimeAsrSession(config, new AliyunGateway.RealtimeTranscriptionCallback() {
            @Override
            public void onPartial(String transcript) {
                runOnUiThread(() -> updatePendingUserTranscript(transcript));
            }

            @Override
            public void onComplete(String transcript) {
                runOnUiThread(() -> completeRecognitionResult(transcript));
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> handleRecognitionError(errorMessage));
            }
        });
    }

    private void updatePendingUserTranscript(String transcript) {
        String normalized = transcript == null ? "" : transcript.trim();
        if (normalized.isEmpty()) {
            return;
        }
        pendingUserTranscript = normalized;
        if (pendingUserBubbleView == null) {
            pendingUserBubbleView = addConversationBubble("user",
                    getString(R.string.message_user_prefix, normalized));
        } else {
            pendingUserBubbleView.setText(getString(R.string.message_user_prefix, normalized));
            scrollConversationToBottom();
        }
        scheduleStablePartialCommitIfNeeded();
        sendLive2dHint("正在实时识别你说的话…");
    }

    private void handleRecognitionResult(String transcript) {
        String normalized = transcript == null ? "" : transcript.trim();
        if (normalized.isEmpty()) {
            normalized = pendingUserTranscript == null ? "" : pendingUserTranscript.trim();
        }
        if (normalized.isEmpty()) {
            clearPendingUserBubble();
            setSessionState(getString(R.string.player_state_listening));
            sendLive2dHint("这句我没有听清，你可以继续说");
            scheduleAutoListening(300L);
            return;
        }
        TextView pendingUserView = ensurePendingUserBubble(normalized);
        validateAndHandleUserText(
                activeAsrConfig != null ? activeAsrConfig : new AliyunConfigStore(this).load(),
                normalized,
                pendingUserView
        );
        pendingUserBubbleView = null;
        pendingUserTranscript = "";
        activeAsrConfig = null;
    }

    private void completeRecognitionResult(String transcript) {
        if (recognitionResultHandled) {
            return;
        }
        recognitionResultHandled = true;
        mainHandler.removeCallbacks(asrFinalFallbackRunnable);
        mainHandler.removeCallbacks(stablePartialCommitRunnable);
        currentAsrSession = null;
        recognitionInFlight = false;
        handleRecognitionResult(transcript);
    }

    private void handleRecognitionError(String errorMessage) {
        if (recognitionResultHandled) {
            return;
        }
        if (!wavRecorder.isRecording() && pendingUserTranscript != null && !pendingUserTranscript.trim().isEmpty()) {
            completeRecognitionResult(pendingUserTranscript);
            return;
        }
        recognitionResultHandled = true;
        mainHandler.removeCallbacks(asrFinalFallbackRunnable);
        mainHandler.removeCallbacks(stablePartialCommitRunnable);
        currentAsrSession = null;
        recognitionInFlight = false;
        if (wavRecorder.isRecording()) {
            wavRecorder.stopSilently();
        }
        clearPendingUserBubble();
        setSessionState(getString(R.string.testing_error, errorMessage));
        sendLive2dHint("识别失败，请再试一次");
        scheduleAutoListening(1000L);
    }

    private void deliverPendingRecognitionFromFallback() {
        if (!recognitionInFlight || recognitionResultHandled) {
            return;
        }
        String fallbackText = pendingUserTranscript == null ? "" : pendingUserTranscript.trim();
        if (fallbackText.isEmpty()) {
            return;
        }
        cancelRealtimeAsrSession();
        completeRecognitionResult(fallbackText);
    }

    private void scheduleStablePartialCommitIfNeeded() {
        mainHandler.removeCallbacks(stablePartialCommitRunnable);
        if (destroyed || recognitionInFlight || latestVadSpeaking || !wavRecorder.isRecording()) {
            return;
        }
        String normalized = pendingUserTranscript == null ? "" : pendingUserTranscript.trim();
        if (normalized.isEmpty() || !shouldAllowStablePartialCommit(normalized)) {
            return;
        }
        if (!wavRecorder.hasCapturedSpeech() || wavRecorder.getSpeechRecordedMs() < MIN_AUTO_ASR_SPEECH_MS) {
            return;
        }
        mainHandler.postDelayed(stablePartialCommitRunnable, PARTIAL_STABLE_COMMIT_MS);
    }

    private void tryCommitStablePartialTranscript() {
        if (destroyed || recognitionInFlight || latestVadSpeaking || !wavRecorder.isRecording()) {
            return;
        }
        String normalized = pendingUserTranscript == null ? "" : pendingUserTranscript.trim();
        if (normalized.isEmpty() || !shouldAllowStablePartialCommit(normalized)) {
            return;
        }
        if (!wavRecorder.hasCapturedSpeech() || wavRecorder.getSpeechRecordedMs() < MIN_AUTO_ASR_SPEECH_MS) {
            return;
        }
        sendLive2dHint("我先按你刚说完的内容继续处理…");
        finishListeningAndRecognize();
    }

    private boolean shouldAllowStablePartialCommit(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim();
        if (normalized.length() >= 10) {
            return true;
        }
        char lastChar = normalized.charAt(normalized.length() - 1);
        return isSpeechBoundary(lastChar);
    }

    private TextView ensurePendingUserBubble(String normalized) {
        if (pendingUserBubbleView == null) {
            pendingUserBubbleView = addConversationBubble("user",
                    getString(R.string.message_user_prefix, normalized));
        } else {
            pendingUserBubbleView.setText(getString(R.string.message_user_prefix, normalized));
            scrollConversationToBottom();
        }
        return pendingUserBubbleView;
    }

    private void clearPendingUserBubble() {
        if (pendingUserBubbleView != null) {
            removeConversationBubble(pendingUserBubbleView);
            pendingUserBubbleView = null;
        }
        pendingUserTranscript = "";
        activeAsrConfig = null;
    }

    private void cancelRealtimeAsrSession() {
        mainHandler.removeCallbacks(asrFinalFallbackRunnable);
        mainHandler.removeCallbacks(stablePartialCommitRunnable);
        if (currentAsrSession != null) {
            currentAsrSession.cancel();
            currentAsrSession = null;
        }
    }

    private void validateAndHandleUserText(
            AliyunConfigStore.Config config,
            String normalized,
            TextView pendingUserView
    ) {
        if (shouldIgnoreByLocalRule(normalized)) {
            removeConversationBubble(pendingUserView);
            setSessionState(getString(R.string.player_state_listening));
            scheduleAutoListening(250L);
            return;
        }

        requestAssistantReplyOrIgnore(config, normalized, pendingUserView);
    }

    private void requestAssistantReplyOrIgnore(
            AliyunConfigStore.Config config,
            String normalized,
            TextView pendingUserView
    ) {
        stopListeningSilently();
        AliyunConfigStore.Config guardedConfig = copyConfigWithoutSystemPrompt(config);
        guardedConfig.systemPrompt = buildGuardedAssistantSystemPrompt(config.systemPrompt);
        ArrayList<AliyunGateway.ChatMessage> messages = new ArrayList<>(history);
        messages.add(new AliyunGateway.ChatMessage("user", normalized));

        final TextView[] pendingAssistantViewHolder = new TextView[1];
        final AliyunGateway.StreamingSpeechSession[] speechSessionHolder = new AliyunGateway.StreamingSpeechSession[1];
        final boolean[] acceptedReply = new boolean[]{false};
        final boolean[] validReplyMarked = new boolean[]{false};
        final int[] emittedAssistantLength = new int[]{0};
        StringBuilder assistantText = new StringBuilder();
        StringBuilder displayedAssistantText = new StringBuilder();
        StringBuilder pendingSpeechText = new StringBuilder();

        setSessionState(getString(R.string.player_state_thinking));
        sendLive2dHint("正在思考回复…");

        currentLlmTask = gateway.streamLlmReply(guardedConfig, messages, new AliyunGateway.StreamCallback() {
            @Override
            public void onDelta(String delta) {
                runOnUiThread(() -> {
                    assistantText.append(delta);
                    if (!acceptedReply[0] && !looksLikeIgnorePrefix(assistantText.toString())) {
                        acceptedReply[0] = true;
                        if (!validReplyMarked[0]) {
                            validReplyMarked[0] = true;
                            markKwsValidReply();
                        }
                        pendingAssistantViewHolder[0] = addConversationBubble(
                                "assistant",
                                getString(R.string.message_pending_assistant)
                        );
                        speechSessionHolder[0] = startAssistantSpeechSession(config);
                    }
                    if (!acceptedReply[0]) {
                        return;
                    }
                    appendAssistantReplyIncrement(
                            assistantText,
                            emittedAssistantLength,
                            displayedAssistantText,
                            pendingSpeechText,
                            pendingAssistantViewHolder[0],
                            speechSessionHolder[0],
                            false
                    );
                });
            }

            @Override
            public void onComplete(String fullText) {
                runOnUiThread(() -> {
                    currentLlmTask = null;
                    String reply = assistantText.toString().trim();
                    if (reply.isEmpty() && fullText != null) {
                        reply = fullText.trim();
                    }
                    if (isIgnoreReply(reply)) {
                        if (speechSessionHolder[0] != null) {
                            speechSessionHolder[0].cancel();
                            if (currentTtsTask == speechSessionHolder[0]) {
                                currentTtsTask = null;
                            }
                        }
                        audioPlayer.stop();
                        removeConversationBubble(pendingUserView);
                        if (pendingAssistantViewHolder[0] != null) {
                            removeConversationBubble(pendingAssistantViewHolder[0]);
                        }
                        setSessionState(getString(R.string.player_state_listening));
                        scheduleAutoListening(250L);
                        return;
                    }

                    if (!acceptedReply[0]) {
                        acceptedReply[0] = true;
                        if (!validReplyMarked[0]) {
                            validReplyMarked[0] = true;
                            markKwsValidReply();
                        }
                        pendingAssistantViewHolder[0] = addConversationBubble(
                                "assistant",
                                getString(R.string.message_pending_assistant)
                        );
                        speechSessionHolder[0] = startAssistantSpeechSession(config);
                    }

                    appendAssistantReplyIncrement(
                            assistantText,
                            emittedAssistantLength,
                            displayedAssistantText,
                            pendingSpeechText,
                            pendingAssistantViewHolder[0],
                            speechSessionHolder[0],
                            true
                    );

                    String finalReply = reply;
                    if (finalReply.isEmpty()) {
                        finalReply = displayedAssistantText.toString().trim();
                    }
                    if (finalReply.isEmpty()) {
                        finalReply = "我暂时没有生成有效回复。";
                    }

                    history.add(new AliyunGateway.ChatMessage("user", normalized));
                    history.add(new AliyunGateway.ChatMessage("assistant", finalReply));

                    if (pendingAssistantViewHolder[0] != null) {
                        pendingAssistantViewHolder[0].setText(
                                getString(R.string.message_assistant_prefix, finalReply)
                        );
                    }
                    if (speechSessionHolder[0] != null) {
                        speechSessionHolder[0].finishStreaming();
                    } else {
                        setSessionState(getString(R.string.player_state_idle));
                        sendLive2dHint("我说完了，你可以继续说");
                        scheduleAutoListening();
                    }
                    scrollConversationToBottom();
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    currentLlmTask = null;
                    if (speechSessionHolder[0] != null) {
                        speechSessionHolder[0].cancel();
                        if (currentTtsTask == speechSessionHolder[0]) {
                            currentTtsTask = null;
                        }
                    }
                    audioPlayer.stop();
                    history.add(new AliyunGateway.ChatMessage("user", normalized));
                    requestAssistantReply(config);
                });
            }
        });
    }

    private void appendAssistantReplyIncrement(
            StringBuilder assistantText,
            int[] emittedAssistantLength,
            StringBuilder displayedAssistantText,
            StringBuilder pendingSpeechText,
            TextView pendingAssistantView,
            AliyunGateway.StreamingSpeechSession speechSession,
            boolean flushAll
    ) {
        if (assistantText == null || emittedAssistantLength == null || displayedAssistantText == null) {
            return;
        }
        if (assistantText.length() > emittedAssistantLength[0]) {
            pendingSpeechText.append(assistantText.substring(emittedAssistantLength[0]));
            emittedAssistantLength[0] = assistantText.length();
        }
        if (pendingAssistantView == null) {
            return;
        }
        if (speechSession != null) {
            String emittedText = emitSpeakableText(pendingSpeechText, speechSession, flushAll);
            if (!emittedText.isEmpty()) {
                displayedAssistantText.append(emittedText);
                pendingAssistantView.setText(
                        getString(R.string.message_assistant_prefix, displayedAssistantText.toString())
                );
                scrollConversationToBottom();
            }
            return;
        }
        String fallbackText = flushAll
                ? assistantText.toString().trim()
                : assistantText.toString();
        pendingAssistantView.setText(getString(R.string.message_assistant_prefix, fallbackText));
        scrollConversationToBottom();
    }

    private String buildGuardedAssistantSystemPrompt(String originalPrompt) {
        String basePrompt = originalPrompt == null ? "" : originalPrompt.trim();
        String guardedPrompt =
                "先判断当前这句用户输入是否值得正式回答。\n" +
                        "如果明显是asr误识别、环境噪声、别人聊天残句、口头禅或无明确意义的内容，只能回复 IGNORE。\n" +
                        "如果是明确且有效的表达，就直接正常回答用户，不要回复 VALID，不要解释判断过程，也不要提到你做了判断。";
        if (basePrompt.isEmpty()) {
            return guardedPrompt;
        }
        return basePrompt + "\n\n" + guardedPrompt;
    }

    private boolean isIgnoreReply(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim()
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", "")
                .toUpperCase(Locale.ROOT);
        return "IGNORE".equals(normalized);
    }

    private boolean looksLikeIgnorePrefix(String text) {
        if (text == null) {
            return true;
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return true;
        }
        return "IGNORE".startsWith(normalized);
    }

    private void requestAssistantReply(AliyunConfigStore.Config config) {
        stopListeningSilently();
        TextView pendingAssistantView = addConversationBubble("assistant", getString(R.string.message_pending_assistant));
        StringBuilder assistantText = new StringBuilder();
        StringBuilder displayedAssistantText = new StringBuilder();
        StringBuilder pendingSpeechText = new StringBuilder();
        final boolean[] validReplyMarked = new boolean[]{false};
        setSessionState(getString(R.string.player_state_thinking));
        sendLive2dHint("正在思考回复…");
        AliyunGateway.StreamingSpeechSession speechSession = startAssistantSpeechSession(config);

        currentLlmTask = gateway.streamLlmReply(config, history, new AliyunGateway.StreamCallback() {
            @Override
            public void onDelta(String delta) {
                runOnUiThread(() -> {
                    assistantText.append(delta);
                    if (!validReplyMarked[0] && assistantText.toString().trim().length() > 0) {
                        validReplyMarked[0] = true;
                        markKwsValidReply();
                    }
                    pendingSpeechText.append(delta);
                    if (speechSession != null) {
                        String emittedText = emitSpeakableText(pendingSpeechText, speechSession, false);
                        if (!emittedText.isEmpty()) {
                            displayedAssistantText.append(emittedText);
                            pendingAssistantView.setText(getString(R.string.message_assistant_prefix, displayedAssistantText.toString()));
                            scrollConversationToBottom();
                        }
                    } else {
                        pendingAssistantView.setText(getString(R.string.message_assistant_prefix, assistantText.toString()));
                        scrollConversationToBottom();
                    }
                });
            }

            @Override
            public void onComplete(String fullText) {
                runOnUiThread(() -> {
                    String reply = assistantText.toString().trim();
                    if (reply.isEmpty() && fullText != null) {
                        reply = fullText.trim();
                    }
                    if (reply.isEmpty()) {
                        reply = "我暂时没有生成有效回复。";
                    }
                    if (!validReplyMarked[0] && !reply.trim().isEmpty()) {
                        validReplyMarked[0] = true;
                        markKwsValidReply();
                    }
                    history.add(new AliyunGateway.ChatMessage("assistant", reply));
                    currentLlmTask = null;
                    if (speechSession != null) {
                        if (assistantText.length() == 0) {
                            pendingSpeechText.append(reply);
                        }
                        String emittedText = emitSpeakableText(pendingSpeechText, speechSession, true);
                        if (!emittedText.isEmpty()) {
                            displayedAssistantText.append(emittedText);
                        }
                        String finalDisplayed = displayedAssistantText.toString().trim();
                        if (finalDisplayed.isEmpty()) {
                            finalDisplayed = reply;
                        }
                        pendingAssistantView.setText(getString(R.string.message_assistant_prefix, finalDisplayed));
                        speechSession.finishStreaming();
                    } else {
                        pendingAssistantView.setText(getString(R.string.message_assistant_prefix, reply));
                        setSessionState(getString(R.string.player_state_idle));
                        sendLive2dHint("我说完了，你可以继续说");
                        scheduleAutoListening();
                    }
                    scrollConversationToBottom();
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    currentLlmTask = null;
                    if (speechSession != null) {
                        speechSession.cancel();
                        if (currentTtsTask == speechSession) {
                            currentTtsTask = null;
                        }
                    }
                    audioPlayer.stop();
                    pendingAssistantView.setText(getString(R.string.message_assistant_prefix, "调用 LLM 失败"));
                    setSessionState(getString(R.string.testing_error, errorMessage));
                    sendLive2dHint("回复失败，请稍后重试");
                    scheduleAutoListening(1000L);
                });
            }
        });
    }

    private AliyunGateway.StreamingSpeechSession startAssistantSpeechSession(AliyunConfigStore.Config config) {
        if (config == null || config.dashscopeApiKey == null || config.dashscopeApiKey.trim().isEmpty()) {
            return null;
        }
        if (currentTtsTask != null) {
            currentTtsTask.cancel();
            currentTtsTask = null;
        }
        audioPlayer.stop();
        final AliyunGateway.StreamingSpeechSession[] holder = new AliyunGateway.StreamingSpeechSession[1];
        audioPlayer.startPcmStream(new StreamAudioPlayer.Callback() {
            @Override
            public void onStarted() {
                runOnUiThread(() -> {
                    setSessionState(getString(R.string.player_state_speaking));
                    sendLive2dHint("数字人正在边生成边说…");
                    sendLive2dTalking(true);
                });
            }

            @Override
            public void onCompleted() {
                runOnUiThread(() -> {
                    if (currentTtsTask != holder[0]) {
                        return;
                    }
                    currentTtsTask = null;
                    stopWakeWordStandby();
                    updateWakeWordOverlay(shouldRouteToWakeWordStandby());
                    setSessionState(getString(R.string.player_state_idle));
                    sendLive2dHint("我说完了，继续跟我聊吧");
                    sendLive2dTalking(false);
                    sendLive2dMouthLevel(0);
                    scheduleAutoListening();
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    if (currentTtsTask != holder[0]) {
                        return;
                    }
                    currentTtsTask = null;
                    stopWakeWordStandby();
                    updateWakeWordOverlay(shouldRouteToWakeWordStandby());
                    setSessionState(getString(R.string.testing_error, errorMessage));
                    sendLive2dHint("语音播放失败");
                    sendLive2dTalking(false);
                    sendLive2dMouthLevel(0);
                    scheduleAutoListening(1000L);
                });
            }

            @Override
            public void onAudioLevel(float level) {
                runOnUiThread(() -> sendLive2dMouthLevel(level));
            }
        });
        holder[0] = gateway.openStreamingSpeechSession(config, new AliyunGateway.AudioStreamCallback() {
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
                    if (currentTtsTask != holder[0]) {
                        return;
                    }
                    audioPlayer.stop();
                    currentTtsTask = null;
                    stopWakeWordStandby();
                    updateWakeWordOverlay(shouldRouteToWakeWordStandby());
                    setSessionState(getString(R.string.testing_error, errorMessage));
                    sendLive2dHint("TTS 请求失败");
                    sendLive2dTalking(false);
                    sendLive2dMouthLevel(0);
                    scheduleAutoListening(1000L);
                });
            }
        });
        currentTtsTask = holder[0];
        startWakeWordInterruptListening();
        updateWakeWordOverlay(false);
        return holder[0];
    }

    private String emitSpeakableText(
            StringBuilder pendingSpeechText,
            AliyunGateway.StreamingSpeechSession speechSession,
            boolean flushAll
    ) {
        StringBuilder emittedText = new StringBuilder();
        while (pendingSpeechText.length() > 0) {
            int cutIndex = findSpeakableCutIndex(pendingSpeechText, flushAll);
            if (cutIndex <= 0) {
                break;
            }
            String chunk = pendingSpeechText.substring(0, cutIndex).trim();
            pendingSpeechText.delete(0, cutIndex);
            trimLeadingWhitespace(pendingSpeechText);
            if (!chunk.isEmpty()) {
                sendLive2dSpeechCue(detectSpeechCue(chunk));
                speechSession.appendText(chunk);
                emittedText.append(chunk);
            }
        }
        return emittedText.toString();
    }

    private int findSpeakableCutIndex(CharSequence text, boolean flushAll) {
        if (text == null || text.length() == 0) {
            return -1;
        }
        if (flushAll) {
            return text.length();
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isSpeechBoundary(ch) && i >= 5) {
                return i + 1;
            }
        }
        if (text.length() >= 28) {
            for (int i = 20; i < text.length(); i++) {
                if (Character.isWhitespace(text.charAt(i))) {
                    return i + 1;
                }
            }
            return 24;
        }
        return -1;
    }

    private boolean isSpeechBoundary(char ch) {
        return ch == '，'
                || ch == '。'
                || ch == '！'
                || ch == '？'
                || ch == '；'
                || ch == ','
                || ch == '.'
                || ch == '!'
                || ch == '?'
                || ch == ';'
                || ch == '\n';
    }

    private void trimLeadingWhitespace(StringBuilder text) {
        while (text.length() > 0 && Character.isWhitespace(text.charAt(0))) {
            text.deleteCharAt(0);
        }
    }

    private void speakText(AliyunConfigStore.Config config, String text) {
        if (text == null || text.trim().isEmpty()) {
            setSessionState(getString(R.string.player_state_idle));
            return;
        }
        stopListeningSilently();
        if (currentTtsTask != null) {
            currentTtsTask.cancel();
            currentTtsTask = null;
        }
        audioPlayer.stop();
        setSessionState(getString(R.string.player_state_speaking));
        sendLive2dHint("数字人正在说话…");
        sendLive2dSpeechCue(detectSpeechCue(text));
        final boolean interruptionPrompt = wakeWordInterruptPromptActive;
        final AliyunGateway.CancelableTask[] taskHolder = new AliyunGateway.CancelableTask[1];
        audioPlayer.startPcmStream(new StreamAudioPlayer.Callback() {
            @Override
            public void onStarted() {
                runOnUiThread(() -> {
                    setSessionState(getString(R.string.player_state_speaking));
                    sendLive2dHint("数字人正在播报");
                    sendLive2dTalking(true);
                });
            }

            @Override
            public void onCompleted() {
                runOnUiThread(() -> {
                    if (currentTtsTask != taskHolder[0]) {
                        return;
                    }
                    currentTtsTask = null;
                    stopWakeWordStandby();
                    updateWakeWordOverlay(shouldRouteToWakeWordStandby());
                    setSessionState(getString(R.string.player_state_idle));
                    sendLive2dHint("我说完了，直接说话就行");
                    sendLive2dTalking(false);
                    sendLive2dMouthLevel(0);
                    boolean listenDirectly = pendingDirectListenAfterSpeech;
                    pendingDirectListenAfterSpeech = false;
                    if (interruptionPrompt) {
                        wakeWordInterruptPromptActive = false;
                    }
                    if (listenDirectly) {
                        scheduleDirectAutoListening(120L);
                    } else {
                        scheduleAutoListening();
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    if (currentTtsTask != taskHolder[0]) {
                        return;
                    }
                    currentTtsTask = null;
                    stopWakeWordStandby();
                    updateWakeWordOverlay(shouldRouteToWakeWordStandby());
                    setSessionState(getString(R.string.testing_error, errorMessage));
                    sendLive2dHint("语音播放失败");
                    sendLive2dTalking(false);
                    sendLive2dMouthLevel(0);
                    boolean listenDirectly = pendingDirectListenAfterSpeech;
                    pendingDirectListenAfterSpeech = false;
                    if (interruptionPrompt) {
                        wakeWordInterruptPromptActive = false;
                    }
                    if (listenDirectly) {
                        scheduleDirectAutoListening(300L);
                    } else {
                        scheduleAutoListening(1000L);
                    }
                });
            }

            @Override
            public void onAudioLevel(float level) {
                runOnUiThread(() -> sendLive2dMouthLevel(level));
            }
        });
        taskHolder[0] = gateway.streamSpeech(config, text, new AliyunGateway.AudioStreamCallback() {
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
                    if (currentTtsTask != taskHolder[0]) {
                        return;
                    }
                    audioPlayer.stop();
                    currentTtsTask = null;
                    stopWakeWordStandby();
                    updateWakeWordOverlay(shouldRouteToWakeWordStandby());
                    setSessionState(getString(R.string.testing_error, errorMessage));
                    sendLive2dHint("TTS 请求失败");
                    sendLive2dTalking(false);
                    sendLive2dMouthLevel(0);
                    boolean listenDirectly = pendingDirectListenAfterSpeech;
                    pendingDirectListenAfterSpeech = false;
                    if (interruptionPrompt) {
                        wakeWordInterruptPromptActive = false;
                    }
                    if (listenDirectly) {
                        scheduleDirectAutoListening(300L);
                    } else {
                        scheduleAutoListening(1000L);
                    }
                });
            }
        });
        currentTtsTask = taskHolder[0];
        if (interruptionPrompt) {
            stopWakeWordStandby();
        } else {
            startWakeWordInterruptListening();
        }
        updateWakeWordOverlay(false);
    }

    private void stopSpeaking() {
        stopListeningSilently();
        stopWakeWordStandby();
        pendingDirectListenAfterSpeech = false;
        wakeWordInterruptPromptActive = false;
        if (currentLlmTask != null) {
            currentLlmTask.cancel();
            currentLlmTask = null;
        }
        if (currentTtsTask != null) {
            currentTtsTask.cancel();
            currentTtsTask = null;
        }
        audioPlayer.stop();
        cancelRealtimeAsrSession();
        clearPendingUserBubble();
        setSessionState(getString(R.string.player_state_idle));
        sendLive2dHint("已停止播报");
        sendLive2dTalking(false);
        sendLive2dMouthLevel(0);
        recognitionInFlight = false;
        if (ensureConversationReady(false)) {
            scheduleAutoListening();
        }
    }

    private void maybePlayOpeningMessage() {
        if (openingPlayed) {
            return;
        }
        openingPlayed = true;
        kwsFallbackToDirectListening = false;
        replayOpeningMessage(false);
    }

    private void replayOpeningMessage(boolean listenAfterIntro) {
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        String intro = config.openingMessage;
        if (intro == null || intro.trim().isEmpty()) {
            intro = getString(R.string.player_intro_default);
        }
        pendingDirectListenAfterSpeech = listenAfterIntro;
        addConversationBubble("assistant", getString(R.string.message_assistant_prefix, intro));
        history.add(new AliyunGateway.ChatMessage("assistant", intro));
        if (config.dashscopeApiKey != null && !config.dashscopeApiKey.trim().isEmpty()) {
            speakText(config, intro);
        } else {
            setSessionState(getString(R.string.player_state_idle));
            if (listenAfterIntro) {
                scheduleDirectAutoListening(120L);
            }
        }
    }

    private TextView addConversationBubble(String role, String text) {
        if (emptyConversationText.getVisibility() == View.VISIBLE) {
            emptyConversationText.setVisibility(View.GONE);
        }
        TextView textView = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        textView.setLayoutParams(params);
        textView.setText(text);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textView.setPadding(dp(12), dp(10), dp(12), dp(10));
        textView.setTextColor(0xFFFFFFFF);
        if ("user".equals(role)) {
            textView.setBackgroundColor(0xFF1F2937);
        } else {
            textView.setBackgroundColor(0xFF0F766E);
        }
        conversationContainer.addView(textView);
        scrollConversationToBottom();
        return textView;
    }

    private void removeConversationBubble(TextView textView) {
        if (textView == null || conversationContainer == null) {
            return;
        }
        conversationContainer.removeView(textView);
        if (conversationContainer.getChildCount() == 1 && emptyConversationText != null) {
            emptyConversationText.setVisibility(View.GONE);
        }
    }

    private void scrollConversationToBottom() {
        mainHandler.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setSessionState(String text) {
        // Player page keeps the screen minimal; session state is maintained internally only.
    }

    private void sendLive2dHint(String text) {
        if (webView == null) {
            return;
        }
        // Disable transient page hints/status text and keep the player UI minimal.
        webView.evaluateJavascript("window.setAndroidHint && window.setAndroidHint('');", null);
        webView.evaluateJavascript("window.setAndroidStatus && window.setAndroidStatus('');", null);
    }

    private void sendLive2dTalking(boolean talking) {
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript("window.setAndroidTalking && window.setAndroidTalking(" + talking + ");", null);
    }

    private void sendLive2dStandby(boolean standby) {
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript("window.setAndroidStandby && window.setAndroidStandby(" + standby + ");", null);
    }

    private void sendLive2dMouthLevel(float level) {
        if (webView == null) {
            return;
        }
        float normalized = Math.max(0f, Math.min(1f, level));
        webView.evaluateJavascript("window.setAndroidMouthLevel && window.setAndroidMouthLevel(" + normalized + ");", null);
    }

    private void sendLive2dSpeechCue(String cue) {
        if (webView == null || cue == null || cue.isEmpty()) {
            return;
        }
        webView.evaluateJavascript("window.setAndroidSpeechCue && window.setAndroidSpeechCue('" + cue + "');", null);
    }

    private void updateWakeWordOverlay(boolean visible) {
        if (wakeWordHintView == null) {
            return;
        }
        AliyunConfigStore.Config config = new AliyunConfigStore(this).load();
        String wakeWord = KwsManager.describeWakeWord(config.wakeWordText);
        if (!shouldUseWakeWord() || wakeWord.isEmpty()) {
            wakeWordHintView.setVisibility(View.GONE);
            return;
        }
        if (currentTtsTask != null) {
            wakeWordHintView.setText(getString(R.string.player_wake_word_interrupt_overlay, wakeWord));
            wakeWordHintView.setVisibility(View.VISIBLE);
            return;
        }
        if (!visible) {
            wakeWordHintView.setVisibility(View.GONE);
            return;
        }
        wakeWordHintView.setText(getString(R.string.player_wake_word_overlay, wakeWord));
        wakeWordHintView.setVisibility(View.VISIBLE);
    }

    private String detectSpeechCue(String text) {
        if (text == null) {
            return "";
        }
        for (int i = text.length() - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || ch == '"' || ch == '\'' || ch == '”' || ch == '’') {
                continue;
            }
            if (ch == '，' || ch == '、' || ch == ',' || ch == ';' || ch == '；') {
                return "comma";
            }
            if (ch == '。' || ch == '.') {
                return "period";
            }
            if (ch == '？' || ch == '?') {
                return "question";
            }
            if (ch == '！' || ch == '!') {
                return "exclaim";
            }
            break;
        }
        return text.length() >= 18 ? "pause" : "";
    }

    private boolean shouldIgnoreByLocalRule(String text) {
        if (text == null) {
            return true;
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return true;
        }
        String simplified = normalized
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}，。！？、；：“”‘’…~·\\s]+", "")
                .toLowerCase(Locale.ROOT);
        if (simplified.isEmpty()) {
            return true;
        }
        for (String filler : FILLER_ONLY_TERMS) {
            if (simplified.equals(filler) || simplified.equals(filler + filler)) {
                return true;
            }
        }
        return simplified.length() <= 2 && simplified.matches("[嗯哦啊呃额欸诶哎哈唉]+");
    }

    private AliyunConfigStore.Config copyConfigWithoutSystemPrompt(AliyunConfigStore.Config source) {
        AliyunConfigStore.Config copy = new AliyunConfigStore.Config();
        copy.onboardingCompleted = source.onboardingCompleted;
        copy.dashscopeApiKey = source.dashscopeApiKey;
        copy.llmModel = source.llmModel;
        copy.asrModel = source.asrModel;
        copy.systemPrompt = "";
        copy.openingMessage = source.openingMessage;
        copy.live2dModelPath = source.live2dModelPath;
        copy.ttsModel = source.ttsModel;
        copy.ttsVoice = source.ttsVoice;
        copy.baseUrl = source.baseUrl;
        copy.wakeWordEnabled = source.wakeWordEnabled;
        copy.wakeWordText = source.wakeWordText;
        return copy;
    }

    private boolean shouldRetryListening(String errorMessage, long totalRecordedMs, long speechRecordedMs) {
        String normalized = errorMessage == null ? "" : errorMessage.trim();
        if (normalized.contains("ASR 未返回最终识别结果")) {
            return true;
        }
        return totalRecordedMs < 2200L || speechRecordedMs < 1000L;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (pendingListenAfterPermission) {
                pendingListenAfterPermission = false;
                if (shouldUseWakeWord()) {
                    enterWakeWordStandbyUi();
                }
                scheduleAutoListening(0L);
            }
            return;
        }
        pendingListenAfterPermission = false;
        setSessionState(getString(R.string.player_state_need_permission));
        sendLive2dHint("没有录音权限，暂时无法自动对话");
    }
}
