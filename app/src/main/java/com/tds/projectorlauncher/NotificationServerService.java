package com.tds.projectorlauncher;

import android.app.Service;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;

@UnstableApi
public class NotificationServerService extends Service {
    private static final String PREFS_NAME = "projector_launcher";
    private static final String TAG = "TDSNotifyServer";
    private static final int NOTIFICATION_PORT = 7980;
    public static final String ACTION_SHOW_PANEL = "com.tds.projectorlauncher.action.SHOW_PANEL";
    public static final String ACTION_HIDE_PANEL = "com.tds.projectorlauncher.action.HIDE_PANEL";
    private static volatile boolean controlPanelVisible = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideOverlayRunnable = this::hideOverlay;
    private volatile boolean running = false;
    private Thread serverThread;
    private ServerSocket serverSocket;
    private WindowManager windowManager;
    private View currentOverlay;
    private ExoPlayer currentVideoPlayer;
    private final OkHttpClient cameraHttpClient = new OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build();
    private int activePort = -1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServerFromConfig();
        if (intent != null && ACTION_SHOW_PANEL.equals(intent.getAction())) {
            showControlPanel();
        } else if (intent != null && ACTION_HIDE_PANEL.equals(intent.getAction())) {
            handler.post(this::hideOverlay);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        hideOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServerFromConfig() {
        JSONObject config = notificationConfig();
        boolean enabled = config.optBoolean("enabled", true);
        if (!enabled) {
            stopServer();
            return;
        }
        if (running && activePort == NOTIFICATION_PORT) {
            return;
        }
        stopServer();
        running = true;
        serverThread = new Thread(() -> runServer(), "tds-notification-server");
        serverThread.start();
    }

    private void stopServer() {
        running = false;
        activePort = -1;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(MainActivity.PREF_NOTIFICATION_PORT)
                .apply();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
            // Closing the socket is best effort.
        }
        serverSocket = null;
    }

