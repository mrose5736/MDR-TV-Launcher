# TV Launcher Backend Example

This folder contains a small public-safe example backend for the TV Launcher app.

It mirrors the behaviour expected by the launcher without including any private HomeServer code, hostnames, API keys, or internal auth details.

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
  "room": "Kitchen",
  "launcherVersion": "1.0.0",
  "apps": [
    {
      "id": "live-tv",
      "label": "Live TV",
      "package": "com.example.livetv"
    },
    {
      "id": "cameras",
      "label": "Cameras",
      "url": "https://example.local/cameras"
    }
  ],
  "settings": {
    "screensaverSeconds": 300,
    "defaultApp": "live-tv"
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
  "launcherVersion": "1.0.0",
  "apps": [],
  "settings": {
    "screensaverSeconds": 300,
    "defaultApp": "live-tv"
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
    "launcherVersion": "1.0.0",
    "apps": [],
    "settings": {
      "screensaverSeconds": 300,
      "defaultApp": "live-tv"
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
  "launcherVersion": "1.0.2",
  "lastSeen": 1783636370,
  "settings": {
    "screensaverSeconds": 120,
    "defaultApp": "cameras"
  }
}
```

This updates only the top-level keys included in the request body:

- `launcherVersion`
- `lastSeen`
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
  "launcherVersion": "1.0.2",
  "apps": [],
  "settings": {
    "screensaverSeconds": 120,
    "defaultApp": "cameras"
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
