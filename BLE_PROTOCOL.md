# Esp Vault BLE Protocol v1 (firmware v1.4.9)

Companion-device protocol for the ESP32-C3 Esp Vault. The same protocol
is spoken by the Linux Python GUI and (later) the Android app.

> **v1.4.9 note:** BLE password reads (labels, count, single entry) now serve
> from the in-RAM `passwordLines` vector (loaded at boot, updated on every
> add/edit/delete) instead of re-reading LittleFS inside the BLE task — the
> filesystem re-read returned empty on-device while the OLED (main task) read
> fine. The Android app also reads `pw_count` on every refresh and shows
> "device reports N" in the status line as a diagnostic.

Firmware version introducing BLE: **1.4.0**. Button gestures (1.4.2+). Full entry CRUD over BLE (1.4.4+). BLE-first portal + first-boot setup (1.4.5+). Device settings over BLE: portal-password change, WiFi update, timezone get/set (1.4.6+). Backup export of TOTP secrets + `/files` auto-create fix for BLE password writes (1.4.7+). Robust list JSON (pre-reserved buffers), idempotent `pw_add`, visible save errors (1.4.8+).

- Main menu (unlocked): tap = next entry, 3 s hold = TOTP/password view
  toggle, triple-tap (locked) = unlock. Bluetooth is NOT touched here.
- Portal mode is **BLE-first**: the radio is always on in portal (primary
  communication path). 3 s hold switches portal transport BLE ↔ WiFi AP web
  portal (OLED shows BLE MODE / WIFI MODE); hold to 6 s leaves portal.
  Triple-tap in portal unlocks the device (so the app gets full access).
  Initializing BLE while the WiFi AP is up rebooted the device (1.4.3), so
  the two transports never run at the same time.
- **First boot**: the device enters the BLE portal automatically
  (`SETUP` on the OLED) so the companion app can do initial setup:
  set the portal password, set WiFi credentials, finish setup → the device
  reboots into normal mode. A device that already has a personalized portal
  password is treated as set up.

## Transport

- BLE GATT server on the ESP32-C3 ("EspVault" advertised name, service
  UUID advertised).
- Radio is **off by default** outside portal mode. It can be enabled two
  ways: Portal Settings → Bluetooth (web), or the app writing the
  `ble_auto` characteristic. When enabled, the radio turns on automatically
  when the device is unlocked (triple-tap), and turns off when the device
  locks or after 5 minutes without BLE activity. The OLED shows a small `BT`
  flag bottom-right while the radio is on. In portal mode the radio is
  always on regardless of this flag.
- One custom service, 128-bit UUIDs. All strings UTF-8. Multi-byte integers
  little-endian. Server requests MTU 517 so long labels/passwords fit.

## Security model

Hobby-grade, same as the Wi-Fi portal: the app authenticates by writing the
**portal password** to the `auth` characteristic. Until auth succeeds, every
other characteristic returns an empty value; writes get `ERR AUTH` on
`status`. **Secrets are only served while the device is unlocked** —
locking drops the session and stops the radio. An authenticated session
lasts until disconnect, device lock, or 5 minutes idle (any authed
operation resets the timer). BLE link-layer pairing is not required —
traffic is readable within radio range, exactly like the open portal AP was
before login. Do not treat this as strong security.

## Service

`81f3d5eb-25b8-4077-aff6-c578a9613ab6` — Esp Vault service. The
firmware implementation is authoritative if anything here drifts.

