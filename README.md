# Esp Vault

A TOTP authenticator and password vault for the ESP32-C3 SuperMini with 0.42" OLED. Small screen, one button, talks to your phone over Bluetooth. No cloud, no accounts, no subscriptions — your secrets stay on the little device in your pocket.

## What it does

- **TOTP codes** — stores your two-factor secrets and shows the rotating 6-digit codes on the OLED, like Google Authenticator but on its own hardware
- **Passwords** — keeps a password list you can scroll through on the screen or pull up in the app
- **Bluetooth LE** — the Android companion app connects over BLE to manage everything: add/edit/delete entries, import from Bitwarden, export backups
- **WiFi portal** — optional web interface (off unless you turn it on) for time sync and file management

## Hardware

Built for the [ESP32-C3 SuperMini with 0.42" OLED](https://github.com/peff74/ESP32-C3_OLED) — the cheap little board with the 72x40 SSD1306 display baked in.

- ESP32-C3 SuperMini + 0.42" 72x40 SSD1306 OLED (SDA GPIO 6, SCL GPIO 5)
- Push button on GPIO 9
- LittleFS for storage

## Getting started

1. Flash the firmware to your ESP32-C3
2. On first boot it goes into setup mode — open the Android app and connect
3. Set your portal password and WiFi (WiFi is optional), then you're good
4. Triple-tap the button to unlock, single-tap to scroll, long-press for options

## The app

The Android app (`Esp Vault`, source in [android-app/](android-app/)) does all the managing over BLE:

- Live TOTP codes with countdown
- Browse, search, add, edit, and delete passwords
- Import from a Bitwarden JSON export (parsed on your phone, never sent anywhere)
- Export an unencrypted backup (keep it somewhere safe)
- Scan QR codes to add TOTP entries
- Generate strong passwords

## BLE protocol

[BLE_PROTOCOL.md](BLE_PROTOCOL.md) has the full characteristic list and message formats, if you ever want to write your own client.

## Building the firmware

You'll need the Arduino IDE or `arduino-cli` with the ESP32 core installed.

```bash
arduino-cli compile --fqbn esp32:esp32:esp32c3 \
  --board-options PartitionScheme=huge_app \
  --output-dir build .
```

Flash the resulting `.merged.bin` at offset `0x0`. Heads up: this wipes the filesystem, so back up first.

## License

MIT — see [LICENSE](LICENSE). Do what you want with it.

---

## Disclaimer

Look, I want to be straight with you about what this is and isn't.

Your TOTP secrets and passwords are stored **in plaintext** in the ESP32's flash. There's no encryption at rest right now. If someone physically gets your device and knows what they're doing, they can read everything off it. Encrypting stored secrets is on the roadmap, but it's not here yet.

Bluetooth doesn't use pairing or link-layer encryption either. The app authenticates with a PIN before the device will talk to it, but the radio traffic itself isn't encrypted — someone sniffing BLE nearby could see what's going across.

The backup export is **plain unencrypted JSON**. It has to be, so Bitwarden can read it. Treat that file like a printed list of all your passwords, because that's exactly what it is. Don't email it to yourself, don't leave it on a shared drive.

The default portal password is `espvault`. The app makes you change it during setup, but if you ever skip that step, change it.

This started as a personal project to get my secrets off other people's servers, and it does that well. But it's not audited, it's not certified, and it's not a replacement for a hardware security key. Understand the tradeoffs, keep the device on you, and don't store anything here you couldn't survive losing.
