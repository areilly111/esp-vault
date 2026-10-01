#include <Arduino.h>
#include <WiFi.h>
#include <WiFiMulti.h>
#include <WebServer.h>
#include <LittleFS.h>    
#include <ArduinoJson.h>  
#include "time.h"
#include <ctime>
#include <U8g2lib.h>
#include <Wire.h> 
#include <TOTP.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "index_html.h" 
#include <esp_system.h>  // esp_random() for the portal auth token

// Firmware version, reported over BLE device_info and the portal.
#define FW_VERSION "1.5.0"

// Forward declarations
void displayTOTPLine(time_t now, struct tm timeinfo);
void displayPasswordLine(time_t now, struct tm timeinfo);
bool restoreLastKnownTime();
void saveLastKnownTime(time_t t);

WiFiMulti wifiMulti;
WebServer server(80); 

// Portal WiFi settings (configurable)
String portalSSID = "Esp-Vault";
String portalPassword = "";  // set from LittleFS; default "espvault" on first boot
bool portalPasswordMustChange = false;  // true until the user replaces the default password
const char* PORTAL_SETTINGS_FILE = "/portal_settings.json"; 

// Store WiFi and NTP info for display
String ntpServerName = "pool.ntp.org, time.google.com";
String lastWifiSSID = "";
String lastWifiBSSID = "";

// Device name for boot screen
String deviceName = "Esp Vault";
const char* DEVICE_NAME_FILE = "/device_name.json";

// WiFi settings for user configuration.
// NOTE: defaults are intentionally blank so WiFi credentials never live in
// source code. Configure once via the portal web UI (stored in /wifi_settings.json).
String wifiSSID1 = "";
String wifiPassword1 = "";
String wifiSSID2 = "";
String wifiPassword2 = "";
const char* WIFI_SETTINGS_FILE = "/wifi_settings.json";

struct DynamicAccount {
    String label;
    String secret;
};

std::vector<DynamicAccount> myVault;

// PIN/Lock state
bool deviceLocked = true;
int unlockTapCount = 0;
unsigned long lastTapTime = 0;
unsigned long lastActivityTime = 0;
const int UNLOCK_TAP_REQUIRED = 3;
const unsigned long TAP_TIMEOUT_MS = 2000;  // 2 seconds between taps
const unsigned long AUTO_LOCK_MS = 300000;  // 5 minutes = 300000ms

// Timezone settings: POSIX TZ string, so DST transitions are automatic
// (e.g. UTC flips between CST/CDT on its own).
String timezoneName = "UTC";
String timezoneTz = "UTC0";  // UTC
const char* TIMEZONE_FILE = "/timezone.json";

U8G2_SSD1306_72X40_ER_F_HW_I2C u8g2(U8G2_R0, /* reset=*/ U8X8_PIN_NONE); 

const int BUTTON_PIN = 9;   // NOTE: GPIO9 is a strapping pin (BOOT button).
int activeIndex = 0;        // Holding it while power is applied enters ROM
bool lastButtonState = HIGH; // download mode; press it only AFTER boot. This
                            // is hardware behavior, not something code can change.

// Password file reading state
std::vector<String> passwordLines;
int passwordIndex = -1;
bool inPasswordMode = false;
// Button state tracking (press edge -> release edge model)
unsigned long pressStartTime = 0;
bool holdActionFired = false;
const unsigned long HOLD_TOGGLE_MS = 3000;  // 3s hold toggles TOTP/password mode
const unsigned long TAP_MAX_MS = 1000;      // release within this = a tap
const unsigned long TAP_DEBOUNCE_MS = 60;

// Scroll state for labels (used for both vault and password modes)
int labelScrollX = 0;
unsigned long lastScrollTime = 0;
const int scrollSpeedMs = 40; 

// Scroll state for password mode (username and password)
int usernameScrollX = 0;
unsigned long usernameScrollTime = 0;
int passwordScrollX = 0;
unsigned long passwordScrollTime = 0; 

const char* ntpServer = "pool.ntp.org";
// (Timezone is handled via POSIX TZ string; see timezoneTz.)

const char* VAULT_FILE = "/vault.json";
const char* VAULT_BACKUP_FILE = "/vault.json.bak";
const char* VAULT_TMP_FILE = "/vault.json.tmp";
const char* LAST_TIME_FILE = "/last_time.json";

// Tracks whether the current clock came from a fresh sync (NTP or browser).
// When false (restored from last-known time after a failed sync), the
// display shows a "!" next to the clock so stale TOTP codes are obvious.
bool timeIsFresh = false;

// Write a vault JsonDocument to flash safely: never leave /vault.json
// half-written, and always keep the previous good copy as /vault.json.bak.
bool writeVaultDoc(DynamicJsonDocument &doc) {
    if (doc.overflowed()) return false;
    if (LittleFS.exists(VAULT_FILE)) {
        LittleFS.remove(VAULT_BACKUP_FILE);
        LittleFS.rename(VAULT_FILE, VAULT_BACKUP_FILE);
    }
    File file = LittleFS.open(VAULT_TMP_FILE, "w");
    if (!file) {
        if (LittleFS.exists(VAULT_BACKUP_FILE)) LittleFS.rename(VAULT_BACKUP_FILE, VAULT_FILE);
        return false;
    }
    serializeJson(doc, file);
    file.close();
    LittleFS.remove(VAULT_FILE);
    LittleFS.rename(VAULT_TMP_FILE, VAULT_FILE);
    return true;
}

void saveVaultToStorage() {
    DynamicJsonDocument doc(16384);
    JsonArray arr = doc.to<JsonArray>();

    for (const auto& acc : myVault) {
        JsonObject obj = arr.createNestedObject();
        obj["name"] = acc.label;
        JsonObject login = obj.createNestedObject("login");
        login["totp"] = "otpauth://totp/" + acc.label + "?secret=" + acc.secret;
    }

    if (!writeVaultDoc(doc)) {
        Serial.println("ERROR: vault too large for JSON buffer or write failed - previous vault preserved");
    }
}

// Persist the in-memory password lines to /files/Passwords.txt.
// Returns false when the file could not be written (caller reports it;
// silently dropping the write is what made passwords "vanish").
bool savePasswordsToStorage() {
    // The /files dir only gets created by portal uploads; a merged-image
    // flash wipes LittleFS, so BLE password writes must create it too.
    // (Without this, open() fails silently and passwords are "OK ADDED"
    // but never persisted.)
    if (!LittleFS.exists("/files")) LittleFS.mkdir("/files");
    File file = LittleFS.open("/files/Passwords.txt", "w");
    if (!file) return false;
    file.println("# Esp Vault passwords - format per line: label:username:password");
    for (size_t i = 0; i < passwordLines.size(); i++) file.println(passwordLines[i]);
    file.close();
    return true;
}

// Sanitize a label/username for the label:username:password line format:
// colons would break split-on-first-two-colons parsing, newlines would
// break the one-line-per-entry file format.
static String sanitizePwField(const String& s, size_t maxlen) {
    String out = s;
    out.replace("\r", ""); out.replace("\n", "");
    out.replace(":", "-");
    out.trim();
    if (out.length() > maxlen) out = out.substring(0, maxlen);
    return out;
}



// Parse one vault file path into myVault. Returns true on success.
bool parseVaultFile(const char* path) {
    File file = LittleFS.open(path, "r");
    if (!file) return false;

    DynamicJsonDocument doc(16384);
    DeserializationError error = deserializeJson(doc, file);
    file.close();

    if (error) return false;

    JsonArray items = doc.is<JsonObject>() ? doc["items"].as<JsonArray>() : doc.as<JsonArray>();
    myVault.clear();
    for (JsonObject item : items) {
        String label, secret;
        if (item.containsKey("login") && item["login"]["totp"]) {
            // Native vault format (also what a Bitwarden export looks like):
            // { "name": ..., "login": { "totp": "otpauth://totp/NAME?secret=..." } }
            String totpUrl = item["login"]["totp"].as<String>();
            int secretIndex = totpUrl.indexOf("secret=");
            if (secretIndex == -1) continue;
            secret = totpUrl.substring(secretIndex + 7);
            int ampIndex = secret.indexOf('&');
            if (ampIndex != -1) secret = secret.substring(0, ampIndex);

            label = item["name"].as<String>();
            label.toUpperCase();
            label.replace(" ", "_");
        } else if (item.containsKey("label") && item.containsKey("secret")) {
            // Clean vault format: { "label": ..., "secret": ... }
            // (written by run.py and the portal Bitwarden importer)
            label = item["label"].as<String>();
            secret = item["secret"].as<String>();
        } else {
            continue;
        }
        label.trim();
        secret.trim();
        if (label.length() == 0 || secret.length() == 0) continue;
        myVault.push_back({label, secret});
    }
    return true;
}

void loadVaultFromStorage() {
    myVault.clear();
    if (parseVaultFile(VAULT_FILE)) {
        Serial.printf("Loaded %d vault entries\n", myVault.size());
        return;
    }
    Serial.println("Vault unreadable, trying backup...");
    if (parseVaultFile(VAULT_BACKUP_FILE)) {
        Serial.printf("Restored %d vault entries from backup\n", myVault.size());
    } else {
        Serial.println("No usable vault found");
    }
}

// Device Name Functions
void saveDeviceName() {
    File file = LittleFS.open(DEVICE_NAME_FILE, "w");
    if (!file) return;

    DynamicJsonDocument doc(256);
    doc["name"] = deviceName;
    // Do NOT save offset - it's always fixed to UTC-5
    serializeJson(doc, file);
    file.close();
}

void loadDeviceName() {
    File file = LittleFS.open(DEVICE_NAME_FILE, "r");
    if (!file) {
        deviceName = "Esp Vault";  // Default
        Serial.println("Device name file not found, using default");
        return;
    }

    DynamicJsonDocument doc(256);
    DeserializationError error = deserializeJson(doc, file);
    file.close();

    if (!error) {
        if (doc.containsKey("name")) {
            deviceName = doc["name"].as<String>();
            Serial.printf("Loaded device name: %s\n", deviceName.c_str());
        }
    } else {
        Serial.printf("Error parsing device name file: %s\n", error.c_str());
    }
}

// WiFi Settings Functions
void loadWiFiSettings() {
    File file = LittleFS.open(WIFI_SETTINGS_FILE, "r");
    if (!file) {
        Serial.println("WiFi settings file not found, using defaults");
        return;
    }

    DynamicJsonDocument doc(512);
    DeserializationError error = deserializeJson(doc, file);
    file.close();

    if (!error) {
        if (doc.containsKey("ssid1")) wifiSSID1 = doc["ssid1"].as<String>();
        if (doc.containsKey("password1")) wifiPassword1 = doc["password1"].as<String>();
        if (doc.containsKey("ssid2")) wifiSSID2 = doc["ssid2"].as<String>();
        if (doc.containsKey("password2")) wifiPassword2 = doc["password2"].as<String>();
        Serial.printf("Loaded WiFi settings: %s, %s\n", wifiSSID1.c_str(), wifiSSID2.c_str());
    } else {
        Serial.printf("Error parsing WiFi settings file: %s\n", error.c_str());
    }
}

void saveWiFiSettings() {
    File file = LittleFS.open(WIFI_SETTINGS_FILE, "w");
    if (!file) return;

    DynamicJsonDocument doc(512);
    doc["ssid1"] = wifiSSID1;
    doc["password1"] = wifiPassword1;
    doc["ssid2"] = wifiSSID2;
    doc["password2"] = wifiPassword2;
    serializeJson(doc, file);
    file.close();
    Serial.println("WiFi settings saved");
}

// Portal Settings Functions
void loadPortalSettings() {
    File file = LittleFS.open(PORTAL_SETTINGS_FILE, "r");
    if (!file) {
        // First boot: use the well-known default "espvault" and require the
        // user to change it after their first portal login. It's shown on the
        // OLED in portal mode and doubles as the web UI login password.
        portalPassword = "espvault";
        portalPasswordMustChange = true;
        savePortalSettings();
        Serial.println("No portal settings file - using default portal password (must be changed)");
        return;
    }

    DynamicJsonDocument doc(512);
    DeserializationError error = deserializeJson(doc, file);
    file.close();

    if (!error) {
        if (doc.containsKey("ssid")) portalSSID = doc["ssid"].as<String>();
        if (doc.containsKey("password")) portalPassword = doc["password"].as<String>();
        if (doc.containsKey("mustChange")) portalPasswordMustChange = doc["mustChange"].as<bool>();
        Serial.printf("Loaded portal settings: SSID=%s\n", portalSSID.c_str());
    } else {
        Serial.printf("Error parsing portal settings file: %s\n", error.c_str());
    }

    // Never run the portal with a blank or short password: it would make the
    // AP open (or fail to start) and the web login trivially bypassable.
    // Fall back to the default and require a change.
    if (portalPassword.length() < 8) {
        portalPassword = "espvault";
        portalPasswordMustChange = true;
        savePortalSettings();
        Serial.println("Portal password was missing/too short - reset to default (must be changed)");
    }
}