    private void runServer() {
        try (ServerSocket socket = new ServerSocket(NOTIFICATION_PORT)) {
            serverSocket = socket;
            activePort = socket.getLocalPort();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(MainActivity.PREF_NOTIFICATION_PORT, activePort)
                    .apply();
            Log.i(TAG, "Notification server listening on :" + activePort);
            while (running) {
                try {
                    handleClient(socket.accept());
                } catch (Exception error) {
                    if (running) {
                        Log.w(TAG, "Notification request failed", error);
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Notification server stopped", error);
        }
    }

    private void handleClient(Socket socket) throws Exception {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             OutputStream output = client.getOutputStream()) {
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                String lower = line.toLowerCase(Locale.UK);
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            char[] bodyChars = new char[Math.max(0, contentLength)];
            int offset = 0;
            while (offset < bodyChars.length) {
                int read = reader.read(bodyChars, offset, bodyChars.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            String body = new String(bodyChars, 0, offset);
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            if ("GET".equals(method) && ("/status".equals(path) || "/".equals(path))) {
                writeJson(output, 200, statusJson().toString());
                return;
            }
            if ("GET".equals(method) && ("/config".equals(path) || "/options".equals(path))) {
                writeJson(output, 200, optionsJson().toString());
                return;
            }
            if ("GET".equals(method) && "/inputs".equals(path)) {
                JSONObject response = new JSONObject();
                response.put("ok", true);
                response.put("inputs", inputsJson());
                writeJson(output, 200, response.toString());
                return;
            }
            if ("POST".equals(method) && "/notify".equals(path)) {
                JSONObject payload = body.length() == 0 ? new JSONObject() : new JSONObject(body);
                showNotification(payload);
                writeJson(output, 200, "{\"ok\":true}");
                return;
            }
            if (("POST".equals(method) || "GET".equals(method)) && path.startsWith("/panel")) {
                if (path.startsWith("/panel/hide")) {
                    handler.post(this::hideOverlay);
                } else {
                    showControlPanel();
                }
                JSONObject response = new JSONObject();
                response.put("ok", true);
                response.put("action", path.startsWith("/panel/hide") ? "hide" : "show");
                writeJson(output, 200, response.toString());
                return;
            }
            if (("POST".equals(method) || "GET".equals(method)) && path.startsWith("/key")) {
                JSONObject payload = body.length() == 0 ? new JSONObject() : new JSONObject(body);
                writeJson(output, 200, sendKeyResponse(path, payload).toString());
                return;
            }
            if (("POST".equals(method) || "GET".equals(method)) && (path.startsWith("/action") || path.startsWith("/power") || path.startsWith("/sleep"))) {
                JSONObject payload = body.length() == 0 ? new JSONObject() : new JSONObject(body);
                writeJson(output, 200, globalActionResponse(path, payload).toString());
                return;
            }
            if (("POST".equals(method) || "GET".equals(method)) && path.startsWith("/input")) {
                JSONObject payload = body.length() == 0 ? new JSONObject() : new JSONObject(body);
                writeJson(output, 200, launchInputResponse(path, payload).toString());
                return;
            }
            writeJson(output, 404, "{\"ok\":false,\"error\":\"not found\"}");
        }
    }

    private JSONObject sendKeyResponse(String path, JSONObject payload) throws Exception {
        String rawKey = payload.optString("keyCode", payload.optString("key", queryParam(path, "keyCode")));
        if (rawKey.length() == 0) {
            rawKey = queryParam(path, "key");
        }
        if (rawKey.length() == 0 && path.startsWith("/key/")) {
            rawKey = decodePathPart(path.substring("/key/".length()));
        }
        int keyCode = parseKeyCode(rawKey);
        JSONObject response = new JSONObject();
        response.put("ok", keyCode != KeyEvent.KEYCODE_UNKNOWN);
        response.put("keyCode", keyCode);
        response.put("keyName", keyCode == KeyEvent.KEYCODE_UNKNOWN ? rawKey : KeyEvent.keyCodeToString(keyCode));
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            response.put("error", "unknown key");
            return response;
        }
        try {
            new Instrumentation().sendKeyDownUpSync(keyCode);
            response.put("sent", true);
        } catch (Exception error) {
            JSONObject fallback = fallbackForBlockedKey(keyCode);
            if (fallback != null) {
                fallback.put("keyCode", keyCode);
                fallback.put("keyName", KeyEvent.keyCodeToString(keyCode));
                fallback.put("fallbackFromInjectError", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
                return fallback;
            }
            response.put("ok", false);
            response.put("sent", false);
            response.put("error", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
        return response;
    }

    private JSONObject fallbackForBlockedKey(int keyCode) throws Exception {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return RemoteBridgeAccessibilityService.performNamedGlobalAction("back");
        }
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            return RemoteBridgeAccessibilityService.performNamedGlobalAction("home");
        }
        if (keyCode == KeyEvent.KEYCODE_SLEEP) {
            return RemoteBridgeAccessibilityService.performNamedGlobalAction("sleep");
        }
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            return RemoteBridgeAccessibilityService.performNamedGlobalAction("power-dialog");
        }
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT) {
            return openInputChooserResponse();
        }
        return null;
    }

    private JSONObject globalActionResponse(String path, JSONObject payload) throws Exception {
        String action = payload.optString("action", queryParam(path, "action"));
        if (action.length() == 0 && path.startsWith("/action/")) {
            action = decodePathPart(path.substring("/action/".length()));
        }
        if (action.length() == 0 && path.startsWith("/power/")) {
            action = decodePathPart(path.substring("/power/".length()));
        }
        if (action.length() == 0 && path.startsWith("/sleep")) {
            action = "sleep";
        }
        String normalized = action.trim().toLowerCase(Locale.UK).replace("_", "-");
        if ("input".equals(normalized) || "inputs".equals(normalized) || "input-settings".equals(normalized) || "tv-input".equals(normalized)) {
            return openInputChooserResponse();
        }
        return RemoteBridgeAccessibilityService.performNamedGlobalAction(action);
    }

    private JSONObject openInputChooserResponse() throws Exception {
        return startFirstActivityResponse(
                "input-chooser",
                new Intent("com.android.tv.action.VIEW_INPUTS"),
                new Intent("android.settings.TV_INPUT_SETTINGS"),
                new Intent("android.settings.INPUT_SETTINGS"),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private JSONObject openInputSettingsResponse() throws Exception {
        return startFirstActivityResponse(
                "input-settings",
                new Intent("android.settings.TV_INPUT_SETTINGS"),
                new Intent("android.settings.INPUT_SETTINGS"),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private JSONObject startFirstActivityResponse(String action, Intent... intents) throws Exception {
        JSONObject response = new JSONObject();
        response.put("action", action);
        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                response.put("ok", true);
                response.put("sent", true);
                response.put("intentAction", intent.getAction());
                return response;
            } catch (Exception ignored) {
                // Try the next vendor/Android settings action.
            }
        }
        response.put("ok", false);
        response.put("sent", false);
        response.put("error", "no matching activity available");
        return response;
    }

    private JSONObject launchInputResponse(String path, JSONObject payload) throws Exception {
        String inputId = payload.optString("inputId", payload.optString("id", queryParam(path, "inputId")));
        if (inputId.length() == 0) {
            inputId = queryParam(path, "id");
        }
        if (inputId.length() == 0 && path.startsWith("/input/")) {
            inputId = decodePathPart(path.substring("/input/".length()));
        }
        JSONObject response = new JSONObject();
        response.put("inputId", inputId);
        if (inputId.length() == 0) {
            response.put("ok", false);
            response.put("error", "missing inputId");
            return response;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_TITLE, payload.optString("label", "Input"));
            intent.putExtra("android.media.tv.extra.INPUT_NAME", payload.optString("label", "Input"));
            markActiveInput(inputId);
            startActivity(intent);
            response.put("ok", true);
        } catch (Exception error) {
            JSONObject fallback = inputFallbackResponse(inputId);
            if (fallback != null) {
                fallback.put("inputId", inputId);
                fallback.put("fallbackFromError", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
                return fallback;
            }
            response.put("ok", false);
            response.put("error", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
        return response;
    }

    private JSONObject inputFallbackResponse(String inputId) throws Exception {
        if (Build.MANUFACTURER == null || !Build.MANUFACTURER.toLowerCase(Locale.UK).contains("xgimi")) {
            return null;
        }
        markActiveInput(inputId);
        return openInputChooserResponse();
    }

    private int parseKeyCode(String rawKey) {
        if (rawKey == null) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        String key = rawKey.trim();
        if (key.length() == 0) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException ignored) {
            // Try Android key names below.
        }
        int keyCode = KeyEvent.keyCodeFromString(key);
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            return keyCode;
        }
        return KeyEvent.keyCodeFromString("KEYCODE_" + key.toUpperCase(Locale.UK));
    }

    private String decodePathPart(String value) throws Exception {
        int queryIndex = value.indexOf('?');
        String part = queryIndex >= 0 ? value.substring(0, queryIndex) : value;
        return URLDecoder.decode(part, "UTF-8");
    }

    private String queryParam(String path, String name) throws Exception {
        int queryIndex = path.indexOf('?');
        if (queryIndex < 0 || queryIndex == path.length() - 1) {
            return "";
        }
        String[] pairs = path.substring(queryIndex + 1).split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            if (name.equals(URLDecoder.decode(key, "UTF-8"))) {
                String value = equals >= 0 ? pair.substring(equals + 1) : "";
                return URLDecoder.decode(value, "UTF-8");
            }
        }
        return "";
    }

    private void markActiveInput(String inputId) {
        long until = System.currentTimeMillis() / 1000L + 21600L;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(MainActivity.PREF_ACTIVE_INPUT_ID, inputId == null ? "" : inputId)
                .putLong(MainActivity.PREF_ACTIVE_INPUT_UNTIL, until)
                .apply();
    }

    private JSONObject statusJson() throws Exception {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        JSONObject status = new JSONObject();
        status.put("ok", true);
        status.put("name", getString(com.tds.projectorlauncher.R.string.app_name));
        status.put("tvId", preferences.getString(MainActivity.PREF_TV_ID, ""));
        status.put("ipAddress", preferences.getString(MainActivity.PREF_DEVICE_IP, ""));
        status.put("port", activePort > 0 ? activePort : NOTIFICATION_PORT);
        status.put("overlayPermission", canDrawOverlays());
        status.put("version", "tds-0.1");
        return status;
    }

    private JSONObject optionsJson() throws Exception {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        JSONObject response = new JSONObject();
        response.put("ok", true);
        response.put("status", statusJson());
        response.put("config", new JSONObject(preferences.getString(MainActivity.PREF_SERVER_CONFIG, "{}")));
        response.put("inputs", inputsJson());
        response.put("remoteControl", new JSONObject(preferences.getString(MainActivity.PREF_REMOTE_CONFIG, "{}")));
        response.put("notificationServer", notificationConfig());
        response.put("api", apiOptionsJson());
        response.put("keyOptions", keyOptionsJson());
        return response;
    }

    private JSONArray inputsJson() throws Exception {
        return new JSONArray(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(MainActivity.PREF_INPUTS_STATE, "[]"));
    }

    private JSONObject apiOptionsJson() throws Exception {
        JSONObject api = new JSONObject();
        JSONArray endpoints = new JSONArray();
        endpoints.put("GET /status");
        endpoints.put("GET /config");
        endpoints.put("GET /options");
        endpoints.put("GET /inputs");
        endpoints.put("POST /notify");
        endpoints.put("GET /panel");
        endpoints.put("GET /panel/hide");
        endpoints.put("POST /input");
        endpoints.put("GET /input/{urlEncodedInputId}");
        endpoints.put("POST /key");
        endpoints.put("GET /key/{keyNameOrCode}");
        endpoints.put("GET /key?keyCode={keyNameOrCode}");
        endpoints.put("POST /action");
        endpoints.put("GET /action/{back|home|sleep|lock-screen|power-dialog|input-settings}");
        endpoints.put("GET /sleep");
        api.put("port", NOTIFICATION_PORT);
        api.put("endpoints", endpoints);
        return api;
    }

    private JSONArray keyOptionsJson() throws Exception {
        JSONArray keys = new JSONArray();
        addKeyOption(keys, KeyEvent.KEYCODE_DPAD_UP, "up");
        addKeyOption(keys, KeyEvent.KEYCODE_DPAD_DOWN, "down");
        addKeyOption(keys, KeyEvent.KEYCODE_DPAD_LEFT, "left");
        addKeyOption(keys, KeyEvent.KEYCODE_DPAD_RIGHT, "right");
        addKeyOption(keys, KeyEvent.KEYCODE_DPAD_CENTER, "select");
        addKeyOption(keys, KeyEvent.KEYCODE_ENTER, "enter");
        addKeyOption(keys, KeyEvent.KEYCODE_BACK, "back");
        addKeyOption(keys, KeyEvent.KEYCODE_HOME, "home");
        addKeyOption(keys, KeyEvent.KEYCODE_MENU, "menu");
        addKeyOption(keys, KeyEvent.KEYCODE_INFO, "info");
        addKeyOption(keys, KeyEvent.KEYCODE_CHANNEL_UP, "channel up");
        addKeyOption(keys, KeyEvent.KEYCODE_CHANNEL_DOWN, "channel down");
        addKeyOption(keys, KeyEvent.KEYCODE_VOLUME_UP, "volume up");
        addKeyOption(keys, KeyEvent.KEYCODE_VOLUME_DOWN, "volume down");
        addKeyOption(keys, KeyEvent.KEYCODE_VOLUME_MUTE, "mute");
        addKeyOption(keys, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "play pause");
        addKeyOption(keys, KeyEvent.KEYCODE_MEDIA_PLAY, "play");
        addKeyOption(keys, KeyEvent.KEYCODE_MEDIA_PAUSE, "pause");
        addKeyOption(keys, KeyEvent.KEYCODE_MEDIA_STOP, "stop");
        addKeyOption(keys, KeyEvent.KEYCODE_MEDIA_REWIND, "rewind");
        addKeyOption(keys, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "fast forward");
        addKeyOption(keys, KeyEvent.KEYCODE_0, "0");
        addKeyOption(keys, KeyEvent.KEYCODE_1, "1");
        addKeyOption(keys, KeyEvent.KEYCODE_2, "2");
        addKeyOption(keys, KeyEvent.KEYCODE_3, "3");
        addKeyOption(keys, KeyEvent.KEYCODE_4, "4");
        addKeyOption(keys, KeyEvent.KEYCODE_5, "5");
        addKeyOption(keys, KeyEvent.KEYCODE_6, "6");
        addKeyOption(keys, KeyEvent.KEYCODE_7, "7");
        addKeyOption(keys, KeyEvent.KEYCODE_8, "8");
        addKeyOption(keys, KeyEvent.KEYCODE_9, "9");
        return keys;
    }

    private void addKeyOption(JSONArray keys, int keyCode, String label) throws Exception {
        JSONObject key = new JSONObject();
        key.put("keyCode", keyCode);
        key.put("keyName", KeyEvent.keyCodeToString(keyCode));
        key.put("label", label);
        key.put("postExample", "{\"keyCode\":\"" + KeyEvent.keyCodeToString(keyCode) + "\"}");
        keys.put(key);
    }

    private void writeJson(OutputStream output, int code, String body) throws Exception {
        byte[] bytes = body.getBytes("UTF-8");
        String status = code == 200 ? "OK" : "Not Found";
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes("UTF-8"));
        output.write(bytes);
        output.flush();
    }

    private void showNotification(JSONObject payload) {
        handler.post(() -> {
            int durationSeconds = notificationDurationSeconds(payload);
            if (durationSeconds == 0) {
                hideOverlay();
                return;
            }
            if (!canDrawOverlays()) {
                Toast.makeText(this, payload.optString("message", payload.optString("title", "Notification")), Toast.LENGTH_LONG).show();
                return;
            }
            hideOverlay();
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                return;
            }
            View view = buildOverlayView(payload);
            WindowManager.LayoutParams params = overlayLayoutParams(payload);
            currentOverlay = view;
            try {
                windowManager.addView(view, params);
                handler.postDelayed(hideOverlayRunnable, durationSeconds * 1000L);
            } catch (Exception error) {
                currentOverlay = null;
                releaseVideoPlayer();
                Toast.makeText(this, payload.optString("message", "Notification"), Toast.LENGTH_LONG).show();
            }
        });
    }

    public static boolean isControlPanelVisible() {
        return controlPanelVisible;
    }

    private void showControlPanel() {
        handler.post(() -> {
            if (!canDrawOverlays()) {
                Toast.makeText(this, "Overlay permission is not enabled", Toast.LENGTH_LONG).show();
                return;
            }
            hideOverlay();
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                return;
            }

            JSONObject config = controlPanelConfig();
            View view = buildControlPanelView(config);
            currentOverlay = view;
            try {
                windowManager.addView(view, controlPanelLayoutParams(config));
                controlPanelVisible = true;
                schedulePanelHide(config);
                view.post(() -> focusInitialPanelButton(view));
            } catch (Exception error) {
                currentOverlay = null;
                controlPanelVisible = false;
                Toast.makeText(this, "Could not open quick controls", Toast.LENGTH_LONG).show();
                Log.w(TAG, "Control panel overlay failed", error);
            }
        });
    }