| Characteristic | UUID | Props | Description |
|---|---|---|---|
| `auth` | 7c896164-1090-43d0-ab1d-1a7251069755 | write | Write portal password (UTF-8). Result arrives on `status`. |
| `status` | b33250c0-7157-4f43-8b80-ad6138026c5a | notify | `OK …` / `ERR …` responses and events (`LOCKED`, `TIMEOUT`). |
| `device_info` | 06d9cadd-eb38-4ce7-b8c7-1c111ca67b3b | read | `KV1;<fw-version>;<locked 0/1>;<setup 0/1>` — no auth needed. `setup=1` (1.4.5+) means first-boot setup is still required: show the setup wizard instead of the normal UI. |
| `vault_count` | 2f096600-5c39-477d-90c2-7f462c8124db | read | u16 LE: number of TOTP entries. |
| `vault_labels` | 86192014-db09-4c0d-9541-2e408811b091 | read | JSON array of labels, e.g. `["GITHUB","BANK"]`. |
| `totp_request` | fca93d15-df17-4b9d-9c87-8d31a7e69ff8 | write | u16 LE index → device notifies `totp_code`. |
| `totp_code` | 563456d9-f8f9-4c9d-9cdc-9db51cec980a | notify | `CODE;<label>;<6 digits>;<seconds-left>` |
| `pw_count` | 68f67e11-cdac-4d61-b5bd-58ae373e1f2c | read | u16 LE: number of password entries. |
| `pw_labels` | 7e1d7521-587e-42e1-ae79-6f4a12d1465a | read | JSON array of password labels. |
| `pw_request` | db65d936-d0ab-4663-a944-1aed62839e0c | write | u16 LE index → device notifies `pw_entry`. |
| `pw_entry` | fa782901-221c-4cec-9839-52d60fbcb1ff | notify | `ENTRY;<label>;<username>;<password>` |
| `time_sync` | eca41348-e62b-400a-9098-db4de06d644e | write | u32 LE unix epoch → sets device clock, notifies `OK TIME`. |
| `add_totp` | 81841413-8a90-40a6-8521-c8c444fce206 | write | JSON `{"label":"X","secret":"…"}` → validated like the portal (`OK ADDED` / `ERR …`). |
| `lock` | 47ce190e-1fbf-4c54-8c18-a775455ae0de | write | Any byte → locks the device immediately, notifies `LOCKED`. |
| `totp_delete` | d29e964e-1377-4b9e-843a-cf3453ed4a54 | write | **(1.4.4+)** JSON `{"index":N}` → deletes the TOTP entry (`OK DELETED` / `ERR …`). |
| `totp_edit` | 22622a17-8714-4361-a7e1-35aae56f337a | write | **(1.4.4+)** JSON `{"index":N,"label":"X","secret":"…"}` → validated like add (`OK UPDATED` / `ERR …`). |
| `pw_add` | 3eb833b0-9943-4c35-9eda-44d456be3745 | write | **(1.4.4+)** JSON `{"label":"X","username":"U","password":"P"}` → appends to `/files/Passwords.txt` (`OK ADDED` / `ERR …`). `:` and newlines are sanitized out of label/username. |
| `pw_edit` | ac74481a-fe9c-40f5-b800-174f5b23f4e5 | write | **(1.4.4+)** JSON `{"index":N,"label":"X","username":"U","password":"P"}` → replaces the entry (`OK UPDATED` / `ERR …`). |
| `pw_delete` | bc172588-0a48-46fb-8105-be7490cb2526 | write | **(1.4.4+)** JSON `{"index":N}` → deletes the password entry (`OK DELETED` / `ERR …`). |
| `setup_set_password` | 4c73d3a3-e7b3-4db1-9cc1-6b7c1371c595 | write | **(1.4.5+) FIRST BOOT ONLY, no auth.** JSON `{"password":"…"}` (min 8 chars) → sets the portal password (`OK PASSWORD` / `ERR …`). Answers `ERR SETUP` after setup is done. |
| `setup_set_wifi` | 293daea1-8da9-425e-baa8-c62557fffad7 | write | **(1.4.5+) FIRST BOOT ONLY, no auth.** JSON `{"ssid":"…","password":"…"}` → saves WiFi network 1 (`OK WIFI` / `ERR …`). Answers `ERR SETUP` after setup is done. |
| `setup_complete` | f241bf45-78af-40af-9741-062ba41ee9b0 | write | **(1.4.5+) FIRST BOOT ONLY, no auth.** Any value → marks setup done, enables BLE auto-start (so the app can reconnect after the reboot), and **reboots** the device (`OK DONE`, then reboot). Requires the password to have been set first (`ERR PASSWORD` otherwise). |
| `ble_auto` | e2e0fbd6-009f-4e45-97d6-c045bf512e66 | write + read | **(1.4.5+)** BLE auto-start flag (normal auth: unlocked + `auth`). Write JSON `{"auto":1}` / `{"auto":0}` → `OK AUTO ON` / `OK AUTO OFF`. Read → `{"auto":1}` or `{"auto":0}`. Same flag as Portal Settings → Bluetooth. |
| `pw_change` | 4a0cb1b6-5c35-4ad6-a253-81edbf811000 | write | **(1.4.6+)** Change the portal password (normal auth: unlocked + `auth`). JSON `{"old":"…","new":"…"}` (new ≥ 8 chars) → `OK CHANGED` / `ERR OLD` / `ERR SHORT` / `ERR JSON`. |
| `wifi_set` | a9cdd97a-f770-4d5a-9d82-1a224859ce91 | write | **(1.4.6+)** Update WiFi credentials (normal auth: unlocked + `auth`). JSON `{"ssid":"…","password":"…"}` → saves WiFi network 1 (`OK WIFI` / `ERR SSID` / `ERR JSON`). Same storage as `setup_set_wifi`. |
| `tz` | 4f0c630a-50a2-4348-ad1b-425a39900942 | write + read | **(1.4.6+)** Timezone (normal auth: unlocked + `auth`). Write JSON `{"name":"America/Winnipeg","tz":"CST6CDT,M3.2.0,M11.1.0"}` (POSIX TZ) → applied immediately (`OK TZ` / `ERR TZ` / `ERR JSON`). Read → `{"name":"…","tz":"…"}`. |
| `vault_export` | 8113d85b-9feb-470e-a36e-5ccbab8dac90 | read | **(1.4.7+)** Full TOTP vault export for backups (normal auth: unlocked + `auth`). Read → `[{"label":"…","secret":"…"}, …]`. Lets the app build a Bitwarden-compatible backup file (the per-code path only returns current codes, never secrets). |

