package com.shef.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class SettingsActivity extends Activity {

    private static final int REQ_PICK_MODEL = 103;

    private LocalChef localChef;
    private EditText etToken;
    private EditText etGeminiKey;
    private Button btnDownload;
    private Button btnPickModel;
    private ProgressBar pbModel;
    private TextView tvModelStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        localChef = new LocalChef(this);

        Button btnBack = findViewById(R.id.btnBack);
        etToken = findViewById(R.id.etToken);
        etGeminiKey = findViewById(R.id.etGeminiKey);
        btnDownload = findViewById(R.id.btnDownload);
        btnPickModel = findViewById(R.id.btnPickModel);
        pbModel = findViewById(R.id.pbModel);
        tvModelStatus = findViewById(R.id.tvModelStatus);
        Button btnSave = findViewById(R.id.btnSave);

        etToken.setText(loadPref("hf_token"));
        etGeminiKey.setText(loadPref("gemini_key"));

        btnBack.setOnClickListener(v -> finish());
        btnDownload.setOnClickListener(v -> startModelDownload());
        btnPickModel.setOnClickListener(v -> launchModelPicker());
        btnSave.setOnClickListener(v -> saveSettings());

        updateModelStatus();
    }

    private void saveSettings() {
        savePref("hf_token", etToken.getText().toString().trim());
        savePref("gemini_key", etGeminiKey.getText().toString().trim());
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
    }

    private void launchModelPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_MODEL);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_PICK_MODEL && data != null && data.getData() != null) {
            importModelFile(data.getData());
        }
    }

    private void importModelFile(Uri uri) {
        if (localChef.isModelPresent()) {
            Toast.makeText(this, "Модель уже на месте", Toast.LENGTH_SHORT).show();
            return;
        }
        btnDownload.setEnabled(false);
        btnPickModel.setEnabled(false);
        pbModel.setVisibility(View.VISIBLE);
        tvModelStatus.setText("Перенос файла модели на место. Подожди...");

        localChef.importModel(uri, new LocalChef.ProgressListener() {
            @Override
            public void onProgress(long done, long total) {
                runOnUiThread(() -> {
                    if (total > 0) {
                        pbModel.setMax(1000);
                        pbModel.setProgress((int) (done * 1000 / total));
                        tvModelStatus.setText(String.format(Locale.getDefault(),
                                "Перенос: %d%% (%d из %d МБ)",
                                done * 100 / total, done / (1024 * 1024), total / (1024 * 1024)));
                    } else {
                        tvModelStatus.setText(String.format(Locale.getDefault(),
                                "Перенос: %d МБ...", done / (1024 * 1024)));
                    }
                });
            }

            @Override
            public void onDone() {
                runOnUiThread(() -> {
                    pbModel.setVisibility(View.GONE);
                    btnDownload.setEnabled(true);
                    btnPickModel.setEnabled(true);
                    updateModelStatus();
                    Toast.makeText(SettingsActivity.this, "Модель установлена! Можно готовить без интернета.", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    pbModel.setVisibility(View.GONE);
                    btnDownload.setEnabled(true);
                    btnPickModel.setEnabled(true);
                    tvModelStatus.setText("Ошибка: " + message);
                    Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void startModelDownload() {
        String token = etToken.getText().toString().trim();
        if (token.isEmpty()) {
            Toast.makeText(this, "Вставь токен Hugging Face", Toast.LENGTH_SHORT).show();
            return;
        }
        if (localChef.isModelPresent()) {
            Toast.makeText(this, "Модель уже скачана", Toast.LENGTH_SHORT).show();
            return;
        }
        long space = localChef.getUsableSpace();
        tvModelStatus.setText(String.format(Locale.getDefault(),
                "Свободно места: ~%d ГБ из %d МБ. Загрузка 3,66 ГБ, не закрывай приложение.",
                space / (1024L * 1024 * 1024), LocalChef.MODEL_SIZE_BYTES / (1024L * 1024)));

        savePref("hf_token", token);
        btnDownload.setEnabled(false);
        pbModel.setVisibility(View.VISIBLE);

        localChef.downloadModel(token, new LocalChef.ProgressListener() {
            @Override
            public void onProgress(long done, long total) {
                runOnUiThread(() -> {
                    pbModel.setMax(1000);
                    pbModel.setProgress((int) (done * 1000 / total));
                    long pct = done * 100 / total;
                    tvModelStatus.setText(String.format(Locale.getDefault(),
                            "Скачано %d%% (%d из %d МБ)",
                            pct, done / (1024 * 1024), total / (1024 * 1024)));
                });
            }

            @Override
            public void onDone() {
                runOnUiThread(() -> {
                    pbModel.setVisibility(View.GONE);
                    btnDownload.setEnabled(true);
                    updateModelStatus();
                    Toast.makeText(SettingsActivity.this, "Локальная модель готова! Можно готовить без интернета.", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    pbModel.setVisibility(View.GONE);
                    btnDownload.setEnabled(true);
                    tvModelStatus.setText("Ошибка: " + message);
                    Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateModelStatus() {
        if (localChef.isModelPresent()) {
            tvModelStatus.setText("Локальная модель: готова (рецепты считаются на телефоне, без интернета).");
        } else if (localChef.isDownloading()) {
            tvModelStatus.setText("Идёт загрузка локальной модели...");
        } else {
            tvModelStatus.setText("Локальная модель не скачана — рецепты считает Gemini через интернет.");
        }
    }

    private void savePref(String key, String value) {
        SharedPreferences prefs = getSharedPreferences("shef", MODE_PRIVATE);
        prefs.edit().putString(key, value).apply();
    }

    private String loadPref(String key) {
        SharedPreferences prefs = getSharedPreferences("shef", MODE_PRIVATE);
        return prefs.getString(key, "");
    }
}