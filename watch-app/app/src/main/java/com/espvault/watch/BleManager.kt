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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.json.JSONObject

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

    /**
     * BLE sync listener. The watch pulls vault data from the device on import
     * and pushes its cache to the device on export (both auth-gated).
     * It never touches device settings — the device is an optional vault
     * peripheral, not something the watch manages.
     * (The auth PIN write is authentication, not vault data.)
     */
    interface Listener {
        fun onDeviceFound(device: BluetoothDevice)
        fun onDeviceInfo(fw: String, locked: Boolean, setup: Boolean)
        fun onPwCount(count: Int)
        fun onTotpCount(count: Int)
        fun onStatus(text: String)
        fun onPwEntry(label: String, username: String, password: String)
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

    /** Give up after 15s — an infinite spinner is how "won't connect" feels. */
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            main.post {
                listener.onError("No vault found")
            }
        }
    }

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
                // Service-UUID filter already guarantees this is our vault device;
                // accept it even if the advertised name is hidden (neverForLocation).
                val name = try { result.device?.name } catch (_: SecurityException) { null }
                if (name == null || name == Protocol.DEVICE_NAME) {
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
        main.postDelayed(scanTimeout, 15_000)
        try {
            sc.startScan(listOf(filter), settings, cb)
        } catch (e: Exception) {
            scanning = false
            main.removeCallbacks(scanTimeout)
            main.post { listener.onError("Could not start scan: ${e.message}") }
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(scanTimeout)
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

    // ---------------- vault sync operations ----------------
    // Import: pull vault data from the device. Export: push the watch's
    // cached vault to the device (delete-all + re-add per entry).

    fun readPwCount() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.PW_COUNT) }
    }

    fun requestPw(index: Int) = writeChar(Protocol.PW_REQ, u16le(index))

    fun readTotpCount() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.VAULT_COUNT) }
    }

    // ---- vault export (requires unlock + auth) ----

    fun readVaultExport() {
        val g = gatt ?: return
        enqueue { readChar(g, Protocol.VAULT_EXPORT) }
    }

    // ---- vault import-to-device (requires unlock + auth) ----

    fun writeAddTotp(label: String, secret: String) {
        val json = JSONObject()
            .put("label", label)
            .put("secret", secret)
            .toString()
        writeChar(Protocol.ADD_TOTP, json.toByteArray(Charsets.UTF_8))
    }

    fun writeTotpDelete(index: Int) {
        val json = JSONObject().put("index", index).toString()
        writeChar(Protocol.TOTP_DELETE, json.toByteArray(Charsets.UTF_8))
    }

    fun writePwAdd(label: String, username: String, password: String) {
        val json = JSONObject()
            .put("label", label)
            .put("username", username)
            .put("password", password)
            .toString()
        writeChar(Protocol.PW_ADD, json.toByteArray(Charsets.UTF_8))
    }

    fun writePwDelete(index: Int) {
        val json = JSONObject().put("index", index).toString()
        writeChar(Protocol.PW_DELETE, json.toByteArray(Charsets.UTF_8))
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

    private fun u16le(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cleanup()
                main.post {
                    // status 0 = we hung up ourselves. Anything else (e.g. 133)
                    // is a real failure the user should see, not a silent Idle.
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        listener.onError("Connection failed ($status) — move closer and retry")
                    } else {
                        listener.onDisconnected()
                    }
                }
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
                Protocol.PW_COUNT -> {
                    val c = if (bytes.size >= 2)
                        (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
                    else 0
                    main.post { listener.onPwCount(c) }
                }
                Protocol.VAULT_COUNT -> {
                    val c = if (bytes.size >= 2)
                        (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
                    else 0
                    main.post { listener.onTotpCount(c) }
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
                Protocol.PW_ENTRY ->
                    Protocol.parsePwEntry(text)?.let { (label, user, pass) ->
                        main.post { listener.onPwEntry(label, user, pass) }
                    }
            }
        }
    }
}
