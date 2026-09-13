package com.shef.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalChef {

    private static final String MODEL_REPO = "google/gemma-3n-E2B-it-litert-lm";
    private static final String MODEL_FILE = "gemma-3n-E2B-it-int4.litertlm";
    private static final String DOWNLOAD_URL = "https://huggingface.co/" + MODEL_REPO + "/resolve/main/" + MODEL_FILE;
    public static final long MODEL_SIZE_BYTES = 3655827456L;
    private static final int MAX_TOKENS = 2048;
    private static final int MAX_IMAGE_DIM = 1024;

    public interface ProgressListener {
        void onProgress(long done, long total);

        void onDone();

        void onError(String message);
    }

    public interface Callback {
        void onResult(String text);

        void onError(String message);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object engineLock = new Object();
    private Engine engine;
    private String engineError;
    private volatile boolean downloading;

    public LocalChef(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getModelFile() {
        return new File(new File(context.getFilesDir(), "models"), MODEL_FILE);
    }

    public boolean isModelPresent() {
        File f = getModelFile();
        return f.exists() && f.length() == MODEL_SIZE_BYTES;
    }

    public boolean isDownloading() {
        return downloading;
    }

    public long getUsableSpace() {
        return getModelFile().getParentFile() == null ? 0 : getModelFile().getParentFile().getUsableSpace();
    }

    public void downloadModel(final String token, final ProgressListener listener) {
        if (downloading) {
            listener.onError("Модель уже скачивается");
            return;
        }
        downloading = true;
        executor.submit(() -> {
            try {
                File modelsDir = new File(context.getFilesDir(), "models");
                if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                    throw new IOException("Не удалось создать папку для модели");
                }
                File part = new File(modelsDir, MODEL_FILE + ".part");
                long done = part.exists() ? part.length() : 0;
                if (done >= MODEL_SIZE_BYTES) {
                    part.delete();
                    done = 0;
                }
                String range = (done > 0) ? ("bytes=" + done + "-") : null;
                HttpURLConnection conn = openWithAuth(range, token);
                int code = conn.getResponseCode();
                FileOutputStream out;
                if (code == 206) {
                    out = new FileOutputStream(part, true);
                } else if (code == 200) {
                    done = 0;
                    out = new FileOutputStream(part, false);
                } else {
                    conn.disconnect();
                    if (code == 401 || code == 403) {
                        throw new IOException("Токен не принят (HTTP " + code + "). Проверь, что ты принял лицензию Gemma на huggingface.co и что доступ к репозиторию google/gemma-3n-E2B-it-litert-lm разрешён для этого токена.");
                    }
                    throw new IOException("Ошибка загрузки: HTTP " + code);
                }

                InputStream in = conn.getInputStream();
                byte[] buffer = new byte[64 * 1024];
                long total = done;
                long lastNotify = System.currentTimeMillis();
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    total += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 250) {
                        lastNotify = now;
                        listener.onProgress(total, MODEL_SIZE_BYTES);
                    }
                }
                out.flush();
                out.close();
                in.close();
                conn.disconnect();

                if (total != MODEL_SIZE_BYTES) {
                    part.delete();
                    throw new IOException("Файл не докачался: " + total + " из " + MODEL_SIZE_BYTES);
                }
                File target = getModelFile();
                if (target.exists()) target.delete();
                if (!part.renameTo(target)) {
                    throw new IOException("Не удалось сохранить модель в папке приложения");
                }
                listener.onDone();
            } catch (Throwable t) {
                downloading = false;
                listener.onError(t.getMessage() != null ? t.getMessage() : t.toString());
            }
        });
    }

    public void generateRecipe(final String systemPrompt, final byte[] imageJpeg, final Callback cb) {
        executor.submit(() -> {
            try {
                Engine e = ensureEngine();
                if (e == null) {
                    cb.onError(engineError != null ? engineError : "Модель не загружена");
                    return;
                }
                byte[] img = scaleJpeg(imageJpeg, MAX_IMAGE_DIM);
                Contents contents = Contents.Companion.of(
                        new Content.Text("Что приготовить из продуктов на фото?"),
                        new Content.ImageBytes(img));

                ConversationConfig convCfg = new ConversationConfig(
                        Contents.Companion.of(systemPrompt),
                        Collections.<Message>emptyList(),
                        Collections.emptyList(),
                        new SamplerConfig(64, 0.95, 0.8, 0),
                        false,
                        Collections.emptyList(),
                        Collections.<String, Object>emptyMap());

                Conversation conversation = e.createConversation(convCfg);
                try {
                    Message msg = conversation.sendMessage(contents, Collections.<String, Object>emptyMap());
                    String text = conversation.renderMessageIntoString(msg, Collections.<String, Object>emptyMap());
                    cb.onResult(text == null ? "" : text);
                } finally {
                    conversation.close();
                }
            } catch (Throwable t) {
                synchronized (engineLock) {
                    engine = null;
                    engineError = t.getMessage() != null ? t.getMessage() : t.toString();
                }
                cb.onError("Ошибка локального ИИ: " + engineError);
            }
        });
    }

    public String getEngineError() {
        synchronized (engineLock) {
            return engineError;
        }
    }

    public void clearEngineError() {
        synchronized (engineLock) {
            engineError = null;
            engine = null;
        }
    }

    private Engine ensureEngine() {
        synchronized (engineLock) {
            if (engine != null && engine.isInitialized()) {
                return engine;
            }
            if (!isModelPresent()) {
                engineError = "Локальная модель ещё не скачана";
                return null;
            }
            try {
                String cacheDir = new File(context.getCacheDir(), "litert_lm").getAbsolutePath();
                EngineConfig cfg = new EngineConfig(
                        getModelFile().getAbsolutePath(),
                        new Backend.CPU(),
                        new Backend.GPU(),
                        new Backend.CPU(),
                        MAX_TOKENS,
                        1,
                        cacheDir);
                Engine e = new Engine(cfg);
                e.initialize();
                engine = e;
                engineError = null;
                return engine;
            } catch (Throwable t) {
                engine = null;
                engineError = t.getMessage() != null ? t.getMessage() : t.toString();
                return null;
            }
        }
    }

    private static HttpURLConnection openWithAuth(String range, String token) throws IOException {
        String urlStr = DOWNLOAD_URL;
        for (int hop = 0; hop < 12; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if (range != null) {
                conn.setRequestProperty("Range", range);
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("Некорректный редирект при скачивании");
                }
                urlStr = loc;
                continue;
            }
            return conn;
        }
        throw new IOException("Слишком много редиректов при скачивании");
    }

    private static byte[] scaleJpeg(byte[] jpeg, int maxDim) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) {
                return jpeg;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (Math.max(w, h) > maxDim) {
                float scale = (float) maxDim / Math.max(w, h);
                Matrix m = new Matrix();
                m.postScale(scale, scale);
                Bitmap scaled = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true);
                if (scaled != bmp) {
                    bmp.recycle();
                }
                bmp = scaled;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out);
            byte[] result = out.toByteArray();
            bmp.recycle();
            return result;
        } catch (Throwable t) {
            return jpeg;
        }
    }
}