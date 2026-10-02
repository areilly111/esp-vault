package com.espvault.watch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pulls a vault from a Bitwarden-compatible server (bitwarden.com or a
 * self-hosted Vaultwarden) and returns it in [VaultCache] shape.
 *
 * Zero-knowledge like the official clients: the master password and the
 * derived keys never leave the watch. The server only ever sees the
 * password *hash* at login and returns encrypted blobs, which are
 * decrypted locally. PBKDF2 and Argon2id KDFs are both supported.
 */
object BitwardenSync {

    class BwException(msg: String) : Exception(msg)

    data class KdfInfo(val type: Int, val iterations: Int, val memoryKb: Int, val parallelism: Int)
    data class VaultData(val totps: List<TotpEntry>, val passwords: List<PwEntry>)

    /**
     * Full sync. Returns the vault data plus a refresh token (null if the
     * server didn't issue one). If [savedRefreshToken] works, the master
     * password isn't needed for the network part — but we still need the
     * password to derive the decryption key, so it's required regardless.
     */
    suspend fun sync(
        server: String,
        email: String,
        password: String,
        savedRefreshToken: String?,
        onStatus: (String) -> Unit
    ): Pair<VaultData, String?> = withContext(Dispatchers.IO) {
        val base = server.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://"))
            throw BwException("Server URL must start with http(s)://")

        onStatus("Checking server…")
        val kdf = prelogin(base, email)

        onStatus("Deriving key…")
        val masterKey = deriveMasterKey(password, email, kdf)
        val encKey = hkdfExpand(masterKey, "enc")
        val macKey = hkdfExpand(masterKey, "mac")
        // Best-effort wipe of the raw key material.
        masterKey.fill(0)

        onStatus("Logging in…")
        val (accessToken, refreshToken) =
            if (savedRefreshToken != null) {
                try {
                    refreshAccessToken(base, savedRefreshToken) to savedRefreshToken
                } catch (_: Exception) {
                    login(base, email, password, kdf)
                }
            } else {
                login(base, email, password, kdf)
            }

        onStatus("Downloading vault…")
        val syncJson = apiGet(base, "/api/sync?excludeDomains=true", accessToken)

        onStatus("Decrypting…")
        // Vault items are encrypted with the account's symmetric key, which
        // is itself encrypted with the stretched master key (profile.key).
        // Decrypting items with the stretched key directly yields nothing.
        val profileKey = syncJson.optJSONObject("profile")?.optString("key", "").orEmpty()
        if (profileKey.isEmpty()) throw BwException("Couldn't unlock vault key")
        val accountKey = try {
            decryptCipherBytes(profileKey, encKey, macKey)
        } catch (_: Exception) {
            throw BwException("Couldn't unlock vault key")
        }
        if (accountKey.size != 64) throw BwException("Couldn't unlock vault key")
        val data = parseVault(syncJson, accountKey.copyOfRange(0, 32), accountKey.copyOfRange(32, 64))
        accountKey.fill(0)
        encKey.fill(0); macKey.fill(0)

        data to refreshToken
    }

    // ---- protocol ----

    private fun prelogin(base: String, email: String): KdfInfo {
        val body = JSONObject().put("email", email.trim()).toString()
        val res = postJson("$base/identity/accounts/prelogin", body, null)
        // Servers answer with capital-K "Kdf…"; accept either casing.
        fun io(default: Int, vararg names: String): Int {
            for (n in names) if (res.has(n) && !res.isNull(n)) return res.optInt(n, default)
            return default
        }
        return KdfInfo(
            type = io(0, "Kdf", "kdf"),
            iterations = io(600_000, "KdfIterations", "kdfIterations"),
            memoryKb = io(65_536, "KdfMemory", "kdfMemory"),
            parallelism = io(4, "KdfParallelism", "kdfParallelism")
        )
    }

