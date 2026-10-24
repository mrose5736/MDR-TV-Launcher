# TV Launcher Backend Example

This folder contains a small public-safe example backend for the TV Launcher app.

It mirrors the behaviour expected by the launcher without including any private HomeServer code, hostnames, API keys, or internal auth details.

It also serves the buildless V2 web launcher from `public/v2` at:

```text
/tvlauncher/v2/
```

Enable it per TV with `uiVersion: 2` and `webAppUrl: "/tvlauncher/v2/"`. The Android shell retains input switching, Admin authentication, notifications, remote-control handling, and the native offline fallback.

## What This Backend Stores

Each TV has one JSON document stored by `tvId`.

A `tvId` should be stable for the device. Good choices are:

- a generated UUID saved on the TV
- a device id from your own system
- a hostname
- a MAC-style identifier

Example saved launcher data:

```json
{
  "name": "Kitchen TV",
  "subtitle": "Choose a source",
  "room": "Kitchen",
  "launcherVersion": "0.2.2",
  "inputs": {
    "input_example_hdmi_1": {
      "inputId": "com.example.tvinput/.TvPassThroughService/HW1",
      "label": "Sky Q",
      "hidden": false,
      "order": 1,
      "icon": "tv"
    }
  ],
  "quickSelect": [
    {
      "id": "sports",
      "label": "Sports",
      "inputId": "com.example.tvinput/.TvPassThroughService/HW1",
      "url": "/api/devices/example-receiver/channel/401/",
      "method": "GET",
      "requestBeforeInput": true
    }
  ],
  "remoteControl": {
    "enabled": true,
    "onlyWhenInputActive": true,
    "keys": {
      "KEYCODE_CHANNEL_UP": {
        "url": "/api/devices/example-receiver/channel-up/",
        "method": "GET",
        "consume": true
      }
    }
  },
  "notificationServer": {
    "enabled": true,
    "duration": 8,
    "position": 1
  },
  "settings": {
    "adminAuthRequired": true,
    "lockScreenEnabled": false,
    "configPollSeconds": 20,
    "screensaverSeconds": 300,
    "backgroundImageUrl": "https://example.local/wallpaper.jpg"
  },
  "deviceStatus": {
    "tvId": "kitchen-tv",
    "ipAddress": "192.168.1.50",
    "notificationUrl": "http://192.168.1.50:7980/notify",
    "notificationStatusUrl": "http://192.168.1.50:7980/status",
    "notificationServerEnabled": true,
    "launcherVersion": "0.2.2",
    "lastSeen": 1783636370
  }
}
```

## Endpoints

The example server exposes these routes:

```http
POST /api/tvlauncher/:tvId/create
POST /api/tvlauncher/:tvId
POST /api/tvlauncher/:tvId/data
POST /api/tvlauncher/:tvId/keys
GET  /api/tvlauncher/:tvId
GET  /api/tvlauncher/:tvId/data
GET  /api/tvlauncher/:tvId/local/options
POST /api/tvlauncher/:tvId/local/notify
POST /api/tvlauncher/:tvId/local/input
GET  /api/tvlauncher/:tvId/local/input/:inputId
POST /api/tvlauncher/:tvId/local/key
POST /api/tvlauncher/:tvId/local/action
GET  /api/tvlauncher/:tvId/local/action/:action
```

## Create Once

Use this when the TV first registers itself. It creates the object only if it does not already exist.

```http
POST /api/tvlauncher/kitchen-tv/create
Content-Type: application/json
```

Input:

```json
{
  "name": "Kitchen TV",
  "room": "Kitchen",
  "launcherVersion": "0.2.2",
  "inputs": {},
  "settings": {
    "screensaverSeconds": 300,
    "configPollSeconds": 20
  }
}
```

Created response:

```json
{
  "ok": true,
  "created": true,
  "tvId": "kitchen-tv",
  "createdAt": 1783636370,
  "data": {
    "name": "Kitchen TV",
    "room": "Kitchen",
    "launcherVersion": "0.2.2",
    "inputs": {},
    "settings": {
      "screensaverSeconds": 300,
      "configPollSeconds": 20
    }
  }
}
```

