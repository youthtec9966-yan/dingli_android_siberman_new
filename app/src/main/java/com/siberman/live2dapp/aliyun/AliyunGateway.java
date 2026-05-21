package com.siberman.live2dapp.aliyun;

import android.util.Base64;

import com.siberman.live2dapp.config.AliyunConfigStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class AliyunGateway {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final int WAV_HEADER_SIZE = 44;
    private static final int ASR_CHUNK_BYTES = 3200;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    public interface Callback<T> {
        void onSuccess(T value);

        void onError(String errorMessage);
    }

    public interface StreamCallback {
        void onDelta(String delta);

        void onComplete(String fullText);

        void onError(String errorMessage);
    }

    public interface AudioStreamCallback {
        void onAudioChunk(byte[] data);

        void onComplete();

        void onError(String errorMessage);
    }

    public interface RealtimeTranscriptionCallback {
        void onPartial(String transcript);

        void onComplete(String transcript);

        void onError(String errorMessage);
    }

    public static class ChatMessage {
        public final String role;
        public final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class CancelableTask {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private Runnable cancelAction;

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                if (cancelAction != null) {
                    cancelAction.run();
                }
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void setCancelAction(Runnable cancelAction) {
            this.cancelAction = cancelAction;
            if (cancelled.get() && this.cancelAction != null) {
                this.cancelAction.run();
            }
        }
    }

    public class StreamingSpeechSession extends CancelableTask {
        private final Object lock = new Object();
        private final AliyunConfigStore.Config config;
        private final AudioStreamCallback callback;
        private final String taskId = UUID.randomUUID().toString();
        private final List<String> pendingTexts = new ArrayList<>();
        private WebSocket webSocket;
        private boolean taskStarted;
        private boolean finishRequested;
        private boolean completed;

        private StreamingSpeechSession(AliyunConfigStore.Config config, AudioStreamCallback callback) {
            this.config = config;
            this.callback = callback;
            setCancelAction(this::closeSilently);
            open();
        }

        public void appendText(String text) {
            if (text == null) {
                return;
            }
            String normalized = text.trim();
            if (normalized.isEmpty() || isCancelled()) {
                return;
            }
            synchronized (lock) {
                if (completed) {
                    return;
                }
                if (!taskStarted || webSocket == null) {
                    pendingTexts.add(normalized);
                    return;
                }
            }
            sendContinue(normalized);
        }

        public void finishStreaming() {
            synchronized (lock) {
                if (completed) {
                    return;
                }
                finishRequested = true;
                if (!taskStarted || webSocket == null) {
                    return;
                }
            }
            sendFinish();
        }

        private void open() {
            EXECUTOR.execute(() -> {
                try {
                    Request request = new Request.Builder()
                            .url(resolveTtsWsUrl(config))
                            .header("Authorization", "Bearer " + config.dashscopeApiKey)
                            .build();
                    webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket webSocket, Response response) {
                            try {
                                webSocket.send(buildTtsRunTask(config, taskId).toString());
                            } catch (Exception e) {
                                notifyError(safeMessage(e));
                            }
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, String message) {
                            if (isCancelled()) {
                                return;
                            }
                            try {
                                JSONObject event = new JSONObject(message);
                                JSONObject header = event.optJSONObject("header");
                                String eventType = header == null ? "" : header.optString("event");
                                if ("task-started".equals(eventType)) {
                                    flushPendingTexts();
                                    return;
                                }
                                if ("task-failed".equals(eventType)) {
                                    notifyError(extractTtsTaskError(header));
                                    return;
                                }
                                if ("task-finished".equals(eventType)) {
                                    synchronized (lock) {
                                        completed = true;
                                    }
                                    callback.onComplete();
                                }
                            } catch (Exception e) {
                                notifyError(safeMessage(e));
                            }
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, ByteString bytes) {
                            if (!isCancelled()) {
                                byte[] chunk = bytes.toByteArray();
                                if (chunk.length > 0) {
                                    callback.onAudioChunk(chunk);
                                }
                            }
                        }

                        @Override
                        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                            if (!isCancelled()) {
                                notifyError(extractWsFailure(t, response));
                            }
                        }
                    });
                } catch (Exception e) {
                    notifyError(safeMessage(e));
                }
            });
        }

        private void flushPendingTexts() {
            List<String> bufferedTexts;
            boolean shouldFinish;
            synchronized (lock) {
                if (completed || isCancelled()) {
                    return;
                }
                taskStarted = true;
                bufferedTexts = new ArrayList<>(pendingTexts);
                pendingTexts.clear();
                shouldFinish = finishRequested;
            }
            for (String bufferedText : bufferedTexts) {
                sendContinue(bufferedText);
            }
            if (shouldFinish) {
                sendFinish();
            }
        }

        private void sendContinue(String text) {
            try {
                WebSocket socket = webSocket;
                if (socket != null) {
                    socket.send(buildTtsContinueTask(taskId, text).toString());
                }
            } catch (Exception e) {
                notifyError(safeMessage(e));
            }
        }

        private void sendFinish() {
            try {
                WebSocket socket = webSocket;
                if (socket != null) {
                    socket.send(buildTtsFinishTask(taskId).toString());
                }
            } catch (Exception e) {
                notifyError(safeMessage(e));
            }
        }

        private void closeSilently() {
            synchronized (lock) {
                completed = true;
            }
            if (webSocket != null) {
                try {
                    webSocket.cancel();
                } catch (Exception ignored) {
                }
            }
        }

        private void notifyError(String errorMessage) {
            synchronized (lock) {
                if (completed || isCancelled()) {
                    return;
                }
                completed = true;
            }
            callback.onError(errorMessage);
            closeSilently();
        }
    }

    public CancelableTask streamLlmReply(
            AliyunConfigStore.Config config,
            List<ChatMessage> messages,
            StreamCallback callback
    ) {
        CancelableTask task = new CancelableTask();
        EXECUTOR.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("model", config.llmModel);
                payload.put("stream", true);
                payload.put("stream_options", new JSONObject().put("include_usage", true));

                JSONArray jsonMessages = new JSONArray();
                if (config.systemPrompt != null && !config.systemPrompt.trim().isEmpty()) {
                    jsonMessages.put(new JSONObject()
                            .put("role", "system")
                            .put("content", config.systemPrompt));
                }
                for (ChatMessage message : messages) {
                    jsonMessages.put(new JSONObject()
                            .put("role", message.role)
                            .put("content", message.content));
                }
                payload.put("messages", jsonMessages);

                Request request = new Request.Builder()
                        .url(config.resolveBaseUrl() + "/compatible-mode/v1/chat/completions")
                        .header("Authorization", "Bearer " + config.dashscopeApiKey)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(JSON, payload.toString()))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException(extractError(response));
                    }
                    String fullText = consumeSse(response.body(), task, callback);
                    if (!task.isCancelled()) {
                        callback.onComplete(fullText);
                    }
                }
            } catch (Exception e) {
                if (!task.isCancelled()) {
                    callback.onError(safeMessage(e));
                }
            }
        });
        return task;
    }

    public void testLlm(AliyunConfigStore.Config config, Callback<String> callback) {
        ArrayList<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", "请只回复“LLM 测试通过”。"));
        completeLlm(config, messages, callback);
    }

    public class RealtimeAsrSession extends CancelableTask {
        private final Object lock = new Object();
        private final AliyunConfigStore.Config config;
        private final RealtimeTranscriptionCallback callback;
        private final List<byte[]> pendingAudioChunks = new ArrayList<>();
        private final StringBuilder partialTranscript = new StringBuilder();
        private WebSocket webSocket;
        private boolean sessionReady;
        private boolean finishRequested;
        private boolean completed;

        private RealtimeAsrSession(AliyunConfigStore.Config config, RealtimeTranscriptionCallback callback) {
            this.config = config;
            this.callback = callback;
            setCancelAction(this::closeSilently);
            open();
        }

        public void appendAudio(byte[] data, int size) {
            if (data == null || size <= 0 || isCancelled()) {
                return;
            }
            byte[] chunk = new byte[size];
            System.arraycopy(data, 0, chunk, 0, size);
            synchronized (lock) {
                if (completed) {
                    return;
                }
                if (!sessionReady || webSocket == null) {
                    pendingAudioChunks.add(chunk);
                    return;
                }
            }
            sendAudioChunk(chunk);
        }

        public void finishInput() {
            synchronized (lock) {
                if (completed) {
                    return;
                }
                finishRequested = true;
                if (!sessionReady || webSocket == null) {
                    return;
                }
            }
            commitAndFinish();
        }

        private void open() {
            EXECUTOR.execute(() -> {
                try {
                    String model = config.asrModel == null || config.asrModel.trim().isEmpty()
                            ? "qwen3-asr-flash-realtime"
                            : config.asrModel.trim();
                    Request request = new Request.Builder()
                            .url(resolveAsrWsUrl(config, model))
                            .header("Authorization", "Bearer " + config.dashscopeApiKey)
                            .header("OpenAI-Beta", "realtime=v1")
                            .build();
                    webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket webSocket, Response response) {
                            try {
                                webSocket.send(buildAsrSessionUpdate().toString());
                            } catch (Exception e) {
                                notifyError(safeMessage(e));
                            }
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, String text) {
                            if (isCancelled()) {
                                return;
                            }
                            try {
                                JSONObject event = new JSONObject(text);
                                String type = event.optString("type");
                                if ("session.created".equals(type) || "session.updated".equals(type)) {
                                    flushPendingAudio();
                                    return;
                                }
                                if ("conversation.item.input_audio_transcription.delta".equals(type)) {
                                    String delta = extractTranscriptText(event, "delta");
                                    if (!delta.isEmpty()) {
                                        String currentText;
                                        synchronized (lock) {
                                            if (completed) {
                                                return;
                                            }
                                            partialTranscript.append(delta);
                                            currentText = partialTranscript.toString();
                                        }
                                        callback.onPartial(currentText);
                                    }
                                    return;
                                }
                                if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                                    complete(extractTranscriptText(event, "transcript"));
                                    return;
                                }
                                if ("error".equals(type)) {
                                    notifyError(extractWsError(event));
                                    return;
                                }
                                if ("session.finished".equals(type)) {
                                    complete("");
                                }
                            } catch (Exception e) {
                                notifyError(safeMessage(e));
                            }
                        }

                        @Override
                        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                            if (!isCancelled()) {
                                notifyError(extractWsFailure(t, response));
                            }
                        }

                        @Override
                        public void onClosed(WebSocket webSocket, int code, String reason) {
                            if (!isCancelled()) {
                                complete("");
                            }
                        }
                    });
                } catch (Exception e) {
                    notifyError(safeMessage(e));
                }
            });
        }

        private void flushPendingAudio() {
            List<byte[]> bufferedChunks;
            boolean shouldFinish;
            synchronized (lock) {
                if (completed || isCancelled()) {
                    return;
                }
                sessionReady = true;
                bufferedChunks = new ArrayList<>(pendingAudioChunks);
                pendingAudioChunks.clear();
                shouldFinish = finishRequested;
            }
            for (byte[] bufferedChunk : bufferedChunks) {
                sendAudioChunk(bufferedChunk);
            }
            if (shouldFinish) {
                commitAndFinish();
            }
        }

        private void sendAudioChunk(byte[] chunk) {
            try {
                WebSocket socket = webSocket;
                if (socket == null) {
                    return;
                }
                socket.send(new JSONObject()
                        .put("type", "input_audio_buffer.append")
                        .put("audio", Base64.encodeToString(chunk, Base64.NO_WRAP))
                        .toString());
            } catch (Exception e) {
                notifyError(safeMessage(e));
            }
        }

        private void commitAndFinish() {
            try {
                WebSocket socket = webSocket;
                if (socket == null) {
                    return;
                }
                socket.send(new JSONObject().put("type", "input_audio_buffer.commit").toString());
                socket.send(new JSONObject().put("type", "session.finish").toString());
            } catch (Exception e) {
                notifyError(safeMessage(e));
            }
        }

        private void complete(String transcript) {
            String finalTranscript = transcript == null ? "" : transcript.trim();
            synchronized (lock) {
                if (completed || isCancelled()) {
                    return;
                }
                if (finalTranscript.isEmpty()) {
                    finalTranscript = partialTranscript.toString().trim();
                }
                completed = true;
            }
            if (finalTranscript.isEmpty()) {
                callback.onError("ASR 未返回最终识别结果");
            } else {
                callback.onComplete(finalTranscript);
            }
            closeSilently();
        }

        private String extractTranscriptText(JSONObject event, String preferredKey) {
            String value = event.optString(preferredKey, "").trim();
            if (!value.isEmpty()) {
                return value;
            }
            value = event.optString("transcript", "").trim();
            if (!value.isEmpty()) {
                return value;
            }
            value = event.optString("delta", "").trim();
            if (!value.isEmpty()) {
                return value;
            }
            JSONObject item = event.optJSONObject("item");
            if (item != null) {
                value = item.optString(preferredKey, "").trim();
                if (!value.isEmpty()) {
                    return value;
                }
                value = item.optString("transcript", "").trim();
                if (!value.isEmpty()) {
                    return value;
                }
                value = item.optString("text", "").trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
            return "";
        }

        private void closeSilently() {
            synchronized (lock) {
                completed = true;
            }
            if (webSocket != null) {
                try {
                    webSocket.cancel();
                } catch (Exception ignored) {
                }
            }
        }

        private void notifyError(String errorMessage) {
            synchronized (lock) {
                if (completed || isCancelled()) {
                    return;
                }
                completed = true;
            }
            callback.onError(errorMessage);
            closeSilently();
        }
    }

    public CancelableTask completeLlm(
            AliyunConfigStore.Config config,
            List<ChatMessage> messages,
            Callback<String> callback
    ) {
        return streamLlmReply(config, messages, new StreamCallback() {
            @Override
            public void onDelta(String delta) {
            }

            @Override
            public void onComplete(String fullText) {
                callback.onSuccess(fullText.trim());
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    public void transcribeAudio(
            AliyunConfigStore.Config config,
            File wavFile,
            Callback<String> callback
    ) {
        EXECUTOR.execute(() -> {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<String> transcriptRef = new AtomicReference<>("");
            AtomicReference<String> errorRef = new AtomicReference<>("");
            AtomicBoolean audioSent = new AtomicBoolean(false);
            final WebSocket[] socketRef = new WebSocket[1];
            try {
                String model = config.asrModel == null || config.asrModel.trim().isEmpty()
                        ? "qwen3-asr-flash-realtime"
                        : config.asrModel.trim();
                Request request = new Request.Builder()
                        .url(resolveAsrWsUrl(config, model))
                        .header("Authorization", "Bearer " + config.dashscopeApiKey)
                        .header("OpenAI-Beta", "realtime=v1")
                        .build();

                socketRef[0] = wsClient.newWebSocket(request, new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        try {
                            webSocket.send(buildAsrSessionUpdate().toString());
                        } catch (Exception e) {
                            errorRef.compareAndSet("", safeMessage(e));
                            finished.countDown();
                        }
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        try {
                            JSONObject event = new JSONObject(text);
                            String type = event.optString("type");
                            if (("session.created".equals(type) || "session.updated".equals(type))
                                    && audioSent.compareAndSet(false, true)) {
                                EXECUTOR.execute(() -> {
                                    try {
                                        sendAsrAudio(webSocket, wavFile);
                                    } catch (Exception e) {
                                        errorRef.compareAndSet("", safeMessage(e));
                                        finished.countDown();
                                    }
                                });
                                return;
                            }
                            if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                                transcriptRef.set(event.optString("transcript", "").trim());
                                return;
                            }
                            if ("error".equals(type)) {
                                errorRef.compareAndSet("", extractWsError(event));
                                finished.countDown();
                                return;
                            }
                            if ("session.finished".equals(type)) {
                                finished.countDown();
                            }
                        } catch (Exception e) {
                            errorRef.compareAndSet("", safeMessage(e));
                            finished.countDown();
                        }
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        errorRef.compareAndSet("", extractWsFailure(t, response));
                        finished.countDown();
                    }

                    @Override
                    public void onClosed(WebSocket webSocket, int code, String reason) {
                        finished.countDown();
                    }
                });

                if (!finished.await(45, TimeUnit.SECONDS)) {
                    errorRef.compareAndSet("", "ASR 超时，请稍后重试");
                }
                if (socketRef[0] != null) {
                    socketRef[0].close(1000, "done");
                }
                if (!errorRef.get().isEmpty()) {
                    throw new IOException(errorRef.get());
                }
                String transcript = transcriptRef.get() == null ? "" : transcriptRef.get().trim();
                if (transcript.isEmpty()) {
                    throw new IOException("ASR 未返回最终识别结果");
                }
                callback.onSuccess(transcript);
            } catch (Exception e) {
                if (socketRef[0] != null) {
                    socketRef[0].cancel();
                }
                callback.onError(safeMessage(e));
            }
        });
    }

    public StreamingSpeechSession openStreamingSpeechSession(
            AliyunConfigStore.Config config,
            AudioStreamCallback callback
    ) {
        return new StreamingSpeechSession(config, callback);
    }

    public RealtimeAsrSession openRealtimeAsrSession(
            AliyunConfigStore.Config config,
            RealtimeTranscriptionCallback callback
    ) {
        return new RealtimeAsrSession(config, callback);
    }

    public CancelableTask streamSpeech(
            AliyunConfigStore.Config config,
            String text,
            AudioStreamCallback callback
    ) {
        CancelableTask task = new CancelableTask();
        EXECUTOR.execute(() -> {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<String> errorRef = new AtomicReference<>("");
            String taskId = UUID.randomUUID().toString();
            final WebSocket[] socketRef = new WebSocket[1];
            try {
                Request request = new Request.Builder()
                        .url(resolveTtsWsUrl(config))
                        .header("Authorization", "Bearer " + config.dashscopeApiKey)
                        .build();

                socketRef[0] = wsClient.newWebSocket(request, new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        try {
                            webSocket.send(buildTtsRunTask(config, taskId).toString());
                        } catch (Exception e) {
                            errorRef.compareAndSet("", safeMessage(e));
                            finished.countDown();
                        }
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String message) {
                        if (task.isCancelled()) {
                            finished.countDown();
                            return;
                        }
                        try {
                            JSONObject event = new JSONObject(message);
                            JSONObject header = event.optJSONObject("header");
                            String eventType = header == null ? "" : header.optString("event");
                            if ("task-started".equals(eventType)) {
                                webSocket.send(buildTtsContinueTask(taskId, text).toString());
                                webSocket.send(buildTtsFinishTask(taskId).toString());
                                return;
                            }
                            if ("task-failed".equals(eventType)) {
                                errorRef.compareAndSet("", extractTtsTaskError(header));
                                finished.countDown();
                                return;
                            }
                            if ("task-finished".equals(eventType)) {
                                finished.countDown();
                            }
                        } catch (Exception e) {
                            errorRef.compareAndSet("", safeMessage(e));
                            finished.countDown();
                        }
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, ByteString bytes) {
                        if (task.isCancelled()) {
                            finished.countDown();
                            return;
                        }
                        byte[] pcmChunk = bytes.toByteArray();
                        if (pcmChunk.length > 0) {
                            callback.onAudioChunk(pcmChunk);
                        }
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        errorRef.compareAndSet("", extractWsFailure(t, response));
                        finished.countDown();
                    }

                    @Override
                    public void onClosed(WebSocket webSocket, int code, String reason) {
                        finished.countDown();
                    }
                });
                task.setCancelAction(() -> {
                    if (socketRef[0] != null) {
                        socketRef[0].cancel();
                    }
                    finished.countDown();
                });

                if (!finished.await(45, TimeUnit.SECONDS)) {
                    errorRef.compareAndSet("", "TTS 超时，请稍后重试");
                }
                if (socketRef[0] != null) {
                    socketRef[0].close(1000, "done");
                }
                if (task.isCancelled()) {
                    return;
                }
                if (!errorRef.get().isEmpty()) {
                    throw new IOException(errorRef.get());
                }
                callback.onComplete();
            } catch (Exception e) {
                if (socketRef[0] != null) {
                    socketRef[0].cancel();
                }
                if (!task.isCancelled()) {
                    callback.onError(safeMessage(e));
                }
            }
        });
        return task;
    }

    private String consumeSse(
            ResponseBody body,
            CancelableTask task,
            StreamCallback callback
    ) throws Exception {
        StringBuilder fullText = new StringBuilder();
        String[] lines = body.string().split("\n");
        for (String rawLine : lines) {
            if (task.isCancelled()) {
                break;
            }
            String line = rawLine.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                continue;
            }
            JSONObject chunk = new JSONObject(data);
            JSONArray choices = chunk.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                continue;
            }
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) {
                continue;
            }
            String text = delta.optString("content");
            if (text != null && !text.isEmpty()) {
                fullText.append(text);
                callback.onDelta(text);
            }
        }
        return fullText.toString();
    }

    private JSONObject buildAsrSessionUpdate() throws Exception {
        JSONObject session = new JSONObject();
        session.put("modalities", new JSONArray().put("text"));
        session.put("input_audio_format", "pcm");
        session.put("sample_rate", 16000);
        session.put("input_audio_transcription", new JSONObject().put("language", "zh"));
        session.put("turn_detection", JSONObject.NULL);
        return new JSONObject()
                .put("type", "session.update")
                .put("session", session);
    }

    private String resolveAsrWsUrl(AliyunConfigStore.Config config, String model) {
        return resolveWsBase(config, "/api-ws/v1/realtime") + "?model=" + model;
    }

    private String resolveTtsWsUrl(AliyunConfigStore.Config config) {
        return resolveWsBase(config, "/api-ws/v1/inference");
    }

    private String resolveWsBase(AliyunConfigStore.Config config, String path) {
        String baseUrl = config == null ? "" : config.resolveBaseUrl();
        boolean useIntl = baseUrl.contains("dashscope-intl.aliyuncs.com");
        return (useIntl ? "wss://dashscope-intl.aliyuncs.com" : "wss://dashscope.aliyuncs.com") + path;
    }

    private void sendAsrAudio(WebSocket webSocket, File wavFile) throws Exception {
        byte[] pcmBytes = readPcmBytesFromWav(wavFile);
        for (int offset = 0; offset < pcmBytes.length; offset += ASR_CHUNK_BYTES) {
            int size = Math.min(ASR_CHUNK_BYTES, pcmBytes.length - offset);
            byte[] chunk = new byte[size];
            System.arraycopy(pcmBytes, offset, chunk, 0, size);
            JSONObject appendEvent = new JSONObject()
                    .put("type", "input_audio_buffer.append")
                    .put("audio", Base64.encodeToString(chunk, Base64.NO_WRAP));
            webSocket.send(appendEvent.toString());
            if (offset + size < pcmBytes.length) {
                Thread.sleep(10);
            }
        }
        webSocket.send(new JSONObject().put("type", "input_audio_buffer.commit").toString());
        webSocket.send(new JSONObject().put("type", "session.finish").toString());
    }

    private JSONObject buildTtsRunTask(AliyunConfigStore.Config config, String taskId) throws Exception {
        String model = config.ttsModel == null || config.ttsModel.trim().isEmpty()
                ? "cosyvoice-v3-flash"
                : config.ttsModel.trim();
        String voice = config.ttsVoice == null || config.ttsVoice.trim().isEmpty()
                ? "longanyang"
                : config.ttsVoice.trim();
        JSONObject header = new JSONObject()
                .put("action", "run-task")
                .put("task_id", taskId)
                .put("streaming", "duplex");
        JSONObject parameters = new JSONObject()
                .put("text_type", "PlainText")
                .put("voice", voice)
                .put("format", "pcm")
                .put("sample_rate", 16000);
        JSONObject payload = new JSONObject()
                .put("task_group", "audio")
                .put("task", "tts")
                .put("function", "SpeechSynthesizer")
                .put("model", model)
                .put("input", new JSONObject())
                .put("parameters", parameters);
        return new JSONObject()
                .put("header", header)
                .put("payload", payload);
    }

    private JSONObject buildTtsContinueTask(String taskId, String text) throws Exception {
        return new JSONObject()
                .put("header", new JSONObject()
                        .put("action", "continue-task")
                        .put("task_id", taskId)
                        .put("streaming", "duplex"))
                .put("payload", new JSONObject()
                        .put("input", new JSONObject().put("text", text)));
    }

    private JSONObject buildTtsFinishTask(String taskId) throws Exception {
        return new JSONObject()
                .put("header", new JSONObject()
                        .put("action", "finish-task")
                        .put("task_id", taskId)
                        .put("streaming", "duplex"))
                .put("payload", new JSONObject().put("input", new JSONObject()));
    }

    private byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(file);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private byte[] readPcmBytesFromWav(File file) throws IOException {
        byte[] wavBytes = readAllBytes(file);
        if (wavBytes.length <= WAV_HEADER_SIZE) {
            throw new IOException("录音文件无效");
        }
        byte[] pcmBytes = new byte[wavBytes.length - WAV_HEADER_SIZE];
        System.arraycopy(wavBytes, WAV_HEADER_SIZE, pcmBytes, 0, pcmBytes.length);
        return pcmBytes;
    }

    private String extractError(Response response) {
        try {
            ResponseBody body = response.body();
            String responseBody = body == null ? "" : body.string();
            if (responseBody.isEmpty()) {
                return "请求失败，HTTP " + response.code();
            }
            JSONObject json = new JSONObject(responseBody);
            String message = json.optString("message");
            if (message.isEmpty()) {
                message = json.optString("code");
            }
            if (message.isEmpty()) {
                message = responseBody;
            }
            return "请求失败，HTTP " + response.code() + "，" + message;
        } catch (Exception e) {
            return "请求失败，HTTP " + response.code();
        }
    }

    private String extractWsFailure(Throwable t, Response response) {
        if (response != null) {
            try {
                return extractError(response);
            } catch (Exception ignored) {
            }
        }
        String message = t == null ? "" : t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "WebSocket 调用失败";
        }
        return message.trim();
    }

    private String extractWsError(JSONObject event) {
        String message = event.optString("message");
        if (message.isEmpty()) {
            message = event.optString("error");
        }
        if (message.isEmpty()) {
            JSONObject error = event.optJSONObject("error");
            if (error != null) {
                message = error.optString("message");
                if (message.isEmpty()) {
                    message = error.optString("code");
                }
            }
        }
        if (message.isEmpty()) {
            message = event.toString();
        }
        return message;
    }

    private String extractTtsTaskError(JSONObject header) {
        if (header == null) {
            return "TTS 任务失败";
        }
        String message = header.optString("error_message");
        if (message.isEmpty()) {
            message = header.optString("error_code");
        }
        if (message.isEmpty()) {
            message = "TTS 任务失败";
        }
        return message;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }
}
