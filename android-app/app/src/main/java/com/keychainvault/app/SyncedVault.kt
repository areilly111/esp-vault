package com.keychainvault.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One synced TOTP entry: display label + base32 secret. */
data class SyncedTotp(val label: String, val secret: String)

/** One synced password entry. */
data class SyncedPw(val label: String, val username: String, val password: String)

/**
 * Local synced vault. The phone keeps a read-only mirror of the Bitwarden
 * server vault here; it only changes when a server sync pulls fresh data.
 * No device connection is needed to browse codes or passwords.
 *
 * Files live in the app's private files dir. Note the data is plaintext —
 * same caveat as the app's backup export; the phone is a personal device.
 */
object SyncedVault {

    private const val TOTPS_FILE = "synced_totps.json"
    private const val PASSWORDS_FILE = "synced_passwords.json"
    private const val META_FILE = "synced_meta.json"

    data class Snapshot(
        val totps: List<SyncedTotp>,
        val passwords: List<SyncedPw>,
        val syncedAt: Long
    )

    fun load(context: Context): Snapshot {
        val totps = mutableListOf<SyncedTotp>()
        readArray(context, TOTPS_FILE)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label", "")
                val secret = o.optString("secret", "")
                if (label.isNotEmpty() && secret.isNotEmpty())
                    totps.add(SyncedTotp(label, secret))
            }
        }
        val passwords = mutableListOf<SyncedPw>()
        readArray(context, PASSWORDS_FILE)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label", "")
                if (label.isNotEmpty())
                    passwords.add(
                        SyncedPw(label, o.optString("username", ""), o.optString("password", ""))
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

    fun save(context: Context, totps: List<SyncedTotp>, passwords: List<SyncedPw>) {
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