    /** Returns (accessToken, refreshToken). Password is sent as the Bitwarden password *hash*. */
    private fun login(base: String, email: String, password: String, kdf: KdfInfo): Pair<String, String?> {
        val masterKey = deriveMasterKey(password, email, kdf)
        val pwHash = base64(masterPasswordHash(masterKey, password))
        masterKey.fill(0)
        val form = mapOf(
            "grant_type" to "password",
            "username" to email.trim(),
            "password" to pwHash,
            "scope" to "api offline_access",
            "client_id" to "espvault-watch",
            "deviceType" to "0",
            "deviceIdentifier" to "espvault-watch-1",
            "deviceName" to "Galaxy Watch"
        ).entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        val res = try {
            postForm("$base/identity/connect/token", form, null)
        } catch (e: HttpException) {
            if (e.code == 400) throw BwException("Wrong email or password")
            throw e
        }
        val access = res.optString("access_token", "")
        if (access.isEmpty()) throw BwException("Login failed")
        val refresh = res.optString("refresh_token", "").ifEmpty { null }
        return access to refresh
    }

    private fun refreshAccessToken(base: String, refreshToken: String): String {
        val form = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to "espvault-watch"
        ).joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        val res = try {
            postForm("$base/identity/connect/token", form, null)
        } catch (e: HttpException) {
            throw BwException("Session expired — sign in again")
        }
        val access = res.optString("access_token", "")
        if (access.isEmpty()) throw BwException("Session expired — sign in again")
        return access
    }

    private fun apiGet(base: String, path: String, token: String): JSONObject {
        val url = URL(base + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        return readJsonResponse(conn)
    }

    // ---- vault parsing ----

    private fun parseVault(sync: JSONObject, encKey: ByteArray, macKey: ByteArray): VaultData {
        val totps = mutableListOf<TotpEntry>()
        val passwords = mutableListOf<PwEntry>()
        val ciphers = sync.optJSONArray("ciphers") ?: return VaultData(emptyList(), emptyList())
        for (i in 0 until ciphers.length()) {
            val c = ciphers.optJSONObject(i) ?: continue
            if (!c.isNull("deletedDate")) continue
            if (!c.isNull("organizationId")) continue // org items need org keys; skip
            val type = c.optInt("type", 0)
            if (type != 1) continue // 1 = login; 2 = secure note (nothing to sync)
            val name = decryptOpt(c.optString("name", ""), encKey, macKey) ?: continue
            val login = c.optJSONObject("login") ?: continue
            val username = decryptOpt(login.optString("username", ""), encKey, macKey).orEmpty()
            val password = decryptOpt(login.optString("password", ""), encKey, macKey).orEmpty()
            val totpRaw = decryptOpt(login.optString("totp", ""), encKey, macKey).orEmpty()

            if (username.isNotEmpty() || password.isNotEmpty()) {
                passwords.add(PwEntry(name, username, password))
            }
            val secret = normalizeTotpSecret(totpRaw)
            if (secret != null) {
                totps.add(TotpEntry(name, secret))
            }
        }
        return VaultData(totps, passwords)
    }

    /** Null/empty or undecryptable fields decode to null; caller decides. */
    private fun decryptOpt(enc: String, encKey: ByteArray, macKey: ByteArray): String? {
        if (enc.isEmpty()) return ""
        return try {
            decryptCipherString(enc, encKey, macKey)
        } catch (_: Exception) {
            null
        }
    }

    /** Accepts a raw base32 secret or an otpauth:// URL; null if unusable. */
    private fun normalizeTotpSecret(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("otpauth://", ignoreCase = true)) {
            val q = s.substringAfter('?', "")
            val secretParam = q.split('&').firstOrNull { it.startsWith("secret=", ignoreCase = true) }
                ?: return null
            s = secretParam.substringAfter('=')
        }
        s = s.replace("\\s".toRegex(), "").uppercase()
        if (s.isEmpty() || s.any { it !in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567=" }) return null
        return s
    }

    // ---- crypto ----

    private fun deriveMasterKey(password: String, email: String, kdf: KdfInfo): ByteArray {
        return if (kdf.type == 1) {
            // Argon2id
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(kdf.iterations)
                .withMemoryAsKB(kdf.memoryKb)
                .withParallelism(kdf.parallelism)
                .withSalt(email.trim().lowercase().toByteArray(StandardCharsets.UTF_8))
                .build()
            val gen = Argon2BytesGenerator()
            gen.init(params)
            val out = ByteArray(32)
            gen.generateBytes(password.toCharArray(), out)
            out
        } else {
            // PBKDF2-HMAC-SHA256, manual for byte-exactness (see above).
            pbkdf2Sha256(
                password.toByteArray(StandardCharsets.UTF_8),
                email.trim().lowercase().toByteArray(StandardCharsets.UTF_8),
                kdf.iterations,
                32
            )
        }
    }

    /** Bitwarden's "master password hash": PBKDF2-HMAC-SHA256(masterKey, password, 1). */
    private fun masterPasswordHash(masterKey: ByteArray, password: String): ByteArray {
        // Done manually: JCE's PBEKeySpec takes chars, and pushing raw key
        // bytes through chars corrupts bytes >= 0x80 (verified) — the server
        // then rejects every login as "wrong password".
        return pbkdf2Sha256(masterKey, password.toByteArray(StandardCharsets.UTF_8), 1, 32)
    }

    /**
     * PBKDF2-HMAC-SHA256 over raw bytes. Bitwarden feeds raw key bytes here,
     * which JCE's char-based PBEKeySpec cannot represent exactly.
     */
    private fun pbkdf2Sha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray {
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val intBuf = ByteArray(4)
        for (i in 1..blocks) {
            intBuf[0] = (i ushr 24).toByte()
            intBuf[1] = (i ushr 16).toByte()
            intBuf[2] = (i ushr 8).toByte()
            intBuf[3] = i.toByte()
            var u = hmacSha256(password, salt.copyOf(salt.size + 4).also {
                intBuf.copyInto(it, salt.size)
            })
            val t = u.copyOf()
            for (j in 1 until iterations) {
                u = hmacSha256(password, u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            t.copyInto(out, (i - 1) * hLen)
        }
        return out.copyOf(dkLen)
    }

    /** HKDF-Expand(SHA256), 32 bytes — how Bitwarden stretches the master key. */
    private fun hkdfExpand(prk: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray(StandardCharsets.UTF_8))
        mac.update(1.toByte())
        return mac.doFinal()
    }

    /**
     * Decrypts a Bitwarden cipher string: "encType.iv|data|mac", all base64.
     * Supports type 0 (AesCbc256_B64) and type 2 (AesCbc256_HmacSha256_B64).
     * Returns the raw decrypted bytes.
     */
    private fun decryptCipherBytes(enc: String, encKey: ByteArray, macKey: ByteArray): ByteArray {
        val dot = enc.indexOf('.')
        require(dot > 0) { "bad cipher string" }
        val type = enc.substring(0, dot).toInt()
        val parts = enc.substring(dot + 1).split('|')
        require(parts.size >= 2) { "bad cipher string" }
        val iv = base64(parts[0])
        val data = base64(parts[1])
        if (type == 2) {
            require(parts.size >= 3) { "missing mac" }
            val mac = base64(parts[2])
            val calcMac = hmacSha256(macKey, iv + data)
            require(MessageDigest.isEqual(calcMac, mac)) { "mac mismatch" }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun decryptCipherString(enc: String, encKey: ByteArray, macKey: ByteArray): String =
        String(decryptCipherBytes(enc, encKey, macKey), StandardCharsets.UTF_8)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ---- http helpers ----

    class HttpException(val code: Int, msg: String) : Exception("HTTP $code: $msg")

    private fun postJson(url: String, body: String, token: String?): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        conn.outputStream.use { os ->
            OutputStreamWriter(os, StandardCharsets.UTF_8).use { it.write(body) }
        }
        return readJsonResponse(conn)
    }

    private fun postForm(url: String, form: String, token: String?): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        conn.outputStream.use { os ->
            OutputStreamWriter(os, StandardCharsets.UTF_8).use { it.write(form) }
        }
        return readJsonResponse(conn)
    }

    private fun readJsonResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = try {
                conn.errorStream?.bufferedReader()?.readText().orEmpty()
            } catch (_: Exception) { "" }
            conn.disconnect()
            throw HttpException(code, err.take(200))
        }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(text)
    }

    private fun base64(s: String): ByteArray = Base64.getDecoder().decode(s)
    private fun base64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
