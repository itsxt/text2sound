package com.shengjian.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All job state is confined to the main thread; engine callbacks are marshalled to it. */
public final class SpeechEngine {
    public interface Listener {
        void onReady(boolean ready);
        void onProgress(String status, boolean busy, int done, int total);
        void onAudioReady(File file);
        void onError(String message);
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final List<String> chunks;
        final List<File> parts = new ArrayList<>();
        final boolean export;
        File directory;
        int index;
        boolean merging;
        Job(String text, boolean export) {
            chunks = new ArrayList<>();
            for (String part : TextChunks.split(text, Math.min(3000, TextToSpeech.getMaxSpeechInputLength() - 1))) {
                if (!part.trim().isEmpty()) chunks.add(part);
            }
            this.export = export;
        }
        String utteranceId() { return id + ":" + index; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Context context;
    private final Listener listener;
    private final AudioManager audioManager;
    private final AudioFocusRequest focus;
    private final TextToSpeech tts;
    private Job current;
    private boolean ready;
    private boolean closed;
    private boolean hasFocus;
    private Runnable timeout;

    public SpeechEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0 && current != null && !current.export)
                        stop("朗读已停止：其他应用正在使用音频");
                }, main).build();
        tts = new TextToSpeech(this.context, status -> main.post(() -> initialize(status, attributes)));
    }

    private void initialize(int status, AudioAttributes attributes) {
        if (closed) return;
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            tts.setAudioAttributes(attributes);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { main.post(() -> completeChunk(id)); }
                @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
                @Override public void onError(String id, int code) {
                    main.post(() -> {
                        if (matches(id)) fail(errorMessage(code));
                    });
                }
            });
        }
        listener.onReady(ready);
    }

    public boolean isReady() { return ready; }
    public boolean isBusy() { return current != null; }
    public String defaultEngine() { return tts.getDefaultEngine(); }
    public int setLanguage(Locale locale) { return ready ? tts.setLanguage(locale) : TextToSpeech.LANG_NOT_SUPPORTED; }

    public List<Voice> voices(Locale locale) {
        List<Voice> result = new ArrayList<>();
        Set<Voice> voices = ready ? tts.getVoices() : null;
        if (voices != null) for (Voice voice : voices) {
            Set<String> features = voice.getFeatures();
            if (voice.getLocale().getLanguage().equals(locale.getLanguage())
                    && (features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) result.add(voice);
        }
        result.sort(Comparator.comparing(Voice::isNetworkConnectionRequired)
                .thenComparing(v -> !v.getLocale().equals(locale))
                .thenComparing(Voice::getName));
        return result;
    }

    public void start(String text, boolean export, Locale locale, Voice voice, float rate, float pitch) {
        if (!ready || closed) { listener.onError("语音引擎尚未就绪，请检查系统语音设置"); return; }
        if (text.trim().isEmpty()) { listener.onError("请先输入要朗读的文字"); return; }
        stopInternal();
        if (tts.setLanguage(locale) < 0) { listener.onError("当前引擎缺少此语言，请安装对应语音包"); return; }
        if (voice != null && tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            listener.onError("此音色暂不可用，请更换音色或安装语音包"); return;
        }
        if (tts.setSpeechRate(rate) != TextToSpeech.SUCCESS || tts.setPitch(pitch) != TextToSpeech.SUCCESS) {
            listener.onError("当前引擎不支持此语速或音调设置"); return;
        }
        if (!export) {
            hasFocus = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) { listener.onError("暂时无法播放声音，请稍后重试"); return; }
        }
        current = new Job(text, export);
        if (export) {
            current.directory = new File(context.getCacheDir(), "speech-" + current.id);
            if (!current.directory.mkdirs()) { fail("无法创建音频缓存，请检查可用存储空间"); return; }
        }
        enqueue();
    }

    private boolean matches(String id) { return !closed && current != null && current.utteranceId().equals(id); }

    private void enqueue() {
        Job job = current;
        String id = job.utteranceId();
        String text = job.chunks.get(job.index);
        listener.onProgress((job.export ? "正在生成音频" : "正在朗读") + " · " + (job.index + 1) + " / " + job.chunks.size(),
                true, job.index, job.chunks.size());
        Bundle options = new Bundle();
        int result;
        if (job.export) {
            File part = new File(job.directory, "part-" + job.index + ".wav");
            job.parts.add(part);
            result = tts.synthesizeToFile(text, options, part, id);
        } else {
            result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, options, id);
        }
        if (result != TextToSpeech.SUCCESS) { fail("语音引擎未接受请求，请检查语音包或更换音色"); return; }
        // Export engines sometimes fail to report a terminal callback. Playback can be arbitrarily long.
        if (job.export) {
            timeout = () -> { if (matches(id)) fail("生成音频超时，请检查语音引擎后重试"); };
            main.postDelayed(timeout, 120000);
        }
    }

    private void completeChunk(String id) {
        if (!matches(id)) return;
        clearTimeout();
        Job job = current;
        job.index++;
        if (job.index < job.chunks.size()) { enqueue(); return; }
        if (!job.export) {
            current = null;
            releaseFocus();
            listener.onProgress("朗读完成", false, job.index, job.index);
            return;
        }
        job.merging = true;
        listener.onProgress("正在整理音频文件…", true, job.index, job.index);
        worker.execute(() -> {
            File output = new File(job.directory, "声笺.wav");
            try {
                WaveFiles.merge(job.parts, output);
                for (File part : job.parts) part.delete();
                main.post(() -> {
                    if (closed || current != job) { deleteTree(job.directory); return; }
                    current = null;
                    listener.onProgress("音频已生成，请选择保存位置", false, job.index, job.index);
                    listener.onAudioReady(output);
                });
            } catch (Exception error) {
                deleteTree(job.directory);
                main.post(() -> { if (current == job && !closed) fail("导出失败：" + error.getMessage()); });
            }
        });
    }

    private void clearTimeout() {
        if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
    }

    private void releaseFocus() {
        if (hasFocus) { audioManager.abandonAudioFocusRequest(focus); hasFocus = false; }
    }

    private void stopInternal() {
        Job old = current;
        current = null;
        clearTimeout();
        tts.stop();
        releaseFocus();
        if (old != null && old.directory != null && !old.merging) {
            // Give the remote engine time to close its output file after stop().
            main.postDelayed(() -> deleteTree(old.directory), 2000);
        }
    }

    public void stop(String status) {
        stopInternal();
        if (!closed) listener.onProgress(status, false, 0, 0);
    }

    private void fail(String message) {
        stopInternal();
        if (!closed) listener.onError(message);
    }

    public void close() {
        closed = true;
        ready = false;
        stopInternal();
        tts.shutdown();
        worker.shutdown();
    }

    public static void deleteTree(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static String errorMessage(int code) {
        switch (code) {
            case TextToSpeech.ERROR_NETWORK:
            case TextToSpeech.ERROR_NETWORK_TIMEOUT: return "此音色需要联网，请检查网络或选择离线音色";
            case TextToSpeech.ERROR_NOT_INSTALLED_YET: return "语音包尚未安装完成，请在系统设置中下载";
            case TextToSpeech.ERROR_OUTPUT: return "无法输出音频，请检查音量、音频设备和存储空间";
            case TextToSpeech.ERROR_INVALID_REQUEST: return "引擎无法处理这段文字，请缩短文字或更换音色";
            default: return "语音合成失败，请检查语音包或更换语音引擎";
        }
    }
}