Already exists response:

```json
{
  "ok": false,
  "error": "TV launcher data already exists",
  "code": 409
}
```

## Save Or Replace All Data

Use this when an admin/backend intentionally wants to replace the whole saved launcher JSON.

```http
POST /api/tvlauncher/kitchen-tv
Content-Type: application/json
```

Equivalent path:

```http
POST /api/tvlauncher/kitchen-tv/data
Content-Type: application/json
```

Response:

```json
{
  "ok": true,
  "tvId": "kitchen-tv",
  "updatedAt": 1783636370,
  "data": {
    "name": "Kitchen TV",
    "room": "Kitchen"
  }
}
```

## Update Only Specific Keys

Use this when the TV should update only part of its saved server data.

```http
POST /api/tvlauncher/kitchen-tv/keys
Content-Type: application/json
```

Input:

```json
{
  "deviceStatus": {
    "ipAddress": "192.168.1.50",
    "notificationUrl": "http://192.168.1.50:7980/notify",
    "notificationStatusUrl": "http://192.168.1.50:7980/status",
    "lastSeen": 1783636370
  },
  "settings": {
    "screensaverSeconds": 120,
    "lockScreenEnabled": false
  }
}
```

This updates only the top-level keys included in the request body:

- `deviceStatus`
- `settings`

It preserves all other keys already stored for that TV.

Keys containing `.` or `$` are rejected before building the MongoDB update, because MongoDB treats those characters specially in update paths.

## Read Data

The TV calls this endpoint to get its saved launcher data.

```http
GET /api/tvlauncher/kitchen-tv
```

Equivalent path:

```http
GET /api/tvlauncher/kitchen-tv/data
```

Response:

```json
{
  "name": "Kitchen TV",
  "room": "Kitchen",
  "launcherVersion": "0.2.2",
  "inputs": {},
  "settings": {
    "screensaverSeconds": 120,
    "lockScreenEnabled": false
  }
}
```

Not found response:

```json
{
  "ok": false,
  "error": "TV launcher data not found",
  "code": 404
}
```

## Local TV API Proxy Examples

The launcher reports its local API URLs in the top-level `deviceStatus` object. This example backend includes convenience routes that read those URLs from MongoDB and call the TV on port `7980`.

These are optional, but they are useful when you want your backend to control a TV without hard-coding the TV IP in every integration.

Read the TV-local options/config snapshot:

```http
GET /api/tvlauncher/kitchen-tv/local/options
```

Show a PiPup-style notification:

```http
POST /api/tvlauncher/kitchen-tv/local/notify
Content-Type: application/json
```

```json
{
  "duration": 45,
  "title": "Front door",
  "message": "Someone is at the door",
  "backgroundColor": "#ffffff",
  "titleColor": "#0066cc",
  "messageColor": "#000000",
  "messageSize": 14,
  "media": {
    "image": {
      "uri": "https://example.local/snapshot.jpg",
      "width": 800
    }
  }
}
```

Open an input by Android input ID:

```http
POST /api/tvlauncher/kitchen-tv/local/input
Content-Type: application/json
```

```json
{
  "inputId": "com.example.tvinput/.TvPassThroughService/HW1",
  "label": "Sky Q"
}
```

Equivalent GET shortcut using a URL-encoded input ID:

```http
GET /api/tvlauncher/kitchen-tv/local/input/com.example.tvinput%2F.TvPassThroughService%2FHW1
```

Run an Accessibility-backed TV action:

```http
POST /api/tvlauncher/kitchen-tv/local/action
Content-Type: application/json
```

```json
{
  "action": "sleep"
}
```

Equivalent shortcut:

```http
GET /api/tvlauncher/kitchen-tv/local/action/sleep
```

Send a keycode:

```http
POST /api/tvlauncher/kitchen-tv/local/key
Content-Type: application/json
```

```json
{
  "keyCode": "KEYCODE_HOME"
}
```

