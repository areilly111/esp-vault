package com.espvault.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Native Android BLE layer for the Esp Vault protocol.
 *
 * Android allows only one outstanding GATT operation at a time, so every
 * read/write/descriptor-write goes through a serial queue. All Listener
 * callbacks are posted on the main thread.
 *
 * Security: this class never logs secret values. The portal password is
 * written once to the auth characteristic and never stored.
 */
@SuppressLint("MissingPermission") // caller checks runtime permissions first
class BleManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onDeviceFound(device: BluetoothDevice)
        fun onDeviceInfo(fw: String, locked: Boolean, setup: Boolean)
        fun onTotpLabels(labels: List<String>)
        fun onPwLabels(labels: List<String>)
        fun onPwLabelPage(labels: List<String>)
        fun onPwCount(count: Int)
        fun onStatus(text: String)
        fun onTotpCode(label: String, code: String, secondsLeft: Int)
        fun onPwEntry(label: String, username: String, password: String)
        fun onBleAuto(enabled: Boolean)
        fun onTimezone(name: String, tz: String)
        fun onVaultExport(json: String)
        fun onDisconnected()
        fun onError(text: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue: ArrayDeque<() -> Unit> = ArrayDeque()
    private var busy = false

    private var gatt: BluetoothGatt? = null
    private val chars = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private var scanning = false

    // ---------------- operation queue ----------------

    private fun enqueue(op: () -> Unit) {
        val runNow: (() -> Unit)? = synchronized(lock) {
            queue.addLast(op)
            if (!busy) {
                busy = true
                queue.removeFirst()
            } else null
        }
        if (runNow != null) runOp(runNow)
    }

    private fun runOp(op: () -> Unit) {
        try {
            op()
        } catch (e: Exception) {
            main.post { listener.onError("BLE operation failed: ${e.message}") }
            opDone()
        }
    }

    private fun opDone() {
        val next: (() -> Unit)? = synchronized(lock) {
            val n = queue.removeFirstOrNull()
            if (n == null) busy = false
            n
        }
        if (next != null) runOp(next)
    }

    private fun clearQueue() {
        synchronized(lock) {
            queue.clear()
            busy = false
        }
    }

    // ---------------- scanning ----------------

    fun startScan(adapter: BluetoothAdapter) {
        stopScan()
        val sc = adapter.bluetoothLeScanner
        if (sc == null) {
            main.post { listener.onError("BLE scanner unavailable — is Bluetooth on?") }
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Protocol.SVC))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device?.name == Protocol.DEVICE_NAME) {
                    stopScan()
                    main.post { listener.onDeviceFound(result.device) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post { listener.onError("Scan failed ($errorCode)") }
            }
        }
        scanner = sc
        scanCb = cb
        scanning = true
        try {
            sc.startScan(listOf(filter), settings, cb)
        } catch (e: Exception) {
            scanning = false
            main.post { listener.onError("Could not start scan: ${e.message}") }
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
        scanner = null
        scanCb = null
    }

    // ---------------- connection ----------------

    fun connect(device: BluetoothDevice) {
        cleanup()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        // onConnectionStateChange(DISCONNECTED) performs cleanup + callback.
    }

    private fun cleanup() {
        clearQueue()
        chars.clear()
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    // ---------------- public protocol operations ----------------

    fun authenticate(password: String) =
        writeChar(Protocol.AUTH, password.toByteArray(Charsets.UTF_8))

    fun readTotpLabels() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.VAULT_LABELS) }
    }

    @Deprecated("Use paged loading via readPwLabelPage() instead")
    fun readPwLabels() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.PW_LABELS) }
    }

    /**
     * Paged password labels (firmware 1.5.0+): write the page index, then read
     * the JSON array of up to 20 labels. Each page is <1KB — no giant allocs.
     */
    fun readPwLabelPage(page: Int) {
        writeChar(Protocol.PW_LABEL_PAGE, u16le(page))
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.PW_LABEL_PAGE) }
    }

    fun readPwCount() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.PW_COUNT) }
    }

    fun requestTotp(index: Int) = writeChar(Protocol.TOTP_REQ, u16le(index))

    fun requestPw(index: Int) = writeChar(Protocol.PW_REQ, u16le(index))

    fun timeSync() =
        writeChar(Protocol.TIME_SYNC, u32le(System.currentTimeMillis() / 1000L))

    fun addTotp(label: String, secret: String) =
        writeChar(
            Protocol.ADD_TOTP,
            """{"label":"$label","secret":"$secret"}""".toByteArray(Charsets.UTF_8)
        )

    fun lock() = writeChar(Protocol.LOCK, byteArrayOf(1))

    // ---- v1.4.4+ CRUD (JSON payloads, results arrive on status) ----

    fun totpDelete(index: Int) =
        writeChar(Protocol.TOTP_DELETE, """{"index":$index}""".toByteArray(Charsets.UTF_8))

    fun totpEdit(index: Int, label: String, secret: String) =
        writeChar(
            Protocol.TOTP_EDIT,
            """{"index":$index,"label":"$label","secret":"$secret"}""".toByteArray(Charsets.UTF_8)
        )

    fun pwAdd(label: String, username: String, password: String) =
        writeChar(
            Protocol.PW_ADD,
            """{"label":"${jsonEsc(label)}","username":"${jsonEsc(username)}","password":"${jsonEsc(password)}"}""".toByteArray(Charsets.UTF_8)
        )

    fun pwEdit(index: Int, label: String, username: String, password: String) =
        writeChar(
            Protocol.PW_EDIT,
            """{"index":$index,"label":"${jsonEsc(label)}","username":"${jsonEsc(username)}","password":"${jsonEsc(password)}"}""".toByteArray(Charsets.UTF_8)
        )

    fun pwDelete(index: Int) =
        writeChar(Protocol.PW_DELETE, """{"index":$index}""".toByteArray(Charsets.UTF_8))

    // ---- v1.4.5+ first-boot setup (no auth; only while setup=1) ----

    fun setupSetPassword(password: String) =
        writeChar(
            Protocol.SETUP_SET_PASSWORD,
            """{"password":"${jsonEsc(password)}"}""".toByteArray(Charsets.UTF_8)
        )

    fun setupSetWifi(ssid: String, password: String) =
        writeChar(
            Protocol.SETUP_SET_WIFI,
            """{"ssid":"${jsonEsc(ssid)}","password":"${jsonEsc(password)}"}""".toByteArray(Charsets.UTF_8)
        )

    fun setupComplete() = writeChar(Protocol.SETUP_COMPLETE, byteArrayOf(1))

    // ---- v1.4.5+ BLE auto-start flag (requires unlock + auth) ----

    fun readBleAuto() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.BLE_AUTO) }
    }

    fun writeBleAuto(enabled: Boolean) =
        writeChar(
            Protocol.BLE_AUTO,
            """{"auto":${if (enabled) 1 else 0}}""".toByteArray(Charsets.UTF_8)
        )

    // ---- v1.4.6+ device settings (require unlock + auth) ----

    fun changePortalPassword(old: String, new: String) =
        writeChar(
            Protocol.PW_CHANGE,
            """{"old":"${jsonEsc(old)}","new":"${jsonEsc(new)}"}""".toByteArray(Charsets.UTF_8)
        )

    fun setWifi(ssid: String, password: String) =
        writeChar(
            Protocol.WIFI_SET,
            """{"ssid":"${jsonEsc(ssid)}","password":"${jsonEsc(password)}"}""".toByteArray(Charsets.UTF_8)
        )

    fun readTimezone() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.TZ) }
    }

    fun writeTimezone(name: String, tz: String) =
        writeChar(
            Protocol.TZ,
            """{"name":"${jsonEsc(name)}","tz":"${jsonEsc(tz)}"}""".toByteArray(Charsets.UTF_8)
        )

    // ---- v1.4.7+ vault export (requires unlock + auth) ----

    fun readVaultExport() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.VAULT_EXPORT) }
    }

    /** Parse `{"name":"...","tz":"..."}` from the tz characteristic. */
    private fun parseTimezone(bytes: ByteArray): Pair<String, String>? {
        return try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Pair(o.optString("name", ""), o.optString("tz", ""))
        } catch (_: Exception) {
            null
        }
    }

    /** Minimal JSON string escaper for characteristic payloads. */
    private fun jsonEsc(s: String): String {
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

    // ---------------- internals ----------------

    private fun writeChar(uuid: UUID, bytes: ByteArray) {
        val g = gatt ?: return
        enqueue {
            val ch = chars[uuid]
            if (ch == null) {
                opDone()
                return@enqueue
            }
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = bytes
            if (!g.writeCharacteristic(ch)) opDone()
        }
    }

    private fun readChar(g: BluetoothGatt, uuid: UUID) {
        val ch = chars[uuid]
        if (ch == null || !g.readCharacteristic(ch)) opDone()
    }

    private fun enableNotify(g: BluetoothGatt, uuid: UUID) {
        val ch = chars[uuid]
        if (ch == null) {
            opDone()
            return
        }
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(Protocol.CCCD)
        if (desc == null) {
            opDone()
            return
        }
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(desc)) opDone()
    }

    private fun parseLabels(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            // Don't fail silently: a truncated/unparseable list used to show
            // up as "0 entries" with no clue. Surface the byte count.
            main.post { listener.onError("List unreadable (${bytes.size} bytes) — retry") }
            emptyList()
        }
    }

    /** Parse `{"auto":1}` / `{"auto":0}` from the ble_auto characteristic. */
    private fun parseBleAuto(bytes: ByteArray): Boolean {
        return try {
            JSONObject(String(bytes, Charsets.UTF_8)).optInt("auto", 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    private fun u16le(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun u32le(v: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cleanup()
                main.post { listener.onDisconnected() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { listener.onError("Service discovery failed") }
                disconnect()
                return
            }
            val svc = g.getService(Protocol.SVC)
            if (svc == null) {
                main.post { listener.onError("Esp Vault service not found") }
                disconnect()
                return
            }
            chars.clear()
            for (u in Protocol.ALL_CHARS) {
                svc.getCharacteristic(u)?.let { chars[u] = it }
            }
            enqueue { g.requestMtu(517) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            opDone()
            enqueue { enableNotify(g, Protocol.STATUS) }
            enqueue { enableNotify(g, Protocol.TOTP_CODE) }
            enqueue { enableNotify(g, Protocol.PW_ENTRY) }
            enqueue { readChar(g, Protocol.DEVICE_INFO) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            val bytes =
                if (status == BluetoothGatt.GATT_SUCCESS) ch.value ?: ByteArray(0)
                else ByteArray(0)
            opDone()
            when (ch.uuid) {
                Protocol.DEVICE_INFO -> {
                    val raw = String(bytes, Charsets.UTF_8)
                    val info = Protocol.parseDeviceInfo(raw)
                    if (info != null) {
                        val (fw, locked, setup) = info
                        main.post { listener.onDeviceInfo(fw, locked, setup) }
                    } else {
                        main.post { listener.onError("Bad device_info response") }
                    }
                }
                Protocol.VAULT_LABELS ->
                    main.post { listener.onTotpLabels(parseLabels(bytes)) }
                Protocol.PW_LABELS -> {
                    // Legacy (firmware < 1.5.0): full list in one read. Deprecated.
                    val labels = parseLabels(bytes)
                    main.post { listener.onPwLabels(labels) }
                }
                Protocol.PW_LABEL_PAGE -> {
                    // Paged labels (firmware 1.5.0+): JSON array of up to 20 labels.
                    val labels = parseLabels(bytes)
                    main.post { listener.onPwLabelPage(labels) }
                }
                Protocol.PW_COUNT -> {
                    val c = if (bytes.size >= 2)
                        (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
                    else 0
                    main.post { listener.onPwCount(c) }
                }
                Protocol.BLE_AUTO ->
                    main.post { listener.onBleAuto(parseBleAuto(bytes)) }
                Protocol.TZ ->
                    parseTimezone(bytes)?.let { (name, tz) ->
                        main.post { listener.onTimezone(name, tz) }
                    }
                Protocol.VAULT_EXPORT ->
                    main.post { listener.onVaultExport(String(bytes, Charsets.UTF_8)) }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            opDone()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { listener.onError("Write failed") }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            opDone()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            val text = String(ch.value ?: ByteArray(0), Charsets.UTF_8)
            when (ch.uuid) {
                Protocol.STATUS ->
                    main.post { listener.onStatus(text) }
                Protocol.TOTP_CODE ->
                    Protocol.parseTotpCode(text)?.let { (label, code, secs) ->
                        main.post { listener.onTotpCode(label, code, secs) }
                    }
                Protocol.PW_ENTRY ->
                    Protocol.parsePwEntry(text)?.let { (label, user, pass) ->
                        main.post { listener.onPwEntry(label, user, pass) }
                    }
            }
        }
    }
}
