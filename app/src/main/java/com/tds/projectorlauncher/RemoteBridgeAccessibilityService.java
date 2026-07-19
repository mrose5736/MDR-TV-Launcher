package com.tds.projectorlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Locale;

public class RemoteBridgeAccessibilityService extends AccessibilityService {
    private static final String PREFS_NAME = "projector_launcher";
    private static final String TAG = "TDSRemoteBridge";
    private static volatile RemoteBridgeAccessibilityService activeService;

    @Override
    protected void onServiceConnected() {
        activeService = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onDestroy() {
        if (activeService == this) {
            activeService = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Key filtering is handled in onKeyEvent.
    }

    @Override
    public void onInterrupt() {
        // No ongoing spoken/audio feedback to interrupt.
    }

    public static JSONObject performNamedGlobalAction(String rawAction) throws Exception {
        JSONObject response = new JSONObject();
        RemoteBridgeAccessibilityService service = activeService;
        String action = rawAction == null ? "" : rawAction.trim().toLowerCase(Locale.UK).replace("_", "-");
        int globalAction = globalActionForName(action);
        response.put("action", action);
        response.put("accessibilityEnabled", service != null);
        response.put("globalAction", globalAction);
        if (service == null) {
            response.put("ok", false);
            response.put("error", "accessibility service is not enabled");
            return response;
        }
        if (globalAction == 0) {
            response.put("ok", false);
            response.put("error", "unsupported action");
            return response;
        }
        boolean sent = service.performGlobalAction(globalAction);
        response.put("ok", sent);
        response.put("sent", sent);
        if (!sent) {
            response.put("error", "performGlobalAction returned false");
        }
        return response;
    }

    private static int globalActionForName(String action) {
        if ("back".equals(action)) {
            return GLOBAL_ACTION_BACK;
        }
        if ("home".equals(action)) {
            return GLOBAL_ACTION_HOME;
        }
        if ("recents".equals(action)) {
            return GLOBAL_ACTION_RECENTS;
        }
        if ("notifications".equals(action) || "notification".equals(action)) {
            return GLOBAL_ACTION_NOTIFICATIONS;
        }
        if ("quick-settings".equals(action) || "settings".equals(action)) {
            return GLOBAL_ACTION_QUICK_SETTINGS;
        }
        if ("power".equals(action) || "power-dialog".equals(action)) {
            return GLOBAL_ACTION_POWER_DIALOG;
        }
        if ("sleep".equals(action) || "lock".equals(action) || "lock-screen".equals(action)) {
            return Build.VERSION.SDK_INT >= 28 ? GLOBAL_ACTION_LOCK_SCREEN : 0;
        }
        if ("screenshot".equals(action)) {
            return Build.VERSION.SDK_INT >= 30 ? GLOBAL_ACTION_TAKE_SCREENSHOT : 0;
        }
        return 0;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            JSONObject remote = new JSONObject(preferences.getString(MainActivity.PREF_REMOTE_CONFIG, "{}"));
            if (!remote.optBoolean("enabled", false)) {
                return false;
            }

            String activeInputId = preferences.getString(MainActivity.PREF_ACTIVE_INPUT_ID, "");
            long activeUntil = preferences.getLong(MainActivity.PREF_ACTIVE_INPUT_UNTIL, 0L);
            boolean inputActive = activeInputId != null
                    && activeInputId.length() > 0
                    && activeUntil > System.currentTimeMillis() / 1000L;
            boolean onlyWhenInputActive = remote.optBoolean("onlyWhenInputActive", true);
            if (onlyWhenInputActive && !inputActive) {
                return false;
            }

            JSONObject action = actionForKey(remote.optJSONObject("keys"), event.getKeyCode());
            if (action == null) {
                return false;
            }
            String requiredInputId = action.optString("inputId", "");
            if (requiredInputId.length() > 0 && !requiredInputId.equals(activeInputId)) {
                return false;
            }

            boolean consume = inputActive && action.optBoolean("consume", true);
            Log.d(TAG, "key=" + event.getKeyCode()
                    + " name=" + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " action=" + event.getAction()
                    + " inputActive=" + inputActive
                    + " consume=" + consume);
            if (event.getAction() == KeyEvent.ACTION_UP) {
                sendRequest(action);
            }
            return consume;
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject actionForKey(JSONObject keys, int keyCode) {
        if (keys == null) {
            return null;
        }
        String eventName = KeyEvent.keyCodeToString(keyCode);
        JSONObject action = keys.optJSONObject(eventName);
        if (action != null) {
            return action;
        }
        action = keys.optJSONObject(String.valueOf(keyCode));
        if (action != null) {
            return action;
        }
        Iterator<String> names = keys.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (keyCodeForName(name) == keyCode) {
                return keys.optJSONObject(name);
            }
        }
        return null;
    }

    private int keyCodeForName(String name) {
        if (name == null) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException ignored) {
            // Try symbolic names below.
        }
        int code = KeyEvent.keyCodeFromString(name);
        if (code != KeyEvent.KEYCODE_UNKNOWN) {
            return code;
        }
        return KeyEvent.keyCodeFromString("KEYCODE_" + name.toUpperCase(Locale.UK));
    }

    private void sendRequest(JSONObject action) {
        String rawUrl = action.optString("url", action.optString("requestUrl", ""));
        String url = resolveConfiguredUrl(rawUrl);
        if (url.length() == 0) {
            return;
        }
        String method = action.optString("method", action.optString("requestMethod", "POST"));
        Object body = action.opt("body");
        if (body == null) {
            body = action.opt("requestBody");
        }
        Object requestBody = body;
        new Thread(() -> {
            try {
                String verb = method == null ? "POST" : method.trim().toUpperCase(Locale.UK);
                if ("GET".equals(verb)) {
                    httpGet(url);
                } else {
                    httpPost(url, requestBody == null ? "{}" : String.valueOf(requestBody));
                }
            } catch (Exception ignored) {
                // Remote bridge key actions should never break normal remote handling.
            }
        }).start();
    }

    private String resolveConfiguredUrl(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String url = rawUrl.trim();
        if (url.length() == 0) {
            return "";
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/")) {
            return BuildConfig.TV_LAUNCHER_BASE_URL + url;
        }
        return url;
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        return readString(connection);
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
        return readString(connection);
    }

    private String readString(HttpURLConnection connection) throws Exception {
        InputStream stream;
        try {
            stream = connection.getInputStream();
        } catch (Exception error) {
            stream = connection.getErrorStream();
            if (stream == null) {
                throw error;
            }
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } finally {
            connection.disconnect();
        }
    }
}