    private View buildControlPanelView(JSONObject config) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(42), dp(18), dp(42), dp(20));
        panel.setBackground(panelBackground(config));
        panel.setFocusable(true);
        panel.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                schedulePanelHide(config);
            }
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)) {
                hideOverlay();
                return true;
            }
            return false;
        });

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(config.optString("title", "Quick controls"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = new TextView(this);
        hint.setText("BACK TO CLOSE");
        hint.setTextColor(Color.argb(175, 255, 255, 255));
        hint.setTextSize(12);
        heading.addView(hint);
        panel.addView(heading);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f);
        controlsParams.setMargins(0, dp(12), 0, dp(8));
        panel.addView(controls, controlsParams);

        JSONArray buttons = panelButtons(config);
        List<TextView> buttonViews = new ArrayList<>();
        for (int i = 0; i < buttons.length(); i++) {
            JSONObject button = buttons.optJSONObject(i);
            if (button == null || button.optBoolean("hidden", false)) {
                continue;
            }
            TextView buttonView = buildPanelButton(button, config);
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f);
            buttonParams.setMargins(dp(7), 0, dp(7), 0);
            controls.addView(buttonView, buttonParams);
            buttonViews.add(buttonView);
        }
        linkPanelFocus(buttonViews);

        TextView status = new TextView(this);
        status.setTag("panel-status");
        status.setText(buttonViews.isEmpty()
                ? "No controls configured for this TV"
                : config.optString("hint", "Use LEFT / RIGHT and SELECT"));
        status.setTextColor(Color.argb(190, 255, 255, 255));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        panel.addView(status);
        return panel;
    }

    private TextView buildPanelButton(JSONObject button, JSONObject config) {
        TextView view = new TextView(this);
        view.setId(View.generateViewId());
        String icon = button.optString("icon", "");
        String label = button.optString("label", "Action");
        view.setText(icon.length() == 0 ? label : icon + "\n" + label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(panelButtonBackground(false));
        view.setOnFocusChangeListener((focusedView, hasFocus) -> {
            focusedView.setBackground(panelButtonBackground(hasFocus));
            focusedView.setScaleX(hasFocus ? 1.04f : 1f);
            focusedView.setScaleY(hasFocus ? 1.04f : 1f);
        });
        view.setOnKeyListener((focusedView, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                schedulePanelHide(config);
            }
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)) {
                hideOverlay();
                return true;
            }
            return false;
        });
        view.setOnClickListener(clicked -> {
            schedulePanelHide(config);
            runPanelAction(button, clicked);
        });
        return view;
    }

    private void linkPanelFocus(List<TextView> buttons) {
        for (int i = 0; i < buttons.size(); i++) {
            TextView button = buttons.get(i);
            button.setNextFocusLeftId(buttons.get(Math.max(0, i - 1)).getId());
            button.setNextFocusRightId(buttons.get(Math.min(buttons.size() - 1, i + 1)).getId());
        }
    }

    private void focusInitialPanelButton(View root) {
        ArrayList<View> focusables = new ArrayList<>();
        root.addFocusables(focusables, View.FOCUS_FORWARD);
        if (focusables.isEmpty()) {
            root.requestFocus();
            return;
        }
        focusables.get(Math.min(1, focusables.size() - 1)).requestFocus();
    }

    private void runPanelAction(JSONObject action, View buttonView) {
        TextView status = findPanelStatus(buttonView.getRootView());
        String label = action.optString("label", "Action");
        if (status != null) {
            status.setText(label + "…");
        }
        new Thread(() -> {
            String resultMessage;
            try {
                String url = resolvePanelUrl(action.optString("url", action.optString("requestUrl", "")));
                if (url.length() == 0) {
                    throw new IllegalArgumentException("No URL configured");
                }
                String method = action.optString("method", action.optString("requestMethod", "GET"));
                Object requestBody = action.has("body") ? action.opt("body") : action.opt("requestBody");
                String response = "GET".equalsIgnoreCase(method)
                        ? httpGet(url)
                        : httpPost(url, requestBody == null ? "{}" : String.valueOf(requestBody));
                resultMessage = panelResultMessage(label, response);
            } catch (Exception error) {
                resultMessage = label + " failed";
                Log.w(TAG, "Control panel action failed: " + label, error);
            }
            final String message = resultMessage;
            handler.post(() -> {
                if (status != null && controlPanelVisible) {
                    status.setText(message);
                }
            });
        }, "tds-panel-action").start();
    }

    private TextView findPanelStatus(View root) {
        if (!(root instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && "panel-status".equals(child.getTag())) {
                return (TextView) child;
            }
            TextView nested = findPanelStatus(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private String panelResultMessage(String label, String response) {
        try {
            JSONObject result = new JSONObject(response);
            String groupAction = result.optString("groupAction", "");
            if ("joined".equalsIgnoreCase(groupAction)) {
                return label + " · joined group";
            }
            if ("left".equalsIgnoreCase(groupAction)) {
                return label + " · left group";
            }
            if (result.has("ok") && !result.optBoolean("ok", true)) {
                return label + " failed";
            }
        } catch (Exception ignored) {
            // A successful endpoint may return plain text or an empty response.
        }
        return label + " sent";
    }

    private String resolvePanelUrl(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String url = rawUrl.trim();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return url.startsWith("/") ? BuildConfig.TV_LAUNCHER_BASE_URL + url : url;
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        return readHttpString(connection);
    }

    private String httpPost(String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes("UTF-8"));
        }
        return readHttpString(connection);
    }

    private String readHttpString(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + status);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            if (status < 200 || status >= 400) {
                throw new IllegalStateException("HTTP " + status + ": " + result);
            }
            return result.toString();
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject controlPanelConfig() {
        try {
            JSONObject server = new JSONObject(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(MainActivity.PREF_SERVER_CONFIG, "{}"));
            JSONObject panel = server.optJSONObject("controlPanel");
            JSONObject settings = server.optJSONObject("settings");
            if (panel == null && settings != null) {
                panel = settings.optJSONObject("controlPanel");
            }
            return panel == null ? new JSONObject() : panel;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONArray panelButtons(JSONObject config) {
        JSONArray configured = config.optJSONArray("buttons");
        // Never send commands to another installation's devices by default.
        return configured == null ? new JSONArray() : configured;
    }

    private GradientDrawable panelBackground(JSONObject config) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(parseColor(config.optString("backgroundColor", "#EC10151D"), Color.argb(236, 16, 21, 29)));
        background.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        background.setStroke(dp(1), Color.argb(70, 255, 255, 255));
        return background;
    }

    private GradientDrawable panelButtonBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(focused ? Color.rgb(35, 118, 255) : Color.argb(135, 255, 255, 255));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(focused ? 3 : 1), focused ? Color.WHITE : Color.argb(65, 255, 255, 255));
        return background;
    }

    private WindowManager.LayoutParams controlPanelLayoutParams(JSONObject config) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int height = config.optInt("heightDp", 0) > 0
                ? dp(config.optInt("heightDp", 0))
                : Math.max(dp(190), getResources().getDisplayMetrics().heightPixels / 4);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.y = 0;
        return params;
    }

    private void schedulePanelHide(JSONObject config) {
        handler.removeCallbacks(hideOverlayRunnable);
        int duration = Math.max(3, config.optInt("duration", config.optInt("durationSeconds", 12)));
        handler.postDelayed(hideOverlayRunnable, duration * 1000L);
    }

    private int notificationDurationSeconds(JSONObject payload) {
        return Math.max(0, payload.optInt("duration", payload.optInt("durationSeconds", notificationConfig().optInt("duration", 8))));
    }

    private View buildOverlayView(JSONObject payload) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(16), dp(22), dp(18));
        box.setBackgroundColor(parseColor(payload.optString("backgroundColor", "#E6000000"), Color.argb(230, 0, 0, 0)));

        String videoUrl = notificationVideoUrl(payload);
        if (videoUrl.length() > 0) {
            PlayerView playerView = (PlayerView) LayoutInflater.from(this)
                    .inflate(R.layout.notification_video, box, false);
            playerView.setUseController(false);
            playerView.setKeepContentOnPlayerReset(true);
            playerView.setShutterBackgroundColor(Color.BLACK);
            playerView.setResizeMode(notificationVideoFit(payload)
                    ? AspectRatioFrameLayout.RESIZE_MODE_FIT
                    : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    notificationVideoHeight(payload));
            videoParams.setMargins(0, 0, 0, titleOrMessagePresent(payload) ? dp(14) : 0);
            box.addView(playerView, videoParams);
            startVideoIntoView(videoUrl, payload, playerView);
        } else {
            String imageUrl = notificationImageUrl(payload);
            if (imageUrl.length() > 0) {
                ImageView imageView = new ImageView(this);
                imageView.setAdjustViewBounds(true);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                imageParams.setMargins(0, 0, 0, titleOrMessagePresent(payload) ? dp(14) : 0);
                box.addView(imageView, imageParams);
                loadImageIntoView(imageUrl, imageView);
            }
        }

        String title = payload.optString("title", "");
        if (title.length() > 0) {
            TextView titleView = new TextView(this);
            titleView.setText(title);
            titleView.setTextColor(parseColor(payload.optString("titleColor", "#FFFFFFFF"), Color.WHITE));
            titleView.setTextSize(payload.optInt("titleSize", 22));
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(titleView);
        }

        String message = payload.optString("message", payload.optString("text", ""));
        if (message.length() > 0) {
            TextView messageView = new TextView(this);
            messageView.setText(message);
            messageView.setTextColor(parseColor(payload.optString("messageColor", "#FFFFFFFF"), Color.WHITE));
            messageView.setTextSize(payload.optInt("messageSize", 18));
            messageView.setPadding(0, title.length() > 0 ? dp(6) : 0, 0, 0);
            box.addView(messageView);
        }
        return box;
    }

    private boolean titleOrMessagePresent(JSONObject payload) {
        return payload.optString("title", "").length() > 0
                || payload.optString("message", payload.optString("text", "")).length() > 0;
    }

    private JSONObject notificationImage(JSONObject payload) {
        JSONObject media = payload.optJSONObject("media");
        if (media == null) {
            return null;
        }
        return media.optJSONObject("image");
    }

    private JSONObject notificationVideo(JSONObject payload) {
        JSONObject media = payload.optJSONObject("media");
        if (media == null) {
            return null;
        }
        return media.optJSONObject("video");
    }

    private String notificationImageUrl(JSONObject payload) {
        String directUrl = payload.optString("imageurl", payload.optString("imageUrl", ""));
        if (directUrl.length() > 0) {
            return directUrl;
        }
        JSONObject image = notificationImage(payload);
        return image == null ? "" : image.optString("uri", image.optString("url", ""));
    }

    private int notificationImageWidth(JSONObject payload) {
        int width = payload.optInt("imagewidth", payload.optInt("imageWidth", 0));
        JSONObject image = notificationImage(payload);
        return image == null ? width : image.optInt("width", width);
    }

    private String notificationVideoUrl(JSONObject payload) {
        String directUrl = payload.optString("videourl", payload.optString("videoUrl", ""));
        if (directUrl.length() > 0) {
            return directUrl;
        }
        JSONObject video = notificationVideo(payload);
        return video == null ? "" : video.optString("uri", video.optString("url", ""));
    }

    private int notificationVideoWidth(JSONObject payload) {
        int width = payload.optInt("videowidth", payload.optInt("videoWidth", 0));
        JSONObject video = notificationVideo(payload);
        return video == null ? width : video.optInt("width", width);
    }

    private int notificationVideoHeight(JSONObject payload) {
        JSONObject video = notificationVideo(payload);
        int explicitHeight = payload.optInt("videoheight", payload.optInt("videoHeight", 0));
        if (video != null) {
            explicitHeight = video.optInt("height", explicitHeight);
        }
        if (explicitHeight > 0) {
            return explicitHeight;
        }
        float aspectRatio = 16f / 9f;
        String ratio = video == null ? "" : video.optString("aspectRatio", "");
        String[] ratioParts = ratio.split(":");
        if (ratioParts.length == 2) {
            try {
                float width = Float.parseFloat(ratioParts[0]);
                float height = Float.parseFloat(ratioParts[1]);
                if (width > 0 && height > 0) {
                    aspectRatio = width / height;
                }
            } catch (NumberFormatException ignored) {
                // Use 16:9 for malformed aspect ratios.
            }
        }
        return Math.max(dp(120), Math.round(notificationContentWidth(payload) / aspectRatio));
    }

    private boolean notificationVideoFit(JSONObject payload) {
        JSONObject video = notificationVideo(payload);
        String resizeMode = video == null ? "zoom" : video.optString("resizeMode", video.optString("contentMode", "zoom"));
        return "fit".equalsIgnoreCase(resizeMode) || "contain".equalsIgnoreCase(resizeMode);
    }

    private boolean notificationVideoMuted(JSONObject payload) {
        JSONObject video = notificationVideo(payload);
        return video == null
                ? payload.optBoolean("muted", true)
                : video.optBoolean("muted", payload.optBoolean("muted", true));
    }

    private void startVideoIntoView(String videoUrl, JSONObject payload, PlayerView playerView) {
        releaseVideoPlayer();
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 3_000, 250, 500)
                .build();
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setMediaCodecSelector(softwareVideoCodecSelector())
                .setEnableDecoderFallback(true);
        ExoPlayer player = new ExoPlayer.Builder(this, renderersFactory)
                .setLoadControl(loadControl)
                .build();
        currentVideoPlayer = player;
        playerView.setPlayer(player);
        player.setVolume(notificationVideoMuted(payload) ? 0f : 1f);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Notification video failed", error);
            }
        });

        WebSocketFmp4DataSource.Factory dataSourceFactory =
                new WebSocketFmp4DataSource.Factory(cameraHttpClient);
        ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(new MediaItem.Builder()
                        .setUri(videoUrl)
                        .setMimeType(MimeTypes.VIDEO_MP4)
                        .build());
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
    }

    private MediaCodecSelector softwareVideoCodecSelector() {
        return (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            List<MediaCodecInfo> available = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder);
            if (mimeType == null || !mimeType.startsWith("video/")) {
                return available;
            }

            List<MediaCodecInfo> software = new ArrayList<>();
            for (MediaCodecInfo decoder : available) {
                String name = decoder.name == null ? "" : decoder.name.toLowerCase(Locale.UK);
                if (name.startsWith("c2.android.")
                        || name.startsWith("omx.google.")
                        || name.contains(".software.")
                        || name.contains(".sw.")) {
                    software.add(decoder);
                }
            }
            if (software.isEmpty()) {
                Log.w(TAG, "No software video decoder found; using the platform decoder list");
                return available;
            }
            Log.i(TAG, "Using software notification video decoder " + software.get(0).name);
            return software;
        };
    }

    private void loadImageIntoView(String imageUrl, ImageView imageView) {
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                try (InputStream input = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(input);
                    if (bitmap != null) {
                        handler.post(() -> {
                            imageView.setImageBitmap(bitmap);
                            imageView.requestLayout();
                        });
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception error) {
                Log.w(TAG, "Notification image failed", error);
            }
        }, "tds-notification-image").start();
    }

    private WindowManager.LayoutParams overlayLayoutParams(JSONObject payload) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                notificationOverlayWidth(payload),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = gravityForPosition(payload.optInt("position", notificationConfig().optInt("position", 1)));
        params.x = dp(payload.optInt("xOffsetDp", 36));
        params.y = dp(payload.optInt("yOffsetDp", 36));
        return params;
    }

    private int notificationOverlayWidth(JSONObject payload) {
        int overlayWidth = dp(payload.optInt("widthDp", 520));
        if (!payload.has("widthDp")) {
            int mediaWidth = Math.max(notificationVideoWidth(payload), notificationImageWidth(payload));
            if (mediaWidth > 0) {
                overlayWidth = mediaWidth + dp(44);
            }
        }
        return Math.min(overlayWidth, getResources().getDisplayMetrics().widthPixels - dp(80));
    }

    private int notificationContentWidth(JSONObject payload) {
        return Math.max(dp(120), notificationOverlayWidth(payload) - dp(44));
    }

    private int gravityForPosition(int position) {
        if (position == 0) {
            return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        }
        if (position == 2) {
            return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        }
        if (position == 3) {
            return Gravity.TOP | Gravity.LEFT;
        }
        return Gravity.TOP | Gravity.RIGHT;
    }

    private void hideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable);
        controlPanelVisible = false;
        releaseVideoPlayer();
        if (currentOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(currentOverlay);
            } catch (Exception ignored) {
                // Already removed.
            }
        }
        currentOverlay = null;
    }

    private void releaseVideoPlayer() {
        if (currentVideoPlayer != null) {
            currentVideoPlayer.stop();
            currentVideoPlayer.release();
            currentVideoPlayer = null;
        }
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private JSONObject notificationConfig() {
        try {
            return new JSONObject(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(MainActivity.PREF_NOTIFICATION_CONFIG, "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