void savePortalSettings() {
    File file = LittleFS.open(PORTAL_SETTINGS_FILE, "w");
    if (!file) return;

    DynamicJsonDocument doc(512);
    doc["ssid"] = portalSSID;
    doc["password"] = portalPassword;
    doc["mustChange"] = portalPasswordMustChange;
    serializeJson(doc, file);
    file.close();
    Serial.println("Portal settings saved");
}

// Timezone Functions
void loadTimezone() {
    File file = LittleFS.open(TIMEZONE_FILE, "r");
    if (!file) {
        Serial.println("Timezone file not found, using default (UTC)");
        return;
    }

    DynamicJsonDocument doc(512);
    DeserializationError error = deserializeJson(doc, file);
    file.close();

    if (!error) {
        if (doc.containsKey("name")) timezoneName = doc["name"].as<String>();
        if (doc.containsKey("tz")) {
            timezoneTz = doc["tz"].as<String>();
        } else if (doc.containsKey("offset")) {
            // Old numeric-offset files can't express DST; fall back to the
            // POSIX TZ (a strict improvement over a fixed offset).
            Serial.println("Note: old numeric timezone offset ignored, using POSIX TZ");
            timezoneName = "UTC";
            timezoneTz = "UTC0";
        }
        Serial.printf("Loaded timezone: %s (%s)\n", timezoneName.c_str(), timezoneTz.c_str());
    } else {
        Serial.printf("Error parsing timezone file: %s\n", error.c_str());
    }
}

void saveTimezone() {
    File file = LittleFS.open(TIMEZONE_FILE, "w");
    if (!file) return;

    DynamicJsonDocument doc(512);
    doc["name"] = timezoneName;
    doc["tz"] = timezoneTz;
    serializeJson(doc, file);
    file.close();
    Serial.println("Timezone saved");
}

// Apply the POSIX TZ string so localtime_r() handles DST automatically.
// Must be called after configTime() (which resets TZ) and after any change.
void applyTimezone() {
    setenv("TZ", timezoneTz.c_str(), 1);
    tzset();
}

// Last-known-time persistence: lets the device recover a sane clock when
// NTP fails (e.g. no WiFi). Saved after every successful sync.
void saveLastKnownTime(time_t t) {
    File file = LittleFS.open(LAST_TIME_FILE, "w");
    if (!file) return;
    DynamicJsonDocument doc(128);
    doc["t"] = (long)t;
    serializeJson(doc, file);
    file.close();
}

bool restoreLastKnownTime() {
    File file = LittleFS.open(LAST_TIME_FILE, "r");
    if (!file) return false;
    DynamicJsonDocument doc(128);
    DeserializationError error = deserializeJson(doc, file);
    file.close();
    if (error || !doc.containsKey("t")) return false;
    time_t t = (time_t)doc["t"].as<long>();
    if (t < 1700000000L) return false;  // sanity: must be after Nov 2023
    struct timeval tv;
    tv.tv_sec = t;
    tv.tv_usec = 0;
    return settimeofday(&tv, NULL) == 0;
}

// Password File Functions - reads from /files/Passwords.txt
// Cached BLE JSON for password labels. The labels JSON can be several KB
// (197 passwords ≈ 8KB); rebuilding it inside onRead on every BLE blob
// request fragments the heap mid-transfer and corrupts long reads. Build it
// once here (main task / write handlers) and have onRead serve the cache.
// Static buffer for the password-labels JSON — allocated at compile time,
// never from the heap. The 8KB heap alloc kept failing on fragmented heaps
// after large imports (197 passwords). 12KB holds ~300 labels at 40 chars.
// Paged password labels: app writes page index to PWLPAGE, reads back JSON array.
// Page size is fixed at 20 labels. No cache, no rebuild flag — built on demand
// from passwordLines, each page <1KB.
#define PW_LABELS_PER_PAGE 20
int gPwPageReq = 0;

static void bleParsePasswordLine(const String& line, String& label, String& username, String& password);

// Build a JSON array of password labels for the given page (0-based).
// Returns e.g. ["label1","label2",...]. Each page has up to PW_LABELS_PER_PAGE entries.
String buildPwLabelsPage(int page) {
    String json = "[";
    int start = page * PW_LABELS_PER_PAGE;
    int total = (int)passwordLines.size();
    for (int i = start; i < start + PW_LABELS_PER_PAGE && i < total; i++) {
        String label, username, password;
        bleParsePasswordLine(passwordLines[i], label, username, password);
        label.replace("\\", "\\\\");
        label.replace("\"", "\\\"");
        if (i > start) json += ",";
        json += "\"" + label + "\"";
    }
    json += "]";
    return json;
}

void loadPasswords() {
    passwordLines.clear();
    passwordIndex = -1;
    File file = LittleFS.open("/files/Passwords.txt", "r");
    if (!file) return;

    while (file.available()) {
        String line = file.readStringUntil('\n');
        line.trim();
        if (line.length() > 0 && !line.startsWith("#")) {  // Skip empty lines and comments
            passwordLines.push_back(line);
        }
    }
    file.close();
    // No cache to rebuild — password labels are served in pages on demand.
}

// Toggle between TOTP and password viewing modes (double-tap gesture).
// Shows a brief confirmation on the OLED.
void togglePasswordMode() {
    if (inPasswordMode) {
        // Exit password mode back to TOTP
        inPasswordMode = false;
        passwordIndex = -1;

        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_04b_03_tr);
        u8g2.drawStr(0, 20, "TOTP MODE");
        u8g2.sendBuffer();
        delay(1000);
    } else {
        // Enter password mode
        loadPasswords();
        if (passwordLines.size() > 0) {
            inPasswordMode = true;
            passwordIndex = 0;

            // Reset scroll variables for password mode
            labelScrollX = 0;
            usernameScrollX = 0;
            usernameScrollTime = 0;
            passwordScrollX = 0;
            passwordScrollTime = 0;

            u8g2.clearBuffer();
            u8g2.setFont(u8g2_font_04b_03_tr);
            u8g2.drawStr(0, 20, "PASSWORD MODE");
            u8g2.sendBuffer();
            delay(1000);
        } else {
            // No passwords loaded
            u8g2.clearBuffer();
            u8g2.setFont(u8g2_font_04b_03_tr);
            u8g2.drawStr(0, 20, "NO PASSWORDS");
            u8g2.sendBuffer();
            delay(1000);
        }
    }
    labelScrollX = 0;
}

String getStorageInfo() {
    // LittleFS is mounted once at boot; never re-mount (and never auto-format) here.
    uint32_t total = LittleFS.totalBytes();
    uint32_t used  = LittleFS.usedBytes();
    uint32_t free  = total - used;

    String usedStr = String((used / 1024.0), 1) + " KB";
    String totalStr = String((total / 1024.0), 1) + " KB";
    String freeStr = String(free) + " B";

    return usedStr + " / " + totalStr + " (Free: " + freeStr + ")";
}

// Current UTC offset in seconds for the configured POSIX TZ (DST-aware).
// newlib on ESP32 has no tm_gmtoff, so derive it from localtime vs gmtime.
int currentUtcOffsetSeconds(time_t t) {
    struct tm localTm, gmTm;
    localtime_r(&t, &localTm);
    gmtime_r(&t, &gmTm);
    return (int)difftime(mktime(&localTm), mktime(&gmTm));
}

// ── Portal web authentication ─────────────────────────────────────────────
// Cookie session: the browser holds kv_auth=<token>, the token itself lives
// only in RAM and is regenerated at every boot (reboot = re-login).
String portalAuthToken = "";

void generatePortalAuthToken() {
    char buf[33];
    for (int i = 0; i < 16; i++) sprintf(buf + i * 2, "%02x", (unsigned int)(esp_random() & 0xFF));
    buf[32] = '\0';
    portalAuthToken = String(buf);
}

bool isPortalAuthenticated() {
    if (portalAuthToken.length() == 0) return false;
    if (!server.hasHeader("Cookie")) return false;
    // Parse cookies properly: "name=value; name2=value2"
    String cookie = server.header("Cookie");
    int start = 0;
    while (start < (int)cookie.length()) {
        int semi = cookie.indexOf(';', start);
        String pair = (semi == -1) ? cookie.substring(start) : cookie.substring(start, semi);
        pair.trim();
        int eq = pair.indexOf('=');
        if (eq > 0 && pair.substring(0, eq) == "kv_auth" &&
            pair.substring(eq + 1) == portalAuthToken) {
            return true;
        }
        if (semi == -1) break;
        start = semi + 1;
    }
    return false;
}

// Call at the top of every protected /api handler.
bool requirePortalAuth() {
    if (!isPortalAuthenticated()) {
        server.send(401, "text/plain", "Unauthorized: portal login required");
        return false;
    }
    return true;
}

void handleApiLogin() {
    if (server.hasArg("password") && server.arg("password") == portalPassword) {
        server.sendHeader("Set-Cookie", "kv_auth=" + portalAuthToken + "; Path=/; Max-Age=3600; HttpOnly; SameSite=Strict");
        server.send(200, "text/plain", "OK");
    } else {
        delay(500);  // slow down password guessing
        server.send(401, "text/plain", "Invalid password");
    }
}

void handleApiLogout() {
    server.sendHeader("Set-Cookie", "kv_auth=; Path=/; Max-Age=0; HttpOnly");
    server.send(200, "text/plain", "OK");
}

void handleApiAuthCheck() {
    if (!isPortalAuthenticated()) { server.send(401, "text/plain", "login required"); return; }
    DynamicJsonDocument doc(128);
    doc["ok"] = true;
    doc["mustChangePassword"] = portalPasswordMustChange;
    String output;
    serializeJson(doc, output);
    server.send(200, "application/json", output);
}

// ── Vault input validation ────────────────────────────────────────────────
bool isValidBase32Secret(const String& s) {
    if (s.length() < 8 || s.length() > 128) return false;
    for (size_t i = 0; i < s.length(); i++) {
        char c = s[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
              (c >= '2' && c <= '7') || c == '=')) return false;
    }
    return true;
}

// Same normalization the loader applies, so API-added entries match.
String normalizeLabel(const String& in) {
    String l = in;
    l.trim();
    l.toUpperCase();
    l.replace(" ", "_");
    return l;
}

int findVaultIndex(const String& label) {
    for (size_t i = 0; i < myVault.size(); i++)
        if (myVault[i].label == label) return (int)i;
    return -1;
}

void handleRoot() { server.send_P(200, "text/html", INDEX_HTML); }

// Labels only — secrets are fetched per-entry via /api/get when editing.
void handleApiList() {
    if (!requirePortalAuth()) return;
    DynamicJsonDocument doc(16384);
    JsonArray arr = doc.to<JsonArray>();
    for (const auto& acc : myVault) {
        JsonObject obj = arr.createNestedObject();
        obj["label"] = acc.label;
    }
    if (doc.overflowed()) { server.send(500, "text/plain", "Vault too large to list"); return; }
    String output;
    serializeJson(doc, output);
    server.send(200, "application/json", output);
}

void handleApiGet() {
    if (!requirePortalAuth()) return;
    if (!server.hasArg("index")) { server.send(400, "text/plain", "Missing index"); return; }
    int idx = server.arg("index").toInt();
    if (idx < 0 || idx >= (int)myVault.size()) { server.send(404, "text/plain", "Not found"); return; }
    DynamicJsonDocument doc(512);
    doc["label"] = myVault[idx].label;
    doc["secret"] = myVault[idx].secret;
    String output;
    serializeJson(doc, output);
    server.send(200, "application/json", output);
}

void handleApiAdd() {
    if (!requirePortalAuth()) return;
    if (!(server.hasArg("label") && server.hasArg("secret"))) {
        server.send(400, "text/plain", "Bad Request");
        return;
    }
    String label = normalizeLabel(server.arg("label"));
    String secret = server.arg("secret");
    secret.trim();
    secret.replace(" ", "");
    if (label.length() == 0) { server.send(400, "text/plain", "Label required"); return; }
    if (!isValidBase32Secret(secret)) { server.send(400, "text/plain", "Invalid Base32 secret"); return; }
    if (findVaultIndex(label) != -1) { server.send(409, "text/plain", "Label already exists"); return; }
    myVault.push_back({label, secret});
    saveVaultToStorage();
    server.send(200, "text/plain", "OK");
}

// 🛠️ NEW BACKEND ROUTE: Overwrites label string matching index reference parameter
void handleApiEdit() {
    if (!requirePortalAuth()) return;
    if (server.hasArg("index") && server.hasArg("label")) {
        int idx = server.arg("index").toInt();
        if (idx >= 0 && idx < (int)myVault.size()) {
            String label = normalizeLabel(server.arg("label"));
            if (label.length() == 0) { server.send(400, "text/plain", "Label required"); return; }
            int dup = findVaultIndex(label);
            if (dup != -1 && dup != idx) { server.send(409, "text/plain", "Label already exists"); return; }
            if (server.hasArg("secret")) {
                String secret = server.arg("secret");
                secret.trim();
                secret.replace(" ", "");
                if (!isValidBase32Secret(secret)) { server.send(400, "text/plain", "Invalid Base32 secret"); return; }
                myVault[idx].secret = secret;
            }
            myVault[idx].label = label;
            saveVaultToStorage();
            server.send(200, "text/plain", "OK");
            return;
        }
    }
    server.send(400, "text/plain", "Bad Request");
}

