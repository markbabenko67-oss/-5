package com.shef.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Html;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String GEMINI_MODEL = "gemini-3.6-flash";

    private static final int REQ_CAMERA = 101;
    private static final int REQ_GALLERY = 102;
    private static final int REQ_PICK_MODEL = 103;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ImageView ivPreview;
    private Button btnAsk;
    private ProgressBar progress;
    private TextView tvResult;

    private LocalChef localChef;
    private EditText etToken;
    private EditText etGeminiKey;
    private Button btnDownload;
    private Button btnPickModel;
    private ProgressBar pbModel;
    private TextView tvModelStatus;

    private Uri pendingPhotoUri;
    private byte[] imageBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnCamera = findViewById(R.id.btnCamera);
        Button btnGallery = findViewById(R.id.btnGallery);
        btnAsk = findViewById(R.id.btnAsk);
        ivPreview = findViewById(R.id.ivPreview);
        progress = findViewById(R.id.progress);
        tvResult = findViewById(R.id.tvResult);

        etToken = findViewById(R.id.etToken);
        etGeminiKey = findViewById(R.id.etGeminiKey);
        btnDownload = findViewById(R.id.btnDownload);
        btnPickModel = findViewById(R.id.btnPickModel);
        pbModel = findViewById(R.id.pbModel);
        tvModelStatus = findViewById(R.id.tvModelStatus);

        btnCamera.setOnClickListener(v -> launchCamera());
        btnGallery.setOnClickListener(v -> launchGallery());
        btnAsk.setOnClickListener(v -> askChef());

        localChef = new LocalChef(this);
        etToken.setText(loadPref("hf_token"));
        etGeminiKey.setText(loadPref("gemini_key"));
        btnDownload.setOnClickListener(v -> startModelDownload());
        btnPickModel.setOnClickListener(v -> launchModelPicker());
        updateModelStatus();
    }

    private void launchCamera() {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "");
        if (!dir.exists()) dir.mkdirs();
        File photo = new File(dir, "fridge_" + System.currentTimeMillis() + ".jpg");
        pendingPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Камера недоступна", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Галерея недоступна", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        try {
            if (requestCode == REQ_PICK_MODEL && data != null && data.getData() != null) {
                importModelFile(data.getData());
            } else if (requestCode == REQ_CAMERA && pendingPhotoUri != null) {
                loadAndPreview(pendingPhotoUri, true);
            } else if (requestCode == REQ_GALLERY && data != null && data.getData() != null) {
                loadAndPreview(data.getData(), false);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось прочитать файл: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void launchModelPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_MODEL);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(MainActivity.this, "Модель установлена! Можно готовить без интернета.", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    updateModelStatus();
                    pbModel.setVisibility(View.GONE);
                    btnDownload.setEnabled(true);
                    btnPickModel.setEnabled(true);
                    tvModelStatus.setText("Ошибка: " + message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadAndPreview(Uri uri, boolean fixOrientation) throws IOException {
        Bitmap bmp = decodeSampledBitmap(uri, 1440);
        if (bmp == null) throw new FileNotFoundException("empty bitmap");

        if (fixOrientation) {
            try {
                String path = new File(uri.getPath()).getAbsolutePath();
                int rotation = new ExifInterface(path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (rotation) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        bmp = rotate(bmp, 90);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        bmp = rotate(bmp, 180);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        bmp = rotate(bmp, 270);
                        break;
                }
            } catch (Exception ignored) {
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        imageBytes = out.toByteArray();
        ivPreview.setImageBitmap(bmp);
    }

    private Bitmap rotate(Bitmap bmp, float degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    private Bitmap decodeSampledBitmap(Uri uri, int maxSize) throws IOException {
        InputStream bounds = getContentResolver().openInputStream(uri);
        if (bounds == null) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(bounds, null, opts);
        bounds.close();

        int width = opts.outWidth, height = opts.outHeight;
        int sample = 1;
        while ((width / sample > maxSize) || (height / sample > maxSize)) {
            sample *= 2;
        }

        BitmapFactory.Options full = new BitmapFactory.Options();
        full.inSampleSize = sample;
        InputStream data = getContentResolver().openInputStream(uri);
        if (data == null) return null;
        Bitmap bmp = BitmapFactory.decodeStream(data, null, full);
        data.close();
        return bmp;
    }

    private void askChef() {
        if (imageBytes == null) {
            Toast.makeText(this, "Сначала выбери или сними фото продуктов", Toast.LENGTH_SHORT).show();
            return;
        }

        if (localChef.isModelPresent()) {
            askLocalChef();
            return;
        }

        btnAsk.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        tvResult.setText("");

        executor.submit(() -> {
            String result;
            try {
                result = callGemini();
            } catch (Exception e) {
                result = "Ошибка: " + e.getMessage();
            }
            String finalResult = markdownToHtml(result);
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                btnAsk.setEnabled(true);
                tvResult.setText(Html.fromHtml(finalResult, Html.FROM_HTML_MODE_LEGACY));
            });
        });
    }

    private void askLocalChef() {
        final byte[] photo = imageBytes;
        btnAsk.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        tvResult.setText("Шеф думает на телефоне...");

        localChef.clearEngineError();
        localChef.generateRecipe(loadRawResourceSafe(), photo, new LocalChef.Callback() {
            @Override
            public void onResult(String text) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnAsk.setEnabled(true);
                    tvResult.setText(Html.fromHtml(markdownToHtml(text), Html.FROM_HTML_MODE_LEGACY));
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnAsk.setEnabled(true);
                    tvResult.setText(message);
                });
            }
        });
    }

    private String loadRawResourceSafe() {
        try {
            return loadRawResource(R.raw.chef_prompt);
        } catch (Exception e) {
            return "Ты шеф-повар. Предложи 3 рецепта из продуктов на фото.";
        }
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
                    Toast.makeText(MainActivity.this, "Локальная модель готова! Можно готовить без интернета.", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    updateModelStatus();
                    pbModel.setVisibility(View.GONE);
                    btnDownload.setEnabled(true);
                    tvModelStatus.setText("Ошибка: " + message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
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
            tvModelStatus.setText("Локальная модель не скачана — сейчас рецепты считает Gemini через интернет.");
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

    private String geminiKey() {
        String fromPref = etGeminiKey != null ? etGeminiKey.getText().toString().trim() : "";
        if (!fromPref.isEmpty()) {
            savePref("gemini_key", fromPref);
            return fromPref;
        }
        return BuildConfig.GEMINI_API_KEY;
    }

    private String callGemini() throws IOException {
        String apiKey = geminiKey();
        if (apiKey.isEmpty()) {
            throw new IOException("Ключ Gemini не задан: введи его в поле 'Ключ Gemini' в приложении");
        }
        String prompt = loadRawResource(R.raw.chef_prompt);

        JSONObject inlineData = new JSONObject();
        try {
            inlineData.put("mime_type", "image/jpeg");
            inlineData.put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP));
        } catch (Exception e) {
            throw new IOException(e);
        }
        JSONObject inlinePart = new JSONObject();
        JSONObject textPart = new JSONObject();
        JSONArray parts = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject body = new JSONObject();

        try {
            inlinePart.put("inline_data", inlineData);
            textPart.put("text", prompt);
            parts.put(inlinePart);
            parts.put(textPart);
            content.put("parts", parts);
            contents.put(content);
            body.put("contents", contents);
        } catch (Exception e) {
            throw new IOException(e);
        }

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + GEMINI_MODEL + ":generateContent?key="
                + URLEncoder.encode(apiKey, "UTF-8"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);

        conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String raw = readAll(stream);
        conn.disconnect();

        try {
            JSONObject root = new JSONObject(raw);
            if (root.has("error")) {
                JSONObject error = root.getJSONObject("error");
                return "Код " + code + ": " + error.optString("message", raw);
            }
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                JSONObject feedback = root.optJSONObject("promptFeedback");
                if (feedback != null && feedback.has("blockReason")) {
                    return "Запрос заблокирован: " + feedback.optString("blockReason");
                }
                return "Пустой ответ от нейросети (код " + code + ")";
            }
            JSONObject respContent = candidates.getJSONObject(0).getJSONObject("content");
            JSONArray respParts = respContent.getJSONArray("parts");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < respParts.length(); i++) {
                text.append(respParts.getJSONObject(i).optString("text", ""));
            }
            return text.toString();
        } catch (Exception e) {
            return "Не удалось разобрать ответ (код " + code + "): " + e.getMessage();
        }
    }

    private String loadRawResource(int id) throws IOException {
        InputStream in = getResources().openRawResource(id);
        String text = readAll(in);
        in.close();
        return text;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    private static String markdownToHtml(String md) {
        if (md == null || md.trim().length() == 0) return "";
        md = md.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder();
        for (String raw : md.split("\n")) {
            String line = raw.trim();
            if (line.length() == 0) {
                sb.append("<br/><br/>");
                continue;
            }
            boolean bullet = false;
            if (line.startsWith("### ")) {
                line = line.substring(4);
            } else if (line.startsWith("## ")) {
                line = line.substring(3);
            } else if (line.startsWith("# ")) {
                line = line.substring(2);
            } else if (line.startsWith("- ")) {
                line = line.substring(2);
                bullet = true;
            } else if (line.startsWith("* ")) {
                line = line.substring(2);
                bullet = true;
            }
            String html = line.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
            if (bullet) {
                sb.append("• ").append(html).append("<br/>");
            } else {
                sb.append(html).append("<br/>");
            }
        }
        return sb.toString();
    }
}