### First-boot setup flow (1.4.5+, app side)

1. Connect, read `device_info`. If `setup=1`, present the setup wizard
   (skip the normal unlock/auth UI — the device has no password yet).
2. Write the new portal password (≥ 8 chars) to `setup_set_password` →
   wait for `OK PASSWORD` on `status`.
3. Optionally write WiFi credentials to `setup_set_wifi` → `OK WIFI`.
4. Optionally import the Bitwarden export now (the normal `add_totp` /
   `pw_add` characteristics **require unlock + auth, so they do NOT work
   during first boot** — import happens after step 5).
5. Write anything to `setup_complete` → `OK DONE`, then the device reboots.
   Reconnect, triple-tap the device to unlock, and authenticate with the new
   password — then import and normal management work.

Note: characteristic UUIDs above are final in firmware 1.4.0.

## Typical session (Linux GUI)

1. Triple-tap the device to unlock (BLE auto-starts if enabled in portal).
2. Scan → connect to `EspVault`.
3. Read `device_info` → show firmware / lock state (`KV1;<fw>;<locked 0/1>`).
4. Subscribe to `status`, `totp_code`, `pw_entry`.
5. Write portal password to `auth` → wait for `OK AUTH` on `status`
   (`ERR AUTH` after a short delay on a wrong password).
6. Read `vault_count` (u16 LE) / `vault_labels` (JSON array) → request live
   codes with `totp_request` (u16 LE index); codes arrive as
   `CODE;<label>;<6 digits>;<seconds-left>` on `totp_code`.
7. Passwords: `pw_count` / `pw_labels`, then `pw_request` → entry arrives as
   `ENTRY;<label>;<username>;<password>` on `pw_entry`.
8. `time_sync` (u32 LE unix epoch) once per session so codes stay accurate.
9. `lock` any write → device locks immediately, `LOCKED` is notified, the
   session drops and the radio stops.

Reads/writes before auth get an empty value / `ERR AUTH`; while the device
is locked they get `ERR LOCKED`. After 5 minutes without activity the
device notifies `TIMEOUT`, drops the connection and stops the radio.

## Out of scope for v1

Link-layer pairing/bonding, encrypted characteristics, multi-client
sessions, a manual on-device BLE toggle gesture.

Note: TOTP *secrets* are never readable over BLE (by design — only
time-limited codes leave the device). A full "export" therefore contains
passwords in full but only TOTP labels; keep the Bitwarden JSON as the
secret backup.