void handleApiDelete() {
    if (!requirePortalAuth()) return;
    if (server.hasArg("index")) {
        int idx = server.arg("index").toInt();
        if (idx >= 0 && idx < (int)myVault.size()) {
            myVault.erase(myVault.begin() + idx);
            saveVaultToStorage();
            server.send(200, "text/plain", "OK");
            return;
        }
    }
    server.send(400, "text/plain", "Bad Request");
}

// Vault database upload ("Sync Database"). Writes to a temp file, validates
// the JSON, then swaps it in — an interrupted upload can no longer destroy
// the existing vault.
bool vaultUploadAuthorized = false;

void handleVaultUpload() {
    HTTPUpload& upload = server.upload();
    if (upload.status == UPLOAD_FILE_START) {
        vaultUploadAuthorized = isPortalAuthenticated();
        if (vaultUploadAuthorized) LittleFS.remove(VAULT_TMP_FILE);
    } else if (upload.status == UPLOAD_FILE_WRITE) {
        if (!vaultUploadAuthorized) return;
        File file = LittleFS.open(VAULT_TMP_FILE, "a");
        if (file) { file.write(upload.buf, upload.currentSize); file.close(); }
    }
}

void handleVaultUploadDone() {
    if (!requirePortalAuth()) { LittleFS.remove(VAULT_TMP_FILE); return; }
    // Validate before touching the live vault
    bool ok = false;
    {
        File f = LittleFS.open(VAULT_TMP_FILE, "r");
        if (f) {
            DynamicJsonDocument doc(16384);
            DeserializationError e = deserializeJson(doc, f);
            f.close();
            ok = !e && (doc.is<JsonArray>() || (doc.is<JsonObject>() && doc.containsKey("items")));
        }
    }
    if (!ok) {
        LittleFS.remove(VAULT_TMP_FILE);
        server.send(400, "text/plain", "Invalid vault file");
        return;
    }
    if (LittleFS.exists(VAULT_FILE)) {
        LittleFS.remove(VAULT_BACKUP_FILE);
        if (!LittleFS.rename(VAULT_FILE, VAULT_BACKUP_FILE)) {
            LittleFS.remove(VAULT_TMP_FILE);
            server.send(500, "text/plain", "Backup failed");
            return;
        }
    }
    if (!LittleFS.rename(VAULT_TMP_FILE, VAULT_FILE)) {
        // Roll back: restore the backup so the live vault is never left missing
        LittleFS.rename(VAULT_BACKUP_FILE, VAULT_FILE);
        server.send(500, "text/plain", "Vault replace failed - previous vault restored");
        return;
    }
    myVault.clear();
    loadVaultFromStorage();  // re-parse what we just wrote, so RAM matches flash
    server.send(200, "text/plain", "Sync Successful! Rebooting device...");
    delay(500);
    ESP.restart();
}

// ── File Browser ──────────────────────────────────────────────────────────────

String uploadFileName = "";
bool fileUploadAuthorized = false;

void handleFileUpload() {
    HTTPUpload& upload = server.upload();
    if (upload.status == UPLOAD_FILE_START) {
        fileUploadAuthorized = isPortalAuthenticated();
        if (!fileUploadAuthorized) return;
        String name = upload.filename;
        name.replace("/", "");
        name.replace("..", "");
        // Ensure all files go into /files/ directory
        uploadFileName = "/files/" + name;
        LittleFS.remove(uploadFileName);
        // Create /files/ directory if it doesn't exist
        if (!LittleFS.exists("/files")) {
            LittleFS.mkdir("/files");
        }
    } else if (upload.status == UPLOAD_FILE_WRITE) {
        if (!fileUploadAuthorized) return;
        File file = LittleFS.open(uploadFileName, "a");
        if (file) { file.write(upload.buf, upload.currentSize); file.close(); }
    }
    // NOTE: the 200 response is sent by the route's completion handler below,
    // not here - sending twice corrupts the HTTP response.
}


void handleApiListFiles() {
    if (!requirePortalAuth()) return;
    DynamicJsonDocument doc(4096);
    JsonArray arr = doc.to<JsonArray>();

    // Only list files in /files/ directory
    File root = LittleFS.open("/files");
    if (!root) {
        server.send(200, "application/json", "[]");
        return;
    }

    File entry = root.openNextFile();
    while (entry) {
        JsonObject obj = arr.createNestedObject();
        String name = String(entry.name());
        if (name.startsWith("/files/")) {
            name = name.substring(7); // Remove "/files/" prefix
        }
        obj["name"] = name;
        obj["size"] = entry.size();
        entry = root.openNextFile();
    }

    if (doc.overflowed()) { server.send(500, "text/plain", "Too many files to list"); return; }
    String output;
    serializeJson(doc, output);
    server.send(200, "application/json", output);
}

void handleApiStorage() {
    if (!requirePortalAuth()) return;
    String info = getStorageInfo();
    server.send(200, "text/plain", info);
}

void handleApiViewFile() {
    if (!requirePortalAuth()) return;
    if (!server.hasArg("path")) { server.send(400, "text/plain", "Missing path"); return; }
    String path = server.arg("path");
    if (path.startsWith("/")) path = path.substring(1);
    path = "/files/" + path; // Prefix with /files/

    File file = LittleFS.open(path, "r");
    if (!file) { server.send(404, "text/plain", "Not found"); return; }

    server.streamFile(file, "text/plain");
    file.close();
}

void handleApiSaveFile() {
    if (!requirePortalAuth()) return;
    if (!server.hasArg("path") || !server.hasArg("content")) {
        server.send(400, "text/plain", "Missing path or content"); return;
    }
    String path = server.arg("path");
    if (path.startsWith("/")) path = path.substring(1);
    path = "/files/" + path; // Prefix with /files/

    if (path == "/files/vault.json") { server.send(403, "text/plain", "Use the vault API"); return; }

    // Ensure /files/ directory exists
    if (!LittleFS.exists("/files")) {
        LittleFS.mkdir("/files");
    }

    File file = LittleFS.open(path, "w");
    if (!file) { server.send(500, "text/plain", "Write failed"); return; }
    file.print(server.arg("content"));
    file.close();
    server.send(200, "text/plain", "OK");
}

void handleApiDeleteFile() {
    if (!requirePortalAuth()) return;
    if (!server.hasArg("path")) { server.send(400, "text/plain", "Missing path"); return; }
    String path = server.arg("path");
    if (path.startsWith("/")) path = path.substring(1);
    path = "/files/" + path; // Prefix with /files/
    if (path == "/files/vault.json") { server.send(403, "text/plain", "Use the vault API"); return; }

    LittleFS.remove(path);
    server.send(200, "text/plain", "OK");
}

// Generate a 6-digit TOTP code for a Base32 secret at the given UTC epoch.
// Shared by the portal /api/auth-codes handler and the BLE totp_request.
String generateTotpCode(const String& secret, time_t now) {
    String decodedSecret;
    int bitsBuffer = 0;
    int bitsCount = 0;
    for (size_t i = 0; i < secret.length(); i++) {
        char c = secret[i];
        if (c >= 'A' && c <= 'Z') {
            bitsBuffer = (bitsBuffer << 5) | (c - 'A');
            bitsCount += 5;
        } else if (c >= 'a' && c <= 'z') {
            bitsBuffer = (bitsBuffer << 5) | (c - 'a');
            bitsCount += 5;
        } else if (c >= '2' && c <= '7') {
            bitsBuffer = (bitsBuffer << 5) | (c - '2' + 26);
            bitsCount += 5;
        }

        if (bitsCount >= 8) {
            bitsCount -= 8;
            decodedSecret += (char)((bitsBuffer >> bitsCount) & 0xFF);
        }
    }
    TOTP totpGenerator((uint8_t*)decodedSecret.c_str(), decodedSecret.length());
    return totpGenerator.getCode(now);
}

void handleApiAuthCodes() {
    if (!requirePortalAuth()) return;
    DynamicJsonDocument doc(16384);
    JsonArray arr = doc.to<JsonArray>();

    time_t now;
    time(&now);  // This is UTC time, which is what TOTP needs

    int secondsRemaining = 30 - (now % 30);

    for (const auto& acc : myVault) {
        String code = generateTotpCode(acc.secret, now);

        // NOTE: codes are deliberately NOT printed to Serial (they're secrets).

        JsonObject obj = arr.createNestedObject();
        obj["label"] = acc.label;
        obj["code"] = code.substring(0, 3) + " " + code.substring(3, 6);
        obj["remaining"] = secondsRemaining;
    }

    if (doc.overflowed()) { server.send(500, "text/plain", "Vault too large to list"); return; }
    Serial.printf("Served %d auth codes via portal\n", myVault.size());
    String output;
    serializeJson(doc, output);
    server.send(200, "application/json", output);
}

void handleApiTimeInfo() {
    if (!requirePortalAuth()) return;
    DynamicJsonDocument doc(1024);
    JsonObject obj = doc.to<JsonObject>();

    // UTC epoch for TOTP; localtime_r applies the POSIX TZ (DST-aware) for display
    time_t utcNow;
    time(&utcNow);
    struct tm localTime;
    localtime_r(&utcNow, &localTime);

    char timeStr[20];
    strftime(timeStr, sizeof(timeStr), "%Y-%m-%d %H:%M:%S", &localTime);

    obj["currentTime"] = String(timeStr);
    obj["ntpServer"] = ntpServerName;
    obj["wifiSSID"] = lastWifiSSID;
    obj["wifiBSSID"] = lastWifiBSSID;
    obj["timezone"] = timezoneName;
    obj["tz"] = timezoneTz;
    obj["offsetSeconds"] = currentUtcOffsetSeconds(utcNow);
    obj["offsetHours"] = currentUtcOffsetSeconds(utcNow) / 3600;
    obj["timeIsFresh"] = timeIsFresh;

    String output;
    serializeJson(doc, output);
    server.send(200, "application/json", output);
}

void handleApiDeviceName() {
    if (!requirePortalAuth()) return;
    time_t now;
    time(&now);
    struct tm localNow;
    localtime_r(&now, &localNow);
    if (server.method() == HTTP_GET) {
        DynamicJsonDocument doc(256);
        doc["name"] = deviceName;
        doc["offsetSeconds"] = currentUtcOffsetSeconds(now);
        doc["offsetHours"] = currentUtcOffsetSeconds(now) / 3600;
        String output;
        serializeJson(doc, output);
        server.send(200, "application/json", output);
    } else if (server.method() == HTTP_POST) {
        String name = server.arg("plain");
        if (name.length() > 0 && name.length() <= 32) {
            deviceName = name;
            saveDeviceName();
            server.send(200, "text/plain", "OK");
        } else {
            server.send(400, "text/plain", "Missing or too long name");
        }
    }
}

void handleApiPasswords() {
    if (!requirePortalAuth()) return;
    DynamicJsonDocument doc(16384);
    JsonArray arr = doc.to<JsonArray>();

    for (const auto& line : passwordLines) {
        arr.add(line);
    }

    if (doc.overflowed()) { server.send(500, "text/plain", "Password file too large"); return; }
    String output;
    serializeJson(doc, output);
    server.send(200, "application/json", output);
}

void handleApiWiFiSettings() {
    if (!requirePortalAuth()) return;
    if (server.method() == HTTP_GET) {
        DynamicJsonDocument doc(512);
        doc["ssid"] = wifiSSID1; // Return primary SSID
        doc["password"] = wifiPassword1; // Return primary password
        doc["ssid2"] = wifiSSID2;
        doc["password2"] = wifiPassword2;
        String output;
        serializeJson(doc, output);
        server.send(200, "application/json", output);
    } else if (server.method() == HTTP_POST) {
        if (server.hasArg("plain")) {
            DynamicJsonDocument doc(512);
            DeserializationError error = deserializeJson(doc, server.arg("plain"));

            if (!error) {
                if (doc.containsKey("ssid")) wifiSSID1 = doc["ssid"].as<String>();
                if (doc.containsKey("password")) wifiPassword1 = doc["password"].as<String>();
                saveWiFiSettings();
                server.send(200, "text/plain", "WiFi settings saved");
                Serial.printf("WiFi settings updated: %s\n", wifiSSID1.c_str());
            } else {
                server.send(400, "text/plain", "Invalid JSON");
            }
        } else {
            server.send(400, "text/plain", "Missing data");
        }
    }
}

void handleApiPortalSettings() {
    if (!requirePortalAuth()) return;
    if (server.method() == HTTP_GET) {
        DynamicJsonDocument doc(512);
        doc["ssid"] = portalSSID;
        doc["password"] = portalPassword;
        String output;
        serializeJson(doc, output);
        server.send(200, "application/json", output);
    } else if (server.method() == HTTP_POST) {
        if (server.hasArg("plain")) {
            DynamicJsonDocument doc(512);
            DeserializationError error = deserializeJson(doc, server.arg("plain"));

            if (!error) {
                if (doc.containsKey("ssid")) portalSSID = doc["ssid"].as<String>();
                if (doc.containsKey("password")) {
                    String p = doc["password"].as<String>();
                    // ESP32 AP needs >= 8 chars for WPA2; a shorter password would
                    // silently fail the next portal boot and lock out the AP.
                    // Empty is not allowed either - it would blank the login.
                    if (p.length() < 8) {
                        server.send(400, "text/plain", "Portal password must be at least 8 characters");
                        return;
                    }
                    portalPassword = p;
                    // The well-known default must always be followed by a change.
                    portalPasswordMustChange = (p == "espvault");
                }
                savePortalSettings();
                server.send(200, "text/plain", "Portal settings saved");
                Serial.println("Portal settings updated");
            } else {
                server.send(400, "text/plain", "Invalid JSON");
            }
        } else {
            server.send(400, "text/plain", "Missing data");
        }
    }
}

