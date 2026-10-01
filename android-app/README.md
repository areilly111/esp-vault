# Esp Vault — Android app

The companion app for the Esp Vault ESP32-C3 device. Connects over Bluetooth LE to manage your TOTP codes and passwords.

## What it does

- **Codes tab** — live TOTP codes with countdown ring, tap to copy
- **Passwords tab** — browse, search, show/hide/copy, add/edit/delete
- **Backup tab** — import from a Bitwarden JSON export (parsed on your phone, never uploaded), export an unencrypted backup
- **Settings tab** — device settings: portal password change, WiFi config, timezone, BLE auto-start toggle, time sync, lock

Extras: QR code scanner for `otpauth://` URIs, password generator, search/filter on both lists.

## Building

You need Android Studio or the command line with JDK 17 and the Android SDK.

```bash
cd android-app
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
gradle assembleDebug --no-daemon
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Notes

- The app talks to the device using the protocol in [../BLE_PROTOCOL.md](../BLE_PROTOCOL.md).
- Package name is `com.keychainvault.app` (historical — the app itself is branded "Esp Vault").
- Bitwarden import/export files are plain JSON. Keep your backups somewhere safe.
