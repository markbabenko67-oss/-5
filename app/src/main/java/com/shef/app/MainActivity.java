package com.shef.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String GEMINI_API_KEY = "AQ.Ab8RN6JVfB7YA5H3w7yiD5HxUyhxaIsUvZYcKDi5bbvbl_vnLA";
    private static final String GEMINI_MODEL = "gemini-3.6-flash";

    private static final int REQ_CAMERA = 101;
    private static final int REQ_GALLERY = 102;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ImageView ivPreview;
    private Button btnAsk;
    private ProgressBar progress;
    private TextView tvResult;

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

        btnCamera.setOnClickListener(v -> launchCamera());
        btnGallery.setOnClickListener(v -> launchGallery());
        btnAsk.setOnClickListener(v -> askChef());
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
            if (requestCode == REQ_CAMERA && pendingPhotoUri != null) {
                loadAndPreview(pendingPhotoUri, true);
            } else if (requestCode == REQ_GALLERY && data != null && data.getData() != null) {
                loadAndPreview(data.getData(), false);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось прочитать фото: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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

    private String callGemini() throws IOException {
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
                + GEMINI_MODEL + ":generateContent?key=" + GEMINI_API_KEY);
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