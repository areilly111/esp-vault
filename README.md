# Esp Vault

A TOTP authenticator and password vault that runs on an ESP32-C3. It has a small OLED screen, a single button, and talks to your phone over Bluetooth. No cloud, no accounts, no subscriptions — your secrets stay on the device in your pocket.

## What it does

- **TOTP codes** — stores your two-factor secrets and shows rotating 6-digit codes on the OLED, just like Google Authenticator but on dedicated hardware
- **Passwords** — keeps a password list you can browse on the screen or pull up in the app
- **Bluetooth LE** — the Android companion app connects over BLE to manage everything: add/edit/delete entries, import from Bitwarden, export backups
- **WiFi portal** — optional web interface (off by default) for time sync and file management

## Hardware

- ESP32-C3 microcontroller
- 72x40 SSD1306 OLED display (I2C)
- Push button on GPIO 9
- LittleFS for storage

## Getting started

1. Flash the firmware to your ESP32-C3
2. On first boot, the device starts in setup mode — open the Android app to connect
3. Set your portal password and WiFi (optional), then you're good to go
4. Triple-tap the button to unlock, single-tap to scroll, long-press for options

## The app

The Android app (`Esp Vault`) handles all management over BLE:

- View live TOTP codes with countdown
- Browse, search, add, edit, and delete passwords
- Import from a Bitwarden JSON export (parsed on your phone, never uploaded)
- Export an unencrypted backup (store it somewhere safe)
- Scan QR codes to add TOTP entries
- Generate strong passwords

## BLE protocol

See [BLE_PROTOCOL.md](BLE_PROTOCOL.md) for the full characteristic list and message formats, if you want to write your own client.

## Building the firmware

You'll need the Arduino IDE or `arduino-cli` with the ESP32 core installed.

```bash
arduino-cli compile --fqbn esp32:esp32:esp32c3 \
  --board-options PartitionScheme=huge_app \
  --output-dir build .
```

Flash the resulting `.merged.bin` at offset `0x0`. Note: this wipes the filesystem — back up first.

## Security notes

Be honest with yourself about what this is:

- Secrets are stored **in plaintext** in the ESP32's flash. If someone gets the chip, they get your secrets. Encryption at rest is planned but not yet implemented.
- BLE has no pairing or link-layer encryption yet. The app authenticates with a PIN, but the radio traffic isn't encrypted.
- The export file is **unencrypted JSON**. Treat it like a password list — because it is one.
- The default portal password is `espvault` — change it on first setup (the app forces this).

This is a personal project, not a audited security product. It keeps your secrets off the cloud, which is a real improvement, but understand the tradeoffs.

## License

MIT — see [LICENSE](LICENSE) for details. Do what you want with it, just don't blame me if something breaks.