void handleApiTimezone() {
    if (!requirePortalAuth()) return;
    if (server.method() == HTTP_GET) {
        DynamicJsonDocument doc(512);
        doc["name"] = timezoneName;
        doc["tz"] = timezoneTz;
        String output;
        serializeJson(doc, output);
        server.send(200, "application/json", output);
    } else if (server.method() == HTTP_POST) {
        if (server.hasArg("plain")) {
            DynamicJsonDocument doc(512);
            DeserializationError error = deserializeJson(doc, server.arg("plain"));

            if (!error) {
                if (doc.containsKey("name")) timezoneName = doc["name"].as<String>();
                if (doc.containsKey("tz")) timezoneTz = doc["tz"].as<String>();
                saveTimezone();
                applyTimezone();
                server.send(200, "text/plain", "Timezone saved");
                Serial.printf("Timezone updated: %s (%s)\n", timezoneName.c_str(), timezoneTz.c_str());
            } else {
                server.send(400, "text/plain", "Invalid JSON");
            }
        } else {
            server.send(400, "text/plain", "Missing data");
        }
    }
}

void handleApiDebugTime() {
    if (!requirePortalAuth()) return;
    DynamicJsonDocument doc(512);

    time_t now;
    time(&now);

    struct tm utcInfo;
    gmtime_r(&now, &utcInfo);

    struct tm localInfo;
    localtime_r(&now, &localInfo);

    doc["utcTimestamp"] = (long)now;

    char utcStr[30];
    strftime(utcStr, sizeof(utcStr), "%Y-%m-%d %H:%M:%S UTC", &utcInfo);
    doc["utcTime"] = String(utcStr);

    doc["timezone"] = timezoneName;
    doc["tz"] = timezoneTz;
    doc["offsetSeconds"] = currentUtcOffsetSeconds(now);
    doc["offsetHours"] = currentUtcOffsetSeconds(now) / 3600;

    char localStr[30];
    strftime(localStr, sizeof(localStr), "%Y-%m-%d %H:%M:%S Local", &localInfo);
    doc["localTime"] = String(localStr);

    String output;
    serializeJson(doc, output);
    server.send(200, "application/json", output);
}

void handleApiSetTime() {
    if (!requirePortalAuth()) return;
    // Accept time from browser (guaranteed to be correct)
    // POST /api/set-time with body: {"timestamp": 1719345600}
    if (server.method() == HTTP_POST && server.hasArg("plain")) {
        DynamicJsonDocument doc(256);
        DeserializationError error = deserializeJson(doc, server.arg("plain"));

        if (!error && doc.containsKey("timestamp")) {
            time_t browserTime = doc["timestamp"].as<time_t>();
            struct timeval tv;
            tv.tv_sec = browserTime;
            tv.tv_usec = 0;

            if (settimeofday(&tv, NULL) == 0) {
                struct tm* t = gmtime(&browserTime);
                Serial.printf("Time set from browser: %04d-%02d-%02d %02d:%02d:%02d (ts: %ld)\n",
                    t->tm_year + 1900, t->tm_mon + 1, t->tm_mday,
                    t->tm_hour, t->tm_min, t->tm_sec, (long)browserTime);

                // Persist so future boots can recover a sane clock when NTP fails
                saveLastKnownTime(browserTime);
                timeIsFresh = true;

                server.send(200, "application/json", "{\"status\":\"ok\"}");
                return;
            }
        }
    }
    server.send(400, "application/json", "{\"error\":\"Invalid request\"}");
}

// ── BLE companion protocol (GATT server) ─────────────────────────────────
// Lets the Linux/Android companion app read TOTP codes and passwords over
// Bluetooth. Security model is hobby-grade, same as the portal: the app
// authenticates by writing the portal password to the auth characteristic.
//
//  * Radio is OFF by default. Portal Settings → Bluetooth enables "auto"
//    mode: the radio turns on automatically on unlock, and turns off when
//    the device locks or after 5 minutes without BLE activity.
//  * Secrets are only served while the device is UNLOCKED. Locking (auto,
//    button, or the BLE lock characteristic) drops the session and stops
//    the radio.
//  * No BLE link-layer pairing — traffic is readable within radio range,
//    exactly like the portal AP before login. Do not treat as strong security.

#define BLE_SVC_UUID      "81f3d5eb-25b8-4077-aff6-c578a9613ab6"
#define BLE_CH_AUTH       "7c896164-1090-43d0-ab1d-1a7251069755"
#define BLE_CH_STATUS     "b33250c0-7157-4f43-8b80-ad6138026c5a"
#define BLE_CH_DEVICEINFO "06d9cadd-eb38-4ce7-b8c7-1c111ca67b3b"
#define BLE_CH_VAULTCOUNT "2f096600-5c39-477d-90c2-7f462c8124db"
#define BLE_CH_VAULTLABEL "86192014-db09-4c0d-9541-2e408811b091"
#define BLE_CH_TOTPREQ    "fca93d15-df17-4b9d-9c87-8d31a7e69ff8"
#define BLE_CH_TOTPCODE   "563456d9-f8f9-4c9d-9cdc-9db51cec980a"
#define BLE_CH_PWCOUNT    "68f67e11-cdac-4d61-b5bd-58ae373e1f2c"
#define BLE_CH_PWLABELS   "7e1d7521-587e-42e1-ae79-6f4a12d1465a"
#define BLE_CH_PWLPAGE    "9c4e2a1f-3b7d-4e8a-9f2c-1d5e6a7b8c9d"
#define BLE_CH_PWREQ      "db65d936-d0ab-4663-a944-1aed62839e0c"
#define BLE_CH_PWENTRY    "fa782901-221c-4cec-9839-52d60fbcb1ff"
#define BLE_CH_TIMESYNC   "eca41348-e62b-400a-9098-db4de06d644e"
#define BLE_CH_ADDTOTP    "81841413-8a90-40a6-8521-c8c444fce206"
#define BLE_CH_LOCK       "47ce190e-1fbf-4c54-8c18-a775455ae0de"
#define BLE_CH_TOTPDEL     "d29e964e-1377-4b9e-843a-cf3453ed4a54"
#define BLE_CH_TOTPSET     "22622a17-8714-4361-a7e1-35aae56f337a"
#define BLE_CH_PWADD       "3eb833b0-9943-4c35-9eda-44d456be3745"
#define BLE_CH_PWSET       "ac74481a-fe9c-40f5-b800-174f5b23f4e5"
#define BLE_CH_PWDEL       "bc172588-0a48-46fb-8105-be7490cb2526"
#define BLE_CH_SETUPPW    "4c73d3a3-e7b3-4db1-9cc1-6b7c1371c595"
#define BLE_CH_SETUPWIFI  "293daea1-8da9-425e-baa8-c62557fffad7"
#define BLE_CH_SETUPDONE  "f241bf45-78af-40af-9741-062ba41ee9b0"
#define BLE_CH_BLEAUTO    "e2e0fbd6-009f-4e45-97d6-c045bf512e66"
// v1.4.6+ device settings (all require unlock + auth)
#define BLE_CH_PWCHANGE   "4a0cb1b6-5c35-4ad6-a253-81edbf811000"
#define BLE_CH_WIFISET    "a9cdd97a-f770-4d5a-9d82-1a224859ce91"
#define BLE_CH_TZ         "4f0c630a-50a2-4348-ad1b-425a39900942"
// v1.4.7+ backup export (requires unlock + auth)
#define BLE_CH_VAULTEXPORT "8113d85b-9feb-470e-a36e-5ccbab8dac90"

const char* BLE_SETTINGS_FILE = "/ble.json";
const unsigned long BLE_IDLE_TIMEOUT_MS = 5UL * 60UL * 1000UL;  // 5 minutes

bool bleAutoEnabled = false;   // persistent: auto-start BLE radio on unlock
bool bleActive = false;        // radio on (advertising or connected)
bool bleAuthed = false;        // this connection passed the password check
bool bleClientConnected = false;
unsigned long bleLastActivity = 0;
bool bleStackInit = false;

BLEServer* bleServer = nullptr;
BLECharacteristic* bleChAuth = nullptr;
BLECharacteristic* bleChStatus = nullptr;
BLECharacteristic* bleChDeviceInfo = nullptr;
BLECharacteristic* bleChVaultCount = nullptr;
BLECharacteristic* bleChVaultLabels = nullptr;
BLECharacteristic* bleChTotpRequest = nullptr;
BLECharacteristic* bleChTotpCode = nullptr;
BLECharacteristic* bleChPwCount = nullptr;
BLECharacteristic* bleChPwLabels = nullptr;
BLECharacteristic* bleChPwPage = nullptr;
BLECharacteristic* bleChPwRequest = nullptr;
BLECharacteristic* bleChPwEntry = nullptr;
BLECharacteristic* bleChTimeSync = nullptr;
BLECharacteristic* bleChAddTotp = nullptr;
BLECharacteristic* bleChLock = nullptr;
BLECharacteristic* bleChTotpDel = nullptr;
BLECharacteristic* bleChTotpSet = nullptr;
BLECharacteristic* bleChPwAdd = nullptr;
BLECharacteristic* bleChPwSet = nullptr;
BLECharacteristic* bleChPwDel = nullptr;
BLECharacteristic* bleChSetupPw = nullptr;
BLECharacteristic* bleChSetupWifi = nullptr;
BLECharacteristic* bleChSetupDone = nullptr;
BLECharacteristic* bleChBleAuto = nullptr;
BLECharacteristic* bleChPwChange = nullptr;
BLECharacteristic* bleChWifiSet = nullptr;
BLECharacteristic* bleChTz = nullptr;
BLECharacteristic* bleChVaultExport = nullptr;

// Defined with the portal helpers (before setup()); declared here because
// the BLE characteristic callbacks below reference them.
extern bool gSetupNeeded;
void markSetupDone();
bool isSetupNeeded();

void loadBleSettings() {
    bleAutoEnabled = false;
    File file = LittleFS.open(BLE_SETTINGS_FILE, "r");
    if (!file) return;
    DynamicJsonDocument doc(128);
    if (!deserializeJson(doc, file)) {
        bleAutoEnabled = doc["auto"] | false;
    }
    file.close();
}

void saveBleSettings() {
    DynamicJsonDocument doc(128);
    doc["auto"] = bleAutoEnabled;
    File file = LittleFS.open(BLE_SETTINGS_FILE, "w");
    if (file) {
        serializeJson(doc, file);
        file.close();
    }
}

void bleNotifyStatus(const String& msg) {
    if (!bleChStatus) return;
    bleChStatus->setValue(msg);
    bleChStatus->notify();
}

// Gate for every secret-serving operation. Emits ERR on status when closed.
bool bleCheckAuth() {
    if (deviceLocked) { bleNotifyStatus("ERR LOCKED"); return false; }
    if (!bleAuthed)   { bleNotifyStatus("ERR AUTH");   return false; }
    bleLastActivity = millis();
    return true;
}

// Split a "label:username:password" line the same way the display code does.
static void bleParsePasswordLine(const String& line, String& label, String& username, String& password) {
    label = "Login"; username = ""; password = "";
    int c1 = line.indexOf(':');
    if (c1 < 0) { username = line; return; }
    label = line.substring(0, c1);
    int c2 = line.indexOf(':', c1 + 1);
    if (c2 < 0) { username = line.substring(c1 + 1); return; }
    username = line.substring(c1 + 1, c2);
    password = line.substring(c2 + 1);  // password may itself contain colons
}

class BleServerCallbacks : public BLEServerCallbacks {
public:
    void onConnect(BLEServer* pServer) override {
        bleClientConnected = true;
        bleAuthed = false;  // every connection starts unauthenticated
        bleLastActivity = millis();
    }
    void onDisconnect(BLEServer* pServer) override {
        bleClientConnected = false;
        bleAuthed = false;
        // Keep advertising so the app can reconnect until the idle timeout.
        if (bleActive && bleServer) bleServer->getAdvertising()->start();
    }
};

class BleCharCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* pCh) override {
        String uuid = pCh->getUUID().toString();
        String val = pCh->getValue();  // never logged: may be the password

        if (uuid.equalsIgnoreCase(BLE_CH_AUTH)) {
            if (deviceLocked) { bleNotifyStatus("ERR LOCKED"); return; }
            if (val.length() > 0 && val == portalPassword) {
                bleAuthed = true;
                bleLastActivity = millis();
                bleNotifyStatus("OK AUTH");
            } else {
                bleAuthed = false;
                delay(300);  // slow down password guessing
                bleNotifyStatus("ERR AUTH");
            }
            return;
        }

