package com.tds.projectorlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseLongArray;
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
    private static final long DEFAULT_LONG_PRESS_MS = 650L;
    private static final long MIN_LONG_PRESS_MS = 200L;
    private static final long MAX_LONG_PRESS_MS = 5000L;
    private static final int TCL_NUMBER_KEYCODE = 4056;
    private static final long SEND_ON_DOWN_DEBOUNCE_MS = 350L;
    private static volatile RemoteBridgeAccessibilityService activeService;
    private final Handler keyHandler = new Handler(Looper.getMainLooper());
    private final SparseArray<PendingKeyPress> pendingKeyPresses = new SparseArray<>();
    private final SparseLongArray lastDownActionTimes = new SparseLongArray();
    private final RemoteKeySequenceRouter keySequenceRouter = new RemoteKeySequenceRouter();

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
        cancelPendingKeyPresses();
        keySequenceRouter.clear();
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
        PendingKeyPress pending = pendingKeyPresses.get(event.getKeyCode());
        if (event.getAction() == KeyEvent.ACTION_UP && pending != null) {
            // Also finish commands explicitly configured with consume:false.
            // Their release still passes through, but their short/long-press
            // bookkeeping must complete exactly once.
            boolean consumed = finishKeyPress(pending, event.isCanceled());
            return keySequenceRouter.route(event.getDeviceId(), event.getKeyCode(),
                    false, true, event.getRepeatCount(), () -> consumed);
        }
        return keySequenceRouter.route(event.getDeviceId(), event.getKeyCode(),
                event.getAction() == KeyEvent.ACTION_DOWN,
                event.getAction() == KeyEvent.ACTION_UP, event.getRepeatCount(),
                () -> handleRemoteKeyEvent(event));
    }

    private boolean handleRemoteKeyEvent(KeyEvent event) {
        try {
            if (NotificationServerService.isControlPanelVisible()) {
                return false;
            }

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

            JSONObject action = actionForActiveInput(remote, activeInputId, event.getKeyCode());
            if (action == null) {
                return false;
            }

            boolean onlyWhenInputActive = remote.optBoolean("onlyWhenInputActive", true);
            boolean alwaysActive = action.optBoolean("alwaysActive", false);
            if (onlyWhenInputActive && !inputActive && !alwaysActive) {
                return false;
            }

            String requiredInputId = action.optString("inputId", "");
            if (requiredInputId.length() > 0 && !requiredInputId.equals(activeInputId)) {
                return false;
            }

            boolean consume = (inputActive || alwaysActive) && action.optBoolean("consume", true);
            Log.d(TAG, "key=" + event.getKeyCode()
                    + " name=" + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " action=" + event.getAction()
                    + " repeat=" + event.getRepeatCount()
                    + " inputActive=" + inputActive
                    + " alwaysActive=" + alwaysActive
                    + " consume=" + consume);

            boolean sendOnDown = action.optBoolean("sendOnDown", event.getKeyCode() == TCL_NUMBER_KEYCODE);
            if (sendOnDown) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    long now = SystemClock.elapsedRealtime();
                    long previous = lastDownActionTimes.get(event.getKeyCode(), 0L);
                    if (now - previous >= SEND_ON_DOWN_DEBOUNCE_MS) {
                        lastDownActionTimes.put(event.getKeyCode(), now);
                        sendRequest(action);
                    }
                }
                return consume;
            }

            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    beginKeyPress(event.getKeyCode(), action, remote, consume);
                }
                return consume;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                sendRequest(action);
            }
            return consume;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void beginKeyPress(int keyCode, JSONObject shortAction, JSONObject remote, boolean consume) {
        PendingKeyPress previous = pendingKeyPresses.get(keyCode);
        if (previous != null) {
            keyHandler.removeCallbacks(previous.longPressRunnable);
            pendingKeyPresses.remove(keyCode);
        }

        JSONObject longAction = mergedLongPressAction(shortAction);
        PendingKeyPress pending = new PendingKeyPress(keyCode, shortAction, longAction, consume);
        pendingKeyPresses.put(keyCode, pending);
        if (longAction == null) {
            return;
        }

        long threshold = shortAction.optLong(
                "longPressMs",
                remote.optLong("longPressMs", DEFAULT_LONG_PRESS_MS)
        );
        threshold = Math.max(MIN_LONG_PRESS_MS, Math.min(MAX_LONG_PRESS_MS, threshold));
        keyHandler.postDelayed(pending.longPressRunnable, threshold);
    }

    private boolean finishKeyPress(PendingKeyPress pending, boolean canceled) {
        keyHandler.removeCallbacks(pending.longPressRunnable);
        pendingKeyPresses.remove(pending.keyCode);
        if (!canceled && !pending.longPressSent) {
            sendRequest(pending.shortAction);
        }
        return pending.consume;
    }

    private JSONObject mergedLongPressAction(JSONObject shortAction) {
        JSONObject configured = shortAction.optJSONObject("longPress");
        if (configured == null) {
            return null;
        }
        try {
            JSONObject merged = new JSONObject(shortAction.toString());
            merged.remove("longPress");
            if (configured.has("requestUrl") && !configured.has("url")) {
                merged.remove("url");
            }
            if (configured.has("requestMethod") && !configured.has("method")) {
                merged.remove("method");
            }
            if (configured.has("requestBody") && !configured.has("body")) {
                merged.remove("body");
            }
            Iterator<String> names = configured.keys();
            while (names.hasNext()) {
                String name = names.next();
                merged.put(name, configured.get(name));
            }
            String rawUrl = merged.optString("url", merged.optString("requestUrl", "")).trim();
            return rawUrl.length() == 0 ? null : merged;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cancelPendingKeyPresses() {
        for (int i = 0; i < pendingKeyPresses.size(); i++) {
            keyHandler.removeCallbacks(pendingKeyPresses.valueAt(i).longPressRunnable);
        }
        pendingKeyPresses.clear();
    }

    private final class PendingKeyPress {
        final int keyCode;
        final JSONObject shortAction;
        final JSONObject longAction;
        final boolean consume;
        boolean longPressSent;
        final Runnable longPressRunnable;

        PendingKeyPress(int keyCode, JSONObject shortAction, JSONObject longAction, boolean consume) {
            this.keyCode = keyCode;
            this.shortAction = shortAction;
            this.longAction = longAction;
            this.consume = consume;
            this.longPressRunnable = () -> {
                if (pendingKeyPresses.get(this.keyCode) != this || this.longAction == null) {
                    return;
                }
                this.longPressSent = true;
                Log.d(TAG, "long press key=" + this.keyCode
                        + " name=" + KeyEvent.keyCodeToString(this.keyCode));
                sendRequest(this.longAction);
            };
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

    private JSONObject actionForActiveInput(JSONObject remote, String activeInputId, int keyCode) {
        JSONObject inputKeys = inputKeysForActiveInput(remote, activeInputId);
        JSONObject action = actionForKey(inputKeys, keyCode);
        return action != null ? action : actionForKey(remote.optJSONObject("keys"), keyCode);
    }

    private JSONObject inputKeysForActiveInput(JSONObject remote, String activeInputId) {
        if (activeInputId == null || activeInputId.length() == 0) {
            return null;
        }
        JSONObject scopedInputs = remote.optJSONObject("inputs");
        if (scopedInputs == null) {
            return null;
        }

        JSONObject direct = scopedInputs.optJSONObject(activeInputId);
        if (direct != null) {
            return direct.optJSONObject("keys") != null ? direct.optJSONObject("keys") : direct;
        }

        try {
            SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            JSONObject server = new JSONObject(preferences.getString(MainActivity.PREF_SERVER_CONFIG, "{}"));
            JSONObject configuredInputs = server.optJSONObject("inputs");
            if (configuredInputs == null) {
                return null;
            }
            Iterator<String> names = configuredInputs.keys();
            while (names.hasNext()) {
                String inputKey = names.next();
                JSONObject input = configuredInputs.optJSONObject(inputKey);
                if (input == null || !activeInputId.equals(input.optString("inputId", inputKey))) {
                    continue;
                }
                JSONObject scoped = scopedInputs.optJSONObject(inputKey);
                if (scoped != null) {
                    return scoped.optJSONObject("keys") != null ? scoped.optJSONObject("keys") : scoped;
                }
            }
        } catch (Exception ignored) {
            // Fall back to the global key map.
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
        if (sendLocalLauncherAction(rawUrl)) {
            return;
        }
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
            } catch (Exception error) {
                Log.w(TAG, "Remote key request failed: " + url, error);
                // Remote bridge key actions should never break normal remote handling.
            }
        }).start();
    }

    private boolean sendLocalLauncherAction(String rawUrl) {
        if (rawUrl == null) {
            return false;
        }
        String url = rawUrl.trim().toLowerCase(Locale.UK);
        String action = null;
        if ("/panel".equals(url)
                || "tds://panel".equals(url)
                || "http://127.0.0.1:7980/panel".equals(url)
                || "http://localhost:7980/panel".equals(url)) {
            action = NotificationServerService.ACTION_SHOW_PANEL;
        } else if ("/panel/hide".equals(url)
                || "tds://panel/hide".equals(url)
                || "http://127.0.0.1:7980/panel/hide".equals(url)
                || "http://localhost:7980/panel/hide".equals(url)) {
            action = NotificationServerService.ACTION_HIDE_PANEL;
        }
        if (action == null) {
            return false;
        }
        try {
            Intent intent = new Intent(this, NotificationServerService.class);
            intent.setAction(action);
            startService(intent);
        } catch (Exception error) {
            Log.w(TAG, "Local launcher action failed: " + action, error);
        }
        return true;
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
