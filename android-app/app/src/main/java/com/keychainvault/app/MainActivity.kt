package com.keychainvault.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.keychainvault.app.databinding.ActivityMainBinding
import com.keychainvault.app.databinding.DialogAddTotpBinding
import com.keychainvault.app.databinding.DialogPwBinding
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray

/**
 * Esp Vault companion app — full management over BLE.
 *
 * Flow: Scan & Connect -> read device_info (firmware + lock state) ->
 * authenticate with the portal password -> browse TOTP codes, passwords,
 * Bitwarden import, and device settings.
 *
 * Firmware 1.4.4+ adds full CRUD: Bitwarden JSON import, TOTP edit/delete,
 * password add/edit/delete. The v1 features (read/auth/codes) work on 1.4.3+.
 * Firmware 1.4.5+ adds the first-boot setup wizard (device_info reports
 * setup=1) and the ble_auto toggle. Firmware 1.4.6+ adds device settings
 * over BLE: portal-password change, WiFi update, timezone get/set.
 *
 * The portal password is never persisted and never logged; all secret UI
 * state is cleared on disconnect.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var totpAdapter: LabelAdapter
    private lateinit var pwAdapter: LabelAdapter

    private var authed = false
    private var fwVersion = ""
    private var crudOk = false
    private var settingsSupported = false
    private var totpLabels: List<String> = emptyList()
    private var pwLabels: List<String> = emptyList()
    private var totpLoaded = false
    private var pwLoaded = false
    private var pwCountFromDevice = -1
    private var pwCountLoaded = false
    private var postRefreshMsg: String? = null
    // Paged password loading (firmware 1.5.0+)
    private var pwLabelsAccum = mutableListOf<String>()
    private var pwCurrentPage = 0
    private var pwTotalCount = 0

    private var selectedTotp = -1
    private var rawCode = ""
    private var codeExpiresAt = 0L
    private var countdownTask: Runnable? = null

    private var pwUsername = ""
    private var pwPassword = ""
    private var pwVisible = false
    private var pendingPwEditIndex = -1

    private var awaitingMutation = false

    // First-boot setup wizard (firmware 1.4.5+)
    private var setupMode = false
    private var setupStep = 0 // 1 = password, 2 = wifi, 3 = finish, 4 = rebooting
    private var setupPasswordSet = false
    private var setupPassword = "" // kept in memory only; pre-fills auth after reboot
    private var setupRebootPending = false
    private var setupJustFinished = false // offer Bitwarden import after post-setup auth

    // BLE auto-start flag (firmware 1.4.5+)
    private var bleAutoSupported = false
    private var bleAutoUiGuard = false

    // Timezone zones: (display name, POSIX TZ string). The POSIX strings must
    // match what the firmware's portal uses (see BLE_PROTOCOL.md).
    private val tzZones = listOf(
        "UTC" to "UTC0",
        "America/Chicago" to "CST6CDT,M3.2.0,M11.1.0",
        "America/Winnipeg" to "CST6CDT,M3.2.0,M11.1.0",
        "America/Toronto" to "EST5EDT,M3.2.0,M11.1.0",
        "America/Vancouver" to "PST8PDT,M3.2.0,M11.1.0",
        "America/New_York" to "EST5EDT,M3.2.0,M11.1.0",
        "America/Los_Angeles" to "PST8PDT,M3.2.0,M11.1.0",
        "Europe/London" to "GMT0BST,M3.5.0,M10.5.0",
        "Europe/Berlin" to "CET-1CEST,M3.5.0,M10.5.0"
    )

    // Bitwarden import state
    private data class ImportOp(val kind: String, val run: () -> Unit)
    private var importQueue: ArrayDeque<ImportOp>? = null
    private var importTotal = 0
    private var importAdded = 0
    private var importAddedTotps = 0
    private var importAddedPws = 0
    private var importFailed = 0
    private var importSkipped = 0
    private var importWatchdog: Runnable? = null
    private var importVerifyPending = false
    private var importLastError: String? = null

    // Backup export state (firmware 1.4.7+)
    private var exportOk = false
    private var exporting = false
    private var exportCollecting = false
    private var exportTotps: List<Pair<String, String>> = emptyList()
    private var exportPwEntries = mutableListOf<Triple<String, String, String>>()
    private var exportPwIndex = 0
    private var exportWatchdog: Runnable? = null

    private var authTimeoutTask: Runnable? = null
    private var scanTimeoutTask: Runnable? = null
    private var scanning = false
    private var disconnectMsg: String? = null // message to show when onDisconnected follows

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScanFlow()
        else setStatus("Bluetooth permissions denied — cannot scan.")
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter.isEnabled) startScanFlow()
        else setStatus("Bluetooth is off.")
    }

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) doImport(uri)
    }

    // QR scanner result: holds the dialog binding to populate on return.
    private var pendingQrDialog: com.keychainvault.app.databinding.DialogAddTotpBinding? = null
    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val dBinding = pendingQrDialog
        pendingQrDialog = null
        if (result.resultCode == RESULT_OK && dBinding != null) {
            val label = result.data?.getStringExtra(QrScanActivity.EXTRA_LABEL).orEmpty()
            val secret = result.data?.getStringExtra(QrScanActivity.EXTRA_SECRET).orEmpty()
            if (label.isNotEmpty()) dBinding.labelEdit.setText(label)
            if (secret.isNotEmpty()) dBinding.secretEdit.setText(secret)
            if (label.isNotEmpty() || secret.isNotEmpty()) {
                Toast.makeText(this, "QR code scanned", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter
        ble = BleManager(this, bleListener)

        totpAdapter = LabelAdapter({ onTotpSelected(it) }, { onTotpLongPress(it) })
        binding.totpRecycler.layoutManager = LinearLayoutManager(this)
        binding.totpRecycler.adapter = totpAdapter
        // Search filter for TOTP codes
        binding.totpSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                totpAdapter.filter(s?.toString().orEmpty())
            }
        })

        pwAdapter = LabelAdapter({ onPwSelected(it) }, { onPwLongPress(it) })
        binding.pwRecycler.layoutManager = LinearLayoutManager(this)
        binding.pwRecycler.adapter = pwAdapter
        // Search filter for passwords
        binding.pwSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                pwAdapter.filter(s?.toString().orEmpty())
            }
        })

        val tzAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            tzZones.map { it.first }
        )
        tzAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.tzSpinner.adapter = tzAdapter

        binding.scanButton.setOnClickListener { ensurePermissionsThenScan() }
        binding.disconnectButton.setOnClickListener { ble.disconnect() }
        binding.authButton.setOnClickListener { onAuth() }
        binding.passwordEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onAuth()
                true
            } else false
        }
        binding.copyCodeButton.setOnClickListener { copyToClipboard(rawCode, "Code copied.") }
        binding.togglePwButton.setOnClickListener { togglePwVisibility() }
        binding.copyUserButton.setOnClickListener { copyToClipboard(pwUsername, "Username copied.") }
        binding.copyPassButton.setOnClickListener { copyToClipboard(pwPassword, "Password copied.") }
        binding.syncTimeButton.setOnClickListener { ble.timeSync() }
        binding.addTotpButton.setOnClickListener { showAddTotpDialog() }
        binding.addPwButton.setOnClickListener { showAddPwDialog() }
        binding.importButton.setOnClickListener { importPicker.launch(arrayOf("application/json")) }
        binding.exportButton.setOnClickListener { onExportClick() }
        binding.lockButton.setOnClickListener { confirmLock() }
        binding.changePwButton.setOnClickListener { onChangePw() }
        binding.saveWifiButton.setOnClickListener { onSaveWifi() }
        binding.saveTzButton.setOnClickListener { onSaveTz() }
        binding.setupPasswordButton.setOnClickListener { onSetupPasswordContinue() }
        binding.setupWifiSaveButton.setOnClickListener { onSetupWifiSave() }
        binding.setupWifiSkipButton.setOnClickListener { onSetupWifiSkip() }
        binding.setupFinishButton.setOnClickListener { onSetupFinish() }
        binding.bleAutoSwitch.setOnCheckedChangeListener { _, checked ->
            if (bleAutoUiGuard) return@setOnCheckedChangeListener
            if (!authed || !bleAutoSupported) return@setOnCheckedChangeListener
            setStatus("Updating Bluetooth auto-start…")
            ble.writeBleAuto(checked)
        }

        // Bottom navigation: one page per area. Starts on Settings so a fresh
        // launch shows the connection card.
        binding.bottomNav.setOnItemSelectedListener { item ->
            val page = when (item.itemId) {
                R.id.nav_codes -> 0
                R.id.nav_passwords -> 1
                R.id.nav_backup -> 2
                R.id.nav_settings -> 3
                else -> return@setOnItemSelectedListener false
            }
            selectPage(page)
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_settings
        setAuthedUi(false)

        setStatus(
            "Triple-tap the device to unlock, then Scan & Connect."
        )
    }

    override fun onDestroy() {
        try {
            ble.disconnect()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun showInfo(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------- permissions + scan ----------------

    private fun ensurePermissionsThenScan() {
        if (!bluetoothAdapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
            return
        }
        startScanFlow()
    }

    private fun startScanFlow() {
        if (scanning) return
        scanning = true
        binding.scanButton.isEnabled = false
        setStatus("Scanning for EspVault… (unlock the device first)")
        ble.startScan(bluetoothAdapter)
        scanTimeoutTask = Runnable {
            if (scanning) {
                scanning = false
                ble.stopScan()
                binding.scanButton.isEnabled = true
                setStatus(
                    "Device not found. Make sure it is unlocked — if the radio " +
                        "doesn't start on unlock, hold the device button at the " +
                        "splash screen to enter portal mode (BLE is always on there), " +
                        "then Scan & Connect."
                )
            }
        }.also { handler.postDelayed(it, 12_000) }
    }

    private fun onDeviceFound(device: BluetoothDevice) {
        scanning = false
        scanTimeoutTask?.let { handler.removeCallbacks(it) }
        setStatus("Connecting…")
        ble.connect(device)
    }

    // ---------------- BLE events ----------------

    private val bleListener = object : BleManager.Listener {
        override fun onDeviceFound(device: BluetoothDevice) =
            this@MainActivity.onDeviceFound(device)

        override fun onDeviceInfo(fw: String, locked: Boolean, setup: Boolean) {
            fwVersion = fw
            crudOk = Protocol.supportsCrud(fw)
            bleAutoSupported = Protocol.supportsSetup(fw)
            settingsSupported = Protocol.supportsSettings(fw)
            exportOk = Protocol.supportsExport(fw)
            binding.deviceStatusText.text = Protocol.DEVICE_NAME
            binding.firmwareText.text = "Firmware $fw"
            binding.scanButton.isEnabled = false
            binding.disconnectButton.isEnabled = true
            if (setup) {
                // First-boot setup: the device has no password yet, so skip the
                // normal unlock/auth UI and show the setup wizard instead.
                showSetupWizard()
                return
            }
            binding.authCard.visibility = View.VISIBLE
            binding.authButton.isEnabled = true
            if (locked) {
                binding.authHintText.text =
                    "Device is LOCKED — triple-tap the device button to unlock, then authenticate."
                setStatus("Connected. Device is locked — triple-tap to unlock.")
            } else {
                binding.authHintText.text = ""
                setStatus("Connected. Enter the portal password.")
            }
            binding.passwordEdit.requestFocus()
            // Bring the user to the Settings page where the sign-in card lives.
            binding.bottomNav.selectedItemId = R.id.nav_settings
        }

        override fun onBleAuto(enabled: Boolean) {
            bleAutoUiGuard = true
            binding.bleAutoSwitch.isChecked = enabled
            bleAutoUiGuard = false
        }

        override fun onTimezone(name: String, tz: String) {
            binding.tzCurrentText.text =
                if (name.isEmpty()) "Current: —" else "Current: $name"
            val idx = tzZones.indexOfFirst { it.second == tz }
            if (idx >= 0) binding.tzSpinner.setSelection(idx)
        }

        override fun onVaultExport(json: String) =
            this@MainActivity.onVaultExport(json)

        override fun onTotpLabels(labels: List<String>) {
            totpLabels = labels
            totpLoaded = true
            totpAdapter.setItems(labels)
            maybeEntriesDone()
        }

        override fun onPwLabels(labels: List<String>) {
            // Legacy (firmware < 1.5.0): full list in one read.
            pwLabels = labels
            pwLoaded = true
            pwAdapter.setItems(labels)
            maybeEntriesDone()
        }

        override fun onPwLabelPage(labels: List<String>) {
            // Paged labels (firmware 1.5.0+): accumulate pages until we have them all.
            pwLabelsAccum.addAll(labels)
            pwCurrentPage++
            val totalPages = (pwTotalCount + Protocol.PW_LABELS_PER_PAGE - 1) / Protocol.PW_LABELS_PER_PAGE
            if (pwCurrentPage < totalPages) {
                // Load next page
                ble.readPwLabelPage(pwCurrentPage)
            } else {
                // All pages loaded
                pwLabels = pwLabelsAccum.toList()
                pwLoaded = true
                pwAdapter.setItems(pwLabels)
                maybeEntriesDone()
            }
        }

        override fun onPwCount(count: Int) {
            pwCountFromDevice = count
            pwCountLoaded = true
            // Start paged password loading (firmware 1.5.0+) or legacy single read.
            // PW_COUNT arrives before we know whether to page, so kick off loading here.
            if (!pwLoaded) {
                if (Protocol.supportsPagedPwLabels(fwVersion) && count > 0) {
                    pwTotalCount = count
                    pwCurrentPage = 0
                    pwLabelsAccum.clear()
                    ble.readPwLabelPage(0)
                } else if (!Protocol.supportsPagedPwLabels(fwVersion)) {
                    // Legacy firmware: single read
                    ble.readPwLabels()
                } else {
                    // No passwords — mark loaded immediately
                    pwLabels = emptyList()
                    pwLoaded = true
                    pwAdapter.setItems(emptyList())
                    maybeEntriesDone()
                }
            } else {
                maybeEntriesDone()
            }
        }

        override fun onStatus(text: String) = handleStatus(text)

        override fun onTotpCode(label: String, code: String, secondsLeft: Int) =
            showCode(code, secondsLeft)

        override fun onPwEntry(label: String, username: String, password: String) {
            if (exportCollecting) {
                this@MainActivity.onExportPwEntry(label, username, password)
                return
            }
            if (pendingPwEditIndex >= 0) {
                val idx = pendingPwEditIndex
                pendingPwEditIndex = -1
                showPwEntry(label, username, password)
                showEditPwDialog(idx, label, username, password)
            } else {
                showPwEntry(label, username, password)
            }
        }

        override fun onDisconnected() {
            // During the wizard's reboot wait the device drops the connection on
            // purpose — keep the wizard UI and let the delayed scan take over.
            if (setupRebootPending) return
            val msg = disconnectMsg ?: "Device disconnected."
            disconnectMsg = null
            onDeviceGone(msg)
        }

        override fun onError(text: String) = setStatus(text)
    }

    private fun handleStatus(text: String) {
        // The first-boot setup wizard consumes its own statuses.
        if (setupMode) {
            handleSetupStatus(text)
            return
        }
        // Bitwarden import consumes its own terminal statuses.
        if (importQueue != null && (text == "OK ADDED" || text.startsWith("ERR"))) {
            onImportStatus(text)
            return
        }
        when {
            text == "OK AUTH" -> {
                authTimeoutTask?.let { handler.removeCallbacks(it) }
                authed = true
                setupPassword = "" // no longer needed after the first auth
                setAuthedUi(true)
                setStatus("Loading entries…")
                refreshLists()
            }
            text == "ERR AUTH" -> {
                authTimeoutTask?.let { handler.removeCallbacks(it) }
                setStatus("Wrong password.")
            }
            text == "ERR LOCKED" ->
                setStatus("Device is locked — triple-tap to unlock.")
            text == "OK TIME" ->
                setStatus("Time synced.")
            text == "OK AUTO ON" ->
                setStatus("Bluetooth will now start automatically on unlock.")
            text == "OK AUTO OFF" ->
                setStatus("Bluetooth auto-start is off.")
            // ---- v1.4.6+ device settings ----
            text == "OK CHANGED" -> {
                resetSettingsBusy()
                clearChangePwFields()
                setStatus("Portal password changed. Use the new one next time you connect.")
            }
            text == "ERR OLD" -> {
                resetSettingsBusy()
                binding.changePwError.text = "The current password didn't match — try again."
                setStatus("Password not changed.")
            }
            text == "ERR SHORT" -> {
                resetSettingsBusy()
                binding.changePwError.text = "New password must be at least 8 characters."
                setStatus("Password not changed.")
            }
            text == "OK WIFI" -> {
                resetSettingsBusy()
                clearWifiFields()
                setStatus("WiFi credentials saved.")
            }
            text == "ERR SSID" -> {
                resetSettingsBusy()
                binding.wifiError.text = "WiFi network name is required."
                setStatus("WiFi not saved.")
            }
            text == "OK TZ" -> {
                resetSettingsBusy()
                binding.tzError.text = ""
                val (name, _) = tzZones[binding.tzSpinner.selectedItemPosition]
                binding.tzCurrentText.text = "Current: $name"
                setStatus("Timezone updated to $name.")
            }
            text == "ERR TZ" -> {
                resetSettingsBusy()
                binding.tzError.text = "That timezone wasn't accepted by the device."
                setStatus("Timezone not changed.")
            }
            text == "ERR JSON" -> {
                resetSettingsBusy()
                setStatus("The device couldn't understand the request.")
            }
            text == "OK ADDED" || text == "OK UPDATED" || text == "OK DELETED" -> {
                awaitingMutation = false
                val msg = when (text) {
                    "OK ADDED" -> "Added."
                    "OK UPDATED" -> "Updated."
                    else -> "Deleted."
                }
                refreshLists(msg)
            }
            text == "LOCKED" || text == "TIMEOUT" -> {
                // The device drops the connection right after these notifies;
                // keep the meaningful message for the onDisconnected that follows.
                disconnectMsg = "Device ended the session ($text)."
                onDeviceGone(disconnectMsg!!)
            }
            text.startsWith("ERR") -> {
                awaitingMutation = false
                resetSettingsBusy()
                refreshLists()
                setStatus("Device: $text")
            }
            else ->
                setStatus("Device: $text")
        }
    }

    private fun maybeEntriesDone() {
        if (totpLoaded && pwLoaded && pwCountLoaded) {
            // Bitwarden import verification: re-read after the whole queue
            // finished so the user sees the codes and passwords actually
            // landed on the device.
            if (importVerifyPending) {
                importVerifyPending = false
                postRefreshMsg = null
                val errLine =
                    if (importFailed > 0 && importLastError != null) " Last error: $importLastError."
                    else ""
                AlertDialog.Builder(this)
                    .setTitle("Import verified")
                    .setMessage(
                        "Import finished: $importAdded added " +
                            "($importAddedTotps TOTP codes, $importAddedPws passwords), " +
                            "$importFailed failed, $importSkipped already on device." +
                            errLine + " The device now holds ${totpLabels.size} TOTP codes and " +
                            "${pwLabels.size} passwords."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                importLastError = null
            }
            val msg = postRefreshMsg
            postRefreshMsg = null
            if (msg != null) setStatus(msg) else setStatus("")
            // Right after the first-boot wizard, an empty vault gets an import offer.
            if (setupJustFinished) {
                setupJustFinished = false
                if (totpLabels.isEmpty() && pwLabels.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Set up your vault")
                        .setMessage(
                            "Setup is complete and the vault is empty. " +
                                "Import your Bitwarden export now?"
                        )
                        .setPositiveButton("Import…") { _, _ ->
                            importPicker.launch(arrayOf("application/json"))
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
    }

    private fun refreshLists(doneMsg: String? = null) {
        postRefreshMsg = doneMsg
        clearCode()
        selectedTotp = -1
        clearPwDetail()
        totpLoaded = false
        pwLoaded = false
        pwCountLoaded = false
        pwCountFromDevice = -1
        // Reset paged loading state
        pwLabelsAccum.clear()
        pwCurrentPage = 0
        pwTotalCount = 0
        ble.readTotpLabels()
        // Password labels: read count first, then pages (1.5.0+) or legacy single read.
        // onPwCount kicks off the label loading.
        ble.readPwCount()
    }

    // ---------------- auth ----------------

    private fun onAuth() {
        val pw = binding.passwordEdit.text?.toString().orEmpty()
        if (pw.isEmpty()) {
            Toast.makeText(this, "Enter the portal password.", Toast.LENGTH_SHORT).show()
            return
        }
        setStatus("Authenticating…")
        ble.authenticate(pw)
        binding.passwordEdit.text?.clear() // never keep it in the UI
        authTimeoutTask = Runnable {
            if (!authed) setStatus("No response from device.")
        }.also { handler.postDelayed(it, 15_000) }
    }

    private fun setAuthedUi(on: Boolean) {
        if (on) binding.authCard.visibility = View.GONE
        // Page placeholders vs real content.
        binding.pageCodesPlaceholder.visibility = if (on) View.GONE else View.VISIBLE
        binding.pageCodesContent.visibility = if (on) View.VISIBLE else View.GONE
        binding.pagePasswordsPlaceholder.visibility = if (on) View.GONE else View.VISIBLE
        binding.pagePasswordsContent.visibility = if (on) View.VISIBLE else View.GONE
        binding.pageBackupPlaceholder.visibility = if (on) View.GONE else View.VISIBLE
        binding.pageBackupContent.visibility = if (on) View.VISIBLE else View.GONE
        // Settings cards: Security, Network, Device
        binding.securityCard.visibility = if (on) View.VISIBLE else View.GONE
        binding.networkCard.visibility = if (on) View.VISIBLE else View.GONE
        binding.deviceCard.visibility = if (on) View.VISIBLE else View.GONE
        // CRUD-gated hint needs firmware 1.4.4+.
        binding.crudHintText.visibility =
            if (on && !crudOk) View.VISIBLE else View.GONE
        if (on && !crudOk) {
            binding.crudHintText.text =
                "Import, editing and deleting need firmware 1.4.4+ (this device: $fwVersion)."
        }
        // Export needs firmware 1.4.7+.
        binding.exportHintText.visibility =
            if (on && !exportOk) View.VISIBLE else View.GONE
        if (on && !exportOk) {
            binding.exportHintText.text =
                "Export needs firmware 1.4.7+ (this device: $fwVersion)."
        }
        binding.totpHintText.visibility =
            if (on && crudOk) View.VISIBLE else View.GONE
        // Timezone needs firmware 1.4.6+.
        if (on && settingsSupported) ble.readTimezone()
        // BLE auto-start toggle needs firmware 1.4.5+.
        binding.bleAutoSwitch.isEnabled = on && bleAutoSupported
        if (on && bleAutoSupported) ble.readBleAuto()
        // Land on the Codes page after signing in.
        if (on) binding.bottomNav.selectedItemId = R.id.nav_codes
    }

    /** Bottom navigation page switch. Pages: 0 codes, 1 passwords, 2 backup, 3 settings. */
    private fun selectPage(page: Int) {
        binding.pageCodes.visibility = if (page == 0) View.VISIBLE else View.GONE
        binding.pagePasswords.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.pageBackup.visibility = if (page == 2) View.VISIBLE else View.GONE
        val settingsVisible = page == 3
        binding.pageSettingsTop.visibility = if (settingsVisible) View.VISIBLE else View.GONE
        binding.pageSettingsBottom.visibility = if (settingsVisible) View.VISIBLE else View.GONE
    }

    // ---------------- device settings (firmware 1.4.6+) ----------------

    private fun onChangePw() {
        val old = binding.changePwOldEdit.text?.toString().orEmpty()
        val new = binding.changePwNewEdit.text?.toString().orEmpty()
        val confirm = binding.changePwConfirmEdit.text?.toString().orEmpty()
        when {
            new.length < 8 ->
                binding.changePwError.text = "New password must be at least 8 characters."
            new != confirm ->
                binding.changePwError.text = "The two new passwords don't match."
            else -> {
                binding.changePwError.text = ""
                binding.changePwButton.isEnabled = false
                setStatus("Changing password…")
                ble.changePortalPassword(old, new)
            }
        }
    }

    private fun clearChangePwFields() {
        binding.changePwOldEdit.text?.clear()
        binding.changePwNewEdit.text?.clear()
        binding.changePwConfirmEdit.text?.clear()
        binding.changePwError.text = ""
    }

    private fun onSaveWifi() {
        val ssid = binding.wifiSsidEdit.text?.toString().orEmpty().trim()
        if (ssid.isEmpty()) {
            binding.wifiError.text = "WiFi network name is required."
            return
        }
        binding.wifiError.text = ""
        val wifiPw = binding.wifiPassEdit.text?.toString().orEmpty()
        binding.saveWifiButton.isEnabled = false
        setStatus("Saving WiFi…")
        ble.setWifi(ssid, wifiPw)
    }

    private fun clearWifiFields() {
        binding.wifiSsidEdit.text?.clear()
        binding.wifiPassEdit.text?.clear()
        binding.wifiError.text = ""
    }

    private fun onSaveTz() {
        val (name, posix) = tzZones[binding.tzSpinner.selectedItemPosition]
        binding.tzError.text = ""
        binding.saveTzButton.isEnabled = false
        setStatus("Saving timezone…")
        ble.writeTimezone(name, posix)
    }

    /** Re-enable the settings buttons after a settings operation finishes. */
    private fun resetSettingsBusy() {
        binding.changePwButton.isEnabled = true
        binding.saveWifiButton.isEnabled = true
        binding.saveTzButton.isEnabled = true
    }

    private fun clearSettingsUi() {
        clearChangePwFields()
        clearWifiFields()
        binding.tzError.text = ""
        binding.tzCurrentText.text = "Current: —"
        bleAutoUiGuard = true
        binding.bleAutoSwitch.isChecked = false
        binding.bleAutoSwitch.isEnabled = false
        bleAutoUiGuard = false
    }

    // ---------------- first-boot setup wizard (firmware 1.4.5+) ----------------

    private fun showSetupWizard() {
        setupMode = true
        setupRebootPending = false
        setupPasswordSet = false
        binding.normalUiContainer.visibility = View.GONE
        binding.bottomNav.visibility = View.GONE
        binding.setupWizardContainer.visibility = View.VISIBLE
        showSetupStep(1)
        setStatus("First-time setup — set the device's portal password.")
    }

    private fun showSetupStep(step: Int) {
        setupStep = step
        binding.setupStep1.visibility = if (step == 1) View.VISIBLE else View.GONE
        binding.setupStep2.visibility = if (step == 2) View.VISIBLE else View.GONE
        binding.setupStep3.visibility = if (step == 3) View.VISIBLE else View.GONE
        binding.setupStep4.visibility = if (step == 4) View.VISIBLE else View.GONE
    }

    private fun hideSetupWizard() {
        setupMode = false
        setupStep = 0
        setupPasswordSet = false
        binding.setupWizardContainer.visibility = View.GONE
        binding.normalUiContainer.visibility = View.VISIBLE
        binding.bottomNav.visibility = View.VISIBLE
    }

    private fun onSetupPasswordContinue() {
        val pw = binding.setupPasswordEdit.text?.toString().orEmpty()
        val confirm = binding.setupPasswordConfirmEdit.text?.toString().orEmpty()
        when {
            pw.length < 8 ->
                binding.setupPasswordError.text = "Password must be at least 8 characters."
            pw != confirm ->
                binding.setupPasswordError.text = "The two passwords don't match."
            else -> {
                binding.setupPasswordError.text = ""
                binding.setupPasswordButton.isEnabled = false
                setupPassword = pw // memory only; pre-fills auth after the reboot
                ble.setupSetPassword(pw)
            }
        }
    }

    private fun onSetupWifiSave() {
        val ssid = binding.setupSsidEdit.text?.toString().orEmpty().trim()
        if (ssid.isEmpty()) {
            binding.setupWifiError.text = "WiFi network name is required."
            return
        }
        binding.setupWifiError.text = ""
        val wifiPw = binding.setupWifiPasswordEdit.text?.toString().orEmpty()
        binding.setupWifiSaveButton.isEnabled = false
        binding.setupWifiSkipButton.isEnabled = false
        ble.setupSetWifi(ssid, wifiPw)
    }

    private fun onSetupWifiSkip() {
        showSetupStep(3)
        setStatus("Finishing setup will reboot the device.")
    }

    private fun onSetupFinish() {
        binding.setupFinishButton.isEnabled = false
        setStatus("Finishing setup…")
        ble.setupComplete()
    }

    /** Route device statuses while the setup wizard is active. */
    private fun handleSetupStatus(text: String) {
        when (text) {
            "OK PASSWORD" -> {
                setupPasswordSet = true
                binding.setupPasswordEdit.text?.clear()
                binding.setupPasswordConfirmEdit.text?.clear()
                binding.setupPasswordButton.isEnabled = true
                showSetupStep(2)
                setStatus("Password set. WiFi is optional.")
            }
            "OK WIFI" -> {
                binding.setupWifiSaveButton.isEnabled = true
                binding.setupWifiSkipButton.isEnabled = true
                showSetupStep(3)
                setStatus("WiFi saved.")
            }
            "OK DONE" ->
                onSetupReboot()
            "ERR SETUP" -> {
                disconnectMsg = "Setup is no longer available — the device was already set up."
                hideSetupWizard()
                ble.disconnect()
            }
            "ERR PASSWORD" -> {
                binding.setupFinishButton.isEnabled = true
                binding.setupPasswordButton.isEnabled = true
                showSetupStep(1)
                binding.setupPasswordError.text =
                    "Set the portal password first, then finish."
            }
            "ERR SHORT" -> {
                binding.setupPasswordButton.isEnabled = true
                binding.setupPasswordError.text =
                    "Password must be at least 8 characters."
            }
            "ERR SSID" -> {
                binding.setupWifiSaveButton.isEnabled = true
                binding.setupWifiSkipButton.isEnabled = true
                binding.setupWifiError.text = "WiFi network name is required."
            }
            "LOCKED", "TIMEOUT" -> {
                disconnectMsg = "Device ended the session ($text)."
                onDeviceGone(disconnectMsg!!)
            }
            else ->
                if (text.startsWith("ERR")) {
                    binding.setupPasswordButton.isEnabled = true
                    binding.setupWifiSaveButton.isEnabled = true
                    binding.setupWifiSkipButton.isEnabled = true
                    binding.setupFinishButton.isEnabled = true
                    setStatus("Device: $text")
                } else {
                    setStatus("Device: $text")
                }
        }
    }

    /**
     * setup_complete answered OK DONE: the device is rebooting. Disconnect,
     * wait for it to come back, then scan again automatically.
     */
    private fun onSetupReboot() {
        setupRebootPending = true
        setupJustFinished = true
        showSetupStep(4)
        setStatus("Device is rebooting — reconnecting…")
        // Secret fields are cleared; the plain password stays in memory only
        // so it can pre-fill the auth field after the reboot.
        binding.setupPasswordEdit.text?.clear()
        binding.setupPasswordConfirmEdit.text?.clear()
        binding.setupSsidEdit.text?.clear()
        binding.setupWifiPasswordEdit.text?.clear()
        try {
            ble.disconnect()
        } catch (_: Exception) {
        }
        // The reboot takes a few seconds; start scanning after it.
        handler.postDelayed({
            if (setupRebootPending) {
                setupRebootPending = false
                hideSetupWizard()
                // Pre-fill the new password so the user only has to tap Authenticate.
                binding.passwordEdit.setText(setupPassword)
                binding.scanButton.isEnabled = true
                setStatus("Device should be back up — scanning…")
                startScanFlow()
            }
        }, 7_000)
    }

    // ---------------- TOTP ----------------

    private fun onTotpSelected(index: Int) {
        if (!authed) return
        selectedTotp = index
        setStatus("Requesting code…")
        ble.requestTotp(index)
    }

    private fun onTotpLongPress(index: Int) {
        if (!authed || !crudOk) return
        val label = totpLabels.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> showEditTotpDialog(index, label)
                    1 -> confirmDeleteTotp(index, label)
                }
            }
            .show()
    }

    private fun showEditTotpDialog(index: Int, label: String) {
        val dBinding = DialogAddTotpBinding.inflate(LayoutInflater.from(this))
        dBinding.labelEdit.setText(label)
        dBinding.secretEdit.hint = "New base32 secret (required)"
        AlertDialog.Builder(this)
            .setTitle("Edit TOTP entry")
            .setMessage("The secret cannot be read back from the device — enter the new secret.")
            .setView(dBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = Protocol.normalizeLabel(
                    dBinding.labelEdit.text?.toString().orEmpty()
                )
                val secret = dBinding.secretEdit.text?.toString().orEmpty()
                    .trim().replace(" ", "")
                when {
                    newLabel.isEmpty() ->
                        setStatus("Label is required.")
                    !Protocol.isValidBase32Secret(secret) ->
                        setStatus("Secret must be 8–128 base32 characters (A–Z, 2–7).")
                    totpLabels.contains(newLabel) && newLabel != label ->
                        setStatus("An entry named $newLabel already exists.")
                    else -> {
                        awaitingMutation = true
                        ble.totpEdit(index, newLabel, secret)
                        setStatus("Updating…")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTotp(index: Int, label: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete TOTP entry")
            .setMessage("Delete \"$label\" from the device? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                awaitingMutation = true
                ble.totpDelete(index)
                setStatus("Deleting…")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCode(code: String, secondsLeft: Int) {
        rawCode = code
        binding.codeText.text =
            if (code.length >= 4) code.substring(0, 3) + " " + code.substring(3)
            else code
        binding.copyCodeButton.isEnabled = true
        codeExpiresAt = System.currentTimeMillis() + secondsLeft * 1000L
        startCountdown()
    }

    private fun startCountdown() {
        countdownTask?.let { handler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                val left = ((codeExpiresAt - System.currentTimeMillis()) / 1000).toInt()
                if (left <= 0) {
                    // Period rolled over: fetch a fresh code for the selection.
                    if (authed && selectedTotp >= 0) ble.requestTotp(selectedTotp)
                    else clearCode()
                    return
                }
                binding.countdownText.text = "${left}s"
                handler.postDelayed(this, 500)
            }
        }
        countdownTask = task
        handler.post(task)
    }

    private fun clearCode() {
        countdownTask?.let { handler.removeCallbacks(it) }
        countdownTask = null
        rawCode = ""
        binding.codeText.text = "—"
        binding.countdownText.text = ""
        binding.copyCodeButton.isEnabled = false
    }

    // ---------------- passwords ----------------

    private fun onPwSelected(index: Int) {
        if (!authed) return
        ble.requestPw(index)
    }

    private fun onPwLongPress(index: Int) {
        if (!authed || !crudOk) return
        val label = pwLabels.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> {
                        pendingPwEditIndex = index
                        ble.requestPw(index)
                        setStatus("Loading entry…")
                    }
                    1 -> confirmDeletePw(index, label)
                }
            }
            .show()
    }

    /** Generates a cryptographically strong random password. */
    private fun generatePassword(length: Int, withSymbols: Boolean): String {
        val lower = "abcdefghijklmnopqrstuvwxyz"
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val digits = "0123456789"
        val symbols = "!@#$%^&*()-_=+[]{}|;:,.<>?"
        var charset = lower + upper + digits
        if (withSymbols) charset += symbols
        val random = java.security.SecureRandom()
        return (1..length)
            .map { charset[random.nextInt(charset.length)] }
            .joinToString("")
    }

    /** Wires the Generate button + length spinner in a password dialog. */
    private fun setupPwGenerator(dBinding: DialogPwBinding) {
        val lengths = arrayOf("12", "16", "20", "24", "32")
        val spinnerAdapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, lengths
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dBinding.pwLengthSpinner.adapter = spinnerAdapter
        dBinding.pwLengthSpinner.setSelection(1) // default 16
        dBinding.pwGenerateButton.setOnClickListener {
            val len = dBinding.pwLengthSpinner.selectedItem?.toString()?.toIntOrNull() ?: 16
            val withSymbols = dBinding.pwSymbolsCheck.isChecked
            dBinding.pwPassEdit.setText(generatePassword(len, withSymbols))
        }
    }

    private fun showAddPwDialog() {
        val dBinding = DialogPwBinding.inflate(LayoutInflater.from(this))
        setupPwGenerator(dBinding)
        AlertDialog.Builder(this)
            .setTitle("Add password")
            .setView(dBinding.root)
            .setPositiveButton("Add") { _, _ ->
                val label = Bitwarden.sanitizeField(
                    dBinding.pwLabelEdit.text?.toString(), 40
                ).ifEmpty { "UNKNOWN" }
                val username = Bitwarden.sanitizeField(
                    dBinding.pwUserEdit.text?.toString(), 64
                )
                val password = dBinding.pwPassEdit.text?.toString().orEmpty()
                if (password.isEmpty()) {
                    setStatus("Password is required.")
                    return@setPositiveButton
                }
                awaitingMutation = true
                ble.pwAdd(label, username, password)
                setStatus("Adding…")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditPwDialog(index: Int, label: String, username: String, password: String) {
        val dBinding = DialogPwBinding.inflate(LayoutInflater.from(this))
        dBinding.pwLabelEdit.setText(label)
        dBinding.pwUserEdit.setText(username)
        dBinding.pwPassEdit.setText(password)
        setupPwGenerator(dBinding)
        AlertDialog.Builder(this)
            .setTitle("Edit password")
            .setView(dBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = Bitwarden.sanitizeField(
                    dBinding.pwLabelEdit.text?.toString(), 40
                ).ifEmpty { "UNKNOWN" }
                val newUser = Bitwarden.sanitizeField(
                    dBinding.pwUserEdit.text?.toString(), 64
                )
                val newPass = dBinding.pwPassEdit.text?.toString().orEmpty()
                if (newPass.isEmpty()) {
                    setStatus("Password is required.")
                    return@setPositiveButton
                }
                awaitingMutation = true
                ble.pwEdit(index, newLabel, newUser, newPass)
                setStatus("Updating…")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePw(index: Int, label: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete password")
            .setMessage("Delete \"$label\" from the device? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                awaitingMutation = true
                ble.pwDelete(index)
                setStatus("Deleting…")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPwEntry(label: String, username: String, password: String) {
        pwUsername = username
        pwPassword = password
        pwVisible = false
        binding.pwDetailLabel.text = label
        binding.pwDetailUser.text = username
        binding.pwDetailPass.text = mask(password)
        binding.togglePwButton.isEnabled = true
        binding.togglePwButton.text = "Show"
        binding.copyUserButton.isEnabled = true
        binding.copyPassButton.isEnabled = true
    }

    private fun clearPwDetail() {
        pwUsername = ""
        pwPassword = ""
        pwVisible = false
        pendingPwEditIndex = -1
        binding.pwDetailLabel.text = ""
        binding.pwDetailUser.text = ""
        binding.pwDetailPass.text = ""
        binding.togglePwButton.isEnabled = false
        binding.copyUserButton.isEnabled = false
        binding.copyPassButton.isEnabled = false
    }

    private fun mask(s: String) = "•".repeat(minOf(s.length, 16))

    private fun togglePwVisibility() {
        pwVisible = !pwVisible
        binding.pwDetailPass.text = if (pwVisible) pwPassword else mask(pwPassword)
        binding.togglePwButton.text = if (pwVisible) "Hide" else "Show"
    }

    // ---------------- Bitwarden import ----------------

    private fun doImport(uri: Uri) {
        setStatus("Reading export file…")
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText()
                if (text.isNullOrEmpty()) {
                    handler.post { setStatus("Could not read the file.") }
                    return@Thread
                }
                val parsed = Bitwarden.parse(text)
                handler.post { confirmImport(parsed) }
            } catch (e: Exception) {
                handler.post { setStatus("Import failed: not a valid Bitwarden JSON export.") }
            }
        }.start()
    }

    private fun confirmImport(parsed: Bitwarden.Parsed) {
        val newTotps = parsed.totps.filter { it.label !in totpLabels }
        val newPws = parsed.passwords.filter { it.label !in pwLabels }
        importSkipped = (parsed.totps.size - newTotps.size) +
            (parsed.passwords.size - newPws.size)
        if (newTotps.isEmpty() && newPws.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Import Bitwarden JSON")
                .setMessage("Nothing new to import — $importSkipped entries are already on the device.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Import Bitwarden JSON")
            .setMessage(
                "This will add:\n\n" +
                    "• ${newTotps.size} TOTP codes\n" +
                    "• ${newPws.size} passwords\n\n" +
                    "$importSkipped entries are already on the device and will be skipped. " +
                    "After the import the app re-reads the device to verify everything landed."
            )
            .setPositiveButton("Import") { _, _ -> startImport(newTotps, newPws) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startImport(
        totps: List<Bitwarden.TotpImport>,
        pws: List<Bitwarden.PwImport>
    ) {
        val q = ArrayDeque<ImportOp>()
        for (t in totps) q.add(ImportOp("totp") { ble.addTotp(t.label, t.secret) })
        for (p in pws) q.add(ImportOp("pw") { ble.pwAdd(p.label, p.username, p.password) })
        importQueue = q
        importTotal = q.size
        importAdded = 0
        importAddedTotps = 0
        importAddedPws = 0
        importFailed = 0
        importLastError = null
        armImportWatchdog()
        sendNextImport()
    }

    private fun sendNextImport() {
        val q = importQueue ?: return
        if (q.isEmpty()) {
            finishImport()
            return
        }
        val done = importAdded + importFailed
        setStatus("Importing ${done + 1}/$importTotal…")
        armImportWatchdog()
        val op = q.removeFirst()
        lastImportKind = op.kind
        op.run()
    }

    private fun armImportWatchdog() {
        importWatchdog?.let { handler.removeCallbacks(it) }
        val progress = importAdded + importFailed
        importWatchdog = Runnable {
            // No status arrived for the in-flight op: abort with a partial report.
            if (importQueue != null && importAdded + importFailed == progress) {
                importQueue = null
                importVerifyPending = true
                refreshLists()
            }
        }.also { handler.postDelayed(it, 20_000) }
    }

    private fun onImportStatus(text: String) {
        // The op just acknowledged is the one most recently sent, so count it
        // by the last-sent kind.
        val kind = lastImportKind
        if (text == "OK ADDED") {
            importAdded++
            if (kind == "totp") importAddedTotps++ else importAddedPws++
        } else {
            importFailed++
            importLastError = text
        }
        sendNextImport()
    }

    private var lastImportKind: String? = null

    private fun finishImport() {
        importQueue = null
        importWatchdog?.let { handler.removeCallbacks(it) }
        // Re-read both lists from the device to verify the import landed.
        importVerifyPending = true
        refreshLists()
    }

    // ---------------- backup export (firmware 1.4.7+) ----------------

    private fun onExportClick() {
        if (!authed) return
        if (!exportOk) {
            setStatus("Export needs firmware 1.4.7+ (this device: $fwVersion).")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Export backup")
            .setMessage(
                "This creates an UNENCRYPTED JSON backup file with ALL your TOTP " +
                    "secrets and passwords, in a format Bitwarden can import. " +
                    "Anyone who opens the file can read everything.\n\n" +
                    "Store it somewhere safe — it is your offline backup if you " +
                    "ever need to restore."
            )
            .setPositiveButton("Export") { _, _ -> startExport() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startExport() {
        exporting = true
        exportCollecting = false
        setStatus("Reading vault for export…")
        ble.readVaultExport()
        exportWatchdog = Runnable {
            if (exporting) {
                exporting = false
                exportCollecting = false
                setStatus("Export failed: the device didn't answer in time.")
            }
        }.also { handler.postDelayed(it, 25_000) }
    }

    private fun armExportWatchdog() {
        exportWatchdog?.let { handler.removeCallbacks(it) }
        exportWatchdog = Runnable {
            // A password entry never arrived: finish with what we have.
            if (exportCollecting) finishExportFile()
        }.also { handler.postDelayed(it, 15_000) }
    }

    /** vault_export read completed: parse [{label, secret}], then collect passwords. */
    private fun onVaultExport(json: String) {
        if (!exporting) return
        val totps = try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                o.getString("label") to o.getString("secret")
            }
        } catch (_: Exception) {
            exporting = false
            exportWatchdog?.let { handler.removeCallbacks(it) }
            setStatus("Export failed: couldn't read the vault data.")
            return
        }
        exportTotps = totps
        exportPwEntries.clear()
        exportPwIndex = 0
        if (pwLabels.isEmpty()) {
            finishExportFile()
            return
        }
        exportCollecting = true
        setStatus("Reading passwords for export…")
        ble.requestPw(0)
        armExportWatchdog()
    }

    private fun onExportPwEntry(label: String, username: String, password: String) {
        exportPwEntries.add(Triple(label, username, password))
        exportPwIndex++
        if (exportPwIndex < pwLabels.size) {
            ble.requestPw(exportPwIndex)
            armExportWatchdog()
        } else {
            finishExportFile()
        }
    }

    /** Build the Bitwarden-compatible JSON and offer to share it. */
    private fun finishExportFile() {
        exportWatchdog?.let { handler.removeCallbacks(it) }
        exporting = false
        exportCollecting = false
        val sb = StringBuilder("{\"encrypted\":false,\"folders\":[],\"items\":[")
        var first = true
        for ((label, secret) in exportTotps) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"name\":\"${Bitwarden.jsonEscape(label)}\",")
            sb.append("\"login\":{\"username\":\"\",\"password\":\"\",")
            sb.append("\"totp\":\"${Bitwarden.jsonEscape(secret)}\"}}")
        }
        for ((label, user, pass) in exportPwEntries) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"name\":\"${Bitwarden.jsonEscape(label)}\",")
            sb.append("\"login\":{\"username\":\"${Bitwarden.jsonEscape(user)}\",")
            sb.append("\"password\":\"${Bitwarden.jsonEscape(pass)}\",\"totp\":null}}")
        }
        sb.append("]}")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = File(cacheDir, "keychain-vault-backup-$stamp.json")
        try {
            file.writeText(sb.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
            setStatus("Export failed: couldn't write the backup file.")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val nTotp = exportTotps.size
        val nPw = exportPwEntries.size
        exportTotps = emptyList() // drop secrets from memory
        exportPwEntries.clear()
        AlertDialog.Builder(this)
            .setTitle("Backup complete")
            .setMessage(
                "Exported $nTotp TOTP codes and $nPw passwords. The file is NOT " +
                    "encrypted — share it to a safe place now."
            )
            .setPositiveButton("Share…") { _, _ ->
                startActivity(Intent.createChooser(share, "Share backup"))
            }
            .setNegativeButton("Done", null)
            .show()
        setStatus("Backup saved.")
    }

    // ---------------- device actions ----------------

    private fun showAddTotpDialog() {
        val dBinding = DialogAddTotpBinding.inflate(LayoutInflater.from(this))
        // QR scan button: launches the scanner, populates fields on return.
        dBinding.scanQrButton.setOnClickListener {
            pendingQrDialog = dBinding
            qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
        AlertDialog.Builder(this)
            .setTitle("Add TOTP entry")
            .setView(dBinding.root)
            .setPositiveButton("Add") { _, _ ->
                val label = Protocol.normalizeLabel(
                    dBinding.labelEdit.text?.toString().orEmpty()
                )
                val secret = dBinding.secretEdit.text?.toString().orEmpty()
                    .trim().replace(" ", "")
                when {
                    label.isEmpty() ->
                        setStatus("Label is required.")
                    !Protocol.isValidBase32Secret(secret) ->
                        setStatus("Secret must be 8–128 base32 characters (A–Z, 2–7).")
                    totpLabels.contains(label) ->
                        setStatus("An entry named $label already exists.")
                    else -> {
                        awaitingMutation = true
                        ble.addTotp(label, secret)
                        setStatus("Adding…")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmLock() {
        AlertDialog.Builder(this)
            .setTitle("Lock device")
            .setMessage("Lock the keychain now? This ends the session.")
            .setPositiveButton("Lock") { _, _ -> ble.lock() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- helpers ----------------

    private fun copyToClipboard(text: String, msg: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kv", text))
        setStatus(msg)
    }

    private fun setStatus(s: String) {
        binding.statusText.text = s
    }

    private fun onDeviceGone(msg: String) {
        authed = false
        crudOk = false
        settingsSupported = false
        bleAutoSupported = false
        fwVersion = ""
        awaitingMutation = false
        setupMode = false
        setupStep = 0
        setupPasswordSet = false
        setupRebootPending = false
        setupJustFinished = false
        importQueue = null
        importVerifyPending = false
        lastImportKind = null
        importLastError = null
        importWatchdog?.let { handler.removeCallbacks(it) }
        exporting = false
        exportCollecting = false
        exportTotps = emptyList()
        exportPwEntries.clear()
        exportWatchdog?.let { handler.removeCallbacks(it) }
        authTimeoutTask?.let { handler.removeCallbacks(it) }
        scanTimeoutTask?.let { handler.removeCallbacks(it) }
        scanning = false
        pendingPwEditIndex = -1
        // NOTE: disconnectMsg is intentionally NOT cleared here — onDisconnected
        // consumes it when the device drops the connection right after a
        // LOCKED/TIMEOUT notify.
        setAuthedUi(false)
        clearSettingsUi()
        binding.authCard.visibility = View.GONE
        binding.authButton.isEnabled = false
        binding.disconnectButton.isEnabled = false
        binding.scanButton.isEnabled = true
        binding.deviceStatusText.text = "Not connected"
        binding.firmwareText.text = ""
        binding.authHintText.text = ""
        binding.setupWizardContainer.visibility = View.GONE
        binding.normalUiContainer.visibility = View.VISIBLE
        // Never retain the setup password beyond the session.
        setupPassword = ""
        clearCode()
        selectedTotp = -1
        totpLabels = emptyList()
        pwLabels = emptyList()
        totpLoaded = false
        pwLoaded = false
        pwCountLoaded = false
        pwCountFromDevice = -1
        postRefreshMsg = null
        totpAdapter.clear()
        pwAdapter.clear()
        clearPwDetail()
        setStatus(msg)
    }
}