        // First-boot setup operations: only while setup is still needed, no
        // auth (no password exists yet). After setup they answer ERR SETUP.
        if (uuid.equalsIgnoreCase(BLE_CH_SETUPPW)) {
            if (!gSetupNeeded) { bleNotifyStatus("ERR SETUP"); return; }
            DynamicJsonDocument doc(256);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            String pw = doc["password"] | "";
            pw.trim();
            if (pw.length() < 8) { bleNotifyStatus("ERR SHORT"); return; }
            portalPassword = pw;
            portalPasswordMustChange = false;
            savePortalSettings();
            bleNotifyStatus("OK PASSWORD");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_SETUPWIFI)) {
            if (!gSetupNeeded) { bleNotifyStatus("ERR SETUP"); return; }
            DynamicJsonDocument doc(256);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            String ssid = doc["ssid"] | "";
            String pw = doc["password"] | "";
            ssid.trim(); pw.trim();
            if (ssid.length() == 0) { bleNotifyStatus("ERR SSID"); return; }
            wifiSSID1 = ssid;
            wifiPassword1 = pw;
            saveWiFiSettings();
            bleNotifyStatus("OK WIFI");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_SETUPDONE)) {
            if (!gSetupNeeded) { bleNotifyStatus("ERR SETUP"); return; }
            if (portalPasswordMustChange || portalPassword.length() < 8) {
                bleNotifyStatus("ERR PASSWORD"); return;
            }
            markSetupDone();
            // The user just set up over BLE — keep that path open so the app
            // can reconnect after the reboot without a portal visit.
            bleAutoEnabled = true;
            saveBleSettings();
            bleNotifyStatus("OK DONE");
            delay(500);
            ESP.restart();
            return;
        }

        // App-managed BLE auto-start flag (normal auth: unlocked + authed).
        if (uuid.equalsIgnoreCase(BLE_CH_BLEAUTO)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(64);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            bleAutoEnabled = (doc["auto"] | 0) == 1;
            saveBleSettings();
            bleNotifyStatus(bleAutoEnabled ? "OK AUTO ON" : "OK AUTO OFF");
            return;
        }

        // v1.4.6+ device settings (normal auth: unlocked + authed).

        // Change the portal password. Payload: {"old":"...","new":"..."}.
        if (uuid.equalsIgnoreCase(BLE_CH_PWCHANGE)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(256);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            String oldPw = doc["old"] | "";
            String newPw = doc["new"] | "";
            newPw.trim();
            if (oldPw != portalPassword) { bleNotifyStatus("ERR OLD"); return; }
            if (newPw.length() < 8) { bleNotifyStatus("ERR SHORT"); return; }
            portalPassword = newPw;
            portalPasswordMustChange = false;
            savePortalSettings();
            bleNotifyStatus("OK CHANGED");
            return;
        }

        // Update WiFi credentials (post-setup; same storage as setup).
        // Payload: {"ssid":"...","password":"..."}.
        if (uuid.equalsIgnoreCase(BLE_CH_WIFISET)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(256);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            String ssid = doc["ssid"] | "";
            String pw = doc["password"] | "";
            ssid.trim(); pw.trim();
            if (ssid.length() == 0) { bleNotifyStatus("ERR SSID"); return; }
            wifiSSID1 = ssid;
            wifiPassword1 = pw;
            saveWiFiSettings();
            bleNotifyStatus("OK WIFI");
            return;
        }

        // Timezone get/set. Write payload: {"name":"...","tz":"..."} (POSIX TZ).
        if (uuid.equalsIgnoreCase(BLE_CH_TZ)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(256);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            String name = doc["name"] | "";
            String tz = doc["tz"] | "";
            name.trim(); tz.trim();
            if (name.length() == 0 || tz.length() == 0) { bleNotifyStatus("ERR TZ"); return; }
            timezoneName = name;
            timezoneTz = tz;
            saveTimezone();
            applyTimezone();
            bleNotifyStatus("OK TZ");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_TOTPREQ)) {
            if (!bleCheckAuth()) return;
            if (val.length() < 2) { bleNotifyStatus("ERR INDEX"); return; }
            uint16_t idx = (uint8_t)val.charAt(0) | ((uint16_t)(uint8_t)val.charAt(1) << 8);
            if (idx >= myVault.size()) { bleNotifyStatus("ERR INDEX"); return; }
            time_t now; time(&now);  // UTC, which is what TOTP needs
            String code = generateTotpCode(myVault[idx].secret, now);
            int secs = 30 - (now % 30);
            bleChTotpCode->setValue("CODE;" + myVault[idx].label + ";" + code + ";" + String(secs));
            bleChTotpCode->notify();
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_PWLPAGE)) {
            if (!bleCheckAuth()) return;
            if (val.length() < 2) { bleNotifyStatus("ERR PAGE"); return; }
            gPwPageReq = (uint8_t)val.charAt(0) | ((uint16_t)(uint8_t)val.charAt(1) << 8);
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_PWREQ)) {
            if (!bleCheckAuth()) return;
            if (val.length() < 2) { bleNotifyStatus("ERR INDEX"); return; }
            uint16_t idx = (uint8_t)val.charAt(0) | ((uint16_t)(uint8_t)val.charAt(1) << 8);
            // passwordLines is kept in sync in RAM (loaded at boot, updated on
            // every add/edit/delete) — no filesystem re-read in the BLE task.
            if (idx >= passwordLines.size()) { bleNotifyStatus("ERR INDEX"); return; }
            String label, username, password;
            bleParsePasswordLine(passwordLines[idx], label, username, password);
            bleChPwEntry->setValue("ENTRY;" + label + ";" + username + ";" + password);
            bleChPwEntry->notify();
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_TIMESYNC)) {
            if (!bleCheckAuth()) return;
            if (val.length() < 4) { bleNotifyStatus("ERR TIME"); return; }
            uint32_t epoch = (uint8_t)val.charAt(0)
                           | ((uint32_t)(uint8_t)val.charAt(1) << 8)
                           | ((uint32_t)(uint8_t)val.charAt(2) << 16)
                           | ((uint32_t)(uint8_t)val.charAt(3) << 24);
            struct timeval tv; tv.tv_sec = epoch; tv.tv_usec = 0;
            if (settimeofday(&tv, NULL) == 0) {
                saveLastKnownTime(epoch);
                timeIsFresh = true;
                bleNotifyStatus("OK TIME");
            } else {
                bleNotifyStatus("ERR TIME");
            }
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_ADDTOTP)) {
            if (!bleCheckAuth()) return;
            // Same validation as the portal /api/add route.
            DynamicJsonDocument doc(512);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            String label = normalizeLabel(doc["label"] | "");
            String secret = doc["secret"] | "";
            secret.trim(); secret.replace(" ", "");
            if (label.length() == 0) { bleNotifyStatus("ERR LABEL"); return; }
            if (!isValidBase32Secret(secret)) { bleNotifyStatus("ERR SECRET"); return; }
            if (findVaultIndex(label) != -1) { bleNotifyStatus("ERR DUP"); return; }
            myVault.push_back({label, secret});
            saveVaultToStorage();
            bleNotifyStatus("OK ADDED");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_TOTPDEL)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(128);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            int idx = doc["index"] | -1;
            if (idx < 0 || idx >= (int)myVault.size()) { bleNotifyStatus("ERR INDEX"); return; }
            myVault.erase(myVault.begin() + idx);
            saveVaultToStorage();
            bleNotifyStatus("OK DELETED");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_TOTPSET)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(512);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            int idx = doc["index"] | -1;
            if (idx < 0 || idx >= (int)myVault.size()) { bleNotifyStatus("ERR INDEX"); return; }
            String label = normalizeLabel(doc["label"] | "");
            String secret = doc["secret"] | "";
            secret.trim(); secret.replace(" ", "");
            if (label.length() == 0) { bleNotifyStatus("ERR LABEL"); return; }
            if (!isValidBase32Secret(secret)) { bleNotifyStatus("ERR SECRET"); return; }
            if (findVaultIndex(label) != -1 && findVaultIndex(label) != idx) {
                bleNotifyStatus("ERR DUP"); return;
            }
            myVault[idx].label = label;
            myVault[idx].secret = secret;
            saveVaultToStorage();
            bleNotifyStatus("OK UPDATED");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_PWADD)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(1024);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            String label = sanitizePwField(doc["label"] | "", 40);
            String username = sanitizePwField(doc["username"] | "", 64);
            String password = doc["password"] | "";
            password.replace("\r", ""); password.replace("\n", "");
            password.trim();
            if (label.length() == 0) { bleNotifyStatus("ERR LABEL"); return; }
            loadPasswords();
            // Idempotent: if the label is already on the device, don't append
            // a duplicate (retried imports used to pile up copies when the
            // app couldn't read the list back).
            for (const auto& line : passwordLines) {
                String el, eu, ep;
                bleParsePasswordLine(line, el, eu, ep);
                if (el == label) { bleNotifyStatus("OK ADDED"); return; }
            }
            passwordLines.push_back(label + ":" + username + ":" + password);
            if (!savePasswordsToStorage()) { bleNotifyStatus("ERR SAVE"); return; }
            bleNotifyStatus("OK ADDED");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_PWSET)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(1024);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            int idx = doc["index"] | -1;
            loadPasswords();
            if (idx < 0 || idx >= (int)passwordLines.size()) { bleNotifyStatus("ERR INDEX"); return; }
            String label = sanitizePwField(doc["label"] | "", 40);
            String username = sanitizePwField(doc["username"] | "", 64);
            String password = doc["password"] | "";
            password.replace("\r", ""); password.replace("\n", "");
            password.trim();
            if (label.length() == 0) { bleNotifyStatus("ERR LABEL"); return; }
            passwordLines[idx] = label + ":" + username + ":" + password;
            if (!savePasswordsToStorage()) { bleNotifyStatus("ERR SAVE"); return; }
            bleNotifyStatus("OK UPDATED");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_PWDEL)) {
            if (!bleCheckAuth()) return;
            DynamicJsonDocument doc(128);
            if (deserializeJson(doc, val.c_str())) { bleNotifyStatus("ERR JSON"); return; }
            int idx = doc["index"] | -1;
            loadPasswords();
            if (idx < 0 || idx >= (int)passwordLines.size()) { bleNotifyStatus("ERR INDEX"); return; }
            passwordLines.erase(passwordLines.begin() + idx);
            if (!savePasswordsToStorage()) { bleNotifyStatus("ERR SAVE"); return; }
            bleNotifyStatus("OK DELETED");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_LOCK)) {
            // Lock immediately: drop the session; loop()'s locked branch
            // stops the radio on its next pass.
            bleNotifyStatus("LOCKED");
            deviceLocked = true;
            unlockTapCount = 0;
            return;
        }
    }

    void onRead(BLECharacteristic* pCh) override {
        String uuid = pCh->getUUID().toString();

        // device_info is public: lets the app show fw version + lock state
        // before authenticating.
        if (uuid.equalsIgnoreCase(BLE_CH_DEVICEINFO)) {
            pCh->setValue(String("KV1;") + FW_VERSION + ";" + (deviceLocked ? "1" : "0") +
                          ";" + (gSetupNeeded ? "1" : "0"));
            return;
        }

        if (!bleCheckAuth()) { pCh->setValue(""); return; }

        if (uuid.equalsIgnoreCase(BLE_CH_BLEAUTO)) {
            pCh->setValue(bleAutoEnabled ? "{\"auto\":1}" : "{\"auto\":0}");
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_TZ)) {
            pCh->setValue("{\"name\":\"" + timezoneName + "\",\"tz\":\"" + timezoneTz + "\"}");
            return;
        }

        // v1.4.8+ full vault export for backups (auth-gated above).
        // Returns [{"label":"...","secret":"..."}, ...] — the app builds a
        // Bitwarden-compatible JSON file from this + the password entries.
        if (uuid.equalsIgnoreCase(BLE_CH_VAULTEXPORT)) {
            String out;
            // Pre-reserve: building a big JSON with repeated += fragments the
            // heap; a failed realloc silently truncates the string and the
            // app then can't parse the list. (v1.4.8)
            out.reserve(myVault.size() * 80 + 2);
            out += '[';
            for (size_t i = 0; i < myVault.size(); i++) {
                if (i) out += ',';
                String l = myVault[i].label;
                String s = myVault[i].secret;
                l.replace("\\", "\\\\"); l.replace("\"", "\\\"");
                s.replace("\\", "\\\\"); s.replace("\"", "\\\"");
                out += "{\"label\":\"";
                out += l;
                out += "\",\"secret\":\"";
                out += s;
                out += "\"}";
            }
            out += ']';
            pCh->setValue(out);
            return;
        }

        if (uuid.equalsIgnoreCase(BLE_CH_VAULTCOUNT)) {
            pCh->setValue((uint16_t)myVault.size());
        } else if (uuid.equalsIgnoreCase(BLE_CH_VAULTLABEL)) {
            String out;
            out.reserve(myVault.size() * 40 + 2);
            out += '[';
            for (size_t i = 0; i < myVault.size(); i++) {
                if (i) out += ',';
                String l = myVault[i].label;
                l.replace("\\", "\\\\"); l.replace("\"", "\\\"");
                out += '\"';
                out += l;
                out += '\"';
            }
            out += ']';
            pCh->setValue(out);
        } else if (uuid.equalsIgnoreCase(BLE_CH_PWCOUNT)) {
            // passwordLines is kept in sync in RAM (loaded at boot, updated on
            // every add/edit/delete) — no filesystem re-read in the BLE task.
            pCh->setValue((uint16_t)passwordLines.size());
        } else if (uuid.equalsIgnoreCase(BLE_CH_PWLABELS)) {
            // Legacy: full label list (deprecated, use PWLPAGE). Returns empty.
            pCh->setValue("");
        } else if (uuid.equalsIgnoreCase(BLE_CH_PWLPAGE)) {
            // Paged password labels: app writes page index, reads JSON array.
            // Each page is <1KB, built on demand from passwordLines.
            pCh->setValue(buildPwLabelsPage(gPwPageReq));
        }
    }
};

