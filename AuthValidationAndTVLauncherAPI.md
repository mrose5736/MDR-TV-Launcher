# TV Launcher API

This app expects a small JSON API that stores launcher configuration per TV and optionally creates QR approval sessions for Admin and unlock flows.

Configure the base URL locally in `gradle.properties`:

```properties
tvLauncherBaseUrl=https://example.com/Brain
tvLauncherAdminService=example.tvlauncher.admin
tvLauncherUnlockService=example.tvlauncher.unlock
```

`gradle.properties` is intentionally ignored by Git.

## Auth QR Validation

The launcher creates an approval session, displays `approveUrl` or `qrData` as a QR code, then polls `pollUrl` until the session is approved, denied, or expired.

### Create Validation Session

```http
POST {baseUrl}/api/auth/internal/validate/session
Content-Type: application/json
```

Example body:

```json
{
  "requiredService": "example.tvlauncher.admin",
  "requester": "TV Management Panel - example-tv-id",
  "validationSeconds": 1800
}
```

Example response:

```json
{
  "sessionId": "session-id",
  "status": "pending",
  "requiredService": "example.tvlauncher.admin",
  "approveUrl": "https://auth.example.com/approve/session-id",
  "qrData": "https://auth.example.com/approve/session-id",
  "pollUrl": "/api/auth/internal/validate/session/session-id/poll-token",
  "expiresAt": 1783636370,
  "validationSeconds": 1800
}
```

### Poll Validation Session

```http
GET {baseUrl}{pollUrl}
```

Pending response:

```json
{
  "sessionId": "session-id",
  "status": "pending",
  "validated": false,
  "requiredService": "example.tvlauncher.admin",
  "expiresAt": 1783636370,
  "validUntil": null
}
```

Approved response:

```json
{
  "sessionId": "session-id",
  "status": "approved",
  "validated": true,
  "requiredService": "example.tvlauncher.admin",
  "expiresAt": 1783636370,
  "validUntil": 1783638170
}
```

Denied or expired response:

```json
{
  "sessionId": "session-id",
  "status": "denied",
  "validated": false,
  "requiredService": "example.tvlauncher.admin",
  "expiresAt": 1783636370,
  "validUntil": null
}
```

Suggested polling behavior:

- Poll every 1 to 2 seconds.
- Stop when `validated` is `true`.
- Stop when `status` is `denied` or `expired`.
- Treat request errors as terminal for that approval attempt.

## Launcher Config

The launcher uses this TV id format:

```text
{manufacturer}-{model}-{android_id}
```

The route should support letters, numbers, dots, dashes, commas, colons, and underscores.

### Create Config If Missing

```http
POST {baseUrl}/api/tvlauncher/{tvId}/create
Content-Type: application/json
```

The launcher sends a default object. The server should create it only if it does not already exist.

Created response:

```json
{
  "ok": true,
  "created": true,
  "tvId": "example-tv-id",
  "data": {}
}
```

Already exists response:

```json
{
  "ok": false,
  "created": false,
  "tvId": "example-tv-id",
  "data": {}
}
```

### Read Config

```http
GET {baseUrl}/api/tvlauncher/{tvId}
```

The response body can be either the config object directly or an envelope with `data`.

Example config:

```json
{
  "name": "Kitchen TV",
  "subtitle": "Use the remote to choose an input",
  "room": "Kitchen",
  "uiVersion": 2,
  "webAppUrl": "/tvlauncher/v2/",
  "webAppRevision": "2026-10-18.1",
  "launcherVersion": "0.2.2",
  "inputs": {
    "input_safeBase64InputId": {
      "inputId": "android.tv.input/.Service/HW1",
      "label": "HDMI 1",
      "hidden": false,
      "order": 10,
      "icon": "hdmi",
      "iconImageUrl": "https://example.com/icons/hdmi.png"
    }
  },
  "projectorMenu": [
    {
      "id": "projector-settings",
      "label": "Projector",
      "icon": "tv",
      "order": 90,
      "action": "projectorSettings"
    }
  ],
  "quickSelect": [
    {
      "id": "sky-sports",
      "label": "Sky Sports",
      "icon": "tv",
      "order": 10,
      "inputId": "android.tv.input/.Service/HW1",
      "url": "https://example.com/api/skyq/channel/401",
      "method": "POST",
      "body": {
        "room": "lounge"
      },
      "requestBeforeInput": true
    }
  ],
  "deviceCommands": [
    {
      "id": "projector",
      "label": "Projector",
      "icon": "tv",
      "order": 10,
      "action": "projectorSettings"
    },
    {
      "id": "sleep",
      "label": "Sleep",
      "icon": "lock",
      "order": 20,
      "url": "/api/tvlauncher/example-tv-id/local/action/sleep",
      "method": "GET"
    }
  ],
  "update": {
    "versionCode": 6,
    "versionName": "0.3.0",
    "apkUrl": "https://example.com/projector-launcher-0.3.0.apk",
    "sha256": "optional-release-sha256",
    "required": false,
    "channel": "stable"
  },
  "remoteControl": {
    "enabled": true,
    "onlyWhenInputActive": true,
    "longPressMs": 650,
    "keys": {
      "KEYCODE_DPAD_LEFT": {
        "url": "https://example.com/api/skyq/remote/left",
        "method": "POST",
        "consume": true,
        "longPress": {
          "url": "https://example.com/api/skyq/remote/rewind",
          "method": "POST"
        }
      }
    }
  },
  "notificationServer": {
    "enabled": true,
    "duration": 8,
    "position": 1
  },
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
    "activeInputSeconds": 21600,
    "burnInShiftDp": 28,
    "burnInPeriodSeconds": 240,
    "backgroundImageUrl": "https://example.com/wallpaper.jpg",
    "updateApkUrl": "https://example.com/projector-launcher.apk"
  },
  "deviceStatus": {
    "tvId": "example-tv-id",
    "ipAddress": "192.168.1.50",
    "notificationUrl": "http://192.168.1.50:7980/notify",
    "notificationStatusUrl": "http://192.168.1.50:7980/status",
    "notificationServerEnabled": true,
    "launcherVersion": "0.2.2",
    "launcherVersionCode": 5,
    "uiVersion": 2,
    "manufacturer": "Example",
    "model": "Android TV",
    "lastSeen": 1783636370
  }
}
```

