package com.tds.projectorlauncher;

import android.app.Service;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.tv.TvContract;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Locale;

public class NotificationServerService extends Service {
    private static final String PREFS_NAME = "projector_launcher";
    private static final String TAG = "TDSNotifyServer";
    private static final int NOTIFICATION_PORT = 7980;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private Thread serverThread;
    private ServerSocket serverSocket;
    private WindowManager windowManager;
    private View currentOverlay;
    private int activePort = -1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServerFromConfig();
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
            return openInputSettingsResponse();
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
            return openInputSettingsResponse();
        }
        return RemoteBridgeAccessibilityService.performNamedGlobalAction(action);
    }

    private JSONObject openInputSettingsResponse() throws Exception {
        JSONObject response = new JSONObject();
        response.put("action", "input-settings");
        Intent[] intents = new Intent[]{
                new Intent("android.settings.TV_INPUT_SETTINGS"),
                new Intent("android.settings.INPUT_SETTINGS"),
                new Intent(Settings.ACTION_SETTINGS)
        };
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
        response.put("error", "no input settings intent available");
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
            response.put("ok", false);
            response.put("error", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
        return response;
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
                handler.postDelayed(this::hideOverlay, durationSeconds * 1000L);
            } catch (Exception error) {
                currentOverlay = null;
                Toast.makeText(this, payload.optString("message", "Notification"), Toast.LENGTH_LONG).show();
            }
        });
    }

    private int notificationDurationSeconds(JSONObject payload) {
        return Math.max(0, payload.optInt("duration", payload.optInt("durationSeconds", notificationConfig().optInt("duration", 8))));
    }

    private View buildOverlayView(JSONObject payload) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(16), dp(22), dp(18));
        box.setBackgroundColor(parseColor(payload.optString("backgroundColor", "#E6000000"), Color.argb(230, 0, 0, 0)));

        String imageUrl = notificationImageUrl(payload);
        if (imageUrl.length() > 0) {
            ImageView imageView = new ImageView(this);
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int imageWidth = Math.max(120, payload.optInt("imagewidth", payload.optInt("imageWidth", 0)));
            JSONObject image = notificationImage(payload);
            if (image != null) {
                imageWidth = Math.max(120, image.optInt("width", imageWidth));
            }
            int maxWidth = getResources().getDisplayMetrics().widthPixels - dp(120);
            int viewWidth = imageWidth > 0 ? Math.min(imageWidth, maxWidth) : Math.min(dp(800), maxWidth);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(viewWidth, LinearLayout.LayoutParams.WRAP_CONTENT);
            imageParams.setMargins(0, 0, 0, titleOrMessagePresent(payload) ? dp(14) : 0);
            box.addView(imageView, imageParams);
            loadImageIntoView(imageUrl, imageView);
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

    private String notificationImageUrl(JSONObject payload) {
        String directUrl = payload.optString("imageurl", payload.optString("imageUrl", ""));
        if (directUrl.length() > 0) {
            return directUrl;
        }
        JSONObject image = notificationImage(payload);
        return image == null ? "" : image.optString("uri", image.optString("url", ""));
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
                Math.min(dp(payload.optInt("widthDp", 520)), getResources().getDisplayMetrics().widthPixels - dp(80)),
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
        if (currentOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(currentOverlay);
            } catch (Exception ignored) {
                // Already removed.
            }
        }
        currentOverlay = null;
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