static void initBleStack() {
    BLEDevice::init("EspVault");
    BLEDevice::setMTU(517);  // room for long labels/passwords in one PDU
    bleServer = BLEDevice::createServer();
    bleServer->setCallbacks(new BleServerCallbacks());

    BLEService* svc = bleServer->createService(BLE_SVC_UUID);
    static BleCharCallbacks charCb;  // one shared callback instance

    bleChAuth       = svc->createCharacteristic(BLE_CH_AUTH,       BLECharacteristic::PROPERTY_WRITE);
    bleChStatus     = svc->createCharacteristic(BLE_CH_STATUS,     BLECharacteristic::PROPERTY_NOTIFY);
    bleChDeviceInfo = svc->createCharacteristic(BLE_CH_DEVICEINFO, BLECharacteristic::PROPERTY_READ);
    bleChVaultCount = svc->createCharacteristic(BLE_CH_VAULTCOUNT, BLECharacteristic::PROPERTY_READ);
    bleChVaultLabels= svc->createCharacteristic(BLE_CH_VAULTLABEL,BLECharacteristic::PROPERTY_READ);
    bleChTotpRequest= svc->createCharacteristic(BLE_CH_TOTPREQ,    BLECharacteristic::PROPERTY_WRITE);
    bleChTotpCode   = svc->createCharacteristic(BLE_CH_TOTPCODE,   BLECharacteristic::PROPERTY_NOTIFY);
    bleChPwCount    = svc->createCharacteristic(BLE_CH_PWCOUNT,    BLECharacteristic::PROPERTY_READ);
    bleChPwLabels   = svc->createCharacteristic(BLE_CH_PWLABELS,   BLECharacteristic::PROPERTY_READ);
    bleChPwPage     = svc->createCharacteristic(BLE_CH_PWLPAGE,     BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    bleChPwRequest  = svc->createCharacteristic(BLE_CH_PWREQ,      BLECharacteristic::PROPERTY_WRITE);
    bleChPwEntry    = svc->createCharacteristic(BLE_CH_PWENTRY,     BLECharacteristic::PROPERTY_NOTIFY);
    bleChTimeSync   = svc->createCharacteristic(BLE_CH_TIMESYNC,   BLECharacteristic::PROPERTY_WRITE);
    bleChAddTotp    = svc->createCharacteristic(BLE_CH_ADDTOTP,    BLECharacteristic::PROPERTY_WRITE);
    bleChLock       = svc->createCharacteristic(BLE_CH_LOCK,        BLECharacteristic::PROPERTY_WRITE);
    bleChTotpDel    = svc->createCharacteristic(BLE_CH_TOTPDEL,     BLECharacteristic::PROPERTY_WRITE);
    bleChTotpSet    = svc->createCharacteristic(BLE_CH_TOTPSET,     BLECharacteristic::PROPERTY_WRITE);
    bleChPwAdd      = svc->createCharacteristic(BLE_CH_PWADD,       BLECharacteristic::PROPERTY_WRITE);
    bleChPwSet      = svc->createCharacteristic(BLE_CH_PWSET,       BLECharacteristic::PROPERTY_WRITE);
    bleChPwDel      = svc->createCharacteristic(BLE_CH_PWDEL,       BLECharacteristic::PROPERTY_WRITE);
    bleChSetupPw    = svc->createCharacteristic(BLE_CH_SETUPPW,     BLECharacteristic::PROPERTY_WRITE);
    bleChSetupWifi  = svc->createCharacteristic(BLE_CH_SETUPWIFI,   BLECharacteristic::PROPERTY_WRITE);
    bleChSetupDone  = svc->createCharacteristic(BLE_CH_SETUPDONE,   BLECharacteristic::PROPERTY_WRITE);
    bleChBleAuto    = svc->createCharacteristic(BLE_CH_BLEAUTO,     BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ);
    bleChPwChange   = svc->createCharacteristic(BLE_CH_PWCHANGE,    BLECharacteristic::PROPERTY_WRITE);
    bleChWifiSet    = svc->createCharacteristic(BLE_CH_WIFISET,     BLECharacteristic::PROPERTY_WRITE);
    bleChTz         = svc->createCharacteristic(BLE_CH_TZ,          BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ);
    bleChVaultExport= svc->createCharacteristic(BLE_CH_VAULTEXPORT, BLECharacteristic::PROPERTY_READ);

    BLECharacteristic* all[] = { bleChAuth, bleChStatus, bleChDeviceInfo,
        bleChVaultCount, bleChVaultLabels, bleChTotpRequest, bleChTotpCode,
        bleChPwCount, bleChPwLabels, bleChPwPage, bleChPwRequest, bleChPwEntry,
        bleChTimeSync, bleChAddTotp, bleChLock,
        bleChTotpDel, bleChTotpSet, bleChPwAdd, bleChPwSet, bleChPwDel,
        bleChSetupPw, bleChSetupWifi, bleChSetupDone, bleChBleAuto,
        bleChPwChange, bleChWifiSet, bleChTz, bleChVaultExport };
    for (auto* ch : all) ch->setCallbacks(&charCb);

    // Client Characteristic Configuration descriptors for notifications.
    bleChStatus->addDescriptor(new BLE2902());
    bleChTotpCode->addDescriptor(new BLE2902());
    bleChPwEntry->addDescriptor(new BLE2902());

    svc->start();

    BLEAdvertising* adv = bleServer->getAdvertising();
    adv->addServiceUUID(BLE_SVC_UUID);
    adv->setScanResponse(true);
}

// Start the BLE radio (advertising). No-op if already active or locked.
void bleStart(bool force = false) {
    if (bleActive || (deviceLocked && !force)) return;
    if (!bleStackInit) { initBleStack(); bleStackInit = true; }
    bleAuthed = false;
    bleLastActivity = millis();
    bleServer->getAdvertising()->start();
    bleActive = true;
}

// Normal entry: never start the radio while the device is locked.
// Portal mode passes force=true (physical button hold = deliberate user
// action); secret characteristics still refuse while locked via bleCheckAuth.

// Stop the radio, dropping any connection. Idempotent.
// If connected and reason != nullptr, the reason is notified first.
void bleStop(const char* reason) {
    if (!bleActive) return;
    bleActive = false;
    bleAuthed = false;
    if (bleServer) {
        if (bleClientConnected) {
            if (reason) { bleNotifyStatus(reason); delay(120); }
            // v1 is single-client; drop every server connection.
            for (auto& kv : bleServer->getPeerDevices(false)) {
                bleServer->disconnect(kv.first);
            }
            bleClientConnected = false;
        }
        bleServer->getAdvertising()->stop();
    }
}

// Called from loop(): enforces the idle timeout.
void blePoll() {
    if (!bleActive) return;
    if (deviceLocked) { bleStop("LOCKED"); return; }
    if (millis() - bleLastActivity > BLE_IDLE_TIMEOUT_MS) {
        bleStop("TIMEOUT");
    }
}

// Portal API: GET -> {"auto": bool}, POST {"auto": bool} -> save.
void handleApiBle() {
    if (!requirePortalAuth()) return;
    if (server.method() == HTTP_GET) {
        DynamicJsonDocument doc(128);
        doc["auto"] = bleAutoEnabled;
        doc["active"] = bleActive;
        String output;
        serializeJson(doc, output);
        server.send(200, "application/json", output);
    } else if (server.method() == HTTP_POST) {
        if (server.hasArg("plain")) {
            DynamicJsonDocument doc(128);
            if (!deserializeJson(doc, server.arg("plain"))) {
                bleAutoEnabled = doc["auto"] | false;
                saveBleSettings();
                server.send(200, "text/plain", "Bluetooth settings saved");
                return;
            }
        }
        server.send(400, "text/plain", "Invalid JSON");
    }
}

// Portal-mode OLED screen: SSID, WiFi/web password (same value), and IP.
// The password is shown because the default ("espvault") is used until the
// user changes it after first login.
void drawPortalScreen() {
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_04b_03_tr);
    u8g2.drawStr(0, 8, "PORTAL MODE");

    String ssidLine = portalSSID;
    if (u8g2.getStrWidth(ssidLine.c_str()) > 72) {
        ssidLine = portalSSID.substring(0, 12) + "...";
    }
    u8g2.drawStr(0, 18, ssidLine.c_str());

    String pwLine = "PW:" + portalPassword;
    if (u8g2.getStrWidth(pwLine.c_str()) > 72) {
        pwLine = "PW:" + portalPassword.substring(0, 10) + "..";
    }
    u8g2.drawStr(0, 28, pwLine.c_str());

    u8g2.drawStr(0, 38, "IP:192.168.4.1");
    u8g2.sendBuffer();
}

// ---------- First-boot setup + BLE-first portal (v1.4.5) ----------
#define SETUP_DONE_FILE "/setup.done"
bool gSetupNeeded = false;  // cached at boot; true until initial setup completes

void markSetupDone() {
    File f = LittleFS.open(SETUP_DONE_FILE, "w");
    if (f) { f.println("1"); f.close(); }
}

// True when the device has never been set up. A device that already has a
// personalized portal password counts as set up (migration for pre-1.4.5).
bool isSetupNeeded() {
    if (LittleFS.exists(SETUP_DONE_FILE)) return false;
    if (!portalPasswordMustChange && portalPassword.length() >= 8 &&
        portalPassword != "espvault") {
        markSetupDone();
        return false;
    }
    return true;
}

// Portal mode is BLE-first: the radio is always on here — it is the primary
// way to talk to the device. A 3s button hold switches to the WiFi AP web
// portal; holding to 6s leaves portal mode. On first boot the device enters
// the BLE portal automatically so the app can do initial setup.
static bool portalUseWifi = false;

void drawBlePortalScreen(bool firstBoot) {
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_04b_03_tr);
    u8g2.drawStr(0, 10, firstBoot ? "SETUP" : "PORTAL");
    u8g2.drawStr(0, 20, "BLE:EspVault");
    u8g2.drawStr(0, 30, deviceLocked ? "LOCKED" : "UNLOCKED");
    u8g2.drawStr(0, 40, "3s:WiFi 6s:exit");
    u8g2.sendBuffer();
}

void portalStartBle() {
    server.close();
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_OFF);
    delay(100);
    if (!bleStackInit) { initBleStack(); bleStackInit = true; }
    bleAuthed = false;
    bleLastActivity = millis();
    bleServer->getAdvertising()->start();
    bleActive = true;
    portalUseWifi = false;
    Serial.println("Portal: BLE mode");
}

void portalStartWifi() {
    bleStop(nullptr);
    WiFi.disconnect(true);
    WiFi.mode(WIFI_OFF);
    delay(100);
    WiFi.mode(WIFI_AP);
    WiFi.softAP(portalSSID.c_str(), portalPassword.c_str());
    Serial.printf("Portal AP started: %s\n", portalSSID.c_str());
    server.begin();
    portalUseWifi = true;
    drawPortalScreen();
}

void registerPortalRoutes() {
    server.on("/", HTTP_GET, handleRoot);
    // Web login (cookie session; every /api/* below requires it)
    server.on("/api/login", HTTP_POST, handleApiLogin);
    server.on("/api/logout", HTTP_POST, handleApiLogout);
    server.on("/api/auth-check", HTTP_GET, handleApiAuthCheck);
    server.on("/api/list", HTTP_GET, handleApiList);
    server.on("/api/get", HTTP_GET, handleApiGet);
    server.on("/api/add", HTTP_GET, handleApiAdd);
    server.on("/api/edit", HTTP_GET, handleApiEdit);
    server.on("/api/delete", HTTP_GET, handleApiDelete);
    server.on("/upload", HTTP_POST, handleVaultUploadDone, handleVaultUpload);
    // File browser routes
    server.on("/api/list-files", HTTP_GET, handleApiListFiles);
    server.on("/api/view-file", HTTP_GET, handleApiViewFile);
    server.on("/api/save-file", HTTP_POST, handleApiSaveFile);
    server.on("/api/delete-file", HTTP_GET, handleApiDeleteFile);
    server.on("/api/upload-file", HTTP_POST, []() {
        if (!isPortalAuthenticated()) { server.send(401, "text/plain", "Unauthorized"); return; }
        server.send(200, "text/plain", "OK");
    }, handleFileUpload);
    server.on("/api/storage", HTTP_GET, handleApiStorage);
    // Vaultwarden routes
    server.on("/api/auth-codes", HTTP_GET, handleApiAuthCodes);
    server.on("/api/time-info", HTTP_GET, handleApiTimeInfo);
    server.on("/api/device-name", HTTP_GET, handleApiDeviceName);
    server.on("/api/device-name", HTTP_POST, handleApiDeviceName);
    server.on("/api/passwords", HTTP_GET, handleApiPasswords);
    server.on("/api/set-time", HTTP_POST, handleApiSetTime);
    server.on("/api/debug-time", HTTP_GET, handleApiDebugTime);
    server.on("/api/wifi-settings", HTTP_GET, handleApiWiFiSettings);
    server.on("/api/wifi-settings", HTTP_POST, handleApiWiFiSettings);
    server.on("/api/portal-settings", HTTP_GET, handleApiPortalSettings);
    server.on("/api/portal-settings", HTTP_POST, handleApiPortalSettings);
    server.on("/api/timezone", HTTP_GET, handleApiTimezone);
    server.on("/api/timezone", HTTP_POST, handleApiTimezone);
    server.on("/api/ble", HTTP_GET, handleApiBle);
    server.on("/api/ble", HTTP_POST, handleApiBle);

    generatePortalAuthToken();
    Serial.println("Portal auth token generated (web login required for /api/*)");
    const char* headerkeys[] = {"Cookie"};
    server.collectHeaders(headerkeys, 1);
}

