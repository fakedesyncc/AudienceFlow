package io.audienceflow.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(247, 248, 246);
    private static final int SURFACE = Color.WHITE;
    private static final int TEXT = Color.rgb(23, 23, 23);
    private static final int MUTED = Color.rgb(102, 112, 133);
    private static final int BORDER = Color.rgb(226, 229, 222);
    private static final int PRIMARY = Color.rgb(215, 45, 11);
    private static final int PRIMARY_DARK = Color.rgb(168, 30, 9);
    private static final int SUCCESS = Color.rgb(16, 117, 105);
    private static final int WARNING = Color.rgb(177, 88, 0);
    private static final int DANGER = Color.rgb(180, 35, 24);
    private static final int REQUEST_CAMERA = 7201;

    private static final String PREFS = "audienceflow.mobile";
    private static final String KEY_API_URL = "apiUrl";
    private static final String KEY_PREVIEW_URL = "previewUrl";
    private static final String KEY_PREVIEW_TOKEN = "previewToken";
    private static final String KEY_BEARER_TOKEN = "bearerToken";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private EditText apiUrlInput;
    private EditText previewUrlInput;
    private EditText previewTokenInput;
    private EditText bearerTokenInput;
    private EditText emailInput;
    private EditText passwordInput;
    private TextView statusView;
    private TextView userView;
    private TextView previewStatusView;
    private TextView summaryView;
    private LinearLayout attendanceContainer;
    private WebView previewWebView;
    private ImageView captureImageView;
    private ProgressBar busyIndicator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        configureWindow();
        buildInterface();
        restoreSettings();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (previewWebView != null) {
            previewWebView.stopLoading();
            previewWebView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAMERA || resultCode != RESULT_OK || data == null || data.getExtras() == null) {
            return;
        }
        Object image = data.getExtras().get("data");
        if (image instanceof Bitmap) {
            captureImageView.setImageBitmap((Bitmap) image);
            captureImageView.setVisibility(View.VISIBLE);
            setPreviewStatus("Снимок с камеры устройства получен. Для production-потока подключай телефон как IP/MJPEG источник worker-а.");
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );
    }

    private void buildInterface() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BG);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(20), dp(18), dp(28));
        scrollView.addView(shell, new ScrollView.LayoutParams(match(), wrap()));

        shell.addView(buildHero());
        shell.addView(buildSettingsCard());
        shell.addView(buildPreviewCard());
        shell.addView(buildAttendanceCard());

        setContentView(scrollView);
    }

    private View buildHero() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(gradient(new int[]{Color.rgb(23, 23, 23), Color.rgb(83, 27, 15), PRIMARY_DARK}, dp(28)));
        card.setLayoutParams(blockParams(dp(0), dp(18)));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);

        TextView mark = text("AF", 18, Color.WHITE, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(PRIMARY, dp(14)));
        top.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setPadding(dp(14), 0, 0, 0);
        top.addView(titleGroup, new LinearLayout.LayoutParams(0, wrap(), 1f));

        titleGroup.addView(text("AudienceFlow Mobile", 24, Color.WHITE, Typeface.BOLD));
        titleGroup.addView(text("нативная консоль мониторинга", 13, Color.rgb(255, 230, 217), Typeface.BOLD));
        card.addView(top);

        TextView headline = text("Камеры, аудитории и посещаемость в реальном времени", 28, Color.WHITE, Typeface.BOLD);
        headline.setPadding(0, dp(22), 0, dp(10));
        headline.setLineSpacing(dp(2), 1.0f);
        card.addView(headline);

        TextView copy = text("Подключи API и preview worker, войди под выданной учётной записью и смотри поток с детекцией прямо на телефоне.", 15, Color.rgb(255, 245, 240), Typeface.NORMAL);
        copy.setLineSpacing(dp(4), 1.0f);
        card.addView(copy);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(18), 0, 0);
        chips.addView(chip("JWT auth", Color.rgb(255, 237, 213), PRIMARY_DARK));
        chips.addView(chip("MJPEG", Color.rgb(209, 250, 229), Color.rgb(6, 95, 70)));
        chips.addView(chip("Native camera", Color.rgb(224, 231, 255), Color.rgb(55, 48, 163)));
        card.addView(chips);

        return card;
    }

    private View buildSettingsCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("Доступ и окружение", "Введи адреса сервисов и учётные данные, которые выданы для стенда."));

        apiUrlInput = input("http://10.0.2.2:8080/api", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        previewUrlInput = input("http://10.0.2.2:8090", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        previewTokenInput = input("Preview token, если задан PREVIEW_TOKEN", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        bearerTokenInput = input("JWT token, если вход уже выполнен", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        emailInput = input("email пользователя", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        passwordInput = input("пароль", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        card.addView(label("API URL"));
        card.addView(apiUrlInput);
        card.addView(label("Preview URL"));
        card.addView(previewUrlInput);
        card.addView(label("Preview token"));
        card.addView(previewTokenInput);
        card.addView(label("JWT token"));
        card.addView(bearerTokenInput);
        card.addView(label("Email"));
        card.addView(emailInput);
        card.addView(label("Password"));
        card.addView(passwordInput);

        LinearLayout row = buttonRow();
        row.addView(button("Войти", true, view -> login()));
        row.addView(button("Обновить", false, view -> refreshAttendance()));
        card.addView(row);

        LinearLayout row2 = buttonRow();
        row2.addView(button("Demo", false, view -> applyDemo()));
        row2.addView(button("Камера устройства", false, view -> openDeviceCamera()));
        card.addView(row2);

        busyIndicator = new ProgressBar(this);
        busyIndicator.setVisibility(View.GONE);
        card.addView(busyIndicator, new LinearLayout.LayoutParams(wrap(), wrap()));

        statusView = text("Ожидаю настройки подключения.", 14, MUTED, Typeface.BOLD);
        statusView.setPadding(0, dp(14), 0, 0);
        card.addView(statusView);

        userView = text("Пользователь не авторизован.", 13, MUTED, Typeface.NORMAL);
        userView.setPadding(0, dp(6), 0, 0);
        card.addView(userView);
        return card;
    }

    private View buildPreviewCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("Live preview", "Поток vision-worker с рамками детекции и масштабированием."));

        previewWebView = new WebView(this);
        WebSettings settings = previewWebView.getSettings();
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        previewWebView.setBackgroundColor(Color.rgb(12, 12, 12));
        card.addView(previewWebView, new LinearLayout.LayoutParams(match(), dp(260)));

        captureImageView = new ImageView(this);
        captureImageView.setAdjustViewBounds(true);
        captureImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        captureImageView.setVisibility(View.GONE);
        LinearLayout.LayoutParams captureParams = new LinearLayout.LayoutParams(match(), dp(220));
        captureParams.setMargins(0, dp(12), 0, 0);
        card.addView(captureImageView, captureParams);

        LinearLayout row = buttonRow();
        row.addView(button("Открыть preview", true, view -> openPreview()));
        row.addView(button("Сохранить настройки", false, view -> {
            saveSettings();
            setPreviewStatus("Настройки сохранены на устройстве.");
        }));
        card.addView(row);

        previewStatusView = text("Preview не запущен.", 14, MUTED, Typeface.BOLD);
        previewStatusView.setPadding(0, dp(14), 0, 0);
        card.addView(previewStatusView);

        return card;
    }

    private View buildAttendanceCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("Текущая посещаемость", "Данные из Analytics API /attendance/current."));

        summaryView = text("Нет загруженных данных.", 15, MUTED, Typeface.BOLD);
        summaryView.setPadding(0, dp(8), 0, dp(12));
        card.addView(summaryView);

        attendanceContainer = new LinearLayout(this);
        attendanceContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(attendanceContainer);
        return card;
    }

    private void restoreSettings() {
        apiUrlInput.setText(prefs.getString(KEY_API_URL, ""));
        previewUrlInput.setText(prefs.getString(KEY_PREVIEW_URL, ""));
        previewTokenInput.setText(prefs.getString(KEY_PREVIEW_TOKEN, ""));
        bearerTokenInput.setText(prefs.getString(KEY_BEARER_TOKEN, ""));
        if (clean(apiUrlInput).isEmpty() && clean(previewUrlInput).isEmpty()) {
            applyDemo();
        }
    }

    private void saveSettings() {
        prefs.edit()
                .putString(KEY_API_URL, normalizeBase(clean(apiUrlInput)))
                .putString(KEY_PREVIEW_URL, normalizeBase(clean(previewUrlInput)))
                .putString(KEY_PREVIEW_TOKEN, clean(previewTokenInput))
                .putString(KEY_BEARER_TOKEN, clean(bearerTokenInput))
                .apply();
    }

    private void applyDemo() {
        apiUrlInput.setText("http://10.0.2.2:8080/api");
        previewUrlInput.setText("http://10.0.2.2:8090");
        previewTokenInput.setText("");
        setStatus("Demo endpoints выставлены для Android Emulator. Введи реальные креды или JWT для API.", MUTED);
        setPreviewStatus("Если Docker worker запущен, нажми «Открыть preview».");
        saveSettings();
    }

    private void login() {
        String apiUrl = normalizeBase(clean(apiUrlInput));
        String email = clean(emailInput);
        String password = clean(passwordInput);
        String manualToken = clean(bearerTokenInput);

        if (apiUrl.isEmpty()) {
            setStatus("Укажи API URL.", DANGER);
            return;
        }

        saveSettings();

        if (!email.isEmpty() && !password.isEmpty()) {
            runAsync("Выполняю вход...", () -> {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("password", password);
                return postJson(apiUrl + "/auth/login", body, null);
            }, response -> {
                String token = response.optString("token", "");
                JSONObject user = response.optJSONObject("user");
                bearerTokenInput.setText(token);
                passwordInput.setText("");
                saveSettings();
                if (user != null) {
                    userView.setText(String.format(
                            Locale.ROOT,
                            "%s · %s · сессия %d мин.",
                            user.optString("displayName", user.optString("email", "user")),
                            user.optString("role", "role"),
                            response.optLong("expiresInMinutes", 0)
                    ));
                }
                setStatus("Вход выполнен, токен сохранён на устройстве.", SUCCESS);
                refreshAttendance();
            });
            return;
        }

        if (!manualToken.isEmpty()) {
            setStatus("Использую вручную введённый JWT.", SUCCESS);
            refreshAttendance();
            return;
        }

        setStatus("Введи email/password или вставь выданный JWT token.", DANGER);
    }

    private void refreshAttendance() {
        String apiUrl = normalizeBase(clean(apiUrlInput));
        String token = clean(bearerTokenInput);
        if (apiUrl.isEmpty()) {
            setStatus("Укажи API URL.", DANGER);
            return;
        }
        if (token.isEmpty()) {
            setStatus("Для /attendance/current нужен JWT token. Выполни вход или вставь токен.", DANGER);
            return;
        }
        saveSettings();
        runAsync("Загружаю посещаемость...", () -> getJsonArray(apiUrl + "/attendance/current", token), attendance -> {
            renderAttendance(attendance);
            setStatus("Посещаемость обновлена.", SUCCESS);
        });
    }

    private void openPreview() {
        String streamUrl = buildPreviewStreamUrl();
        if (streamUrl.isEmpty()) {
            setPreviewStatus("Укажи Preview URL.");
            return;
        }
        saveSettings();

        String escapedUrl = html(streamUrl);
        String html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#0c0c0c;color:#fff;font-family:sans-serif;}"
                + "body{display:flex;align-items:center;justify-content:center;overflow:hidden;}"
                + "img{width:100%;height:100%;object-fit:contain;display:block;}"
                + ".badge{position:fixed;left:12px;top:12px;background:rgba(0,0,0,.62);border:1px solid rgba(255,255,255,.22);"
                + "border-radius:999px;padding:7px 10px;font-size:12px;font-weight:700}</style></head>"
                + "<body><div class=\"badge\">AudienceFlow preview</div><img src=\"" + escapedUrl + "\" alt=\"MJPEG preview\"></body></html>";
        previewWebView.loadDataWithBaseURL(streamUrl, html, "text/html", "UTF-8", null);
        setPreviewStatus("Preview открыт: " + streamUrl);
    }

    private void openDeviceCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            setPreviewStatus("На устройстве не найдено приложение камеры.");
            return;
        }
        startActivityForResult(intent, REQUEST_CAMERA);
    }

    private void renderAttendance(JSONArray attendance) {
        attendanceContainer.removeAllViews();
        int totalCount = 0;
        int totalCapacity = 0;
        int warnings = 0;

        for (int index = 0; index < attendance.length(); index++) {
            JSONObject item = attendance.optJSONObject(index);
            if (item == null) {
                continue;
            }
            int count = item.optInt("count", 0);
            int capacity = item.optInt("capacity", 0);
            int occupancy = item.optInt("occupancyPercent", capacity > 0 ? Math.round(count * 100f / capacity) : 0);
            totalCount += count;
            totalCapacity += Math.max(capacity, 0);
            if (occupancy >= 80) {
                warnings++;
            }
            attendanceContainer.addView(attendanceCard(item, occupancy));
        }

        if (attendance.length() == 0) {
            summaryView.setText("API вернул пустой список аудиторий.");
            return;
        }

        int totalOccupancy = totalCapacity > 0 ? Math.round(totalCount * 100f / totalCapacity) : 0;
        summaryView.setText(String.format(
                Locale.ROOT,
                "%d аудиторий · %d/%d человек · %d%% заполненность · %d зон требуют внимания",
                attendance.length(),
                totalCount,
                totalCapacity,
                totalOccupancy,
                warnings
        ));
    }

    private View attendanceCard(JSONObject item, int occupancy) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        int tone = occupancy >= 95 ? DANGER : occupancy >= 80 ? WARNING : SUCCESS;
        card.setBackground(roundedStroke(Color.rgb(252, 252, 250), BORDER, dp(1), dp(20)));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        top.addView(titleGroup, new LinearLayout.LayoutParams(0, wrap(), 1f));

        titleGroup.addView(text(item.optString("roomName", "Аудитория"), 18, TEXT, Typeface.BOLD));
        String subtitle = String.format(
                Locale.ROOT,
                "%s · этаж %s · обновлено %s",
                item.optString("building", "корпус"),
                item.optString("floor", "-"),
                formatTimestamp(item.optString("timestamp", ""))
        );
        titleGroup.addView(text(subtitle, 13, MUTED, Typeface.BOLD));

        TextView count = text(item.optInt("count", 0) + "/" + item.optInt("capacity", 0), 20, tone, Typeface.BOLD);
        top.addView(count);
        card.addView(top);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(Math.max(0, Math.min(100, occupancy)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(match(), dp(8));
        progressParams.setMargins(0, dp(13), 0, dp(10));
        card.addView(progress, progressParams);

        String details = String.format(
                Locale.ROOT,
                "%d%% · confidence %.0f%% · статус %s",
                occupancy,
                item.optDouble("confidence", 0.0) * 100.0,
                item.optString("status", "normal")
        );
        card.addView(text(details, 13, MUTED, Typeface.BOLD));

        LinearLayout.LayoutParams params = blockParams(dp(0), dp(10));
        card.setLayoutParams(params);
        return card;
    }

    private JSONObject postJson(String url, JSONObject body, String bearerToken) throws Exception {
        HttpURLConnection connection = openConnection(url, "POST", bearerToken);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }
        String response = readResponse(connection);
        return new JSONObject(response);
    }

    private JSONArray getJsonArray(String url, String bearerToken) throws Exception {
        HttpURLConnection connection = openConnection(url, "GET", bearerToken);
        String response = readResponse(connection);
        return new JSONArray(response);
    }

    private HttpURLConnection openConnection(String rawUrl, String method, String bearerToken) throws Exception {
        URL url = new URL(rawUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        return connection;
    }

    private String readResponse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : readAll(stream);
        if (status >= 400) {
            throw new IOException("HTTP " + status + (body.isEmpty() ? "" : ": " + body));
        }
        return body;
    }

    private String readAll(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private <T> void runAsync(String busyText, AsyncTask<T> task, AsyncResult<T> result) {
        setBusy(true, busyText);
        executor.execute(() -> {
            try {
                T value = task.run();
                mainHandler.post(() -> {
                    setBusy(false, "");
                    result.accept(value);
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    setBusy(false, "");
                    setStatus(cleanError(exception), DANGER);
                    Toast.makeText(this, cleanError(exception), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String buildPreviewStreamUrl() {
        String previewUrl = normalizeBase(clean(previewUrlInput));
        if (previewUrl.isEmpty()) {
            return "";
        }
        String streamUrl = previewUrl.endsWith("/v1/stream.mjpg") ? previewUrl : previewUrl + "/v1/stream.mjpg";
        String token = clean(previewTokenInput);
        if (token.isEmpty()) {
            return streamUrl;
        }
        String separator = streamUrl.contains("?") ? "&" : "?";
        return streamUrl + separator + "token=" + urlEncode(token);
    }

    private String normalizeBase(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String clean(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private String formatTimestamp(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        try {
            return DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(value));
        } catch (Exception ignored) {
            return value;
        }
    }

    private String cleanError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Ошибка выполнения запроса.";
        }
        return message.length() > 240 ? message.substring(0, 240) + "..." : message;
    }

    private void setBusy(boolean busy, String message) {
        if (busyIndicator != null) {
            busyIndicator.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (busy && message != null && !message.isEmpty()) {
            setStatus(message, MUTED);
        }
    }

    private void setStatus(String message, int color) {
        statusView.setText(message);
        statusView.setTextColor(color);
    }

    private void setPreviewStatus(String message) {
        if (previewStatusView != null) {
            previewStatusView.setText(message);
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundedStroke(SURFACE, BORDER, dp(1), dp(24)));
        card.setLayoutParams(blockParams(dp(0), dp(18)));
        return card;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, 0);
        return row;
    }

    private View sectionTitle(String title, String caption) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, 0, 0, dp(12));
        group.addView(text(title, 20, TEXT, Typeface.BOLD));
        TextView captionView = text(caption, 13, MUTED, Typeface.BOLD);
        captionView.setPadding(0, dp(4), 0, 0);
        captionView.setLineSpacing(dp(2), 1.0f);
        group.addView(captionView);
        return group;
    }

    private TextView label(String value) {
        TextView label = text(value, 12, MUTED, Typeface.BOLD);
        label.setPadding(0, dp(11), 0, dp(6));
        return label;
    }

    private EditText input(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setTextColor(TEXT);
        editText.setHintTextColor(Color.rgb(151, 161, 176));
        editText.setTextSize(15);
        editText.setHint(hint);
        editText.setInputType(inputType);
        editText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        editText.setPadding(dp(14), 0, dp(14), 0);
        editText.setMinHeight(dp(52));
        editText.setBackground(roundedStroke(Color.rgb(252, 252, 250), BORDER, dp(1), dp(16)));
        editText.setLayoutParams(blockParams(dp(0), 0));
        return editText;
    }

    private Button button(String title, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : PRIMARY_DARK);
        button.setBackground(primary ? rounded(PRIMARY, dp(16)) : roundedStroke(Color.WHITE, BORDER, dp(1), dp(16)));
        button.setMinHeight(dp(50));
        button.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView chip(String value, int background, int color) {
        TextView chip = text(value, 12, color, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(7), dp(10), dp(7));
        chip.setBackground(rounded(background, dp(999)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(wrap(), wrap());
        params.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.DEFAULT, style);
        return textView;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundedStroke(int color, int strokeColor, int strokeWidth, int radius) {
        GradientDrawable drawable = rounded(color, radius);
        drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private GradientDrawable gradient(int[] colors, int radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams blockParams(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.setMargins(0, top, 0, bottom);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private String html(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }

    private interface AsyncTask<T> {
        T run() throws Exception;
    }

    private interface AsyncResult<T> {
        void accept(T value);
    }
}
