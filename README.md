# Projector Launcher

A tiny Android TV launcher for projector-style setups.

The first version intentionally does very little:

- shows connected TV inputs discovered through Android's `TvInputManager`
- launches an input when selected
- hides, restores, and renames inputs from an admin page
- polls a JSON config server for config and wallpaper
- can lock the launcher from server config
- can require QR-based approval for Admin
- shows a QR code for Admin approval
- shows a large clock, including an idle full-screen clock

## Remote Controls

- D-pad: move between sources and the clock
- Select / OK on a source: open the selected input
- Select / OK on the clock: open Admin
- Menu: open Admin
- Admin Select / OK on an input: rename, hide/show, or open it
- Admin Select / OK on settings rows: open TV/input/projector settings, refresh config, or open update URL
- Admin Back: return to the launcher

## Server Config

The launcher uses this TV id format:

```text
{manufacturer}-{model}-{android_id}
```

It reads and creates config using the endpoints in `AuthValidationAndTVLauncherAPI.md`.

Supported JSON shape:

```json
{
  "name": "Kitchen TV",
  "subtitle": "Use the remote to choose an input",
  "room": "Kitchen",
  "launcherVersion": "0.2.1",
  "inputs": {
    "input_Y29tLnRjbC50dmlucHV0Ly5UdlBhc3NUaHJvdWdoU2VydmljZS9IVzE2": {
      "inputId": "com.tcl.tvinput/.TvPassThroughService/HW16",
      "label": "Sky",
      "hidden": false,
      "order": 10,
      "icon": "tv",
      "iconImageUrl": "https://example.local/icons/sky.png"
    }
  },
  "projectorMenu": [
    {
      "id": "projector-settings",
      "label": "Projector",
      "icon": "tv",
      "order": 90,
      "action": "projectorSettings"
    },
    {
      "id": "picture-settings",
      "label": "Picture",
      "icon": "app",
      "order": 100,
      "intentAction": "android.settings.DISPLAY_SETTINGS"
    }
  ],
  "settings": {
    "requiredService": "example.tvlauncher.admin",
    "unlockRequiredService": "example.tvlauncher.unlock",
    "adminAuthRequired": true,
    "lockScreenEnabled": false,
    "configPollSeconds": 20,
    "screensaverSeconds": 300,
    "sleepWallpaperDimAlpha": 220,
    "homeClockYOffsetDp": 42,
    "sleepClockYOffsetDp": 120,
    "lockedClockYOffsetDp": 0,
    "unlockValidationSeconds": 14400,
    "burnInShiftDp": 28,
    "burnInPeriodSeconds": 240,
    "backgroundImageUrl": "https://example.local/wallpaper.jpg",
    "updateApkUrl": "https://example.local/projector-launcher.apk"
  }
}
```

`name`, `subtitle`, `backgroundImageUrl`, labels, hidden states, projector menu items, lock state, auth requirement, unlock auth requirement, poll interval, screensaver timeout, and update URL are polled from the server. Rename/hide changes made in Admin patch the top-level `inputs` key through `/api/tvlauncher/{tvId}/keys`, so the server remains the source of truth.

Wallpaper images are cached on the TV after the first successful download. On the next launcher start, the cached image is shown immediately while the launcher polls the server for config and checks whether the image has changed.

Sleep clock options:

- `screensaverSeconds`: seconds of home-screen inactivity before the large clock appears. `0` disables it.
- `sleepWallpaperDimAlpha`: black overlay during idle/locked clock mode. `0` leaves the wallpaper bright, `255` makes it fully black.
- `homeClockYOffsetDp`: moves the normal top-right home clock down. Increase for lower placement, decrease or use a negative value to move it back up.
- `sleepClockYOffsetDp`: moves the large idle clock down. Increase for lower placement, decrease or use a negative value to move it back up.
- `lockedClockYOffsetDp`: moves the large locked-screen clock separately so it does not cover the lock message.
- `burnInShiftDp`: maximum clock drift in density-independent pixels. `0` disables movement.
- `burnInPeriodSeconds`: how long the horizontal drift takes to complete a full cycle.