void runPortal(bool firstBoot) {
    Serial.println(firstBoot ? "First boot: BLE setup portal" : "Starting portal mode (BLE)...");
    registerPortalRoutes();
    portalStartBle();
    drawBlePortalScreen(firstBoot);

    // Portal window: 45s, extended while a station/client stays connected,
    // never longer than 10 minutes.
    unsigned long portalStart = millis();
    unsigned long holdStart = 0;
    bool wasLow = false;
    bool holdFired = false;
    unsigned long tapWindowStart = 0;
    uint8_t tapCount = 0;
    String toast = "";
    unsigned long toastUntil = 0;

    while ((millis() - portalStart < 45000 || WiFi.softAPgetStationNum() > 0 || bleClientConnected) &&
           millis() - portalStart < 600000) {
        if (portalUseWifi) server.handleClient();

        bool low = (digitalRead(BUTTON_PIN) == LOW);
        if (low && !wasLow) { holdStart = millis(); holdFired = false; }
        if (!low && wasLow) {
            // Release: count taps for triple-tap unlock (not on first boot —
            // there is nothing to unlock before setup).
            if (!firstBoot && deviceLocked) {
                if (millis() - tapWindowStart < 800) tapCount++; else tapCount = 1;
                tapWindowStart = millis();
                if (tapCount >= 3) {
                    deviceLocked = false;
                    tapCount = 0;
                    toast = "UNLOCKED";
                    toastUntil = millis() + 1200;
                    Serial.println("Portal: unlocked via triple-tap");
                }
            }
        }
        if (low && !holdFired && millis() - holdStart >= 3000) {
            holdFired = true;
            tapCount = 0;
            // 3s hold: switch portal transport BLE <-> WiFi.
            if (portalUseWifi) {
                portalStartBle();
                drawBlePortalScreen(firstBoot);
                toast = "BLE MODE";
            } else {
                portalStartWifi();
                toast = "WIFI MODE";
            }
            toastUntil = millis() + 1200;
        }
        if (low && millis() - holdStart >= 6000) {
            if (firstBoot) {
                // Nowhere to go without setup — reboot back into setup.
                Serial.println("Setup incomplete: rebooting into setup");
                ESP.restart();
            }
            break;
        }
        wasLow = low;

        if (millis() < toastUntil) {
            u8g2.clearBuffer();
            u8g2.setFont(u8g2_font_04b_03_tr);
            u8g2.drawStr(0, 20, toast.c_str());
            u8g2.sendBuffer();
        } else if (portalUseWifi) {
            drawPortalScreen();
        } else {
            drawBlePortalScreen(firstBoot);
        }
        delay(10);
    }

    // Clean up both transports before the normal boot continues.
    server.close();
    WiFi.softAPdisconnect(true);
    bleStop(nullptr);
    WiFi.mode(WIFI_STA);  // clean STA state for the normal boot that follows
    delay(1000);
}
void setup() {
    Serial.begin(115200);
    // Wait briefly for a USB host, but never hang the boot when running on
    // battery power with no USB connected.
    {
        unsigned long serialWaitStart = millis();
        while (!Serial && millis() - serialWaitStart < 1500) { delay(10); }
    }
    Serial.println("\n--- ESP32 Esp Vault Starting ---");

    pinMode(BUTTON_PIN, INPUT_PULLUP);
    Wire.begin(5, 6);
    u8g2.begin();

    // IMPORTANT: Arduino IDE partition scheme MUST include LittleFS, otherwise
    // this halts with "FS ERROR" at every boot.
    //
    // NOTE: begin(false) on purpose — auto-format would silently WIPE the
    // vault on any filesystem hiccup. Formatting requires a deliberate 3s
    // button hold.
    if (!LittleFS.begin(false)) {
        u8g2.clearBuffer(); u8g2.setFont(u8g2_font_04b_03_tr);
        u8g2.drawStr(0, 10, "FS ERROR");
        u8g2.drawStr(0, 22, "Hold BTN 3s");
        u8g2.drawStr(0, 32, "to format");
        u8g2.sendBuffer();
        unsigned long fsHoldStart = 0;
        bool fsWasLow = false;
        while (true) {
            bool low = (digitalRead(BUTTON_PIN) == LOW);
            if (low && !fsWasLow) fsHoldStart = millis();
            if (low && millis() - fsHoldStart >= 3000) {
                u8g2.clearBuffer();
                u8g2.drawStr(0, 20, "FORMATTING...");
                u8g2.sendBuffer();
                LittleFS.format();
                ESP.restart();
            }
            fsWasLow = low;
            delay(20);
        }
    }

    // Load device name and offset BEFORE displaying boot screen
    loadDeviceName();
    loadWiFiSettings();
    loadPortalSettings();
    loadTimezone();  // Load timezone settings
    loadBleSettings();
    loadVaultFromStorage();
    loadPasswords();

    // Start WiFi connection BEFORE boot screen (parallel operation).
    // Networks come from the portal web UI; blanks are skipped so WiFi
    // credentials never need to live in this source file.
    bool wifiConfigured = false;
    Serial.println("Starting WiFi connection...");
    if (wifiSSID1.length() > 0) { wifiMulti.addAP(wifiSSID1.c_str(), wifiPassword1.c_str()); wifiConfigured = true; }
    if (wifiSSID2.length() > 0) { wifiMulti.addAP(wifiSSID2.c_str(), wifiPassword2.c_str()); wifiConfigured = true; }

    if (wifiConfigured) {
        wifiMulti.run();  // non-blocking begin
    } else {
        Serial.println("No WiFi networks configured - offline mode (configure via portal web UI)");
    }

    // Configure NTP servers early (will start syncing as soon as WiFi connects)
    configTime(0, 0, "pool.ntp.org", "ca.pool.ntp.org");
    applyTimezone();  // configTime resets TZ; re-apply our POSIX TZ for localtime_r
    Serial.println("NTP servers configured, will sync when WiFi ready");
    
    // Show device name on boot - bigger font, centered
    // WiFi/NTP will continue connecting in the background during this time
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_9x15_tr);  // Bigger font
    int textWidth = u8g2.getStrWidth(deviceName.c_str());
    
    bool portalRequested = false;
    
    if (textWidth <= 72) {
        // If it fits, center it and show for full duration
        int startX = (72 - textWidth) / 2;
        u8g2.drawStr(startX, 25, deviceName.c_str());
        u8g2.sendBuffer();
        
        // Check for portal mode during the 2-second display
        // Also continue WiFi connection attempts in background
        unsigned long bootStartTime = millis();
        while (millis() - bootStartTime < 2000) {
            if (digitalRead(BUTTON_PIN) == LOW) {
                portalRequested = true;
            }
            if (wifiConfigured) wifiMulti.run(); // Continue WiFi connection attempts
            delay(10);
        }
    } else {
        // If too long, scroll it completely at least once
        int scrollX = 72;
        unsigned long bootStartTime = millis();
        unsigned long lastScrollTime = millis();
        const int scrollSpeedMs = 20;  // Faster scroll speed (was 30)
        bool hasCompletedOneLoop = false;
        
        // Scroll until complete name has been shown at least once
        while (!hasCompletedOneLoop || (millis() - bootStartTime < 3000)) {
            u8g2.clearBuffer();
            u8g2.setFont(u8g2_font_9x15_tr);
            u8g2.drawStr(scrollX, 25, deviceName.c_str());
            
            if (millis() - lastScrollTime > scrollSpeedMs) {
                scrollX--;
                if (scrollX < -textWidth) {
                    scrollX = 72;
                    hasCompletedOneLoop = true; // Name has scrolled completely once
                }
                lastScrollTime = millis();
            }
            
            // Check for portal mode during scrolling
            if (digitalRead(BUTTON_PIN) == LOW) {
                portalRequested = true;
            }
            
            // Continue WiFi connection attempts during scroll
            if (wifiConfigured) wifiMulti.run();
            
            u8g2.sendBuffer();
            delay(10);
            
            // Break if we've completed at least one full scroll and minimum time passed
            if (hasCompletedOneLoop && (millis() - bootStartTime >= 2000)) {
                break;
            }
        }
    }

    gSetupNeeded = isSetupNeeded();
    if (gSetupNeeded || portalRequested) {
        runPortal(gSetupNeeded);
    }

    // WiFi was already started before boot screen, check connection status
    // Continue connection attempts if not yet connected
    int syncAttempts = 0;
    bool wifiOk = false;
    if (wifiConfigured) {
        Serial.println("Checking WiFi connection status...");
        while (wifiMulti.run() != WL_CONNECTED && syncAttempts < 10) {
            delay(500);
            syncAttempts++;
        }
        wifiOk = (WiFi.status() == WL_CONNECTED);
    } else {
        Serial.println("WiFi not configured - skipping connection (set it via portal web UI)");
    }

    if (wifiOk) {
        // Get WiFi info
        lastWifiSSID = WiFi.SSID();
        lastWifiBSSID = WiFi.BSSIDstr();
        Serial.printf("WiFi Connected: %s\n", lastWifiSSID.c_str());

        // Show sync screen
        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_04b_03_tr);
        u8g2.drawStr(0, 15, "SYNCING TIME...");
        u8g2.sendBuffer();

        // Check if time is already synced (from parallel operation)
        time_t now;
        time(&now);
        struct tm* t = gmtime(&now);
        bool timeSynced = (t->tm_year > 120); // Already synced?

        if (timeSynced) {
            Serial.println("✓ Time already synced during boot!");
            Serial.printf("  UTC: %04d-%02d-%02d %02d:%02d:%02d\n",
                t->tm_year + 1900, t->tm_mon + 1, t->tm_mday,
                t->tm_hour, t->tm_min, t->tm_sec);
        } else {
            // Need to wait for NTP sync
            Serial.println("Waiting for NTP sync...");
            for (int attempt = 0; attempt < 30 && !timeSynced; attempt++) {
                time(&now);
                t = gmtime(&now);

                if (t->tm_year > 120) {  // Year > 2020
                    timeSynced = true;
                    Serial.println("✓ NTP sync successful");
                    Serial.printf("  UTC: %04d-%02d-%02d %02d:%02d:%02d\n",
                        t->tm_year + 1900, t->tm_mon + 1, t->tm_mday,
                        t->tm_hour, t->tm_min, t->tm_sec);
                    break;
                }
                delay(500);
            }
        }

        if (timeSynced) {
            timeIsFresh = true;
            saveLastKnownTime(now);
            // Show success briefly
            u8g2.clearBuffer();
            u8g2.setFont(u8g2_font_04b_03_tr);
            u8g2.drawStr(0, 20, "TIME SYNCED!");
            u8g2.sendBuffer();
            delay(1000);
        } else if (restoreLastKnownTime()) {
            // NTP failed but we have a previous sync to fall back on.
            // The clock is now behind by however long the device was off;
            // timeIsFresh=false makes the display flag it.
            timeIsFresh = false;
            Serial.println("✗ NTP sync failed - restored last known time (may be stale)");
            u8g2.clearBuffer();
            u8g2.setFont(u8g2_font_04b_03_tr);
            u8g2.drawStr(0, 10, "TIME RESTORED");
            u8g2.drawStr(0, 25, "May be stale!");
            u8g2.sendBuffer();
            delay(3000);
        } else {
            Serial.println("✗ NTP sync failed on all servers - no fallback time");
            Serial.println("TOTP codes may be incorrect until time is synced!");
            Serial.println("Workaround: Access web UI to sync time from browser");

            // Show warning on screen
            u8g2.clearBuffer();
            u8g2.setFont(u8g2_font_04b_03_tr);
            u8g2.drawStr(0, 10, "TIME SYNC FAIL");
            u8g2.drawStr(0, 25, "Use Web UI to");
            u8g2.drawStr(0, 35, "sync time");
            u8g2.sendBuffer();
            delay(3000);
        }

        // Log final time
        time(&now);
        struct tm* utcTime = gmtime(&now);
        Serial.printf("\nFinal system UTC time: %04d-%02d-%02d %02d:%02d:%02d\n",
            utcTime->tm_year + 1900, utcTime->tm_mon + 1, utcTime->tm_mday,
            utcTime->tm_hour, utcTime->tm_min, utcTime->tm_sec);

        // Show local time (POSIX TZ, DST-aware) for verification
        struct tm localTime;
        localtime_r(&now, &localTime);
        Serial.printf("Local time (%s): %04d-%02d-%02d %02d:%02d:%02d\n",
            timezoneName.c_str(),
            localTime.tm_year + 1900, localTime.tm_mon + 1, localTime.tm_mday,
            localTime.tm_hour, localTime.tm_min, localTime.tm_sec);
    } else {
        if (wifiConfigured) {
            Serial.println("WiFi connection failed");
        } else {
            Serial.println("WiFi not configured - offline mode");
        }
        if (restoreLastKnownTime()) {
            timeIsFresh = false;
            Serial.println("Restored last known time (may be stale)");
        } else {
            Serial.println("No fallback time available - TOTP codes may be incorrect!");
        }
        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_04b_03_tr);
        u8g2.drawStr(0, 10, wifiConfigured ? "NO WIFI" : "WIFI NOT SET");
        u8g2.drawStr(0, 25, "See portal to");
        u8g2.drawStr(0, 35, "configure");
        u8g2.sendBuffer();
        delay(2000);
    }
    WiFi.disconnect(true); WiFi.mode(WIFI_OFF);
    
    // Show initial lock screen
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_9x15_tr);
    u8g2.drawStr(10, 15, "LOCKED");
    u8g2.setFont(u8g2_font_04b_03_tr);
    String tapIndicator = "- - -";
    int indicatorWidth = u8g2.getStrWidth(tapIndicator.c_str());
    u8g2.drawStr((72 - indicatorWidth) / 2, 35, tapIndicator.c_str());
    u8g2.sendBuffer();
}

