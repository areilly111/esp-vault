package com.espvault.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

data class TotpEntry(val label: String, val secret: String)
data class PwEntry(val label: String, val username: String, val password: String)

sealed class ConnState {
    object Idle : ConnState()
    object Scanning : ConnState()
    object Connecting : ConnState()
    object NeedPin : ConnState()
    object Syncing : ConnState()
    object Ready : ConnState()
    data class Error(val msg: String) : ConnState()
}

/**
 * Offline-first ViewModel. Everything the user sees comes from the local
 * [VaultCache]; the ESP32 is only contacted to refresh that cache.
 * The watch never writes vault data to the device — sync is pull-only
 * (the auth PIN is the one exception, and it's authentication, not data).
 */
@SuppressLint("MissingPermission") // permissions checked by the activity
class WatchViewModel : ViewModel(), BleManager.Listener {

    private lateinit var ble: BleManager
    private var appContext: Context? = null

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn

    private val _totps = MutableStateFlow<List<TotpEntry>>(emptyList())
    val totps: StateFlow<List<TotpEntry>> = _totps

    private val _pwEntries = MutableStateFlow<List<PwEntry>>(emptyList())
    val pwEntries: StateFlow<List<PwEntry>> = _pwEntries

    private val _selectedPw = MutableStateFlow<PwEntry?>(null)
    val selectedPw: StateFlow<PwEntry?> = _selectedPw

    private val _hasCache = MutableStateFlow(false)
    val hasCache: StateFlow<Boolean> = _hasCache

    private val _lastSyncAt = MutableStateFlow(0L)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    // ---- sync accumulation ----
    private val pendingTotps = mutableListOf<TotpEntry>()
    private val pwAccum = mutableListOf<PwEntry>()
    private var pwTotal = 0
    private var syncWatchdog: Job? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // Cache first: the app is fully usable without the device.
        val snap = VaultCache.load(context.applicationContext)
        _totps.value = snap.totps
        _pwEntries.value = snap.passwords
        _lastSyncAt.value = snap.syncedAt
        _hasCache.value = snap.syncedAt > 0L
        ble = BleManager(context.applicationContext, this)
    }

    fun startScan() {
        val ctx = appContext ?: return
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = mgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            _conn.value = ConnState.Error("Bluetooth is off — turn it on and retry")
            return
        }
        _conn.value = ConnState.Scanning
        _status.value = ""
        ble.startScan(adapter)
    }

    fun authenticate(pin: String) {
        _conn.value = ConnState.Syncing
        _status.value = "Unlocking…"
        armWatchdog()
        ble.authenticate(pin)
    }

    /** Ends the BLE session. Cached vault data is untouched. */
    fun disconnect() {
        syncWatchdog?.cancel()
        _status.value = ""
        ble.disconnect()
    }

    fun selectPw(index: Int) {
        _selectedPw.value = _pwEntries.value.getOrNull(index)
    }

    fun clearSelectedPw() {
        _selectedPw.value = null
    }

    // ---- auto-scan handoff ----
    // The connect screen auto-scans only when something explicitly asked for
    // a sync (first run with no cache, or the Sync button) — not when the
    // user merely swipes back to it.
    private var autoScanPending = false
    fun requestAutoScan() {
        autoScanPending = true
    }

    fun consumeAutoScan(): Boolean {
        val v = autoScanPending
        autoScanPending = false
        return v
    }

    // ---- sync ----

    private fun armWatchdog() {
        syncWatchdog?.cancel()
        syncWatchdog = viewModelScope.launch {
            delay(60_000)
            if (_conn.value is ConnState.Syncing) {
                _conn.value = ConnState.Error("Sync timed out — try again")
                ble.disconnect()
            }
        }
    }

    private fun syncVault() {
        _conn.value = ConnState.Syncing
        _status.value = "Syncing codes…"
        pendingTotps.clear()
        pwAccum.clear()
        pwTotal = 0
        armWatchdog()
        ble.readVaultExport()
    }

    private fun finishSync() {
        syncWatchdog?.cancel()
        val ctx = appContext
        _totps.value = pendingTotps.toList()
        _pwEntries.value = pwAccum.toList()
        _lastSyncAt.value = System.currentTimeMillis()
        _hasCache.value = true
        if (ctx != null) {
            VaultCache.save(ctx, pendingTotps.toList(), pwAccum.toList())
        }
        _status.value = "Synced ✓"
        _conn.value = ConnState.Ready
        // Sync-only peripheral: drop the connection so the phone app (or the
        // next sync) can use the device's single BLE slot. The cache is the app.
        viewModelScope.launch {
            delay(1500)
            ble.disconnect()
        }
    }

    // ---- BleManager.Listener ----

    override fun onDeviceFound(device: BluetoothDevice) {
        // Auto-connect — watches have tiny screens, skip the tap.
        _conn.value = ConnState.Connecting
        ble.connect(device)
    }

    override fun onDeviceInfo(fw: String, locked: Boolean, setup: Boolean) {
        if (locked) {
            syncWatchdog?.cancel()
            _conn.value = ConnState.NeedPin
        } else {
            syncVault()
        }
    }

    override fun onVaultExport(json: String) {
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val label = o.optString("label", "")
                val secret = o.optString("secret", "")
                if (label.isNotEmpty() && secret.isNotEmpty())
                    pendingTotps.add(TotpEntry(label, secret))
            }
            _status.value = "Syncing passwords…"
            ble.readPwCount()
        } catch (_: Exception) {
            syncWatchdog?.cancel()
            _conn.value = ConnState.Error("Bad vault data")
        }
    }

    override fun onPwCount(count: Int) {
        pwTotal = count
        if (count <= 0) {
            finishSync()
        } else {
            _status.value = "Passwords 0/$count…"
            ble.requestPw(0)
        }
    }

    override fun onPwEntry(label: String, username: String, password: String) {
        pwAccum.add(PwEntry(label, username, password))
        _status.value = "Passwords ${pwAccum.size}/$pwTotal…"
        if (pwAccum.size >= pwTotal) {
            finishSync()
        } else {
            ble.requestPw(pwAccum.size)
        }
    }

    override fun onStatus(text: String) {
        // Auth result arrives here.
        when {
            text == "OK AUTH" -> syncVault()
            text == "ERR AUTH" -> {
                syncWatchdog?.cancel()
                _conn.value = ConnState.Error("Wrong PIN — try again")
            }
            text == "ERR LOCKED" -> {
                syncWatchdog?.cancel()
                _conn.value = ConnState.Error("Device is locked — triple-tap its button")
            }
            text.startsWith("ERR") -> {
                syncWatchdog?.cancel()
                _conn.value = ConnState.Error(text)
            }
        }
    }

    override fun onDisconnected() {
        syncWatchdog?.cancel()
        _conn.value = ConnState.Idle
    }

    override fun onError(text: String) {
        syncWatchdog?.cancel()
        _conn.value = ConnState.Error(text)
    }
}
