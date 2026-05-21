package com.siberman.live2dapp.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class WavRecorder {
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final long MIN_SPEECH_MS = 320L;
    private static final long SILENCE_TIMEOUT_MS = 650L;
    private static final long MAX_RECORDING_MS = 12000L;
    private static final float MIN_VOICE_LEVEL = 0.03f;
    private static final float START_MARGIN = 0.028f;
    private static final float CONTINUE_MARGIN = 0.016f;
    private static final float NOISE_FLOOR_SMOOTHING = 0.12f;

    public interface Listener {
        default void onLevel(float level) {
        }

        default void onPcmChunk(byte[] data, int size) {
        }

        default void onVadDebug(
                float level,
                float noiseFloor,
                float startThreshold,
                float continueThreshold,
                boolean speaking,
                long totalRecordedMs,
                long speechRecordedMs
        ) {
        }

        default void onSpeechDetected() {
        }

        default void onSilenceTimeout() {
        }

        default void onMaxDurationReached() {
        }

        default void onError(String errorMessage) {
        }
    }

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording;
    private File outputFile;
    private Listener listener;
    private volatile boolean capturedSpeech;
    private volatile long totalRecordedMs;
    private volatile long speechRecordedMs;

    public void start(File file) throws IOException {
        start(file, null);
    }

    public void start(File file, Listener listener) throws IOException {
        stopSilently();

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuffer * 2, 4096);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("录音器初始化失败");
        }

        outputFile = file;
        this.listener = listener;
        capturedSpeech = false;
        totalRecordedMs = 0L;
        speechRecordedMs = 0L;
        writeEmptyHeader(file);
        isRecording = true;
        audioRecord.startRecording();

        recordingThread = new Thread(() -> writePcmLoop(bufferSize), "wav-recorder");
        recordingThread.start();
    }

    public File stop() throws IOException {
        if (!isRecording) {
            return outputFile;
        }

        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
        }
        try {
            if (recordingThread != null) {
                recordingThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        release();
        if (outputFile != null) {
            finalizeHeader(outputFile);
        }
        return outputFile;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public boolean hasCapturedSpeech() {
        return capturedSpeech;
    }

    public long getTotalRecordedMs() {
        return totalRecordedMs;
    }

    public long getSpeechRecordedMs() {
        return speechRecordedMs;
    }

    public void stopSilently() {
        try {
            stop();
        } catch (Exception ignored) {
            release();
        }
    }

    private void writePcmLoop(int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        boolean speechDetected = false;
        boolean silenceDispatched = false;
        boolean maxDurationDispatched = false;
        long speechDurationMs = 0L;
        long silenceDurationMs = 0L;
        float noiseFloor = 0.012f;
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            raf.seek(44);
            while (isRecording && audioRecord != null) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    raf.write(buffer, 0, read);
                    if (listener != null) {
                        byte[] chunk = new byte[read];
                        System.arraycopy(buffer, 0, chunk, 0, read);
                        listener.onPcmChunk(chunk, read);
                    }
                    float level = computeAudioLevel(buffer, read);
                    if (listener != null) {
                        listener.onLevel(level);
                    }
                    long chunkDurationMs = (long) ((read / 2f) * 1000f / SAMPLE_RATE);
                    totalRecordedMs += chunkDurationMs;
                    float startThreshold = Math.max(MIN_VOICE_LEVEL, noiseFloor + START_MARGIN);
                    float continueThreshold = Math.max(MIN_VOICE_LEVEL * 0.75f, noiseFloor + CONTINUE_MARGIN);

                    if (!speechDetected) {
                        noiseFloor += (level - noiseFloor) * NOISE_FLOOR_SMOOTHING;
                    } else if (level < continueThreshold) {
                        noiseFloor += (level - noiseFloor) * (NOISE_FLOOR_SMOOTHING * 0.35f);
                    }

                    boolean speakingFrame = level >= (speechDetected ? continueThreshold : startThreshold);
                    if (listener != null) {
                        listener.onVadDebug(
                                level,
                                noiseFloor,
                                startThreshold,
                                continueThreshold,
                                speakingFrame,
                                totalRecordedMs,
                                speechRecordedMs
                        );
                    }

                    if (level >= startThreshold) {
                        speechDurationMs += chunkDurationMs;
                        speechRecordedMs = speechDurationMs;
                        silenceDurationMs = 0L;
                        if (!speechDetected) {
                            speechDetected = true;
                            capturedSpeech = true;
                            if (listener != null) {
                                listener.onSpeechDetected();
                            }
                        }
                    } else if (speechDetected && level < continueThreshold) {
                        silenceDurationMs += chunkDurationMs;
                        if (!silenceDispatched
                                && speechDurationMs >= MIN_SPEECH_MS
                                && silenceDurationMs >= SILENCE_TIMEOUT_MS) {
                            silenceDispatched = true;
                            if (listener != null) {
                                listener.onSilenceTimeout();
                            }
                        }
                    } else if (speechDetected) {
                        silenceDurationMs = 0L;
                    }

                    if (!maxDurationDispatched && totalRecordedMs >= MAX_RECORDING_MS) {
                        maxDurationDispatched = true;
                        if (listener != null) {
                            listener.onMaxDurationReached();
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (listener != null) {
                listener.onError(e.getMessage() == null ? "录音写入失败" : e.getMessage());
            }
        }
    }

    private void release() {
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        recordingThread = null;
        listener = null;
    }

    private void writeEmptyHeader(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            for (int i = 0; i < 44; i++) {
                raf.write(0);
            }
        }
    }

    private void finalizeHeader(File file) throws IOException {
        long totalAudioLen = file.length() - 44;
        long totalDataLen = totalAudioLen + 36;
        long byteRate = SAMPLE_RATE * 2L;

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.writeBytes("RIFF");
            raf.writeInt(Integer.reverseBytes((int) totalDataLen));
            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");
            raf.writeInt(Integer.reverseBytes(16));
            raf.writeShort(Short.reverseBytes((short) 1));
            raf.writeShort(Short.reverseBytes((short) 1));
            raf.writeInt(Integer.reverseBytes(SAMPLE_RATE));
            raf.writeInt(Integer.reverseBytes((int) byteRate));
            raf.writeShort(Short.reverseBytes((short) 2));
            raf.writeShort(Short.reverseBytes((short) 16));
            raf.writeBytes("data");
            raf.writeInt(Integer.reverseBytes((int) totalAudioLen));
        }
    }

    private float computeAudioLevel(byte[] data, int size) {
        if (data == null || size < 2) {
            return 0f;
        }
        double sumSquares = 0d;
        int samples = 0;
        int end = size - 1;
        for (int i = 0; i < end; i += 2) {
            int sample = (data[i] & 0xff) | (data[i + 1] << 8);
            if (sample > 32767) {
                sample -= 65536;
            }
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
            samples++;
        }
        if (samples == 0) {
            return 0f;
        }
        double rms = Math.sqrt(sumSquares / samples);
        double boosted = Math.pow(Math.min(1.0, rms * 6.5), 0.7);
        if (boosted < 0.025) {
            return 0f;
        }
        return (float) Math.max(0.04, Math.min(1.0, boosted));
    }
}