void loop() {
    blePoll();  // BLE idle timeout / lock enforcement

    if (myVault.empty()) {
        u8g2.clearBuffer(); u8g2.setFont(u8g2_font_04b_03_tr);
        u8g2.drawStr(0, 20, "NO KEYS FOUND"); u8g2.sendBuffer();
        return;
    }

    bool currentButtonState = digitalRead(BUTTON_PIN);
    unsigned long milliNow = millis();
    
    // Handle PIN/Lock screen
    if (deviceLocked) {
        // Locking kills any BLE session and stops the radio.
        if (bleActive) bleStop("LOCKED");
        // Check for auto-lock timeout reset
        if (milliNow - lastTapTime > TAP_TIMEOUT_MS) {
            unlockTapCount = 0;
        }
        
        // Always show lock screen when locked
        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_9x15_tr);
        u8g2.drawStr(10, 15, "LOCKED");
        
        // Draw tap indicators
        String tapIndicator = "";
        for (int i = 0; i < UNLOCK_TAP_REQUIRED; i++) {
            if (i < unlockTapCount) {
                tapIndicator += "*";
            } else {
                tapIndicator += "-";
            }
        }
        u8g2.setFont(u8g2_font_04b_03_tr);
        int indicatorWidth = u8g2.getStrWidth(tapIndicator.c_str());
        u8g2.drawStr((72 - indicatorWidth) / 2, 35, tapIndicator.c_str());
        u8g2.sendBuffer();
        
        // Handle tap to unlock (debounced; ~20ms poll so quick taps register)
        if (currentButtonState == LOW && lastButtonState == HIGH &&
            milliNow - lastTapTime > TAP_DEBOUNCE_MS) {
            unlockTapCount++;
            lastTapTime = milliNow;

            if (unlockTapCount >= UNLOCK_TAP_REQUIRED) {
                deviceLocked = false;
                unlockTapCount = 0;
                lastActivityTime = milliNow;

                // Show unlocked confirmation
                u8g2.clearBuffer();
                u8g2.setFont(u8g2_font_9x15_tr);
                u8g2.drawStr(8, 20, "UNLOCKED");
                u8g2.sendBuffer();
                delay(500);

                // BLE radio comes up on unlock when auto mode is enabled.
                if (bleAutoEnabled) bleStart();
            }
        }
        lastButtonState = currentButtonState;
        delay(20);
        return;
    }
    
    // Auto-lock after 5 minutes of inactivity
    if (milliNow - lastActivityTime > AUTO_LOCK_MS) {
        deviceLocked = true;
        unlockTapCount = 0;
        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_9x15_tr);
        u8g2.drawStr(4, 20, "AUTO-LOCK");
        u8g2.sendBuffer();
        delay(1000);
        return;
    }
    
    // Update activity time on any button press
    if (currentButtonState == LOW) {
        lastActivityTime = milliNow;
    }

    // Press edge: start tracking this press
    if (currentButtonState == LOW && lastButtonState == HIGH) {
        pressStartTime = milliNow;
        holdActionFired = false;
    }

    // 3s hold (fires exactly once per press): toggle TOTP/password mode.
    // (Bluetooth lives in portal mode: 3s hold there toggles the radio.)
    if (currentButtonState == LOW && !holdActionFired &&
        milliNow - pressStartTime >= HOLD_TOGGLE_MS) {
        holdActionFired = true;
        togglePasswordMode();
    }

    // Release edge: a short press (no hold fired) advances to the next item.
    // Advancing on release means starting a hold never also skips an entry.
    if (currentButtonState == HIGH && lastButtonState == LOW) {
        if (!holdActionFired && milliNow - pressStartTime < TAP_MAX_MS) {
            if (inPasswordMode) {
                // In password mode, advance to next password
                if (passwordLines.size() > 0) {
                    passwordIndex = (passwordIndex + 1) % passwordLines.size();
                    // Reset scroll positions when switching passwords
                    usernameScrollX = 0;
                    usernameScrollTime = 0;
                    passwordScrollX = 0;
                    passwordScrollTime = 0;
                }
            } else {
                // In TOTP mode, advance to next vault key
                activeIndex = (activeIndex + 1) % myVault.size();
            }
            labelScrollX = 0;
        }
    }
    lastButtonState = currentButtonState;

    // UTC epoch for TOTP; localtime_r applies the POSIX TZ (DST-aware) for display
    time_t utcNow;
    time(&utcNow);
    struct tm timeinfo;
    localtime_r(&utcNow, &timeinfo);

    if (inPasswordMode) {
        displayPasswordLine(utcNow, timeinfo);
    } else {
        displayTOTPLine(utcNow, timeinfo);
    }
    delay(20); 
}

void displayTOTPLine(time_t now, struct tm timeinfo) {
    // CRITICAL: 'now' is UTC time - use it directly for TOTP
    // 'timeinfo' contains broken-down local time for DISPLAY ONLY
    
    int secondsRemaining = 30 - (now % 30);
    int barPixels = map(secondsRemaining, 0, 30, 0, 42); 

    const char* currentSecret = myVault[activeIndex].secret.c_str();
    
    // Base32 decode the secret
    String decodedSecret;
    int bitsBuffer = 0;
    int bitsCount = 0;
    for (size_t i = 0; i < strlen(currentSecret); i++) {
        char c = currentSecret[i];
        if (c >= 'A' && c <= 'Z') {
            bitsBuffer = (bitsBuffer << 5) | (c - 'A');
            bitsCount += 5;
        } else if (c >= 'a' && c <= 'z') {
            bitsBuffer = (bitsBuffer << 5) | (c - 'a');
            bitsCount += 5;
        } else if (c >= '2' && c <= '7') {
            bitsBuffer = (bitsBuffer << 5) | (c - '2' + 26);
            bitsCount += 5;
        }
        
        if (bitsCount >= 8) {
            bitsCount -= 8;
            decodedSecret += (char)((bitsBuffer >> bitsCount) & 0xFF);
        }
    }
    
    // Standard TOTP
    TOTP totpGenerator((uint8_t*)decodedSecret.c_str(), decodedSecret.length());
    String generatedCode = totpGenerator.getCode(now);
    String brokenCode = generatedCode.substring(0, 3) + " " + generatedCode.substring(3, 6);

    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_04b_03_tr);
    
    // Show label
    int textWidth = u8g2.getStrWidth(myVault[activeIndex].label.c_str());
    
    if (textWidth > 72) {
        u8g2.drawStr(labelScrollX, 10, myVault[activeIndex].label.c_str());
        if (millis() - lastScrollTime > scrollSpeedMs) {
            labelScrollX--; if (labelScrollX < -textWidth) labelScrollX = 72; 
            lastScrollTime = millis();
        }
    } else {
        u8g2.drawStr(0, 10, myVault[activeIndex].label.c_str());
    }

    u8g2.setCursor(0, 20); 
    if(secondsRemaining < 10) u8g2.print("0"); 
    u8g2.print(secondsRemaining); u8g2.print("s");
    u8g2.drawFrame(22, 14, 44, 6); u8g2.drawBox(23, 15, barPixels, 4); 

    u8g2.setFont(u8g2_font_6x12_tr); u8g2.drawStr(0, 31, brokenCode.c_str());

    // Display local time (from timeinfo parameter with offset already applied)
    int displayHour = timeinfo.tm_hour;
    const char* ampm = "AM";
    if (displayHour >= 12) { ampm = "PM"; if (displayHour > 12) displayHour -= 12; }
    else if (displayHour == 0) displayHour = 12;

    char timeString[18];
    // "!" prefix flags a restored-but-possibly-stale clock (NTP failed at boot)
    snprintf(timeString, sizeof(timeString), "%s%02d:%02d:%02d%s",
             timeIsFresh ? "" : "!", displayHour, timeinfo.tm_min, timeinfo.tm_sec, ampm);
    u8g2.setFont(u8g2_font_04b_03_tr); u8g2.drawStr(0, 40, timeString);
    // Tiny "BT" flag bottom-right while the BLE radio is on.
    if (bleActive) u8g2.drawStr(62, 40, "BT");

    u8g2.sendBuffer(); 
    delay(20); 
}

void displayPasswordLine(time_t now, struct tm timeinfo) {
    if (passwordLines.size() == 0) {
        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_04b_03_tr);
        u8g2.drawStr(0, 20, "NO PASSWORDS");
        u8g2.sendBuffer();
        return;
    }

    String line = passwordLines[passwordIndex];
    
    // Parse line: assume format "label:username:password" or "url username password"
    String label = "Login";
    String username = "";
    String password = "";
    
    if (line.indexOf(':') >= 0) {
        // Format: label:username:password
        int firstColon = line.indexOf(':');
        int secondColon = line.indexOf(':', firstColon + 1);
        label = line.substring(0, firstColon);
        if (secondColon > 0) {
            username = line.substring(firstColon + 1, secondColon);
            password = line.substring(secondColon + 1);
        } else {
            username = line.substring(firstColon + 1);
        }
    } else {
        // Format: label username password (or just username password)
        int firstSpace = line.indexOf(' ');
        if (firstSpace > 0) {
            label = line.substring(0, firstSpace);
            String rest = line.substring(firstSpace + 1);
            int secondSpace = rest.indexOf(' ');
            if (secondSpace > 0) {
                username = rest.substring(0, secondSpace);
                password = rest.substring(secondSpace + 1);
            } else {
                username = rest;
            }
        } else {
            label = "Login";
            username = line;
        }
    }

    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_04b_03_tr);
    int textWidth = u8g2.getStrWidth(label.c_str());
    
    // Scroll label if too wide
    if (textWidth > 72) {
        u8g2.drawStr(labelScrollX, 10, label.c_str());
        if (millis() - lastScrollTime > scrollSpeedMs) {
            labelScrollX--;
            if (labelScrollX < -textWidth) labelScrollX = 72; 
            lastScrollTime = millis();
        }
    } else {
        u8g2.drawStr(0, 10, label.c_str());
    }

    // Display username on line 2 (scroll if too wide)
    u8g2.setFont(u8g2_font_6x12_tr);
    String usernameDisplay = username;
    int usernameWidth = u8g2.getStrWidth(username.c_str());
    if (usernameWidth > 72) {
        // Username needs scrolling - show it on line 2 with scroll
        u8g2.drawStr(usernameScrollX, 25, usernameDisplay.c_str());
        if (millis() - usernameScrollTime > scrollSpeedMs) {
            usernameScrollX--;
            if (usernameScrollX < -usernameWidth) usernameScrollX = 72;
            usernameScrollTime = millis();
        }
    } else {
        u8g2.drawStr(0, 25, usernameDisplay.c_str());
    }

    // Display password on line 3 (scroll if too wide)
    if (password.length() > 0) {
        String pwdDisplay = password;
        int pwdWidth = u8g2.getStrWidth(password.c_str());
        if (pwdWidth > 72) {
            // Password needs scrolling
            u8g2.drawStr(passwordScrollX, 35, pwdDisplay.c_str());
            if (millis() - passwordScrollTime > scrollSpeedMs) {
                passwordScrollX--;
                if (passwordScrollX < -pwdWidth) passwordScrollX = 72;
                passwordScrollTime = millis();
            }
        } else {
            u8g2.drawStr(0, 35, pwdDisplay.c_str());
        }
    }

    u8g2.sendBuffer(); 
    delay(20); 
}