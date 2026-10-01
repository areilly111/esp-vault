package com.keychainvault.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat

/**
 * Scans a QR code containing an otpauth://totp/... URI and returns the
 * parsed label + secret to the caller.
 *
 * Result extras:
 *   "label"  — TOTP label (from URI path / issuer)
 *   "secret" — Base32 secret (from ?secret=)
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_SECRET = "secret"
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private var scanned = false

    private val callback = BarcodeCallback { result: BarcodeResult? ->
        if (scanned || result == null) return@BarcodeCallback
        val raw = result.text ?: return@BarcodeCallback
        val parsed = parseOtpauth(raw)
        if (parsed != null) {
            scanned = true
            val (label, secret) = parsed
            val data = Intent().apply {
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_SECRET, secret)
            }
            setResult(RESULT_OK, data)
            finish()
        } else {
            // Not a TOTP QR — keep scanning, but hint once
            runOnUiThread {
                Toast.makeText(
                    this, "Not a TOTP QR code — keep scanning", Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        barcodeView = DecoratedBarcodeView(this)
        // QR codes only, for speed
        barcodeView.decoderFactory = DefaultDecoderFactory(
            listOf(BarcodeFormat.QR_CODE), null, null, 0
        )
        setContentView(barcodeView)
        barcodeView.decodeContinuous(callback)
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    /**
     * Parses otpauth://totp/Label?secret=ABC&issuer=XYZ (also handles
     * otpauth://totp/Issuer:Label?secret=...).
     * Returns Pair(label, secret) or null if not a valid TOTP URI.
     */
    private fun parseOtpauth(uri: String): Pair<String, String>? {
        if (!uri.startsWith("otpauth://totp/", ignoreCase = true)) return null
        val afterScheme = uri.substringAfter("otpauth://totp/", "")
        val pathPart = afterScheme.substringBefore("?")
        val queryPart = afterScheme.substringAfter("?", "")

        val secret = queryPart.split("&")
            .firstOrNull { it.startsWith("secret=", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.trim()
            ?.replace(" ", "")
            ?: return null
        if (secret.isEmpty() || !Protocol.isValidBase32Secret(secret)) return null

        var label = queryPart.split("&")
            .firstOrNull { it.startsWith("issuer=", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            .orEmpty()
        if (label.isEmpty()) {
            val decoded = java.net.URLDecoder.decode(pathPart, "UTF-8")
            label = if (decoded.contains(":")) decoded.substringAfter(":") else decoded
        }
        label = Protocol.normalizeLabel(label)
        if (label.isEmpty()) label = "Scanned"
        return Pair(label, secret.uppercase())
    }
}