Android TV commonly blocks true key injection from normal apps with `INJECT_EVENTS` errors. The launcher falls back for known system keys such as Home, Back, Sleep, Power, and TV Input, but D-pad/channel/number injection may still be blocked by the TV firmware.

## LG webOS TVs (kiosk PC)

LG webOS sets can't run the Android launcher. Put a small PC on one HDMI port, open V2 in a kiosk browser, and let this backend switch the TV's inputs over the LAN (SSAP). Requires Node 22+.

1. On the TV: fixed IP, and on 2014 sets enable **Settings → Network → LG Connect Apps**.
2. Pair once (accept the prompt on the TV). Credentials are saved to `lgtv.json`, which is git-ignored and never served to the browser:

```bash
node lg-pair.mjs lounge-tv 10.95.0.96
```

   2013–2014 "Netcast" sets (e.g. 42LB570V, no port 3000, menus without webOS cards) pair with the key the TV displays, and are driven by remote-key presses:

```bash
node lg-pair.mjs bedroom-tv 10.95.0.72 netcast
```

   Netcast input ids are `ANTENNA`, `HDMI1`, `AV1`, `AV2`, `COMPONENT`, `HDMI2`, `HDMI3` (the `HDMI_2` spelling also works). The driver moves along the on-screen input list from the TV's current input, so if your set's list order differs, add `"inputOrder": [...]` to its entry in `lgtv.json`. Netcast power is a toggle, so `/lg/off` only works while the TV is on.

3. Config for that `tvId`: use the LG input ids (`GET /api/tvlauncher/lounge-tv/lg/inputs` lists them) as `inputId`:

```json
{
  "name": "Lounge",
  "uiVersion": 2,
  "inputs": {
    "ps5": { "inputId": "HDMI_3", "label": "PS5", "order": 10 },
    "launcher-pc": { "inputId": "HDMI_2", "label": "Launcher PC", "hidden": true }
  },
  "deviceCommands": [
    { "id": "off", "label": "Turn off", "order": 10, "url": "/api/tvlauncher/lounge-tv/lg/off", "method": "POST" }
  ]
}
```

4. Kiosk browser on the PC (Linux shown; on Windows use `chrome.exe --kiosk` with the same URL):

```bash
chromium --kiosk --noerrdialogs --disable-infobars "http://BACKEND:3000/tvlauncher/v2/?tvId=lounge-tv"
```

Without `nativeBridge=1` the page sends tile presses to `/api/tvlauncher/:tvId/lg/input`. There is no authentication on these routes; keep them on the LAN.

## Notes For Public Deployments

This example intentionally has no authentication. Add your own auth, rate limiting, and network restrictions before exposing it beyond a trusted LAN.

The backend only rejects top-level patch keys containing `.` or `$`; this is enough for the launcher's `/keys` usage because the server updates whole top-level fields such as `inputs`, `settings`, `deviceStatus`, `remoteControl`, and `quickSelect`.

## Running The Example

Install dependencies:

```bash
npm install
```

Start MongoDB locally, then run:

```bash
MONGO_URL="mongodb://127.0.0.1:27017" npm start
```

Optional environment variables:

```text
PORT=3000
MONGO_URL=mongodb://127.0.0.1:27017
MONGO_DB=tv_launcher_example
```

## Files

- `server.example.js` - complete Express + MongoDB example implementation.
- `package.json` - minimal dependencies and start script.

## Docker deployment

`docker-compose.yml` runs the backend and MongoDB. Put `ADMIN_PASSWORD=...` in a `.env` beside it; the admin dashboard is at `/admin/` (HTTP Basic auth, any username). Pairing keys live in `./data/lgtv.json`.

```bash
docker compose up -d --build
```

On GitHub, `.github/workflows/backend.yml` publishes `ghcr.io/mrose5736/mdr-tv-launcher-backend:latest` on every push to `main` that touches `backend/`. The server polls for it with a cron entry (no inbound access to the LAN):

```cron
*/5 * * * * cd ~/tv-launcher && docker compose pull -q backend && docker compose up -d backend
```
