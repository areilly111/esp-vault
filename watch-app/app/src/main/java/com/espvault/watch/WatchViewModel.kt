package com.espvault.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@SuppressLint("MissingPermission") // permissions checked by the activity
class WatchViewModel : ViewModel(), BleManager.Listener {

    private lateinit var ble: BleManager
    private var appContext: Context? = null

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn

    private val _totps = MutableStateFlow<List<TotpEntry>>(emptyList())
    val totps: StateFlow<List<TotpEntry>> = _totps

    private val _pwLabels = MutableStateFlow<List<String>>(emptyList())
    val pwLabels: StateFlow<List<String>> = _pwLabels

    private val _pwDetail = MutableStateFlow<PwEntry?>(null)
    val pwDetail: StateFlow<PwEntry?> = _pwDetail

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private var pwCount = 0
    private var pwPagesLoaded = 0
    private val pwAccum = mutableListOf<String>()
    private var pendingPwIndex = -1

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ble = BleManager(context.applicationContext, this)
    }

    fun startScan() {
        val ctx = appContext ?: return
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = mgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            _conn.value = ConnState.Error("Bluetooth is off")
            return
        }
        _conn.value = ConnState.Scanning
        ble.startScan(adapter)
    }

    fun connect(device: BluetoothDevice) {
        _conn.value = ConnState.Connecting
        ble.connect(device)
    }

    fun authenticate(pin: String) {
        _conn.value = ConnState.Syncing
        ble.authenticate(pin)
    }

    fun disconnect() {
        ble.disconnect()
        _conn.value = ConnState.Idle
        _totps.value = emptyList()
        _pwLabels.value = emptyList()
        _pwDetail.value = null
    }

    fun requestPwDetail(index: Int) {
        pendingPwIndex = index
        _pwDetail.value = null
        ble.requestPw(index)
    }

    fun clearPwDetail() {
        _pwDetail.value = null
    }

    // ---- BleManager.Listener ----

    override fun onDeviceFound(device: BluetoothDevice) {
        // Auto-connect on first find — watches have tiny screens, skip the tap.
        connect(device)
    }

    override fun onDeviceInfo(fw: String, locked: Boolean, setup: Boolean) {
        if (locked) {
            _conn.value = ConnState.NeedPin
        } else {
            syncVault()
        }
    }

    private fun syncVault() {
        _conn.value = ConnState.Syncing
        _status.value = "Syncing codes…"
        ble.readVaultExport()
    }

    override fun onVaultExport(json: String) {
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<TotpEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val label = o.optString("label", "")
                val secret = o.optString("secret", "")
                if (label.isNotEmpty() && secret.isNotEmpty()) list.add(TotpEntry(label, secret))
            }
            _totps.value = list
            _status.value = "Syncing passwords…"
            pwAccum.clear()
            pwPagesLoaded = 0
            ble.readPwCount()
        } catch (_: Exception) {
            _conn.value = ConnState.Error("Bad vault data")
        }
    }

    override fun onPwCount(count: Int) {
        pwCount = count
        if (count <= 0) {
            _pwLabels.value = emptyList()
            _conn.value = ConnState.Ready
        } else {
            ble.readPwLabelPage(0)
        }
    }

    override fun onPwLabelPage(labels: List<String>) {
        pwAccum.addAll(labels)
        pwPagesLoaded++
        val totalPages = (pwCount + Protocol.PW_LABELS_PER_PAGE - 1) / Protocol.PW_LABELS_PER_PAGE
        if (pwPagesLoaded < totalPages) {
            ble.readPwLabelPage(pwPagesLoaded)
        } else {
            _pwLabels.value = pwAccum.toList()
            _conn.value = ConnState.Ready
            _status.value = ""
        }
    }

    override fun onPwEntry(label: String, username: String, password: String) {
        _pwDetail.value = PwEntry(label, username, password)
    }

    override fun onStatus(text: String) {
        // Auth result arrives here.
        when {
            text == "OK AUTH" -> syncVault()
            text == "ERR AUTH" -> _conn.value = ConnState.Error("Wrong password")
            text == "ERR LOCKED" -> _conn.value = ConnState.Error("Device is locked — triple-tap it")
            text.startsWith("ERR") -> _conn.value = ConnState.Error(text)
        }
    }

    // ---- unused for the watch's read-only flow ----
    override fun onTotpLabels(labels: List<String>) {}
    override fun onPwLabels(labels: List<String>) {}
    override fun onTotpCode(label: String, code: String, secondsLeft: Int) {}
    override fun onBleAuto(enabled: Boolean) {}
    override fun onTimezone(name: String, tz: String) {}
    override fun onDisconnected() {
        _conn.value = ConnState.Idle
    }
    override fun onError(text: String) {
        _conn.value = ConnState.Error(text)
    }
}
