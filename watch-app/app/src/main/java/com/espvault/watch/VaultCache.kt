package com.espvault.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline vault cache. The watch is a read-only mirror: everything the user
 * sees comes from here, and it only changes when a sync pulls fresh data
 * from the ESP32. No connection is needed to browse codes or passwords.
 *
 * Files live in the app's private files dir. Note the data is plaintext —
 * same caveat as the phone app's export; the watch is a personal device.
 */
object VaultCache {

    private const val TOTPS_FILE = "vault_totps.json"
    private const val PASSWORDS_FILE = "vault_passwords.json"
    private const val META_FILE = "vault_meta.json"

    data class Snapshot(
        val totps: List<TotpEntry>,
        val passwords: List<PwEntry>,
        val syncedAt: Long
    )

    fun load(context: Context): Snapshot {
        val totps = mutableListOf<TotpEntry>()
        readArray(context, TOTPS_FILE)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label", "")
                val secret = o.optString("secret", "")
                if (label.isNotEmpty() && secret.isNotEmpty())
                    totps.add(TotpEntry(label, secret))
            }
        }
        val passwords = mutableListOf<PwEntry>()
        readArray(context, PASSWORDS_FILE)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label", "")
                if (label.isNotEmpty())
                    passwords.add(
                        PwEntry(label, o.optString("username", ""), o.optString("password", ""))
                    )
            }
        }
        val syncedAt = try {
            JSONObject(File(context.filesDir, META_FILE).readText()).optLong("syncedAt", 0L)
        } catch (_: Exception) {
            0L
        }
        return Snapshot(totps, passwords, syncedAt)
    }

    fun save(context: Context, totps: List<TotpEntry>, passwords: List<PwEntry>) {
        val ta = JSONArray()
        for (t in totps) {
            ta.put(JSONObject().put("label", t.label).put("secret", t.secret))
        }
        val pa = JSONArray()
        for (p in passwords) {
            pa.put(
                JSONObject()
                    .put("label", p.label)
                    .put("username", p.username)
                    .put("password", p.password)
            )
        }
        File(context.filesDir, TOTPS_FILE).writeText(ta.toString())
        File(context.filesDir, PASSWORDS_FILE).writeText(pa.toString())
        File(context.filesDir, META_FILE).writeText(
            JSONObject().put("syncedAt", System.currentTimeMillis()).toString()
        )
    }

    fun hasCache(context: Context): Boolean =
        File(context.filesDir, META_FILE).exists()

    private fun readArray(context: Context, name: String): JSONArray? {
        return try {
            val f = File(context.filesDir, name)
            if (!f.exists()) null else JSONArray(f.readText())
        } catch (_: Exception) {
            null
        }
    }
}
