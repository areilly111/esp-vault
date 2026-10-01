package com.keychainvault.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bitwarden JSON export parsing — mirrors ~/workspace/esp32c3-key/run.py.
 *
 * The export is parsed entirely on-device; nothing is uploaded anywhere.
 * TOTP entries: items[].login.totp may be an otpauth:// URI (the secret=
 * parameter is extracted) or a raw base32 secret.
 */
object Bitwarden {

    data class TotpImport(val label: String, val secret: String)
    data class PwImport(val label: String, val username: String, val password: String)
    data class Parsed(val totps: List<TotpImport>, val passwords: List<PwImport>)

    private val BASE32_RE = Regex("^[A-Z2-7]+$")
    private val SECRET_PARAM = Regex("secret=([A-Za-z0-9=]+)", RegexOption.IGNORE_CASE)

    /** run.py normalize_label(): UPPER, spaces -> _, strip odd chars, max 48. */
    fun normalizeLabel(name: String?): String {
        var label = (name ?: "UNKNOWN").uppercase().replace(" ", "_")
        label = label.replace(Regex("[^A-Z0-9_\\-]"), "")
        if (label.isEmpty()) label = "UNKNOWN"
        return label.take(48)
    }

    /** run.py sanitize_field(): drop newlines, ':' -> '-', strip, truncate. */
    fun sanitizeField(s: String?, maxLen: Int): String {
        var t = (s ?: "").replace("\r", "").replace("\n", "").replace(":", "-")
        t = t.trim()
        return t.take(maxLen)
    }

    /** Extract + validate the base32 secret from a login.totp field. Null if unusable. */
    fun extractSecret(totpField: String?): String? {
        if (totpField.isNullOrEmpty()) return null
        // otpauth:// URI -> secret parameter; otherwise treat as raw secret.
        val raw = SECRET_PARAM.find(totpField)?.groupValues?.get(1) ?: totpField
        val s = raw.trim().uppercase().replace(" ", "").trimEnd('=')
        if (s.isEmpty() || s.length < 8 || !BASE32_RE.matches(s)) return null
        return s
    }

    /** Minimal JSON string escaper (for building export payloads). */
    fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    fun parse(exportJson: String): Parsed {
        val totps = mutableListOf<TotpImport>()
        val passwords = mutableListOf<PwImport>()
        val seenTotpLabels = mutableSetOf<String>()
        val seenPwLines = mutableSetOf<String>()

        val root = JSONObject(exportJson)
        val items: JSONArray = when {
            root.has("items") -> root.getJSONArray("items")
            else -> JSONArray(exportJson) // bare array export
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val login = item.optJSONObject("login") ?: JSONObject()
            val name = item.optString("name", null)

            // TOTP entries
            val secret = extractSecret(login.optString("totp", null))
            if (secret != null) {
                var label = normalizeLabel(name)
                var n = 2
                var base = label
                while (label in seenTotpLabels) {
                    label = "${base}_$n"
                    n++
                }
                seenTotpLabels.add(label)
                totps.add(TotpImport(label, secret))
            }

            // Password entries (password required, like run.py; only
            // newlines are stripped — colons are fine, the firmware
            // splits label:username:password on the first two colons).
            val password = (login.optString("password", null) ?: "")
                .replace("\r", "").replace("\n", "")
            if (password.isNotEmpty()) {
                val label = sanitizeField(name, 40).ifEmpty { "UNKNOWN" }
                val username = sanitizeField(login.optString("username", null), 64)
                val line = "$label:$username:$password"
                if (line !in seenPwLines) {
                    seenPwLines.add(line)
                    passwords.add(PwImport(label, username, password))
                }
            }
        }
        return Parsed(totps, passwords)
    }
}
