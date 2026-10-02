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

/** What a BLE session is for. The device is optional; Bitwarden is the default. */
enum class BleJob { IMPORT, EXPORT }

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

    // ---- Bitwarden server sync (second sync source) ----
    // Server URL + email are kept in private prefs; the refresh token too so
    // "Sync now" is one tap. The master password is saved to private prefs
    // after the first successful sync (never displayed) — the user's explicit
    // choice for one-tap syncs; "Forget saved password" removes it.
    private val _bwServer = MutableStateFlow("")
    val bwServer: StateFlow<String> = _bwServer

    private val _bwEmail = MutableStateFlow("")
    val bwEmail: StateFlow<String> = _bwEmail

    private val _bwState = MutableStateFlow<ConnState>(ConnState.Idle)
    val bwState: StateFlow<ConnState> = _bwState

    private val _bwStatus = MutableStateFlow("")
    val bwStatus: StateFlow<String> = _bwStatus

    // Remembered master password: saved to private prefs after the first
    // *successful* sync, never shown in the UI, so "Sync now" is one tap.
    // Same storage caveat as the vault cache itself (app-private plaintext
    // on a personal device) — the user's explicit tradeoff for convenience.
    private val _bwHasSavedPw = MutableStateFlow(false)
    val bwHasSavedPw: StateFlow<Boolean> = _bwHasSavedPw

    // Remembered vault PIN (ESP32 portal password for BLE auth). Same deal:
    // saved in private prefs, never displayed, used automatically when the
    // device asks for it. Falls back to manual entry if it gets rejected.
    private val _hasVaultPin = MutableStateFlow(false)
    val hasVaultPin: StateFlow<Boolean> = _hasVaultPin
    private var usedSavedPin = false

    private var bwJob: Job? = null

    // ---- sync accumulation ----
    private val pendingTotps = mutableListOf<TotpEntry>()
    private val pwAccum = mutableListOf<PwEntry>()
    private var pwTotal = 0
    private var syncWatchdog: Job? = null

    // ---- BLE device job (optional peripheral) ----
    private var bleJob = BleJob.IMPORT
    val currentBleJob: BleJob get() = bleJob

    // Export state machine (driven by OK ADDED / OK DELETED on status).
    private enum class ExportPhase { IDLE, DEL_TOTP, ADD_TOTP, DEL_PW, ADD_PW }
    private var exportPhase = ExportPhase.IDLE
    private var exportDelRemaining = 0
    private var exportAddIndex = 0
    // Deduped copy of the cache used for the device (firmware labels must
    // be unique; Bitwarden names needn't be).
    private var exportTotps: List<TotpEntry> = emptyList()
    private var exportRenamed = 0
    private var exportInFlightLabel = ""

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
        // Bitwarden server config, if the user set one up before.
        val prefs = context.applicationContext.getSharedPreferences("bw", Context.MODE_PRIVATE)
        _bwServer.value = prefs.getString("server", "").orEmpty()
        _bwEmail.value = prefs.getString("email", "").orEmpty()
        _bwHasSavedPw.value = prefs.getString("master_pw", null) != null
        _hasVaultPin.value = prefs.getString("vault_pin", null) != null
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
        usedSavedPin = false
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

    // ---- Bitwarden sync ----

    fun hasBwConfig(): Boolean = _bwServer.value.isNotEmpty() && _bwEmail.value.isNotEmpty()

    fun clearBwConfig() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences("bw", Context.MODE_PRIVATE).edit().clear().apply()
        _bwServer.value = ""
        _bwEmail.value = ""
        _bwHasSavedPw.value = false
        _bwState.value = ConnState.Idle
        resetBwSetup()
    }

    /** Forgets just the saved master password; server + email stay. */
    fun clearBwSavedPw() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences("bw", Context.MODE_PRIVATE).edit().remove("master_pw").apply()
        _bwHasSavedPw.value = false
        _bwState.value = ConnState.Idle
    }

    // ---- vault PIN (ESP32 BLE auth) ----

    fun setVaultPin(pin: String) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences("bw", Context.MODE_PRIVATE).edit().putString("vault_pin", pin).apply()
        _hasVaultPin.value = true
    }

    fun clearVaultPin() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences("bw", Context.MODE_PRIVATE).edit().remove("vault_pin").apply()
        _hasVaultPin.value = false
    }

    private fun savedVaultPin(): String? {
        val ctx = appContext ?: return null
        return ctx.getSharedPreferences("bw", Context.MODE_PRIVATE).getString("vault_pin", null)
    }

    // ---- Bitwarden setup wizard (one visible step at a time) ----
    private val _bwStep = MutableStateFlow(0) // 0 = server, 1 = email, 2 = master password
    val bwStep: StateFlow<Int> = _bwStep
    private val _bwSetupServer = MutableStateFlow("")
    val bwSetupServer: StateFlow<String> = _bwSetupServer
    private val _bwSetupEmail = MutableStateFlow("")
    val bwSetupEmail: StateFlow<String> = _bwSetupEmail

    /** Adds https:// when the user typed a bare host — one less thing to type. */
    fun setBwSetupServer(v: String) {
        var s = v.trim().trimEnd('/')
        if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        _bwSetupServer.value = s
    }
    fun setBwSetupEmail(v: String) { _bwSetupEmail.value = v.trim() }
    fun bwStepNext() { if (_bwStep.value < 2) _bwStep.value++ }
    fun bwStepBack() { if (_bwStep.value > 0) _bwStep.value-- }
    private fun resetBwSetup() {
        _bwStep.value = 0
        _bwSetupServer.value = ""
        _bwSetupEmail.value = ""
    }

    /**
     * Pulls the vault from the Bitwarden server, decrypts it on-watch, and
     * replaces the local cache — the same cache the BLE sync fills.
     * [server]/[email] are remembered for next time when non-empty;
     * [password] is saved to private prefs after a successful sync (the
     * user's choice for one-tap syncs) and never displayed.
     */
    fun syncFromBitwarden(server: String, email: String, password: String) {
        val ctx = appContext ?: return
        bwJob?.cancel()
        _bwState.value = ConnState.Syncing
        _bwStatus.value = ""
        bwJob = viewModelScope.launch {
            try {
                val prefs = ctx.getSharedPreferences("bw", Context.MODE_PRIVATE)
                val savedRefresh = prefs.getString("refresh", null)
                val (data, newRefresh) = BitwardenSync.sync(
                    server, email, password, savedRefresh
                ) { _bwStatus.value = it }
                // Remember server+email+refresh for one-tap syncs, and the
                // master password itself (never displayed) after a good sync.
                prefs.edit()
                    .putString("server", server.trim().trimEnd('/'))
                    .putString("email", email.trim())
                    .putString("refresh", newRefresh ?: savedRefresh)
                    .putString("master_pw", password)
                    .apply()
                _bwServer.value = server.trim().trimEnd('/')
                _bwEmail.value = email.trim()
                _bwHasSavedPw.value = true
                // Same finish as a BLE sync: cache becomes the app.
                _totps.value = data.totps
                _pwEntries.value = data.passwords
                _lastSyncAt.value = System.currentTimeMillis()
                _hasCache.value = true
                VaultCache.save(ctx, data.totps, data.passwords)
                _bwStatus.value = "Synced ✓"
                _bwState.value = ConnState.Ready
            } catch (e: Exception) {
                _bwState.value = ConnState.Error(
                    e.message?.take(120) ?: "Sync failed"
                )
            }
        }
    }

    /** One-tap sync using the remembered server, email, and master password. */
    fun syncFromBitwardenSaved() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("bw", Context.MODE_PRIVATE)
        val server = prefs.getString("server", "").orEmpty()
        val email = prefs.getString("email", "").orEmpty()
        val pw = prefs.getString("master_pw", null) ?: return
        if (server.isEmpty() || email.isEmpty()) return
        syncFromBitwarden(server, email, pw)
    }

    fun resetBwState() {
        bwJob?.cancel()
        _bwState.value = ConnState.Idle
        _bwStatus.value = ""
        resetBwSetup()
    }

    /** Clears a sync error but keeps the wizard's typed values, for retry. */
    fun clearBwError() {
        bwJob?.cancel()
        _bwState.value = ConnState.Idle
        _bwStatus.value = ""
    }

    // ---- auto-scan handoff ----
    // The device screen auto-scans only when a device job (import/export)
    // was explicitly requested from Settings — not on swipe-back.
    private var autoScanPending = false

    /** Ask for a BLE device job; the device screen picks it up and scans. */
    fun requestBleJob(job: BleJob) {
        bleJob = job
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

    // ---- export to device ----
    // Replaces the device's vault with the watch's cache: delete-all, then
    // re-add every cached entry. Sequenced by OK DELETED / OK ADDED on status.

    private fun startExport() {
        _conn.value = ConnState.Syncing
        exportPhase = ExportPhase.DEL_TOTP
        exportDelRemaining = 0
        exportAddIndex = 0
        exportTotps = dedupeLabels(_totps.value)
        exportInFlightLabel = ""
        _status.value = "Reading device…"
        armWatchdog()
        ble.readTotpCount()
    }

    /**
     * The firmware requires unique normalized labels, but Bitwarden allows
     * duplicate names ("Bank" and "BANK" collide after normalization).
     * Rename collisions to LABEL_2, LABEL_3… so nothing is lost on the device.
     */
    private fun dedupeLabels(entries: List<TotpEntry>): List<TotpEntry> {
        val seen = mutableSetOf<String>()
        exportRenamed = 0
        return entries.map { e ->
            var label = Protocol.normalizeLabel(e.label).ifEmpty { "CODE" }
            val base = label
            var n = 2
            while (!seen.add(label)) {
                label = "${base}_$n"
                n++
            }
            if (label != Protocol.normalizeLabel(e.label)) exportRenamed++
            e.copy(label = label)
        }
    }

    private fun exportStepOnDeleted() {
        armWatchdog() // large vaults take a while; don't time out mid-export
        when (exportPhase) {
            ExportPhase.DEL_TOTP -> {
                exportDelRemaining--
                if (exportDelRemaining > 0) {
                    _status.value = "Clearing codes…"
                    ble.writeTotpDelete(0)
                } else {
                    exportPhase = ExportPhase.ADD_TOTP
                    exportAddIndex = 0
                    exportNextTotpAdd()
                }
            }
            ExportPhase.DEL_PW -> {
                exportDelRemaining--
                if (exportDelRemaining > 0) {
                    _status.value = "Clearing passwords…"
                    ble.writePwDelete(0)
                } else {
                    exportPhase = ExportPhase.ADD_PW
                    exportAddIndex = 0
                    exportNextPwAdd()
                }
            }
            else -> Unit
        }
    }

    private fun exportNextTotpAdd() {
        val list = exportTotps
        if (exportAddIndex >= list.size) {
            // Codes done — move to passwords.
            exportPhase = ExportPhase.DEL_PW
            _status.value = "Reading passwords…"
            ble.readPwCount()
            return
        }
        val e = list[exportAddIndex]
        exportInFlightLabel = e.label
        _status.value = "Codes ${exportAddIndex + 1}/${list.size}…"
        ble.writeAddTotp(e.label, e.secret)
    }

    private fun exportNextPwAdd() {
        val list = _pwEntries.value
        if (exportAddIndex >= list.size) {
            finishExport()
            return
        }
        val e = list[exportAddIndex]
        exportInFlightLabel = e.label
        _status.value = "Passwords ${exportAddIndex + 1}/${list.size}…"
        ble.writePwAdd(e.label, e.username, e.password)
    }

    private fun exportStepOnAdded() {
        armWatchdog() // large vaults take a while; don't time out mid-export
        when (exportPhase) {
            ExportPhase.ADD_TOTP -> {
                exportAddIndex++
                exportNextTotpAdd()
            }
            ExportPhase.ADD_PW -> {
                exportAddIndex++
                exportNextPwAdd()
            }
            else -> Unit
        }
    }

    private fun finishExport() {
        syncWatchdog?.cancel()
        exportPhase = ExportPhase.IDLE
        _status.value =
            if (exportRenamed > 0) "Exported ✓ ($exportRenamed renamed)"
            else "Exported ✓"
        _conn.value = ConnState.Ready
        viewModelScope.launch {
            delay(1500)
            ble.disconnect()
        }
    }

    private fun failBleJob(msg: String) {
        syncWatchdog?.cancel()
        exportPhase = ExportPhase.IDLE
        _conn.value = ConnState.Error(msg)
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
        val pin = savedVaultPin()
        if (pin != null) {
            // Always authenticate when we have a PIN — the firmware gates
            // vault reads/writes on auth even when unlocked.
            usedSavedPin = true
            _conn.value = ConnState.Syncing
            _status.value = "Unlocking…"
            armWatchdog()
            ble.authenticate(pin)
        } else if (locked) {
            syncWatchdog?.cancel()
            _conn.value = ConnState.NeedPin
        } else if (bleJob == BleJob.EXPORT) {
            // Export can't probe auth with a harmless read — ask up front.
            syncWatchdog?.cancel()
            _conn.value = ConnState.NeedPin
            _status.value = "Enter vault PIN"
        } else {
            // Import: the export read returns blank when unauthed, which
            // falls back to the PIN screen.
            beginBleJob()
        }
    }

    /** Dispatches to import or export once the device is unlocked. */
    private fun beginBleJob() {
        if (bleJob == BleJob.EXPORT) startExport() else syncVault()
    }

    override fun onVaultExport(json: String) {
        if (json.isBlank()) {
            // The firmware returns an empty value for reads before auth.
            // This happens when the device is unlocked but we haven't sent
            // the PIN yet — ask for it instead of failing the import.
            syncWatchdog?.cancel()
            _conn.value = ConnState.NeedPin
            _status.value = "Enter vault PIN"
            return
        }
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
            failBleJob("Couldn't parse vault data — retry")
        }
    }

    override fun onTotpCount(count: Int) {
        // Only used by export: clear the device's codes before re-adding.
        if (exportPhase != ExportPhase.DEL_TOTP) return
        if (count <= 0) {
            exportPhase = ExportPhase.ADD_TOTP
            exportAddIndex = 0
            exportNextTotpAdd()
        } else {
            exportDelRemaining = count
            _status.value = "Clearing codes…"
            ble.writeTotpDelete(0)
        }
    }

    override fun onPwCount(count: Int) {
        if (exportPhase == ExportPhase.DEL_PW) {
            // Export: clear the device's passwords before re-adding.
            if (count <= 0) {
                exportPhase = ExportPhase.ADD_PW
                exportAddIndex = 0
                exportNextPwAdd()
            } else {
                exportDelRemaining = count
                _status.value = "Clearing passwords…"
                ble.writePwDelete(0)
            }
            return
        }
        pwTotal = count
        if (count <= 0) {
            finishSync()
        } else {
            _status.value = "Passwords 0/$count…"
            ble.requestPw(0)
        }
    }

    override fun onPwEntry(label: String, username: String, password: String) {
        armWatchdog() // large vaults take a while; don't time out mid-import
        pwAccum.add(PwEntry(label, username, password))
        _status.value = "Passwords ${pwAccum.size}/$pwTotal…"
        if (pwAccum.size >= pwTotal) {
            finishSync()
        } else {
            ble.requestPw(pwAccum.size)
        }
    }

    override fun onStatus(text: String) {
        // Auth + export sequencing arrives here.
        when {
            text == "OK AUTH" -> beginBleJob()
            text == "OK ADDED" -> {
                if (exportPhase == ExportPhase.ADD_TOTP || exportPhase == ExportPhase.ADD_PW) {
                    exportStepOnAdded()
                }
            }
            text == "OK DELETED" -> {
                if (exportPhase == ExportPhase.DEL_TOTP || exportPhase == ExportPhase.DEL_PW) {
                    exportStepOnDeleted()
                }
            }
            text == "ERR AUTH" -> {
                syncWatchdog?.cancel()
                exportPhase = ExportPhase.IDLE
                if (usedSavedPin) {
                    // The saved PIN was rejected — fall back to manual entry
                    // instead of erroring out.
                    usedSavedPin = false
                    _status.value = "Saved PIN didn't work"
                    _conn.value = ConnState.NeedPin
                } else {
                    _conn.value = ConnState.Error("Wrong PIN — try again")
                }
            }
            text == "ERR LOCKED" -> {
                failBleJob("Device is locked — triple-tap its button")
            }
            text.startsWith("ERR") -> {
                failBleJob(friendlyBleError(text))
            }
        }
    }

    /** Turns firmware error codes into something a human can act on. */
    private fun friendlyBleError(text: String): String {
        val label = exportInFlightLabel.ifEmpty { "entry" }
        return when {
            text.startsWith("ERR DUP") -> "Duplicate label: $label"
            text.startsWith("ERR SECRET") -> "Bad code secret: $label"
            text.startsWith("ERR LABEL") -> "Bad label: $label"
            text.startsWith("ERR INDEX") -> "Device list changed — retry"
            text.startsWith("ERR SAVE") -> "Device couldn't save — retry"
            text.startsWith("ERR JSON") -> "Couldn't send: $label"
            else -> text
        }
    }

    override fun onDisconnected() {
        syncWatchdog?.cancel()
        exportPhase = ExportPhase.IDLE
        usedSavedPin = false
        _conn.value = ConnState.Idle
    }

    override fun onError(text: String) {
        failBleJob(text)
    }
}
