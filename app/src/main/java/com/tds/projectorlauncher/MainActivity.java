package com.tds.projectorlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

public class MainActivity extends Activity {
    private static final String BASE_URL = BuildConfig.TV_LAUNCHER_BASE_URL;
    private static final String DEFAULT_REQUIRED_SERVICE = BuildConfig.TV_LAUNCHER_ADMIN_SERVICE;
    private static final String DEFAULT_UNLOCK_SERVICE = BuildConfig.TV_LAUNCHER_UNLOCK_SERVICE;
    private static final String LAUNCHER_VERSION = "0.2.2";
    private static final String PREFS_NAME = "projector_launcher";
    private static final String PREF_BACKGROUND_URL = "cached_background_url";
    private static final String PREF_ADMIN_VALID_UNTIL = "admin_valid_until";
    private static final String PREF_LOCK_VALID_UNTIL = "lock_valid_until";
    static final String PREF_SERVER_CONFIG = "server_config";
    static final String PREF_INPUTS_STATE = "inputs_state";
    static final String PREF_REMOTE_CONFIG = "remote_control_config";
    static final String PREF_NOTIFICATION_CONFIG = "notification_server_config";
    static final String PREF_NOTIFICATION_PORT = "notification_server_port";
    static final String PREF_TV_ID = "tv_id";
    static final String PREF_DEVICE_IP = "device_ip";
    private static final int NOTIFICATION_PORT = 7980;
    static final String PREF_ACTIVE_INPUT_ID = "active_input_id";
    static final String PREF_ACTIVE_INPUT_UNTIL = "active_input_until";
    private static final String CACHED_BACKGROUND_FILE = "cached_background_image";

