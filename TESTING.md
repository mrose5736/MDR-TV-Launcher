# Regression checks

From the repository root:

```sh
npm --prefix backend ci
npm --prefix backend test
sh backend/tests/remote-key-sequences.sh
gradle assembleDebug
```

Use Gradle 8.10.2 and a compatible JDK (17 or 21) with Android SDK 35 configured as described in README.md. The Java key-routing check needs only a JDK. The web tests run without a TV, database or network services.

The DOM suite covers first-source focus, quick-select command IDs, navigation between rows, stable focus through 120 native configuration injections, live-state changes, and removal of a focused tile. The Java suite covers keeping key-down/repeat/key-up on the same route across launcher-to-HDMI transitions, orphan events, independent input devices and service resets.

After installing on a test TV, check source launching, return-to-Home focus, clock/Admin cancellation, quick selects and room commands. Check enabled **and bound** accessibility services, preserving other remote remappers. Test real remote presses while on HDMI, including the OK press used to enter HDMI; navigating the launcher alone does not verify interception.

Room adapters, credentials, device IDs, deployment backups and per-installation PHP tests stay local and are ignored by Git. Test those separately against the relevant installation. Do not publish locally configured APKs as generic releases.
