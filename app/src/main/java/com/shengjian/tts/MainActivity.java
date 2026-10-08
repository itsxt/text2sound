package com.shengjian.tts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements SpeechEngine.Listener {
    private static final int CREATE_AUDIO = 301;
    private static final int TEXT_LIMIT = 20000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Runnable saveDraft = this::persist;
    private SharedPreferences preferences;
    private EditText editor;
    private Spinner voice;
    private SeekBar rate;
    private SeekBar pitch;
    private TextView status;
    private TextView count;
    private Button play;
    private Button export;
    private ProgressBar progress;
    private SpeechEngine engine;
    private List<Voice> voices = new ArrayList<>();
    private boolean languageReady;
    private boolean reloadOnResume;
    private boolean saving;
    private File pendingExport;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences("shengjian", MODE_PRIVATE);
        editor = findViewById(R.id.editor);
        voice = findViewById(R.id.voice);
        rate = findViewById(R.id.rate);
        pitch = findViewById(R.id.pitch);
        status = findViewById(R.id.status);
        count = findViewById(R.id.count);
        play = findViewById(R.id.play);
        export = findViewById(R.id.export);
        progress = findViewById(R.id.progress);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(TEXT_LIMIT)});
        editor.setText(preferences.getString("draft", ""));
        voice.setAdapter(adapter(new String[]{"正在加载音色…"}));
        rate.setProgress(preferences.getInt("rate", 50));
        pitch.setProgress(preferences.getInt("pitch", 50));
        updateSlider(rate, R.id.rate_value);
        updateSlider(pitch, R.id.pitch_value);
        configureSlider(rate, R.id.rate_value);
        configureSlider(pitch, R.id.pitch_value);

        voice.setOnItemSelectedListener(new SelectionListener() {
            @Override public void onSelected(int position) {
                if (engine == null || !engine.isReady() || !languageReady) return;
                if (position > 0 && position <= voices.size())
                    preferences.edit().putString(voiceKey(), voices.get(position - 1).getName()).apply();
                else preferences.edit().remove(voiceKey()).apply();
            }
        });
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int length) {
                updateCount();
                updateControls();
                main.removeCallbacks(saveDraft);
                main.postDelayed(saveDraft, 400);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        findViewById(R.id.paste).setOnClickListener(view -> paste());
        findViewById(R.id.clear).setOnClickListener(view -> replaceText("", "清空当前文字？"));
        findViewById(R.id.sample).setOnClickListener(view -> replaceText(getString(R.string.sample), "用示例替换当前文字？"));
        findViewById(R.id.history).setOnClickListener(view -> showHistory());
        findViewById(R.id.engine_settings).setOnClickListener(view -> showEngineOptions());
        play.setOnClickListener(view -> {
            if (engine != null && engine.isBusy()) engine.stop("已停止");
            else start(false);
        });
        export.setOnClickListener(view -> start(true));
        String pendingPath = preferences.getString("pending_export", null);
        if (pendingPath != null) {
            File pending = new File(pendingPath);
            if (pending.isFile() && pending.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath() + "/speech-")) pendingExport = pending;
            else preferences.edit().remove("pending_export").apply();
        }
        cleanOldAudio();
        updateCount();
        connectEngine();
    }

    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void connectEngine() {
        if (engine != null) engine.close();
        languageReady = false;
        status.setText("正在连接语音引擎…");
        engine = new SpeechEngine(this, this);
        updateControls();
    }

    @Override public void onReady(boolean ready) {
        if (isDestroyed()) return;
        if (ready) refreshVoices();
        else {
            status.setText("未找到可用语音引擎，请打开下方语音设置");
            voice.setAdapter(adapter(new String[]{"暂无可用音色"}));
            updateControls();
        }
    }

    private Locale selectedLocale() { return Locale.SIMPLIFIED_CHINESE; }
    private String voiceKey() { return "voice_" + selectedLocale().toLanguageTag(); }

    private void refreshVoices() {
        int result = engine.setLanguage(selectedLocale());
        languageReady = result >= TextToSpeech.LANG_AVAILABLE;
        voices = engine.voices(selectedLocale());
        String saved = preferences.getString(voiceKey(), "");
        List<String> labels = new ArrayList<>();
        labels.add("推荐音色 · 优先离线");
        int selected = 0;
        for (int i = 0; i < voices.size(); i++) {
            Voice item = voices.get(i);
            labels.add("中文音色 " + (i + 1) + " · " + (item.isNetworkConnectionRequired() ? "联网" : "离线"));
            if (item.getName().equals(saved)) selected = i + 1;
        }
        voice.setAdapter(adapter(labels.toArray(new String[0])));
        voice.setSelection(selected);
        status.setText(languageReady ? "准备好了，听听你的文字" :
                result == TextToSpeech.LANG_MISSING_DATA ? "缺少中文语音包，请在语音设置中安装" : "当前引擎不支持中文，请切换语音引擎");
        updateControls();
    }

    private void start(boolean exporting) {
        if (engine == null || saving) return;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        persist();
        int position = voice.getSelectedItemPosition();
        Voice selected = position > 0 && position <= voices.size() ? voices.get(position - 1) : null;
        if (position == 0) {
            for (Voice candidate : voices) {
                if (!candidate.isNetworkConnectionRequired()) { selected = candidate; break; }
            }
        }
        engine.start(editor.getText().toString(), exporting, selectedLocale(), selected,
                (rate.getProgress() + 50) / 100f, (pitch.getProgress() + 50) / 100f);
        if (engine.isBusy()) addHistory(editor.getText().toString());
    }

    @Override public void onProgress(String message, boolean busy, int done, int total) {
        if (isDestroyed()) return;
        status.setText(message);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setMax(Math.max(1, total));
        progress.setProgress(done);
        updateControls();
    }

    @Override public void onError(String message) {
        if (isDestroyed()) return;
        status.setText(message);
        progress.setVisibility(View.GONE);
        updateControls();
        new AlertDialog.Builder(this).setTitle("暂时无法完成").setMessage(message)
                .setPositiveButton("知道了", null)
                .setNeutralButton("语音设置", (dialog, which) -> showEngineOptions()).show();
    }

    private void updateControls() {
        boolean busy = engine != null && engine.isBusy();
        boolean ready = engine != null && engine.isReady() && languageReady;
        boolean hasText = !editor.getText().toString().trim().isEmpty();
        play.setText(busy ? "■  停止" : "▶  开始朗读");
        play.setEnabled(!saving && (busy || ready && hasText));
        export.setEnabled(!saving && !busy && ready && hasText);
        play.setAlpha(play.isEnabled() ? 1f : 0.45f);
        export.setAlpha(export.isEnabled() ? 1f : 0.45f);
        editor.setEnabled(!busy && !saving);
        voice.setEnabled(!busy && !saving && ready);
        rate.setEnabled(!busy && !saving);
        pitch.setEnabled(!busy && !saving);
        for (int id : new int[]{R.id.paste, R.id.clear, R.id.sample, R.id.history, R.id.engine_settings})
            findViewById(id).setEnabled(!busy && !saving);
    }

    private void updateCount() {
        // The limit follows Android TTS's UTF-16 character budget, including surrogate pairs.
        count.setText(String.format(Locale.US, "%,d / 20,000", editor.length()));
    }

    private void configureSlider(SeekBar slider, int label) {
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                updateSlider(bar, label);
                if (fromUser) persist();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private void updateSlider(SeekBar slider, int label) {
        ((TextView) findViewById(label)).setText(String.format(Locale.US, "%.2f×", (slider.getProgress() + 50) / 100f));
    }

    private void persist() {
        if (preferences == null || editor == null) return;
        preferences.edit().putString("draft", editor.getText().toString())
                .putInt("rate", rate.getProgress()).putInt("pitch", pitch.getProgress()).apply();
    }

    private void paste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast("剪贴板里没有文字"); return; }
        CharSequence pasted = clip.getItemAt(0).coerceToText(this);
        if (pasted == null) { toast("剪贴板里没有文字"); return; }
        int start = Math.max(0, editor.getSelectionStart());
        int end = Math.max(start, editor.getSelectionEnd());
        if (editor.length() - (end - start) + pasted.length() > TEXT_LIMIT) toast("最多支持 20,000 字符，超出部分将截断");
        editor.getText().replace(start, end, pasted);
    }

    private void replaceText(String text, String title) {
        if (editor.length() == 0) { editor.setText(text); return; }
        new AlertDialog.Builder(this).setTitle(title).setMessage("当前草稿将被替换。")
                .setNegativeButton("取消", null).setPositiveButton("确定", (dialog, which) -> editor.setText(text)).show();
    }

    private JSONArray history() {
        try { return new JSONArray(preferences.getString("history", "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private void addHistory(String text) {
        JSONArray old = history();
        JSONArray recent = new JSONArray();
        recent.put(text);
        for (int i = 0; i < old.length() && recent.length() < 10; i++) {
            String item = old.optString(i);
            if (!text.equals(item)) recent.put(item);
        }
        preferences.edit().putString("history", recent.toString()).apply();
    }

    private void showHistory() {
        JSONArray recent = history();
        if (recent.length() == 0) { toast("开始朗读后，最近的 10 段文字会保存在这里"); return; }
        String[] labels = new String[recent.length()];
        for (int i = 0; i < recent.length(); i++) {
            String text = recent.optString(i).replaceAll("\\s+", " ");
            int end = text.offsetByCodePoints(0, Math.min(36, text.codePointCount(0, text.length())));
            labels[i] = text.substring(0, end) + (end < text.length() ? "…" : "");
        }
        new AlertDialog.Builder(this).setTitle("最近朗读 · 仅保存在本机")
                .setItems(labels, (dialog, which) -> replaceText(recent.optString(which), "恢复这段文字？"))
                .setNegativeButton("关闭", null)
                .setNeutralButton("清除记录", (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("清除全部朗读记录？").setMessage("当前草稿会保留，历史记录清除后无法恢复。")
                        .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> {
                            preferences.edit().remove("history").apply(); toast("朗读记录已清除");
                        }).show()).show();
    }

    private void showEngineOptions() {
        new AlertDialog.Builder(this).setTitle("语音引擎与语音包")
                .setMessage("在系统“文字转语音”设置中选择支持普通话的引擎，并下载中文语音包。离线音色需要先安装语音数据。\n\n若手机没有语音引擎，请先从可信应用商店安装与设备兼容的 TTS 引擎。")
                .setPositiveButton("系统语音设置", (dialog, which) -> openEngineActivity(new Intent("com.android.settings.TTS_SETTINGS")))
                .setNeutralButton("安装语音数据", (dialog, which) -> {
                    Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    if (engine != null && engine.defaultEngine() != null) intent.setPackage(engine.defaultEngine());
                    openEngineActivity(intent);
                }).setNegativeButton("关闭", null).show();
    }

    private void openEngineActivity(Intent intent) {
        try {
            reloadOnResume = true;
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException error) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); toast("请在系统设置中搜索“文字转语音”"); }
            catch (android.content.ActivityNotFoundException ignored) { reloadOnResume = false; toast("未找到设置页面，请手动打开手机设置"); }
        }
    }

    @Override public void onAudioReady(File file) {
        if (pendingExport != null && !pendingExport.equals(file)) SpeechEngine.deleteTree(pendingExport.getParentFile());
        pendingExport = file;
        preferences.edit().putString("pending_export", file.getAbsolutePath()).apply();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("audio/wav")
                .putExtra(Intent.EXTRA_TITLE, "声笺_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav");
        try { startActivityForResult(intent, CREATE_AUDIO); }
        catch (android.content.ActivityNotFoundException error) { discardExport(); onError("手机没有可用的文件保存程序"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CREATE_AUDIO) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            discardExport(); status.setText("已取消保存音频"); return;
        }
        File source = pendingExport;
        if (source == null || !source.exists()) { onError("临时音频已失效，请重新导出"); return; }
        Uri destination = data.getData();
        saving = true;
        status.setText("正在保存音频…");
        updateControls();
        files.execute(() -> {
            String error = null;
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("无法打开保存位置");
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            } catch (Exception failure) {
                error = failure.getMessage() == null ? "文件写入中断" : failure.getMessage();
            }
            final String failure = error;
            // Preserve a completed export in the chosen document; only cache files are removed.
            SpeechEngine.deleteTree(source.getParentFile());
            if (source.getAbsolutePath().equals(preferences.getString("pending_export", null)))
                preferences.edit().remove("pending_export").apply();
            main.post(() -> {
                pendingExport = null;
                saving = false;
                if (isDestroyed()) return;
                updateControls();
                if (failure == null) { status.setText(R.string.audio_saved); toast("音频已保存到所选位置"); }
                else onError("保存失败，请检查存储空间与目标文件权限：" + failure);
            });
        });
    }

    private void discardExport() {
        if (pendingExport != null) SpeechEngine.deleteTree(pendingExport.getParentFile());
        pendingExport = null;
        preferences.edit().remove("pending_export").apply();
    }

    private void cleanOldAudio() {
        File[] cache = getCacheDir().listFiles();
        if (cache == null) return;
        for (File entry : cache) {
            if (entry.getName().startsWith("speech-") && System.currentTimeMillis() - entry.lastModified() > 86400000L
                    && (pendingExport == null || !entry.equals(pendingExport.getParentFile()))) SpeechEngine.deleteTree(entry);
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() {
        super.onResume();
        if (reloadOnResume) { reloadOnResume = false; connectEngine(); }
    }

    @Override protected void onStop() {
        persist();
        if (engine != null && engine.isBusy()) engine.stop("已停止 · 返回后可重新开始");
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(saveDraft);
        if (engine != null) engine.close();
        files.shutdown();
        super.onDestroy();
    }

    private abstract static class SelectionListener implements AdapterView.OnItemSelectedListener {
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { onSelected(position); }
        @Override public void onNothingSelected(AdapterView<?> parent) { }
        abstract void onSelected(int position);
    }
}
