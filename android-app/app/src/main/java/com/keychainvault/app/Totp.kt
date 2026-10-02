package com.keychainvault.app

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * RFC 6238 TOTP, computed on the phone from synced secrets.
 * The synced copy keeps working even when the ESP32 is out of range —
 * codes only need the secret and the phone's own clock.
 */
object Totp {

    /**
     * Generate the current TOTP code for a base32 secret.
     * Returns null if the secret is invalid.
     */
    fun generate(secret: String, timeMs: Long = System.currentTimeMillis(), digits: Int = 6, periodSec: Int = 30): String? {
        val key = base32Decode(secret) ?: return null
        val counter = (timeMs / 1000L) / periodSec
        val msg = ByteBuffer.allocate(8).putLong(counter).array()
        return try {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(msg)
            val offset = hash.last().toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)
            val otp = binary % 10.0.pow(digits).toInt()
            otp.toString().padStart(digits, '0')
        } catch (_: Exception) {
            null
        }
    }

    /** Seconds remaining in the current 30s window. */
    fun secondsLeft(timeMs: Long = System.currentTimeMillis(), periodSec: Int = 30): Int {
        val elapsed = (timeMs / 1000L) % periodSec
        return (periodSec - elapsed).toInt().let { if (it == 0) periodSec else it }
    }

    private val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Decode a base32 secret (case-insensitive, padding optional). Null if invalid. */
    fun base32Decode(s: String): ByteArray? {
        val clean = s.trim().replace(" ", "").replace("=", "").uppercase()
        if (clean.isEmpty() || clean.any { it !in BASE32_CHARS }) return null
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var outPos = 0
        for (c in clean) {
            buffer = (buffer shl 5) or BASE32_CHARS.indexOf(c)
            bitsLeft += 5
            if (bitsLeft >= 8) {
                if (outPos >= out.size) return null
                out[outPos++] = (buffer shr (bitsLeft - 8)).toByte()
                bitsLeft -= 8
            }
        }
        return out.copyOf(outPos)
    }
}