Lock behavior:

- `requiredService` controls Admin approval. Default: `example.tvlauncher.admin`.
- `unlockRequiredService` controls unlocking a locked TV. Default: `example.tvlauncher.unlock`.
- `unlockValidationSeconds` controls how long an approved unlock lasts. Default: `14400` seconds / 4 hours.
- When `lockScreenEnabled` is true, OK opens the unlock QR flow.
- Admin includes a `Lock TV now` row that sets server `lockScreenEnabled` to true.
- Menu still opens the Admin QR flow.
- Admin includes logout rows to clear cached Admin approval and cached TV unlock approval.
- If the launcher cannot connect to the server during boot/config refresh, it enters an offline lock state. OK/Up opens network or Wi-Fi settings, Menu opens TV settings, and Down retries the server connection.

Input icon options:

- `icon`: preset name. Current presets are `tv`, `hdmi`, `camera`, `game`, and `app`.
- `iconImageUrl`: custom PNG/JPG/WebP image URL. This takes priority over the preset.
- `order`: lower numbers appear first. Inputs with `order` appear before inputs without `order`; unordered inputs then stay in the TV's detected order.

When an input is launched, the launcher sends the custom `label` as the intent title and shows it briefly as an overlay toast. The vendor Live TV/input app may still show its own built-in HDMI name because Android does not expose a standard way for a launcher app to rename the TV firmware's input overlay.

Projectivy's bundled icons are not directly available to this launcher, but any exported or hosted icon image can be used through `iconImageUrl`.

Projector menu options:

- Put menu items in top-level `projectorMenu` or `settings.projectorMenu`.
- `action` can be `projectorSettings`, `inputSettings`, `tvSettings`, `refreshConfig`, or `update`.
- `intentAction` can be used instead of `action` for a device/vendor settings intent.
- Menu items support the same `icon` and `iconImageUrl` fields as inputs.
- Menu items also support `order`; lower numbers appear first in the menu group after the inputs.

## Building

This is a standard Android Gradle project. Open the folder in Android Studio, let it install the Android Gradle plugin if needed, then run the `app` configuration.

Private server settings are supplied through Gradle properties. Copy the example file and edit it for your environment:

```sh
cp gradle.properties.example gradle.properties
```

```properties
tvLauncherBaseUrl=https://example.com/Brain
tvLauncherAdminService=example.tvlauncher.admin
tvLauncherUnlockService=example.tvlauncher.unlock
```

`gradle.properties` is ignored by Git so a public repository can keep real internal URLs and access keys out of source control.

From a configured command line:

```sh
./gradlew assembleDebug
```

## Device Notes

Android TV input launching is vendor-sensitive. The app uses the standard passthrough-input URI:

```java
TvContract.buildChannelUriForPassthroughInput(inputId)
```

That works on many Android TV devices, but some projector firmware may route HDMI/input switching through private activities or settings panels. If your device lists inputs but does not switch to them, the launcher can be adapted once we inspect the projector's installed packages and intents with ADB.

## Target Devices

The first devices to validate are:

- TCL TV
- XGIMI projector
- Sharp TV

For each device, check:

- The device/status line shows the expected manufacturer and model.
- The detected input count matches the connected HDMI/TV inputs.
- Selecting each visible input switches to the correct source.
- Hiding an input removes it from the main screen.
- Opening hidden inputs and restoring one returns it to the main screen.

If a device shows no inputs, or shows inputs but cannot launch them, capture this from ADB:

```sh
adb shell dumpsys tv_input
adb shell pm list packages | grep -i -E 'input|tv|hdmi|source|xgimi|tcl|sharp'
```

Those two outputs will tell us whether the device supports the standard Android TV input path or needs a vendor-specific fallback.
