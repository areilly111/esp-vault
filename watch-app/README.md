# Esp Vault — Wear OS app

A Wear OS companion for the Esp Vault ESP32-C3 device. Connects directly to the vault over Bluetooth LE — no phone needed.

## What it does

- **Codes** — live TOTP codes computed on the watch, with a countdown showing seconds left in the current window. Keeps working even when the vault is out of range, since the secrets are synced to the watch.
- **Passwords** — browse your password list, tap any entry to see the username and password.
- **Direct BLE** — scans for the vault, connects, authenticates with your vault PIN, and syncs everything itself.

## Flow

1. Open the app — it scans for your vault automatically
2. Enter your vault PIN (voice or keyboard) when prompted
3. Codes and passwords sync over, then you're good

The vault only handles one BLE connection at a time, so if your phone app is connected, disconnect it first.

## Building

You need JDK 17 and the Android SDK.

```bash
cd watch-app
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
gradle assembleDebug --no-daemon
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Sideload it with `adb install` over Wi-Fi debugging on the watch.

## Notes

- Package name is `com.espvault.watch`.
- The BLE protocol is shared with the Android app — see [../BLE_PROTOCOL.md](../BLE_PROTOCOL.md).
- Secrets are held in memory while the app runs. Same plaintext caveats as the main project — read the [security disclaimer](../README.md#disclaimer).
