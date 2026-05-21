package com.siberman.live2dapp.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamAudioPlayer {
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final byte[] END_MARKER = new byte[0];
    private static final int FRAME_BYTES = 640;

    private final Object lock = new Object();
    private LinkedBlockingQueue<byte[]> queue;
    private AudioTrack audioTrack;
    private Thread playbackThread;
    private Callback callback;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean streamOpen;
    private volatile float playbackGain = 1.0f;

    public interface Callback {
        void onStarted();

        void onCompleted();

        void onError(String errorMessage);

        default void onAudioLevel(float level) {
        }
    }

    public void startPcmStream(Callback callback) {
        stop();
        this.callback = callback;
        this.queue = new LinkedBlockingQueue<>();
        this.started.set(false);
        this.streamOpen = true;
        this.playbackThread = new Thread(this::playbackLoop, "pcm-stream-player");
        this.playbackThread.start();
    }

    public void appendPcmChunk(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        LinkedBlockingQueue<byte[]> currentQueue = queue;
        if (!streamOpen || currentQueue == null) {
            return;
        }
        currentQueue.offer(data);
    }

    public void finishStream() {
        streamOpen = false;
        LinkedBlockingQueue<byte[]> currentQueue = queue;
        if (currentQueue != null) {
            currentQueue.offer(END_MARKER);
        }
    }

    public boolean isPlaying() {
        AudioTrack track = audioTrack;
        return track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    public void setPlaybackGain(float gain) {
        float normalized = Math.max(0.0f, Math.min(1.0f, gain));
        playbackGain = normalized;
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                track.setVolume(normalized);
            } catch (Exception ignored) {
            }
        }
    }

    public void stop() {
        streamOpen = false;
        LinkedBlockingQueue<byte[]> currentQueue = queue;
        if (currentQueue != null) {
            currentQueue.clear();
            currentQueue.offer(END_MARKER);
        }
        Thread thread = playbackThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseTrack();
        playbackThread = null;
        queue = null;
        callback = null;
        started.set(false);
    }

    private void playbackLoop() {
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
            if (minBufferSize <= 0) {
                throw new IllegalStateException("无法初始化音频播放器");
            }
            audioTrack = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(ENCODING)
                            .setChannelMask(CHANNEL_MASK)
                            .build(),
                    Math.max(minBufferSize, FRAME_BYTES * 4),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            audioTrack.setVolume(playbackGain);
            audioTrack.play();

            while (true) {
                byte[] chunk = queue.take();
                if (chunk == END_MARKER) {
                    break;
                }
                if (chunk.length == 0) {
                    continue;
                }
                if (started.compareAndSet(false, true) && callback != null) {
                    callback.onStarted();
                }
                int offset = 0;
                while (offset < chunk.length) {
                    int size = Math.min(FRAME_BYTES, chunk.length - offset);
                    if (callback != null) {
                        callback.onAudioLevel(computeAudioLevel(chunk, offset, size));
                    }
                    audioTrack.write(chunk, offset, size, AudioTrack.WRITE_BLOCKING);
                    offset += size;
                }
            }

            if (audioTrack != null) {
                audioTrack.stop();
            }
            if (callback != null) {
                callback.onAudioLevel(0f);
            }
            if (callback != null && started.get()) {
                callback.onCompleted();
            }
        } catch (Exception e) {
            if (callback != null) {
                String message = e.getMessage() == null ? "音频播放失败" : e.getMessage();
                callback.onError(message);
            }
        } finally {
            releaseTrack();
            playbackThread = null;
            queue = null;
            streamOpen = false;
            started.set(false);
        }
    }

    private void releaseTrack() {
        synchronized (lock) {
            if (audioTrack == null) {
                return;
            }
            try {
                audioTrack.flush();
            } catch (Exception ignored) {
            }
            try {
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }

    private float computeAudioLevel(byte[] data, int offset, int size) {
        if (data == null || size < 2) {
            return 0f;
        }
        double sumSquares = 0;
        int samples = 0;
        int end = offset + size - 1;
        for (int i = offset; i < end; i += 2) {
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
        double boosted = Math.pow(Math.min(1.0, rms * 7.5), 0.72);
        if (boosted < 0.035) {
            return 0f;
        }
        return (float) Math.max(0.05, Math.min(1.0, boosted));
    }
}