    private ProjectorLauncherView launcherView;
    private TvInputManager tvInputManager;
    private String tvId;
    private String requiredService = DEFAULT_REQUIRED_SERVICE;
    private String unlockRequiredService = DEFAULT_UNLOCK_SERVICE;
    private JSONObject serverConfig = new JSONObject();
    private Bitmap backgroundBitmap;
    private Bitmap authQrBitmap;
    private final Map<String, Bitmap> inputIconBitmaps = new HashMap<>();
    private final Map<String, String> inputIconUrls = new HashMap<>();
    private String backgroundBitmapUrl = "";
    private String updateApkUrl = "";
    private String tvName = "";
    private String tvSubtitle = "";
    private final List<MenuItem> projectorMenuItems = new ArrayList<>();
    private final List<QuickSelectItem> quickSelectItems = new ArrayList<>();
    private boolean serverConfigLoaded = false;
    private boolean offlineLockEnabled = false;
    private String offlineLockMessage = "";
    private boolean adminAuthRequired = true;
    private boolean serverLockEnabled = false;
    private boolean lockScreenEnabled = false;
    private boolean adminValidated = false;
    private boolean lockValidated = false;
    private long adminValidUntil = 0L;
    private long lockValidUntil = 0L;
    private int configPollSeconds = 20;
    private int screensaverSeconds = 300;
    private int sleepWallpaperDimAlpha = 220;
    private int homeClockYOffsetDp = 42;
    private int sleepClockYOffsetDp = 120;
    private int lockedClockYOffsetDp = 0;
    private int unlockValidationSeconds = 14400;
    private int burnInShiftDp = 28;
    private int burnInPeriodSeconds = 240;
    private String currentIpAddress = "IP unavailable";
    private String lastPostedIpAddress = "";
    private int lastPostedNotificationPort = -1;
    private long lastDeviceStatusPostMs = 0L;
    private long lastInteractionMs = System.currentTimeMillis();
    private AuthSession authSession;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (launcherView != null) {
                launcherView.invalidate();
            }
            handler.postDelayed(this, 1000L);
        }
    };
    private final Runnable configPoll = new Runnable() {
        @Override
        public void run() {
            fetchOrCreateConfig();
            handler.postDelayed(this, Math.max(5, configPollSeconds) * 1000L);
        }
    };
    private final Runnable networkTick = new Runnable() {
        @Override
        public void run() {
            currentIpAddress = getCurrentIpAddress();
            persistDeviceIp();
            postDeviceStatusIfNeeded(false);
            if (launcherView != null) {
                launcherView.invalidate();
            }
            handler.postDelayed(this, 10000L);
        }
    };
    private final Runnable authPoll = new Runnable() {
        @Override
        public void run() {
            pollAuthSession();
        }
    };

    private final TvInputManager.TvInputCallback tvInputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String inputId) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String inputId) {
            loadInputs();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        tvId = buildTvId();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_TV_ID, tvId)
                .apply();
        loadCachedApprovals();
        launcherView = new ProjectorLauncherView(this);
        loadCachedBackground();
        setContentView(launcherView);
        hideSystemUi();
        loadInputs();
        startNotificationServer();
        fetchOrCreateConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (tvInputManager != null) {
            tvInputManager.registerCallback(tvInputCallback, handler);
        }
        loadInputs();
        clearActiveInput();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        handler.removeCallbacks(configPoll);
        handler.postDelayed(configPoll, 3000L);
        handler.removeCallbacks(networkTick);
        handler.post(networkTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(configPoll);
        handler.removeCallbacks(networkTick);
        handler.removeCallbacks(authPoll);
        if (tvInputManager != null) {
            tvInputManager.unregisterCallback(tvInputCallback);
        }
    }

    @Override
    public void onBackPressed() {
        if (launcherView != null) {
            launcherView.handleBackPressed();
        }
    }

    private String buildTvId() {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().length() == 0) {
            androidId = Build.SERIAL;
        }
        String label = cleanForId(Build.MANUFACTURER + "-" + Build.MODEL + "-" + androidId);
        return label.length() == 0 ? "unknown-tv" : label;
    }

    private String cleanForId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._,:-]", "-");
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void loadInputs() {
        List<InputItem> allInputs = new ArrayList<>();
        if (tvInputManager != null) {
            List<TvInputInfo> tvInputs = tvInputManager.getTvInputList();
            for (TvInputInfo info : tvInputs) {
                if (!info.isPassthroughInput()) {
                    continue;
                }
                int state = tvInputManager.getInputState(info.getId());
                boolean connected = state == TvInputManager.INPUT_STATE_CONNECTED;
                if (state != TvInputManager.INPUT_STATE_DISCONNECTED) {
                    CharSequence label = info.loadLabel(this);
                    allInputs.add(new InputItem(info.getId(), label == null ? "Input" : label.toString(), connected));
                }
            }
        }
        saveInputsState(allInputs);
        launcherView.setInputs(allInputs);
    }

    private void fetchOrCreateConfig() {
        new Thread(() -> {
            try {
                String response = httpGet(BASE_URL + "/api/tvlauncher/" + urlPart(tvId));
                JSONObject config = new JSONObject(response);
                if (config.optInt("code", 0) == 404 || config.optBoolean("ok", true) == false && config.has("error")) {
                    createDefaultConfig();
                    return;
                }
                offlineLockEnabled = false;
                offlineLockMessage = "";
                applyServerConfig(config);
            } catch (Exception firstError) {
                try {
                    createDefaultConfig();
                } catch (Exception ignored) {
                    enableOfflineLock("Cannot connect to TV launcher server");
                }
            }
        }).start();
    }

    private void createDefaultConfig() throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", Build.MODEL == null ? "TV" : Build.MODEL);
        body.put("room", "");
        body.put("launcherVersion", LAUNCHER_VERSION);
        body.put("inputs", new JSONObject());
        JSONObject settings = new JSONObject();
        settings.put("requiredService", DEFAULT_REQUIRED_SERVICE);
        settings.put("unlockRequiredService", DEFAULT_UNLOCK_SERVICE);
        settings.put("adminAuthRequired", true);
        settings.put("lockScreenEnabled", false);
        settings.put("configPollSeconds", 20);
        settings.put("screensaverSeconds", 300);
        settings.put("sleepWallpaperDimAlpha", 220);
        settings.put("homeClockYOffsetDp", 42);
        settings.put("sleepClockYOffsetDp", 120);
        settings.put("lockedClockYOffsetDp", 0);
        settings.put("unlockValidationSeconds", 14400);
        settings.put("burnInShiftDp", 28);
        settings.put("burnInPeriodSeconds", 240);
        settings.put("backgroundImageUrl", "");
        settings.put("updateApkUrl", "");
        settings.put("activeInputSeconds", 21600);
        body.put("settings", settings);

        try {
            httpPost(BASE_URL + "/api/tvlauncher/" + urlPart(tvId) + "/create", body.toString());
        } catch (Exception ignored) {
            // Existing config returns 409. Read it below either way.
        }
        String response = httpGet(BASE_URL + "/api/tvlauncher/" + urlPart(tvId));
        offlineLockEnabled = false;
        offlineLockMessage = "";
        applyServerConfig(new JSONObject(response));
    }

    private void enableOfflineLock(String message) {
        offlineLockEnabled = true;
        serverLockEnabled = false;
        lockScreenEnabled = true;
        offlineLockMessage = message == null ? "Cannot connect to server" : message;
        postStatus(offlineLockMessage);
        handler.post(() -> {
            if (launcherView != null) {
                launcherView.invalidate();
            }
        });
    }

    private void applyServerConfig(JSONObject config) {
        offlineLockEnabled = false;
        offlineLockMessage = "";
        serverConfig = config;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_SERVER_CONFIG, config.toString())
                .apply();
        tvName = config.optString("name", "");
        tvSubtitle = config.optString("subtitle", config.optString("description", config.optString("room", "")));
        serverLockEnabled = false;
        JSONObject settings = config.optJSONObject("settings");
        if (settings != null) {
            requiredService = settings.optString("requiredService", settings.optString("adminRequiredService", DEFAULT_REQUIRED_SERVICE));
            unlockRequiredService = settings.optString("unlockRequiredService", settings.optString("lockRequiredService", DEFAULT_UNLOCK_SERVICE));
            adminAuthRequired = settings.optBoolean("adminAuthRequired", true);
            boolean serverLocked = settings.optBoolean("lockScreenEnabled", settings.optBoolean("locked", false));
            serverLockEnabled = serverLocked;
            long nowSeconds = System.currentTimeMillis() / 1000L;
            lockScreenEnabled = serverLocked && !(lockValidated && lockValidUntil > nowSeconds);
            configPollSeconds = settings.optInt("configPollSeconds", settings.optInt("pollSeconds", 20));
            screensaverSeconds = settings.optInt("screensaverSeconds", 300);
            sleepWallpaperDimAlpha = clamp(settings.optInt("sleepWallpaperDimAlpha", settings.optInt("sleepDimAlpha", 220)), 0, 255);
            homeClockYOffsetDp = settings.optInt("homeClockYOffsetDp", 42);
            sleepClockYOffsetDp = settings.optInt("sleepClockYOffsetDp", 120);
            lockedClockYOffsetDp = settings.optInt("lockedClockYOffsetDp", 0);
            unlockValidationSeconds = Math.max(60, settings.optInt("unlockValidationSeconds", settings.optInt("unlockSeconds", 14400)));
            burnInShiftDp = Math.max(0, settings.optInt("burnInShiftDp", 28));
            burnInPeriodSeconds = Math.max(30, settings.optInt("burnInPeriodSeconds", 240));
            updateApkUrl = settings.optString("updateApkUrl", "");
            String backgroundUrl = settings.optString("backgroundImageUrl", settings.optString("wallpaperUrl", ""));
            if (backgroundUrl.length() > 0 && !backgroundUrl.equals(backgroundBitmapUrl)) {
                downloadBackground(backgroundUrl);
            }
        }
        JSONObject inputConfigs = config.optJSONObject("inputs");
        if (inputConfigs != null) {
            JSONArray keys = inputConfigs.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String configKey = keys.optString(i, "");
                    JSONObject inputConfig = inputConfigs.optJSONObject(configKey);
                    if (inputConfig == null) {
                        continue;
                    }
                    String inputId = inputConfig.optString("inputId", configKey);
                    String iconUrl = inputConfig.optString("iconImageUrl", inputConfig.optString("iconUrl", ""));
                    if (iconUrl.length() > 0 && !iconUrl.equals(inputIconUrls.get(inputId))) {
                        downloadInputIcon(inputId, iconUrl);
                    }
                }
            }
        }
        parseProjectorMenu(config);
        parseQuickSelect(config);
        saveRemoteBridgeConfig(config);
        saveNotificationServerConfig(config);
        startNotificationServer();
        serverConfigLoaded = true;
        postDeviceStatusIfNeeded(true);
        handler.post(() -> {
            if (launcherView != null) {
                launcherView.applyConfig();
            }
        });
    }

    private void parseProjectorMenu(JSONObject config) {
        projectorMenuItems.clear();
        JSONArray menu = config.optJSONArray("projectorMenu");
        JSONObject settings = config.optJSONObject("settings");
        if (menu == null && settings != null) {
            menu = settings.optJSONArray("projectorMenu");
        }
        if (menu == null) {
            return;
        }
        for (int i = 0; i < menu.length(); i++) {
            JSONObject item = menu.optJSONObject(i);
            if (item == null || item.optBoolean("hidden", false)) {
                continue;
            }
            MenuItem menuItem = new MenuItem();
            menuItem.id = item.optString("id", "menu-" + i);
            menuItem.label = item.optString("label", "Menu");
            menuItem.icon = item.optString("icon", "app");
            menuItem.iconImageUrl = item.optString("iconImageUrl", item.optString("iconUrl", ""));
            menuItem.action = item.optString("action", "projectorSettings");
            menuItem.intentAction = item.optString("intentAction", "");
            menuItem.order = item.optInt("order", i);
            projectorMenuItems.add(menuItem);
            if (menuItem.iconImageUrl.length() > 0 && !menuItem.iconImageUrl.equals(inputIconUrls.get(menuItem.id))) {
                downloadInputIcon(menuItem.id, menuItem.iconImageUrl);
            }
        }
        Collections.sort(projectorMenuItems, new Comparator<MenuItem>() {
            @Override
            public int compare(MenuItem first, MenuItem second) {
                return first.order - second.order;
            }
        });
    }

    private void parseQuickSelect(JSONObject config) {
        quickSelectItems.clear();
        JSONArray items = config.optJSONArray("quickSelect");
        JSONObject settings = config.optJSONObject("settings");
        if (items == null && settings != null) {
            items = settings.optJSONArray("quickSelect");
        }
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || item.optBoolean("hidden", false)) {
                continue;
            }
            QuickSelectItem quick = new QuickSelectItem();
            quick.id = item.optString("id", "quick-" + i);
            quick.label = item.optString("label", "Quick");
            quick.icon = item.optString("icon", "tv");
            quick.iconImageUrl = item.optString("iconImageUrl", item.optString("iconUrl", ""));
            quick.inputId = item.optString("inputId", "");
            quick.url = item.optString("url", item.optString("requestUrl", ""));
            quick.method = item.optString("method", item.optString("requestMethod", "POST"));
            quick.body = item.opt("body");
            if (quick.body == null) {
                quick.body = item.opt("requestBody");
            }
            quick.requestBeforeInput = item.optBoolean("requestBeforeInput", true);
            quick.order = item.optInt("order", i);
            quickSelectItems.add(quick);
            if (quick.iconImageUrl.length() > 0 && !quick.iconImageUrl.equals(inputIconUrls.get(quick.id))) {
                downloadInputIcon(quick.id, quick.iconImageUrl);
            }
        }
        Collections.sort(quickSelectItems, new Comparator<QuickSelectItem>() {
            @Override
            public int compare(QuickSelectItem first, QuickSelectItem second) {
                return first.order - second.order;
            }
        });
    }

    private void saveRemoteBridgeConfig(JSONObject config) {
        JSONObject remote = config.optJSONObject("remoteControl");
        JSONObject settings = config.optJSONObject("settings");
        if (remote == null && settings != null) {
            remote = settings.optJSONObject("remoteControl");
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_REMOTE_CONFIG, remote == null ? "{}" : remote.toString())
                .apply();
    }

    private void saveNotificationServerConfig(JSONObject config) {
        JSONObject notification = config.optJSONObject("notificationServer");
        JSONObject settings = config.optJSONObject("settings");
        if (notification == null && settings != null) {
            notification = settings.optJSONObject("notificationServer");
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_NOTIFICATION_CONFIG, notification == null ? "{}" : notification.toString())
                .apply();
    }

    private void saveInputsState(List<InputItem> inputs) {
        JSONArray items = new JSONArray();
        for (InputItem input : inputs) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", input.id);
                item.put("label", input.label);
                item.put("connected", input.connected);
                items.put(item);
            } catch (JSONException ignored) {
                // Skip malformed input rows.
            }
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_INPUTS_STATE, items.toString())
                .apply();
    }

    private void patchServerKeys(JSONObject keys, String successMessage) {
        new Thread(() -> {
            try {
                httpPost(BASE_URL + "/api/tvlauncher/" + urlPart(tvId) + "/keys", keys.toString());
                if (successMessage != null && successMessage.length() > 0) {
                    postStatus(successMessage);
                }
            } catch (Exception error) {
                postStatus("Config save failed");
            }
        }).start();
    }

    private void patchInputsConfig(JSONObject config) {
        serverConfig = config;
        JSONObject body = new JSONObject();
        try {
            JSONObject inputs = config.optJSONObject("inputs");
            body.put("inputs", inputs == null ? new JSONObject() : inputs);
            patchServerKeys(body, "Inputs saved");
        } catch (JSONException ignored) {
            postStatus("Config save failed");
        }
    }

    private void patchSettingsConfig(JSONObject config, String successMessage) {
        serverConfig = config;
        JSONObject body = new JSONObject();
        try {
            JSONObject settings = config.optJSONObject("settings");
            body.put("settings", settings == null ? new JSONObject() : settings);
            patchServerKeys(body, successMessage);
        } catch (JSONException ignored) {
            postStatus("Config save failed");
        }
    }

    private void postDeviceStatusIfNeeded(boolean force) {
        if (!serverConfigLoaded || tvId == null || tvId.length() == 0) {
            return;
        }
        String ip = cleanIpAddress(currentIpAddress);
        int notificationPort = notificationServerPort();
        long nowMs = System.currentTimeMillis();
        boolean ipChanged = ip.length() > 0 && !ip.equals(lastPostedIpAddress);
        boolean portChanged = notificationPort != lastPostedNotificationPort;
        boolean expired = nowMs - lastDeviceStatusPostMs > 300000L;
        if (!force && !ipChanged && !portChanged && !expired) {
            return;
        }
        lastPostedIpAddress = ip;
        lastPostedNotificationPort = notificationPort;
        lastDeviceStatusPostMs = nowMs;
        JSONObject body = new JSONObject();
        JSONObject status = new JSONObject();
        try {
            status.put("tvId", tvId);
            status.put("ipAddress", ip);
            status.put("notificationUrl", ip.length() == 0 ? "" : "http://" + ip + ":" + notificationPort + "/notify");
            status.put("notificationStatusUrl", ip.length() == 0 ? "" : "http://" + ip + ":" + notificationPort + "/status");
            status.put("notificationServerEnabled", notificationServerEnabled());
            status.put("launcherVersion", LAUNCHER_VERSION);
            status.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            status.put("model", Build.MODEL == null ? "" : Build.MODEL);
            status.put("lastSeen", System.currentTimeMillis() / 1000L);
            body.put("deviceStatus", status);
            patchServerKeys(body, "");
        } catch (JSONException ignored) {
            // Status is best effort.
        }
    }

    private String cleanIpAddress(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("IP ", "").trim();
    }

    private void persistDeviceIp() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_DEVICE_IP, cleanIpAddress(currentIpAddress))
                .apply();
    }

    private boolean notificationServerEnabled() {
        try {
            JSONObject config = new JSONObject(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_NOTIFICATION_CONFIG, "{}"));
            return config.optBoolean("enabled", true);
        } catch (JSONException ignored) {
            return true;
        }
    }

    private int notificationServerPort() {
        int activePort = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_NOTIFICATION_PORT, -1);
        if (activePort > 0) {
            return activePort;
        }
        return NOTIFICATION_PORT;
    }

    private void startNotificationServer() {
        Intent intent = new Intent(this, NotificationServerService.class);
        try {
            startService(intent);
        } catch (Exception ignored) {
            // Some TV builds may restrict service start; launcher remains usable.
        }
    }

    private void downloadBackground(String imageUrl) {
        new Thread(() -> {
            try {
                byte[] bytes = httpGetBytes(imageUrl);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    backgroundBitmap = bitmap;
                    backgroundBitmapUrl = imageUrl;
                    cacheBackground(imageUrl, bytes);
                    handler.post(() -> launcherView.invalidate());
                }
            } catch (Exception error) {
                loadCachedBackground();
                postStatus("Wallpaper unavailable");
            }
        }).start();
    }

    private void loadCachedBackground() {
        try (InputStream input = openFileInput(CACHED_BACKGROUND_FILE);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap != null) {
                backgroundBitmap = bitmap;
                backgroundBitmapUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_BACKGROUND_URL, "");
                if (launcherView != null) {
                    handler.post(() -> launcherView.invalidate());
                }
            }
        } catch (Exception ignored) {
            // No cached wallpaper yet.
        }
    }

    private void cacheBackground(String imageUrl, byte[] bytes) {
        try (FileOutputStream output = openFileOutput(CACHED_BACKGROUND_FILE, MODE_PRIVATE)) {
            output.write(bytes);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_BACKGROUND_URL, imageUrl)
                    .apply();
        } catch (Exception ignored) {
            // The launcher can still use the in-memory wallpaper for this session.
        }
    }

    private void downloadInputIcon(String inputId, String imageUrl) {
        new Thread(() -> {
            try {
                byte[] bytes = httpGetBytes(imageUrl);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    inputIconBitmaps.put(inputId, bitmap);
                    inputIconUrls.put(inputId, imageUrl);
                    handler.post(() -> launcherView.invalidate());
                }
            } catch (Exception error) {
                postStatus("Input icon unavailable");
            }
        }).start();
    }

    private void postStatus(String message) {
        handler.post(() -> {
            if (launcherView != null) {
                launcherView.setStatus(message);
            }
        });
    }

    private String getCurrentIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return "IP " + address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep the launcher usable even if the network stack is unavailable.
        }
        return "IP unavailable";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String urlPart(String value) {
        return value.replace(" ", "-");
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("GET");
        return readString(connection);
    }

    private byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("GET");
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private String httpPost(String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
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

    private void sendConfiguredRequest(String rawUrl, String method, Object body, String successMessage) {
        String url = resolveConfiguredUrl(rawUrl);
        if (url.length() == 0) {
            return;
        }
        new Thread(() -> {
            try {
                String verb = method == null ? "POST" : method.trim().toUpperCase(Locale.UK);
                if ("GET".equals(verb)) {
                    httpGet(url);
                } else {
                    httpPost(url, body == null ? "{}" : String.valueOf(body));
                }
                if (successMessage != null && successMessage.length() > 0) {
                    postStatus(successMessage);
                }
            } catch (Exception error) {
                postStatus("Quick action failed");
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
            return BASE_URL + url;
        }
        return url;
    }

    private void launchInput(InputItem item) {
        String label = launcherView == null ? item.label : launcherView.displayLabel(item);
        Uri inputUri = TvContract.buildChannelUriForPassthroughInput(item.id);
        Intent intent = new Intent(Intent.ACTION_VIEW, inputUri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_TITLE, label);
        intent.putExtra("android.media.tv.extra.INPUT_NAME", label);
        try {
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
            markActiveInput(item.id);
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            if (!launchInputFallback(item, label)) {
                clearActiveInput();
                Toast.makeText(this, "This projector does not expose a standard input switcher.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean launchInputFallback(InputItem item, String label) {
        JSONObject inputConfig = inputConfigForItem(item);
        String fallbackIntentAction = inputConfig == null ? "" : inputConfig.optString("fallbackIntentAction", inputConfig.optString("inputFallbackIntentAction", ""));
        String fallbackAction = inputConfig == null ? "" : inputConfig.optString("fallbackAction", inputConfig.optString("inputFallbackAction", ""));
        if (fallbackIntentAction.trim().length() > 0) {
            Intent fallbackIntent = new Intent(fallbackIntentAction.trim());
            fallbackIntent.putExtra("inputId", item.id);
            fallbackIntent.putExtra(Intent.EXTRA_TITLE, label);
            fallbackIntent.putExtra("android.media.tv.extra.INPUT_NAME", label);
            markActiveInput(item.id);
            return openFirstAvailable(fallbackIntent);
        }
        if ("inputSettings".equals(fallbackAction) || "inputs".equals(fallbackAction) || "inputChooser".equals(fallbackAction) || "viewInputs".equals(fallbackAction)) {
            markActiveInput(item.id);
            return openInputChooser();
        }
        if (Build.MANUFACTURER != null && Build.MANUFACTURER.toLowerCase(Locale.UK).contains("xgimi")) {
            markActiveInput(item.id);
            return openInputChooser();
        }
        return false;
    }

    private JSONObject inputConfigForItem(InputItem item) {
        JSONObject inputs = serverConfig.optJSONObject("inputs");
        if (inputs == null || item == null) {
            return null;
        }
        String safeKey = serverInputKey(item.id);
        JSONObject inputConfig = inputs.optJSONObject(safeKey);
        if (inputConfig != null) {
            return inputConfig;
        }
        inputConfig = inputs.optJSONObject(item.id);
        if (inputConfig != null) {
            return inputConfig;
        }
        JSONArray keys = inputs.names();
        if (keys == null) {
            return null;
        }
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.optString(i, "");
            inputConfig = inputs.optJSONObject(key);
            if (inputConfig != null && item.id.equals(inputConfig.optString("inputId", ""))) {
                return inputConfig;
            }
        }
        return null;
    }

    private String serverInputKey(String inputId) {
        String encoded = Base64.encodeToString(inputId.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return "input_" + encoded;
    }

    private void markActiveInput(String inputId) {
        JSONObject settings = serverConfig.optJSONObject("settings");
        int activeSeconds = settings == null ? 21600 : Math.max(60, settings.optInt("activeInputSeconds", 21600));
        long until = System.currentTimeMillis() / 1000L + activeSeconds;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_ACTIVE_INPUT_ID, inputId == null ? "" : inputId)
                .putLong(PREF_ACTIVE_INPUT_UNTIL, until)
                .apply();
    }

    private void clearActiveInput() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_ACTIVE_INPUT_ID, "")
                .putLong(PREF_ACTIVE_INPUT_UNTIL, 0L)
                .apply();
    }

    private void openSystemSettings() {
        openFirstAvailable(
                new Intent(Settings.ACTION_SETTINGS),
                new Intent("android.settings.SETTINGS"));
    }

    private void openInputSettings() {
        openFirstAvailable(
                new Intent("android.settings.TV_INPUT_SETTINGS"),
                new Intent("android.settings.INPUT_SETTINGS"),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private boolean openInputChooser() {
        return openFirstAvailable(
                new Intent("com.android.tv.action.VIEW_INPUTS"),
                new Intent("android.settings.TV_INPUT_SETTINGS"),
                new Intent("android.settings.INPUT_SETTINGS"),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private void openNetworkSettings() {
        openFirstAvailable(
                new Intent(Settings.ACTION_WIFI_SETTINGS),
                new Intent("android.settings.NETWORK_SETTINGS"),
                new Intent("android.settings.WIFI_SETTINGS"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private void openProjectorSettings() {
        openFirstAvailable(
                new Intent("com.xgimi.settings.PROJECTOR_SETTINGS"),
                new Intent("android.settings.DISPLAY_SETTINGS"),
                new Intent(Settings.ACTION_DISPLAY_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private boolean openFirstAvailable(Intent... intents) {
        for (Intent intent : intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                return true;
            } catch (ActivityNotFoundException ignored) {
                // Try the next settings surface exposed by this device.
            }
        }
        Toast.makeText(this, "Settings are not available on this device.", Toast.LENGTH_LONG).show();
        return false;
    }

    private void showInputActions(InputItem item) {
        String[] actions = new String[]{
                "Rename",
                launcherView.isHidden(item) ? "Show on launcher" : "Hide from launcher",
                "Open input"
        };
        new AlertDialog.Builder(this)
                .setTitle(launcherView.displayLabel(item))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        showRenameDialog(item);
                    } else if (which == 1) {
                        launcherView.toggleHidden(item);
                    } else if (which == 2) {
                        launchInput(item);
                    }
                })
                .show();
    }

    private void showRenameDialog(InputItem item) {
        EditText nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameField.setText(launcherView.displayLabel(item));
        nameField.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("Rename input")
                .setView(nameField)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Use default", (dialog, which) -> launcherView.clearCustomName(item))
                .setPositiveButton("Save", (dialog, which) -> launcherView.setCustomName(item, nameField.getText().toString()))
                .show();
    }

    private void requestAdminAccess() {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        if (!adminAuthRequired || (adminValidated && adminValidUntil > nowSeconds)) {
            launcherView.openAdmin();
            return;
        }
        requestAccess("admin", requiredService.length() == 0 ? DEFAULT_REQUIRED_SERVICE : requiredService, "TV Management Panel - " + tvId, 1800);
    }

    private void requestUnlockAccess() {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        if (lockValidated && lockValidUntil > nowSeconds) {
            lockScreenEnabled = false;
            launcherView.invalidate();
            return;
        }
        requestAccess("unlock", unlockRequiredService.length() == 0 ? DEFAULT_UNLOCK_SERVICE : unlockRequiredService, "TV Unlock - " + tvId, unlockValidationSeconds);
    }

    private void requestAccess(String purpose, String service, String requester, int validationSeconds) {
        launcherView.showAuth(purpose, "Creating approval session...");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("requiredService", service);
                body.put("requester", requester);
                body.put("validationSeconds", validationSeconds);
                JSONObject response = new JSONObject(httpPost(BASE_URL + "/api/auth/internal/validate/session", body.toString()));
                AuthSession session = new AuthSession();
                session.purpose = purpose;
                session.requiredService = service;
                session.sessionId = response.optString("sessionId", "");
                session.pollUrl = response.optString("pollUrl", "");
                session.approveUrl = response.optString("approveUrl", response.optString("qrData", ""));
                authSession = session;
                authQrBitmap = session.approveUrl.length() == 0 ? null : createQrBitmap(session.approveUrl, 320);
                handler.post(() -> {
                    launcherView.showAuth(purpose, session.approveUrl.length() == 0 ? "Approval session created" : session.approveUrl);
                    handler.removeCallbacks(authPoll);
                    handler.postDelayed(authPoll, 1200L);
                });
            } catch (Exception error) {
                handler.post(() -> launcherView.showAuth(purpose, "Unable to create approval session"));
            }
        }).start();
    }

    private Bitmap createQrBitmap(String text, int size) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.rgb(8, 15, 28) : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception error) {
            return null;
        }
    }

    private void pollAuthSession() {
        final AuthSession session = authSession;
        if (session == null || session.pollUrl.length() == 0) {
            return;
        }
        new Thread(() -> {
            try {
                JSONObject response = new JSONObject(httpGet(BASE_URL + session.pollUrl));
                boolean validated = response.optBoolean("validated", false);
                String status = response.optString("status", "pending");
                if (validated) {
                    long nowSeconds = System.currentTimeMillis() / 1000L;
                    long responseValidUntil = response.optLong("validUntil", nowSeconds + validationSecondsForSession(session));
                    long validUntil = responseValidUntil;
                    if ("unlock".equals(session.purpose)) {
                        validUntil = Math.max(responseValidUntil, nowSeconds + unlockValidationSeconds);
                        lockValidated = true;
                        lockValidUntil = validUntil;
                        lockScreenEnabled = false;
                        persistCachedApprovals();
                    } else {
                        adminValidated = true;
                        adminValidUntil = validUntil;
                        persistCachedApprovals();
                    }
                    authSession = null;
                    handler.post(() -> {
                        if ("unlock".equals(session.purpose)) {
                            launcherView.returnHome();
                        } else {
                            launcherView.openAdmin();
                        }
                    });
                } else if ("denied".equals(status) || "expired".equals(status)) {
                    authSession = null;
                    handler.post(() -> launcherView.showAuth(session.purpose, "Approval " + status));
                } else {
                    handler.postDelayed(authPoll, 1500L);
                }
            } catch (Exception error) {
                handler.post(() -> launcherView.showAuth(session.purpose, "Approval check failed"));
            }
        }).start();
    }

    private int validationSecondsForSession(AuthSession session) {
        return "unlock".equals(session.purpose) ? unlockValidationSeconds : 1800;
    }

    private void loadCachedApprovals() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long nowSeconds = System.currentTimeMillis() / 1000L;
        adminValidUntil = preferences.getLong(PREF_ADMIN_VALID_UNTIL, 0L);
        lockValidUntil = preferences.getLong(PREF_LOCK_VALID_UNTIL, 0L);
        adminValidated = adminValidUntil > nowSeconds;
        lockValidated = lockValidUntil > nowSeconds;
    }

    private void persistCachedApprovals() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(PREF_ADMIN_VALID_UNTIL, adminValidUntil)
                .putLong(PREF_LOCK_VALID_UNTIL, lockValidUntil)
                .apply();
    }

    private void openUpdateUrl() {
        if (updateApkUrl == null || updateApkUrl.trim().length() == 0) {
            Toast.makeText(this, "No update URL in config.", Toast.LENGTH_LONG).show();
            return;
        }
        openFirstAvailable(new Intent(Intent.ACTION_VIEW, Uri.parse(updateApkUrl)));
    }

    private static final class AuthSession {
        String purpose;
        String requiredService;
        String sessionId;
        String pollUrl;
        String approveUrl;
    }

    private static final class InputItem {
        final String id;
        final String label;
        final boolean connected;

        InputItem(String id, String label, boolean connected) {
            this.id = id;
            this.label = label;
            this.connected = connected;
        }
    }

    private static final class MenuItem {
        String id;
        String label;
        String icon;
        String iconImageUrl;
        String action;
        String intentAction;
        int order;
    }

    private static final class QuickSelectItem {
        String id;
        String label;
        String icon;
        String iconImageUrl;
        String inputId;
        String url;
        String method;
        Object body;
        boolean requestBeforeInput;
        int order;
    }

    private final class ProjectorLauncherView extends View {
        private static final int MODE_HOME = 0;
        private static final int MODE_ADMIN = 1;
        private static final int MODE_AUTH = 2;
        private static final int ADMIN_TV_SETTINGS = 0;
        private static final int ADMIN_INPUT_SETTINGS = 1;
        private static final int ADMIN_PROJECTOR_SETTINGS = 2;
        private static final int ADMIN_REFRESH_CONFIG = 3;
        private static final int ADMIN_UPDATE = 4;
        private static final int ADMIN_LOCK_NOW = 5;
        private static final int ADMIN_LOGOUT_ADMIN = 6;
        private static final int ADMIN_LOGOUT_UNLOCK = 7;
        private static final int ADMIN_INPUT_OFFSET = 8;

        private final SharedPreferences preferences;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint smallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.UK);
        private final RectF rect = new RectF();
        private final String deviceName = buildDeviceName();
        private List<InputItem> allInputs = new ArrayList<>();
        private List<InputItem> visibleInputs = new ArrayList<>();
        private List<InputItem> hiddenInputs = new ArrayList<>();
        private Set<String> hiddenIds = new HashSet<>();
        private String status = "";
        private String authMessage = "";
        private String authPurpose = "admin";
        private int mode = MODE_HOME;
        private int homeFocus = 0;
        private int adminFocus = 0;
        private float adminScroll = 0;

        ProjectorLauncherView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();

            preferences = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            hiddenIds = new HashSet<>(preferences.getStringSet("hidden_inputs", new HashSet<String>()));
            textPaint.setColor(Color.WHITE);
            textPaint.setFakeBoldText(true);
            smallTextPaint.setColor(Color.argb(190, 255, 255, 255));
            focusPaint.setStyle(Paint.Style.STROKE);
            focusPaint.setStrokeWidth(dp(3));
            focusPaint.setColor(Color.WHITE);
        }

        void setInputs(List<InputItem> inputs) {
            allInputs = inputs;
            splitInputs();
            applyConfig();
        }

        void setStatus(String message) {
            status = message == null ? "" : message;
            invalidate();
        }

        void applyConfig() {
            Set<String> serverHidden = new HashSet<>();
            for (InputItem item : allInputs) {
                JSONObject inputConfig = inputConfig(item);
                if (inputConfig != null && inputConfig.optBoolean("hidden", false)) {
                    serverHidden.add(item.id);
                }
            }
            hiddenIds = serverHidden;
            preferences.edit().putStringSet("hidden_inputs", new HashSet<>(hiddenIds)).apply();
            ensureInputsInServerConfig();
            splitInputs();
            clampFocus();
            invalidate();
        }

        boolean isHidden(InputItem item) {
            return hiddenIds.contains(item.id);
        }

        String displayLabel(InputItem item) {
            JSONObject config = inputConfig(item);
            if (config != null) {
                String serverLabel = config.optString("label", "");
                if (serverLabel.trim().length() > 0) {
                    return serverLabel.trim();
                }
            }
            String custom = preferences.getString(customNameKey(item), "");
            if (custom != null && custom.trim().length() > 0) {
                return custom.trim();
            }
            return item.label;
        }

        void setCustomName(InputItem item, String name) {
            String cleanName = name == null ? "" : name.trim();
            preferences.edit().putString(customNameKey(item), cleanName).apply();
            JSONObject config = mutableConfig();
            JSONObject inputConfig = ensureInputConfig(config, item);
            try {
                inputConfig.put("label", cleanName);
                patchInputsConfig(config);
            } catch (JSONException ignored) {
                setStatus("Could not save name");
            }
            invalidate();
        }

        void clearCustomName(InputItem item) {
            preferences.edit().remove(customNameKey(item)).apply();
            JSONObject config = mutableConfig();
            JSONObject inputConfig = ensureInputConfig(config, item);
            inputConfig.remove("label");
            patchInputsConfig(config);
            invalidate();
        }

        void toggleHidden(InputItem item) {
            if (hiddenIds.contains(item.id)) {
                hiddenIds.remove(item.id);
                Toast.makeText(getContext(), displayLabel(item) + " shown", Toast.LENGTH_SHORT).show();
            } else {
                hiddenIds.add(item.id);
                Toast.makeText(getContext(), displayLabel(item) + " hidden", Toast.LENGTH_SHORT).show();
            }
            preferences.edit().putStringSet("hidden_inputs", new HashSet<>(hiddenIds)).apply();
            JSONObject config = mutableConfig();
            JSONObject inputConfig = ensureInputConfig(config, item);
            try {
                inputConfig.put("hidden", hiddenIds.contains(item.id));
                patchInputsConfig(config);
            } catch (JSONException ignored) {
                setStatus("Could not save visibility");
            }
            splitInputs();
            clampFocus();
            invalidate();
        }

        void openAdmin() {
            mode = MODE_ADMIN;
            adminFocus = 0;
            updateAdminScroll();
            invalidate();
        }

        void returnHome() {
            mode = MODE_HOME;
            invalidate();
        }

        void showAuth(String purpose, String message) {
            mode = MODE_AUTH;
            authPurpose = purpose == null ? "admin" : purpose;
            authMessage = message == null ? "" : message;
            invalidate();
        }

        void handleBackPressed() {
            lastInteractionMs = System.currentTimeMillis();
            if (mode == MODE_ADMIN || mode == MODE_AUTH) {
                mode = MODE_HOME;
            }
            invalidate();
        }

        private String customNameKey(InputItem item) {
            return "name_" + item.id;
        }

        private JSONObject inputConfig(InputItem item) {
            JSONObject inputs = serverConfig.optJSONObject("inputs");
            if (inputs == null) {
                return null;
            }
            JSONObject inputConfig = inputs.optJSONObject(serverInputKey(item));
            if (inputConfig != null) {
                return inputConfig;
            }
            inputConfig = inputs.optJSONObject(item.id);
            if (inputConfig != null) {
                return inputConfig;
            }
            JSONArray keys = inputs.names();
            if (keys == null) {
                return null;
            }
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.optString(i, "");
                inputConfig = inputs.optJSONObject(key);
                if (inputConfig != null && item.id.equals(inputConfig.optString("inputId", ""))) {
                    return inputConfig;
                }
            }
            return null;
        }

        private JSONObject mutableConfig() {
            try {
                return new JSONObject(serverConfig.toString());
            } catch (JSONException ignored) {
                return new JSONObject();
            }
        }

        private JSONObject ensureInputConfig(JSONObject config, InputItem item) {
            JSONObject inputs = config.optJSONObject("inputs");
            if (inputs == null) {
                inputs = new JSONObject();
                try {
                    config.put("inputs", inputs);
                } catch (JSONException ignored) {
                    return new JSONObject();
                }
            }
            String safeKey = serverInputKey(item);
            JSONObject inputConfig = inputs.optJSONObject(safeKey);
            if (inputConfig == null) {
                inputConfig = inputs.optJSONObject(item.id);
                if (inputConfig != null) {
                    inputs.remove(item.id);
                }
            }
            if (inputConfig == null) {
                JSONArray keys = inputs.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String key = keys.optString(i, "");
                        JSONObject candidate = inputs.optJSONObject(key);
                        if (candidate != null && item.id.equals(candidate.optString("inputId", ""))) {
                            inputConfig = candidate;
                            if (!safeKey.equals(key)) {
                                inputs.remove(key);
                            }
                            break;
                        }
                    }
                }
            }
            if (inputConfig == null) {
                inputConfig = new JSONObject();
            }
            try {
                inputConfig.put("inputId", item.id);
                inputs.put(safeKey, inputConfig);
            } catch (JSONException ignored) {
                return new JSONObject();
            }
            return inputConfig;
        }

        private String serverInputKey(InputItem item) {
            String encoded = Base64.encodeToString(item.id.getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return "input_" + encoded;
        }

        private boolean hasMongoUnsafeKey(JSONObject object) {
            JSONArray keys = object.names();
            if (keys == null) {
                return false;
            }
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.optString(i, "");
                if (key.indexOf('.') >= 0 || key.indexOf('$') >= 0) {
                    return true;
                }
            }
            return false;
        }

        private void removeUnsafeInputKeys(JSONObject config) {
            JSONObject inputs = config.optJSONObject("inputs");
            if (inputs == null) {
                return;
            }
            JSONArray keys = inputs.names();
            if (keys == null) {
                return;
            }
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.optString(i, "");
                if (key.indexOf('.') >= 0 || key.indexOf('$') >= 0) {
                    inputs.remove(key);
                }
            }
        }

        private void ensureInputsInServerConfig() {
            if (!serverConfigLoaded || allInputs.isEmpty()) {
                return;
            }
            JSONObject config = mutableConfig();
            JSONObject inputs = config.optJSONObject("inputs");
            boolean changed = inputs != null && hasMongoUnsafeKey(inputs);
            for (InputItem item : allInputs) {
                JSONObject inputConfig = ensureInputConfig(config, item);
                try {
                    if (!inputConfig.has("label")) {
                        inputConfig.put("label", item.label);
                        changed = true;
                    }
                    if (!inputConfig.has("hidden")) {
                        inputConfig.put("hidden", false);
                        changed = true;
                    }
                    if (!inputConfig.has("icon")) {
                        inputConfig.put("icon", "tv");
                        changed = true;
                    }
                    if (!item.id.equals(inputConfig.optString("inputId", ""))) {
                        inputConfig.put("inputId", item.id);
                        changed = true;
                    }
                } catch (JSONException ignored) {
                    // Ignore a single bad field and keep the launcher usable.
                }
            }
            removeUnsafeInputKeys(config);
            if (changed) {
                patchInputsConfig(config);
            }
        }

        private void splitInputs() {
            visibleInputs = new ArrayList<>();
            hiddenInputs = new ArrayList<>();
            for (InputItem input : allInputs) {
                if (hiddenIds.contains(input.id)) {
                    hiddenInputs.add(input);
                } else {
                    visibleInputs.add(input);
                }
            }
            Collections.sort(visibleInputs, new Comparator<InputItem>() {
                @Override
                public int compare(InputItem first, InputItem second) {
                    int orderCompare = Integer.compare(inputOrder(first), inputOrder(second));
                    if (orderCompare != 0) {
                        return orderCompare;
                    }
                    return Integer.compare(allInputs.indexOf(first), allInputs.indexOf(second));
                }
            });
            Collections.sort(hiddenInputs, new Comparator<InputItem>() {
                @Override
                public int compare(InputItem first, InputItem second) {
                    int orderCompare = Integer.compare(inputOrder(first), inputOrder(second));
                    if (orderCompare != 0) {
                        return orderCompare;
                    }
                    return Integer.compare(allInputs.indexOf(first), allInputs.indexOf(second));
                }
            });
        }

        private int inputOrder(InputItem item) {
            JSONObject config = inputConfig(item);
            if (config != null && config.has("order")) {
                return config.optInt("order", allInputs.indexOf(item));
            }
            return 100000 + allInputs.indexOf(item);
        }

        private void clampFocus() {
            int homeCount = homeTileCount() + 1;
            homeFocus = Math.max(0, Math.min(homeCount - 1, homeFocus));
            adminFocus = Math.max(0, Math.min(adminItemCount() - 1, adminFocus));
            updateAdminScroll();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawWallpaper(canvas);
            if (mode == MODE_AUTH) {
                drawAuth(canvas);
            } else if (lockScreenEnabled) {
                drawLocked(canvas);
            } else if (isIdle()) {
                drawSleepClock(canvas);
            } else if (mode == MODE_ADMIN) {
                drawAdmin(canvas);
            } else {
                drawHome(canvas);
            }
        }

        private void drawWallpaper(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (backgroundBitmap != null) {
                float scale = Math.max(width / (float) backgroundBitmap.getWidth(), height / (float) backgroundBitmap.getHeight());
                float drawWidth = backgroundBitmap.getWidth() * scale;
                float drawHeight = backgroundBitmap.getHeight() * scale;
                rect.set((width - drawWidth) / 2f, (height - drawHeight) / 2f, (width + drawWidth) / 2f, (height + drawHeight) / 2f);
                canvas.drawBitmap(backgroundBitmap, null, rect, paint);
            } else {
                LinearGradient gradient = new LinearGradient(
                        0, 0, width, height,
                        new int[]{Color.rgb(0, 0, 0), Color.rgb(18, 18, 18), Color.rgb(0, 0, 0)},
                        new float[]{0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP);
                paint.setShader(gradient);
                canvas.drawRect(0, 0, width, height, paint);
                paint.setShader(null);
                paint.setColor(Color.argb(24, 255, 255, 255));
                canvas.drawOval(width * 0.56f, height * 0.12f, width * 1.08f, height * 0.84f, paint);
            }
            int dimAlpha = (lockScreenEnabled || isIdle()) ? sleepWallpaperDimAlpha : (backgroundBitmap == null ? 55 : 105);
            paint.setColor(Color.argb(dimAlpha, 0, 0, 0));
            canvas.drawRect(0, 0, width, height, paint);
        }

        private void drawHome(Canvas canvas) {
            drawClock(canvas, homeFocus == clockIndex(), false, 0);
            drawTitle(canvas);
            drawSourceSection(canvas);
            drawQuickSelectSection(canvas);
            drawStatusLine(canvas);
        }

        private void drawStatusLine(Canvas canvas) {
            String ip = currentIpAddress == null || currentIpAddress.length() == 0 ? "IP unavailable" : currentIpAddress;
            String line = status.length() > 0 ? status + " · " + ip : ip;
            smallTextPaint.setTextSize(sp(12));
            smallTextPaint.setTextAlign(Paint.Align.LEFT);
            smallTextPaint.setColor(Color.argb(145, 255, 255, 255));
            canvas.drawText(line, dp(48), getHeight() - dp(32), smallTextPaint);
        }

        private void drawTitle(Canvas canvas) {
            String title = tvName == null || tvName.trim().length() == 0 ? "" : tvName.trim();
            String subtitle = tvSubtitle == null ? "" : tvSubtitle.trim();
            if (title.length() == 0 && subtitle.length() == 0) {
                return;
            }
            float left = dp(52);
            float top = dp(72);
            if (title.length() > 0) {
                textPaint.setTextAlign(Paint.Align.LEFT);
                textPaint.setTextSize(sp(34));
                textPaint.setFakeBoldText(true);
                textPaint.setColor(Color.WHITE);
                canvas.drawText(title, left, top, textPaint);
            }
            if (subtitle.length() > 0) {
                smallTextPaint.setTextAlign(Paint.Align.LEFT);
                smallTextPaint.setTextSize(sp(15));
                smallTextPaint.setColor(Color.argb(205, 255, 255, 255));
                canvas.drawText(subtitle, left, top + dp(30), smallTextPaint);
            }
        }

        private void drawClock(Canvas canvas, boolean focused, boolean large, int largeYOffsetDp) {
            String time = clockFormat.format(new Date());
            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setFakeBoldText(true);
            textPaint.setTextSize(large ? sp(190) : sp(92));
            float textWidth = textPaint.measureText(time);
            float driftX = large ? burnInOffsetX() : 0f;
            float driftY = large ? burnInOffsetY() : 0f;
            float right = large ? getWidth() / 2f + driftX + textWidth / 2f : getWidth() - dp(62);
            float y = large ? getHeight() / 2f + driftY + dp(28 + largeYOffsetDp) : dp(86 + homeClockYOffsetDp);
            if (focused && !large) {
                Paint.FontMetrics metrics = textPaint.getFontMetrics();
                rect.set(
                        right - textWidth - dp(18),
                        y + metrics.ascent - dp(8),
                        right + dp(18),
                        y + metrics.descent + dp(8));
                drawFocusPill(canvas, rect);
            }
            textPaint.setColor(Color.WHITE);
            canvas.drawText(time, right, y, textPaint);
        }

        private float burnInOffsetX() {
            if (burnInShiftDp <= 0) {
                return 0f;
            }
            double phase = (System.currentTimeMillis() / 1000.0) / burnInPeriodSeconds * Math.PI * 2.0;
            return (float) Math.sin(phase) * dp(burnInShiftDp);
        }

        private float burnInOffsetY() {
            if (burnInShiftDp <= 0) {
                return 0f;
            }
            double phase = (System.currentTimeMillis() / 1000.0) / (burnInPeriodSeconds * 1.37) * Math.PI * 2.0;
            return (float) Math.cos(phase) * dp(burnInShiftDp * 0.65f);
        }

        private void drawSourceSection(Canvas canvas) {
            float left = dp(52);
            float top = getHeight() * 0.40f;
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(18));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(Color.WHITE);
            drawSmallInputIcon(canvas, left, top - dp(40), Color.WHITE);
            canvas.drawText("Inputs", left + dp(34), top - dp(22), textPaint);

            int lockTileCount = showHomeLockTile() ? 1 : 0;
            int cardCount = visibleInputs.size() + lockTileCount + projectorMenuItems.size();
            if (cardCount == 0) {
                smallTextPaint.setTextSize(sp(15));
                smallTextPaint.setTextAlign(Paint.Align.LEFT);
                smallTextPaint.setColor(Color.argb(205, 255, 255, 255));
                canvas.drawText("No visible inputs", left, top + dp(30), smallTextPaint);
                return;
            }
            float cardWidth = dp(150);
            float cardHeight = dp(88);
            float gap = dp(14);
            for (int i = 0; i < visibleInputs.size(); i++) {
                float x = left + i * (cardWidth + gap);
                drawSourceCard(canvas, visibleInputs.get(i), homeFocus == i, x, top, cardWidth, cardHeight, i);
            }
            if (lockTileCount > 0) {
                int focusIndex = visibleInputs.size();
                float x = left + focusIndex * (cardWidth + gap);
                drawLockCard(canvas, homeFocus == focusIndex, x, top, cardWidth, cardHeight);
            }
            for (int i = 0; i < projectorMenuItems.size(); i++) {
                int focusIndex = visibleInputs.size() + lockTileCount + i;
                float x = left + focusIndex * (cardWidth + gap);
                drawMenuCard(canvas, projectorMenuItems.get(i), homeFocus == focusIndex, x, top, cardWidth, cardHeight);
            }
        }

        private void drawQuickSelectSection(Canvas canvas) {
            if (quickSelectItems.isEmpty()) {
                return;
            }
            float left = dp(52);
            float top = getHeight() * 0.40f + dp(176);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(16));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(Color.WHITE);
            canvas.drawText("Quick Select", left, top - dp(18), textPaint);

            float cardWidth = dp(124);
            float cardHeight = dp(66);
            float gap = dp(12);
            int quickStart = quickStartIndex();
            for (int i = 0; i < quickSelectItems.size(); i++) {
                float x = left + i * (cardWidth + gap);
                drawQuickSelectCard(canvas, quickSelectItems.get(i), homeFocus == quickStart + i, x, top, cardWidth, cardHeight);
            }
        }

        private void drawSourceCard(Canvas canvas, InputItem item, boolean focused, float x, float y, float width, float height, int index) {
            rect.set(x, y, x + width, y + height);
            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(focused ? Color.argb(238, 245, 245, 245) : Color.argb(205, 26, 26, 26));
            canvas.drawRoundRect(rect, dp(10), dp(10), cardPaint);
            if (focused) {
                focusPaint.setStrokeWidth(dp(4));
                focusPaint.setColor(Color.WHITE);
                canvas.drawRoundRect(rect, dp(10), dp(10), focusPaint);
            }

            drawInputIcon(canvas, item, x + width / 2f, y + dp(39), focused ? Color.rgb(20, 20, 20) : Color.WHITE);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(15));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(displayLabel(item), x + dp(10), y + height + dp(24), textPaint);
        }

        private void drawMenuCard(Canvas canvas, MenuItem item, boolean focused, float x, float y, float width, float height) {
            rect.set(x, y, x + width, y + height);
            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(focused ? Color.argb(238, 245, 245, 245) : Color.argb(205, 26, 26, 26));
            canvas.drawRoundRect(rect, dp(10), dp(10), cardPaint);
            if (focused) {
                focusPaint.setStrokeWidth(dp(4));
                focusPaint.setColor(Color.WHITE);
                canvas.drawRoundRect(rect, dp(10), dp(10), focusPaint);
            }

            drawPresetIcon(canvas, item.id, item.icon, x + width / 2f, y + dp(39), focused ? Color.rgb(20, 20, 20) : Color.WHITE);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(15));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(item.label == null ? "Menu" : item.label, x + dp(10), y + height + dp(24), textPaint);
        }

        private void drawQuickSelectCard(Canvas canvas, QuickSelectItem item, boolean focused, float x, float y, float width, float height) {
            rect.set(x, y, x + width, y + height);
            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(focused ? Color.argb(238, 245, 245, 245) : Color.argb(185, 18, 18, 18));
            canvas.drawRoundRect(rect, dp(8), dp(8), cardPaint);
            if (focused) {
                focusPaint.setStrokeWidth(dp(3));
                focusPaint.setColor(Color.WHITE);
                canvas.drawRoundRect(rect, dp(8), dp(8), focusPaint);
            }

            drawPresetIcon(canvas, item.id, item.icon, x + width / 2f, y + dp(30), focused ? Color.rgb(20, 20, 20) : Color.WHITE);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(13));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(item.label == null ? "Quick" : item.label, x + dp(8), y + height + dp(20), textPaint);
        }

        private void drawLockCard(Canvas canvas, boolean focused, float x, float y, float width, float height) {
            rect.set(x, y, x + width, y + height);
            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(focused ? Color.argb(238, 245, 245, 245) : Color.argb(205, 26, 26, 26));
            canvas.drawRoundRect(rect, dp(10), dp(10), cardPaint);
            if (focused) {
                focusPaint.setStrokeWidth(dp(4));
                focusPaint.setColor(Color.WHITE);
                canvas.drawRoundRect(rect, dp(10), dp(10), focusPaint);
            }

            drawLockIcon(canvas, x + width / 2f, y + dp(39), focused ? Color.rgb(20, 20, 20) : Color.WHITE);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(15));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(Color.WHITE);
            canvas.drawText("Lock", x + dp(10), y + height + dp(24), textPaint);
        }

        private boolean showHomeLockTile() {
            return serverLockEnabled && !lockScreenEnabled && !offlineLockEnabled;
        }

        private void drawAdmin(Canvas canvas) {
            float left = dp(60);
            float top = dp(74);
            float width = Math.min(dp(560), getWidth() - dp(120));
            float rowHeight = dp(52);
            float gap = dp(8);

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(27));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(Color.WHITE);
            canvas.drawText("Admin", left, top, textPaint);

            smallTextPaint.setTextAlign(Paint.Align.LEFT);
            smallTextPaint.setTextSize(sp(13));
            smallTextPaint.setColor(Color.argb(175, 255, 255, 255));
            canvas.drawText(deviceName + " · " + tvId + " · " + allInputs.size() + " inputs · " + hiddenInputs.size() + " hidden", left, top + dp(28), smallTextPaint);

            canvas.save();
            canvas.clipRect(0, top + dp(44), getWidth(), getHeight() - dp(24));
            float y = top + dp(58) - adminScroll;
            drawAdminRow(canvas, "TV settings", "Open Android TV settings", adminFocus == ADMIN_TV_SETTINGS, left, y, width, rowHeight);
            y += rowHeight + gap;
            drawAdminRow(canvas, "Input settings", "Open source/input settings", adminFocus == ADMIN_INPUT_SETTINGS, left, y, width, rowHeight);
            y += rowHeight + gap;
            drawAdminRow(canvas, "Projector settings", "Display or projector controls when available", adminFocus == ADMIN_PROJECTOR_SETTINGS, left, y, width, rowHeight);
            y += rowHeight + gap;
            drawAdminRow(canvas, "Refresh config", serverConfigLoaded ? "Poll server now" : "Create/read server config", adminFocus == ADMIN_REFRESH_CONFIG, left, y, width, rowHeight);
            y += rowHeight + gap;
            drawAdminRow(canvas, "Update launcher", updateApkUrl.length() > 0 ? updateApkUrl : "No update URL in server config", adminFocus == ADMIN_UPDATE, left, y, width, rowHeight);
            y += rowHeight + gap;
            drawAdminRow(canvas, "Lock TV now", "Set server lock and return to locked screen", adminFocus == ADMIN_LOCK_NOW, left, y, width, rowHeight);
            y += rowHeight + gap;
            drawAdminRow(canvas, "Log out Admin", adminValidated ? "Require QR approval next time" : "Admin is not currently approved", adminFocus == ADMIN_LOGOUT_ADMIN, left, y, width, rowHeight);
            y += rowHeight + gap;
            drawAdminRow(canvas, "Log out unlock", lockValidated ? "Forget current TV unlock approval" : "TV unlock is not currently approved", adminFocus == ADMIN_LOGOUT_UNLOCK, left, y, width, rowHeight);
            y += rowHeight + gap + dp(10);

            for (int i = 0; i < allInputs.size(); i++) {
                InputItem item = allInputs.get(i);
                boolean hidden = isHidden(item);
                String subtitle = (hidden ? "Hidden" : "Visible") + " · " + (item.connected ? "Connected" : "Detected");
                drawAdminRow(canvas, displayLabel(item), subtitle, adminFocus == ADMIN_INPUT_OFFSET + i, left, y, width, rowHeight);
                y += rowHeight + gap;
            }
            canvas.restore();
        }

        private void drawAdminRow(Canvas canvas, String title, String subtitle, boolean focused, float x, float y, float width, float height) {
            rect.set(x, y, x + width, y + height);
            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(focused ? Color.argb(232, 248, 250, 252) : Color.argb(100, 15, 23, 42));
            canvas.drawRoundRect(rect, dp(7), dp(7), cardPaint);
            if (focused) {
                canvas.drawRoundRect(rect, dp(7), dp(7), focusPaint);
            }

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(17));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(focused ? Color.BLACK : Color.WHITE);
            canvas.drawText(title, x + dp(16), y + dp(23), textPaint);

            smallTextPaint.setTextAlign(Paint.Align.LEFT);
            smallTextPaint.setTextSize(sp(12));
            smallTextPaint.setColor(focused ? Color.rgb(40, 40, 40) : Color.argb(170, 255, 255, 255));
            canvas.drawText(subtitle, x + dp(16), y + dp(42), smallTextPaint);
        }

        private void drawAuth(Canvas canvas) {
            float left = dp(72);
            float top = getHeight() * 0.24f;
            boolean unlock = "unlock".equals(authPurpose);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(26));
            textPaint.setFakeBoldText(true);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(unlock ? "Unlock approval required" : "Admin approval required", left, top, textPaint);

            smallTextPaint.setTextAlign(Paint.Align.LEFT);
            smallTextPaint.setTextSize(sp(15));
            smallTextPaint.setColor(Color.argb(210, 255, 255, 255));
            canvas.drawText(unlock ? unlockRequiredService : requiredService, left, top + dp(34), smallTextPaint);

            float qrSize = dp(220);
            rect.set(left, top + dp(62), left + qrSize, top + dp(62) + qrSize);
            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(Color.WHITE);
            canvas.drawRoundRect(rect, dp(8), dp(8), cardPaint);
            if (authQrBitmap != null) {
                RectF qrRect = new RectF(rect.left + dp(12), rect.top + dp(12), rect.right - dp(12), rect.bottom - dp(12));
                canvas.drawBitmap(authQrBitmap, null, qrRect, paint);
            }

            float textLeft = left + qrSize + dp(34);
            float textTop = top + dp(78);
            textPaint.setTextSize(sp(20));
            textPaint.setColor(Color.WHITE);
            canvas.drawText(unlock ? "Scan to unlock this TV" : "Scan to unlock Admin", textLeft, textTop, textPaint);
            smallTextPaint.setTextSize(sp(13));
            smallTextPaint.setColor(Color.argb(210, 255, 255, 255));
            canvas.drawText("Waiting for approval...", textLeft, textTop + dp(30), smallTextPaint);
            String detail = authDetailMessage();
            if (detail.length() > 0) {
                drawWrappedText(canvas, detail, textLeft, textTop + dp(64), getWidth() - textLeft - dp(72), dp(22), smallTextPaint);
            }
        }

        private String authDetailMessage() {
            String detail = authMessage == null ? "" : authMessage.trim();
            if (detail.startsWith("http://") || detail.startsWith("https://")) {
                return "";
            }
            return detail;
        }

        private void drawLocked(Canvas canvas) {
            drawLockedClock(canvas);
            smallTextPaint.setTextAlign(Paint.Align.CENTER);
            smallTextPaint.setTextSize(sp(18));
            smallTextPaint.setColor(Color.argb(210, 255, 255, 255));
            canvas.drawText(offlineLockEnabled ? "Server unavailable" : "Locked by server policy", getWidth() / 2f, getHeight() / 2f + dp(105), smallTextPaint);
            smallTextPaint.setTextSize(sp(14));
            if (offlineLockEnabled) {
                String message = offlineLockMessage == null || offlineLockMessage.length() == 0 ? "Cannot connect to TV launcher server" : offlineLockMessage;
                canvas.drawText(message, getWidth() / 2f, getHeight() / 2f + dp(134), smallTextPaint);
                canvas.drawText("OK: Network settings  •  Up: Wi-Fi  •  Down: Retry  •  Menu: TV settings", getWidth() / 2f, getHeight() / 2f + dp(162), smallTextPaint);
            } else {
                canvas.drawText("Press OK to request unlock approval", getWidth() / 2f, getHeight() / 2f + dp(134), smallTextPaint);
                canvas.drawText("Press Menu for Admin", getWidth() / 2f, getHeight() / 2f + dp(162), smallTextPaint);
            }
            drawStatusLine(canvas);
        }

        private void drawSleepClock(Canvas canvas) {
            drawClock(canvas, false, true, sleepClockYOffsetDp);
        }

        private void drawLockedClock(Canvas canvas) {
            drawClock(canvas, false, true, lockedClockYOffsetDp);
        }

        private void drawInputIcon(Canvas canvas, InputItem item, float cx, float cy, int color) {
            JSONObject config = inputConfig(item);
            String icon = config == null ? "tv" : config.optString("icon", "tv").toLowerCase(Locale.UK);
            drawPresetIcon(canvas, item.id, icon, cx, cy, color);
        }

        private void drawPresetIcon(Canvas canvas, String id, String icon, float cx, float cy, int color) {
            Bitmap bitmap = inputIconBitmaps.get(id);
            if (bitmap != null) {
                float size = dp(48);
                rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
                canvas.drawBitmap(bitmap, null, rect, paint);
                return;
            }

            icon = icon == null ? "tv" : icon.toLowerCase(Locale.UK);
            if ("hdmi".equals(icon)) {
                drawHdmiIcon(canvas, cx, cy, color);
            } else if ("camera".equals(icon)) {
                drawCameraIcon(canvas, cx, cy, color);
            } else if ("game".equals(icon)) {
                drawGameIcon(canvas, cx, cy, color);
            } else if ("app".equals(icon)) {
                drawAppIcon(canvas, cx, cy, color);
            } else {
                drawTvIcon(canvas, cx, cy, color);
            }
        }

        private void drawTvIcon(Canvas canvas, float cx, float cy, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(color);
            rect.set(cx - dp(38), cy - dp(22), cx + dp(38), cy + dp(18));
            canvas.drawRoundRect(rect, dp(5), dp(5), paint);
            canvas.drawLine(cx - dp(14), cy + dp(27), cx + dp(14), cy + dp(27), paint);
            canvas.drawLine(cx, cy + dp(18), cx, cy + dp(27), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawHdmiIcon(Canvas canvas, float cx, float cy, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(color);
            rect.set(cx - dp(36), cy - dp(13), cx + dp(36), cy + dp(13));
            canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            canvas.drawLine(cx - dp(22), cy + dp(20), cx + dp(22), cy + dp(20), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawCameraIcon(Canvas canvas, float cx, float cy, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(color);
            rect.set(cx - dp(36), cy - dp(20), cx + dp(22), cy + dp(20));
            canvas.drawRoundRect(rect, dp(5), dp(5), paint);
            path.reset();
            path.moveTo(cx + dp(22), cy - dp(9));
            path.lineTo(cx + dp(38), cy - dp(18));
            path.lineTo(cx + dp(38), cy + dp(18));
            path.lineTo(cx + dp(22), cy + dp(9));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawGameIcon(Canvas canvas, float cx, float cy, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(color);
            rect.set(cx - dp(38), cy - dp(18), cx + dp(38), cy + dp(18));
            canvas.drawRoundRect(rect, dp(12), dp(12), paint);
            canvas.drawLine(cx - dp(24), cy, cx - dp(10), cy, paint);
            canvas.drawLine(cx - dp(17), cy - dp(7), cx - dp(17), cy + dp(7), paint);
            canvas.drawCircle(cx + dp(18), cy - dp(5), dp(3), paint);
            canvas.drawCircle(cx + dp(28), cy + dp(5), dp(3), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawAppIcon(Canvas canvas, float cx, float cy, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(color);
            float s = dp(16);
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    float x = cx - dp(22) + col * dp(30);
                    float y = cy - dp(22) + row * dp(30);
                    rect.set(x, y, x + s, y + s);
                    canvas.drawRoundRect(rect, dp(3), dp(3), paint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawLockIcon(Canvas canvas, float cx, float cy, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(color);
            rect.set(cx - dp(22), cy - dp(34), cx + dp(22), cy + dp(12));
            canvas.drawArc(rect, 205, 130, false, paint);
            rect.set(cx - dp(32), cy - dp(6), cx + dp(32), cy + dp(30));
            canvas.drawRoundRect(rect, dp(5), dp(5), paint);
            canvas.drawCircle(cx, cy + dp(10), dp(3), paint);
            canvas.drawLine(cx, cy + dp(13), cx, cy + dp(21), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawSmallInputIcon(Canvas canvas, float x, float y, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(color);
            rect.set(x, y, x + dp(24), y + dp(17));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
            canvas.drawLine(x + dp(7), y + dp(20), x + dp(17), y + dp(20), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawWrappedText(Canvas canvas, String text, float x, float y, float width, float lineHeight, Paint textPaint) {
            if (text == null) {
                return;
            }
            String[] words = text.split(" ");
            String line = "";
            for (String word : words) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (textPaint.measureText(next) > width && line.length() > 0) {
                    canvas.drawText(line, x, y, textPaint);
                    y += lineHeight;
                    line = word;
                } else {
                    line = next;
                }
            }
            if (line.length() > 0) {
                canvas.drawText(line, x, y, textPaint);
            }
        }

        private void drawFocusPill(Canvas canvas, RectF area) {
            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(Color.argb(72, 255, 255, 255));
            canvas.drawRoundRect(area, dp(7), dp(7), cardPaint);
            canvas.drawRoundRect(area, dp(7), dp(7), focusPaint);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            lastInteractionMs = System.currentTimeMillis();
            if (mode == MODE_AUTH) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    mode = MODE_HOME;
                    invalidate();
                }
                return true;
            }
            if (lockScreenEnabled) {
                if (offlineLockEnabled) {
                    return handleOfflineLockKeyDown(keyCode);
                } else if (isSelectKey(keyCode)) {
                    requestUnlockAccess();
                } else if (keyCode == KeyEvent.KEYCODE_MENU) {
                    requestAdminAccess();
                } else {
                    invalidate();
                }
                return true;
            }
            if (isSelectKey(keyCode)) {
                event.startTracking();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                handleBackPressed();
                return true;
            }
            if (mode == MODE_ADMIN) {
                return handleAdminKeyDown(keyCode) || super.onKeyDown(keyCode, event);
            }
            return handleHomeKeyDown(keyCode) || super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (isSelectKey(keyCode) && !event.isCanceled()) {
                if (offlineLockEnabled) {
                    return true;
                } else if (lockScreenEnabled) {
                    requestUnlockAccess();
                } else if (mode == MODE_ADMIN) {
                    activateAdminFocus();
                } else if (mode == MODE_AUTH) {
                    // Approval continues polling; OK simply keeps the URL on screen.
                } else {
                    activateHomeFocus();
                }
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }

        private boolean isSelectKey(int keyCode) {
            return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A;
        }

        private boolean handleOfflineLockKeyDown(int keyCode) {
            if (isSelectKey(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                openNetworkSettings();
            } else if (keyCode == KeyEvent.KEYCODE_MENU) {
                openSystemSettings();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                fetchOrCreateConfig();
                Toast.makeText(getContext(), "Retrying server", Toast.LENGTH_SHORT).show();
            } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                invalidate();
            } else {
                invalidate();
            }
            return true;
        }

        private boolean handleHomeKeyDown(int keyCode) {
            int tileCount = homeTileCount();
            int clockIndex = clockIndex();
            int quickStart = quickStartIndex();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                requestAdminAccess();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (homeFocus < tileCount - 1) {
                    homeFocus++;
                } else {
                    homeFocus = clockIndex;
                }
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (homeFocus == clockIndex) {
                    homeFocus = tileCount > 0 ? tileCount - 1 : clockIndex;
                } else {
                    homeFocus = Math.max(0, homeFocus - 1);
                }
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (homeFocus >= quickStart && !quickSelectItems.isEmpty()) {
                    homeFocus = primaryTileCount() > 0 ? 0 : clockIndex;
                } else {
                    homeFocus = clockIndex;
                }
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (homeFocus == clockIndex && tileCount > 0) {
                    homeFocus = 0;
                } else if (homeFocus < quickStart && !quickSelectItems.isEmpty()) {
                    homeFocus = quickStart;
                }
                invalidate();
                return true;
            }
            return false;
        }

        private boolean handleAdminKeyDown(int keyCode) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                mode = MODE_HOME;
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                adminFocus = Math.max(0, adminFocus - 1);
                updateAdminScroll();
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                adminFocus = Math.min(adminItemCount() - 1, adminFocus + 1);
                updateAdminScroll();
                invalidate();
                return true;
            }
            return false;
        }

        private void activateHomeFocus() {
            int inputCount = visibleInputs.size();
            int lockTileCount = showHomeLockTile() ? 1 : 0;
            if (homeFocus < inputCount) {
                launchInput(visibleInputs.get(homeFocus));
            } else if (lockTileCount > 0 && homeFocus == inputCount) {
                lockTvNow();
            } else if (homeFocus < inputCount + lockTileCount + projectorMenuItems.size()) {
                activateMenuItem(projectorMenuItems.get(homeFocus - inputCount - lockTileCount));
            } else if (homeFocus < quickStartIndex() + quickSelectItems.size()) {
                activateQuickSelect(quickSelectItems.get(homeFocus - quickStartIndex()));
            } else {
                requestAdminAccess();
            }
        }

        private int clockIndex() {
            return homeTileCount();
        }

        private int homeTileCount() {
            return primaryTileCount() + quickSelectItems.size();
        }

        private int primaryTileCount() {
            return visibleInputs.size() + (showHomeLockTile() ? 1 : 0) + projectorMenuItems.size();
        }

        private int quickStartIndex() {
            return primaryTileCount();
        }

        private void activateQuickSelect(QuickSelectItem item) {
            InputItem input = findInputById(item.inputId);
            if (item.requestBeforeInput) {
                sendConfiguredRequest(item.url, item.method, item.body, item.label + " requested");
            }
            if (input != null) {
                launchInput(input);
            } else {
                Toast.makeText(getContext(), "Quick input is not available.", Toast.LENGTH_LONG).show();
            }
            if (!item.requestBeforeInput) {
                sendConfiguredRequest(item.url, item.method, item.body, item.label + " requested");
            }
        }

        private InputItem findInputById(String inputId) {
            if (inputId == null || inputId.trim().length() == 0) {
                return null;
            }
            for (InputItem item : allInputs) {
                if (inputId.equals(item.id)) {
                    return item;
                }
            }
            return null;
        }

        private void activateMenuItem(MenuItem item) {
            String action = item.action == null ? "" : item.action;
            if ("projectorSettings".equals(action) || "projector".equals(action)) {
                openProjectorSettings();
            } else if ("inputSettings".equals(action) || "inputs".equals(action)) {
                openInputSettings();
            } else if ("tvSettings".equals(action) || "settings".equals(action)) {
                openSystemSettings();
            } else if ("refreshConfig".equals(action) || "refresh".equals(action)) {
                fetchOrCreateConfig();
                Toast.makeText(getContext(), "Refreshing config", Toast.LENGTH_SHORT).show();
            } else if ("update".equals(action)) {
                openUpdateUrl();
            } else if (item.intentAction != null && item.intentAction.trim().length() > 0) {
                openFirstAvailable(new Intent(item.intentAction.trim()));
            } else {
                openProjectorSettings();
            }
        }

        private void activateAdminFocus() {
            if (adminFocus == ADMIN_TV_SETTINGS) {
                openSystemSettings();
            } else if (adminFocus == ADMIN_INPUT_SETTINGS) {
                openInputSettings();
            } else if (adminFocus == ADMIN_PROJECTOR_SETTINGS) {
                openProjectorSettings();
            } else if (adminFocus == ADMIN_REFRESH_CONFIG) {
                fetchOrCreateConfig();
                Toast.makeText(getContext(), "Refreshing config", Toast.LENGTH_SHORT).show();
            } else if (adminFocus == ADMIN_UPDATE) {
                openUpdateUrl();
            } else if (adminFocus == ADMIN_LOCK_NOW) {
                lockTvNow();
            } else if (adminFocus == ADMIN_LOGOUT_ADMIN) {
                adminValidated = false;
                adminValidUntil = 0L;
                persistCachedApprovals();
                Toast.makeText(getContext(), "Admin approval cleared", Toast.LENGTH_SHORT).show();
            } else if (adminFocus == ADMIN_LOGOUT_UNLOCK) {
                lockValidated = false;
                lockValidUntil = 0L;
                persistCachedApprovals();
                Toast.makeText(getContext(), "TV unlock approval cleared", Toast.LENGTH_SHORT).show();
            } else {
                int inputIndex = adminFocus - ADMIN_INPUT_OFFSET;
                if (inputIndex >= 0 && inputIndex < allInputs.size()) {
                    showInputActions(allInputs.get(inputIndex));
                }
            }
        }

        private void lockTvNow() {
            JSONObject config = mutableConfig();
            JSONObject settings = config.optJSONObject("settings");
            if (settings == null) {
                settings = new JSONObject();
                try {
                    config.put("settings", settings);
                } catch (JSONException ignored) {
                    setStatus("Could not lock TV");
                    return;
                }
            }
            try {
                settings.put("lockScreenEnabled", true);
                lockValidated = false;
                lockValidUntil = 0L;
                persistCachedApprovals();
                serverLockEnabled = true;
                lockScreenEnabled = true;
                mode = MODE_HOME;
                patchSettingsConfig(config, "TV locked");
                invalidate();
            } catch (JSONException ignored) {
                setStatus("Could not lock TV");
            }
        }

        private int adminItemCount() {
            return ADMIN_INPUT_OFFSET + allInputs.size();
        }

        private void updateAdminScroll() {
            float rowHeight = dp(60);
            float header = dp(132);
            float selectedTop = adminFocus * rowHeight;
            float selectedBottom = selectedTop + rowHeight;
            float viewport = Math.max(dp(160), getHeight() - header - dp(30));
            if (selectedTop - adminScroll < 0) {
                adminScroll = selectedTop;
            } else if (selectedBottom - adminScroll > viewport) {
                adminScroll = selectedBottom - viewport;
            }
            adminScroll = Math.max(0, adminScroll);
        }

        private boolean isIdle() {
            return mode == MODE_HOME
                    && screensaverSeconds > 0
                    && System.currentTimeMillis() - lastInteractionMs > screensaverSeconds * 1000L;
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private float sp(float value) {
            return value * getResources().getDisplayMetrics().scaledDensity;
        }

        private String buildDeviceName() {
            String manufacturer = clean(Build.MANUFACTURER);
            String model = clean(Build.MODEL);
            if (manufacturer.length() == 0 && model.length() == 0) {
                return "Unknown device";
            }
            if (manufacturer.length() == 0 || model.toLowerCase(Locale.UK).contains(manufacturer.toLowerCase(Locale.UK))) {
                return model;
            }
            return manufacturer + " " + model;
        }

        private String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
