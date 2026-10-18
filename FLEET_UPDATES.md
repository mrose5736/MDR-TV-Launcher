# Fleet Updates

The launcher currently opens a configured APK URL from Admin. That works for a few TVs, but it does not answer the fleet questions: which device should receive which release, whether it installed successfully, and how to pause a bad rollout.

## Recommended model

Keep release data separate from each TV's ordinary launcher configuration:

```json
{
  "channel": "stable",
  "versionCode": 6,
  "versionName": "0.3.0",
  "apkUrl": "https://example.local/releases/projector-launcher-0.3.0.apk",
  "sha256": "release-sha256",
  "required": false,
  "rolloutPercent": 25,
  "minimumVersionCode": 5,
  "releaseNotes": "V2 launcher and device controls"
}
```

Assign each TV to a channel such as `canary`, `stable`, or `pinned`. The server resolves the channel and rollout rules into the per-TV `update` object already consumed by the launcher. A deterministic hash of `tvId + versionCode` makes percentage rollouts stable: a device does not jump in and out of a cohort between polls.

## Rollout sequence

1. Publish the signed APK, immutable version metadata, file size, and SHA-256.
2. Assign one nearby TV to `canary` and install manually from the Admin update row.
3. Confirm the device reports the new `launcherVersionCode`, stays online, and can still open its inputs. Check the remote-bridge accessibility service is enabled **and bound**, then test physical remote navigation on the configured input; launcher navigation alone does not exercise this bridge. Preserve other accessibility services if restoring its setting.
4. Expand the stable rollout through 10%, 25%, 50%, and 100% cohorts.
5. Pause by setting `rolloutPercent` to the last safe value. Roll forward with a higher version code rather than attempting an Android downgrade.

## Device reporting

Each status heartbeat should include:

- `launcherVersion` and `launcherVersionCode`
- `uiVersion`
- `updateState`: `idle`, `available`, `downloading`, `ready`, `installing`, `failed`, or `current`
- `updateError`, when applicable
- `lastSeen`, model, and manufacturer

The first three fields are already available in the launcher. The remaining update-state fields belong in the download/install phase.

## Installation phases

### Phase 1: assigned link

Use the current Admin row to open the resolved APK URL. This keeps installation user-approved and requires no additional Android privileges.

### Phase 2: verified one-click update

Have the launcher download the APK with `DownloadManager`, verify its SHA-256 and signing certificate, then open Android's package installer through a `FileProvider`. The user still confirms installation, but there is no browser or manual file navigation.

### Phase 3: unattended managed updates

Only use silent installation on devices enrolled as Android Device Owner or managed through an MDM/OEM management API. A normal launcher app cannot safely or reliably install APKs silently across arbitrary TV firmware.

## Backend operations

The management surface should support:

- filtering by room, model, channel, current version, online state, and UI version;
- bulk assignment of a release or UI version with a preview of affected TVs;
- a per-device override and a pinned channel;
- live counts for current, pending, offline, and failed devices;
- an audit record containing actor, release, target rule, and time;
- no mutation of input labels, wallpaper, or other launcher configuration during release assignment.

Treat release artifacts as immutable. Never replace the APK behind an existing version URL; publish a new version code and URL instead.