`uiVersion` is deliberately opt-in. Missing values and `1` use the original home screen; `2` enables V2. When `webAppUrl` is present, the native shell loads the hosted interface and falls back to the native V2 renderer if its main page fails. V2 reads `deviceCommands` from the top level or `settings`, falling back to `projectorMenu` when no dedicated commands are supplied.

The native shell appends `tvId`, `nativeVersion`, and `nativeBridge=1` query parameters to `webAppUrl`. It injects each successfully polled configuration through `window.tvLauncherApplyConfig(json)`, allowing the static web app to be hosted on a separate HTTPS origin without enabling cross-origin API access. The page invokes native capabilities only through these URLs:

- `tvlauncher://input?id={androidInputId}`
- `tvlauncher://quick?id={quickSelectId}`
- `tvlauncher://command?id={deviceCommandId}`
- `tvlauncher://admin`
- `tvlauncher://refresh`

The shell rejects top-level navigation outside the configured `webAppUrl` origin and does not expose an unrestricted JavaScript interface.

Increment `webAppRevision` whenever hosted assets change. The page compares it on each configuration poll and reloads itself when the value changes.

Each device command supports `id`, `label`, `icon`, `iconImageUrl`, `order`, and `hidden`, plus one execution mechanism:

- `action`: `projectorSettings`, `inputSettings`, `tvSettings`, `refreshConfig`, `update`, `lock`, or `controlPanel`
- `intentAction`: an Android intent action exposed by the TV firmware
- `url` / `requestUrl`, `method` / `requestMethod`, and optional `body` / `requestBody`

The `update` object is the fleet-friendly replacement for a bare `settings.updateApkUrl`. `versionCode` is used for comparisons, `versionName` is presented to administrators, and `required` flags a mandatory release without bypassing Android's installer confirmation. The legacy URL remains supported.

The launcher also exposes a local TV API on `deviceStatus.notificationStatusUrl`'s host/port:

```http
GET http://{tvIp}:7980/status
GET http://{tvIp}:7980/config
GET http://{tvIp}:7980/options
GET http://{tvIp}:7980/inputs
POST http://{tvIp}:7980/notify
POST http://{tvIp}:7980/input
POST http://{tvIp}:7980/key
POST http://{tvIp}:7980/action
GET http://{tvIp}:7980/sleep
```

`POST /notify` supports static images through `media.image.uri` and live H.264/AAC fragmented-MP4 WebSocket streams through `media.video.uri`. Video options are `width`, `height`, `aspectRatio`, `muted`, and `resizeMode` (`zoom` or `fit`).

`/config` and `/options` return the TV status, cached server config, detected input IDs/labels, remote-control mapping, notification settings, supported local endpoints, and common keycode options. `/inputs` returns only the input list.

Input selection accepts:

```json
{
  "inputId": "android.tv.input/.Service/HW1",
  "label": "Sky Q"
}
```

Key sending accepts Android key names or numeric codes:

```json
{
  "keyCode": "KEYCODE_DPAD_CENTER"
}
```

Some Android TVs block `/key` from controlling another focused app because non-system apps do not have `INJECT_EVENTS`. For TV-wide actions use the Accessibility-backed action endpoint:

```json
{
  "action": "sleep"
}
```

Supported action names include `back`, `home`, `recents`, `notifications`, `quick-settings`, `power-dialog`, `sleep`, `lock-screen`, and `screenshot` where Android supports them.

Simple GET forms are also supported:

```http
GET http://{tvIp}:7980/input/{urlEncodedInputId}
GET http://{tvIp}:7980/key/KEYCODE_BACK
GET http://{tvIp}:7980/key?keyCode=4
GET http://{tvIp}:7980/action/sleep
GET http://{tvIp}:7980/sleep
```

### Patch Config Keys

```http
POST {baseUrl}/api/tvlauncher/{tvId}/keys
Content-Type: application/json
```

The launcher uses this endpoint when Admin changes input visibility, input labels, or lock state.

Example body:

```json
{
  "inputs": {
    "input_safeBase64InputId": {
      "inputId": "android.tv.input/.Service/HW1",
      "label": "HDMI 1",
      "hidden": true,
      "order": 10
    }
  }
}
```

Expected response:

```json
{
  "ok": true,
  "data": {}
}
```

Input config keys should avoid characters that can be problematic for document databases. The app stores input config under:

```text
input_{URL-safe Base64 of Android input id}
```

The original Android input id is preserved in the nested `inputId` field.
