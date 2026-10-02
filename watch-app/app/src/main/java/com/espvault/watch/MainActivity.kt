package com.espvault.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    companion object {
        private val BLE_PERMS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    }

    internal val permsGranted = mutableStateOf(false)
    private var askedOnce = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermState()
        setContent { WatchApp() }
    }

    override fun onResume() {
        super.onResume()
        // Re-check: user may have granted/denied in Settings.
        refreshPermState()
    }

    private fun hasBlePerms(): Boolean = BLE_PERMS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshPermState() {
        permsGranted.value = hasBlePerms()
    }

    fun requestBlePerms() {
        askedOnce = true
        permLauncher.launch(BLE_PERMS)
    }

    /** True if the user already saw the system dialog and it won't show again. */
    fun permsPermanentlyDenied(): Boolean {
        if (!askedOnce || hasBlePerms()) return false
        return BLE_PERMS.none { shouldShowRequestPermissionRationale(it) }
    }
}

@Composable
fun WatchApp(vm: WatchViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? MainActivity
    val granted by (activity?.permsGranted ?: mutableStateOf(true))
    var ready by remember { mutableStateOf(false) }
    // Bluetooth is optional now (Bitwarden is the default sync), so the
    // permission gate can be skipped — device features just won't scan.
    var bleSkipped by remember { mutableStateOf(false) }
    // Init once permissions are granted (or skipped); the cache loads
    // synchronously here so the app is usable immediately, with or without
    // the device.
    LaunchedEffect(granted, bleSkipped) {
        if (granted || bleSkipped) {
            vm.init(context)
            ready = true
        }
    }
    MaterialTheme {
        if (!granted && !bleSkipped) {
            PermissionGate(
                permanentlyDenied = activity?.permsPermanentlyDenied() == true,
                onRequest = { activity?.requestBlePerms() },
                onSkip = { bleSkipped = true }
            )
            return@MaterialTheme
        }
        if (!ready) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            return@MaterialTheme
        }
        val nav = rememberSwipeDismissableNavController()
        val conn by vm.conn.collectAsState()
        val hasCache by vm.hasCache.collectAsState()

        // Drive navigation from connection state. Sync progress lives on the
        // device screen; everything else is served from the offline cache.
        // Bitwarden is the default sync — the device is optional.
        LaunchedEffect(conn, hasCache) {
            val target = when (conn) {
                is ConnState.NeedPin -> "pin"
                is ConnState.Idle -> if (hasCache) "menu" else "bwsync"
                else -> "connect" // Scanning, Connecting, Syncing, Ready, Error
            }
            if (nav.currentDestination?.route != target) nav.navigate(target)
        }

        SwipeDismissableNavHost(
            navController = nav,
            startDestination = if (hasCache) "menu" else "bwsync"
        ) {
            composable("connect") { ConnectScreen(vm) }
            composable("pin") { PinScreen(vm) }
            composable("menu") {
                MenuScreen(
                    vm,
                    onTotps = { nav.navigate("totps") },
                    onPasswords = { nav.navigate("passwords") },
                    onSync = { nav.navigate("bwsync") },
                    onSettings = { nav.navigate("settings") }
                )
            }
            composable("totps") { TotpsScreen(vm) }
            composable("settings") {
                SettingsScreen(
                    vm,
                    onImportDevice = { vm.requestDeviceImport(); nav.navigate("connect") },
                    onBwSetup = { nav.navigate("bwsync") }
                )
            }
            composable("bwsync") {
                BwSyncScreen(vm, onDone = { nav.popBackStack("menu", false) })
            }
            composable("passwords") {
                PasswordsScreen(vm, onSelect = { i ->
                    vm.selectPw(i)
                    nav.navigate("pwdetail")
                })
            }
            composable("pwdetail") { PwDetailScreen(vm) }
        }
    }
}

@Composable
fun ConnectScreen(vm: WatchViewModel) {
    val conn by vm.conn.collectAsState()
    val status by vm.status.collectAsState()
    val hasCache by vm.hasCache.collectAsState()
    val jobTitle = "Import from device"
    // Auto-scan only when a device job was explicitly requested — not on swipe-back.
    LaunchedEffect(Unit) { if (vm.consumeAutoScan()) vm.startScan() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(jobTitle, style = MaterialTheme.typography.title2)
        when (val c = conn) {
            is ConnState.Scanning -> {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                Text("Looking for your vault…", textAlign = TextAlign.Center)
            }
            is ConnState.Connecting -> {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                Text("Connecting…", textAlign = TextAlign.Center)
            }
            is ConnState.Syncing -> {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                Text(
                    status.ifEmpty { "Syncing…" },
                    textAlign = TextAlign.Center
                )
            }
            is ConnState.Ready -> {
                Text("✓", style = MaterialTheme.typography.title1)
                Text(
                    status.ifEmpty { "Synced" },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            is ConnState.Error -> {
                if (c.msg == "No vault found") {
                    Text("No vault found", textAlign = TextAlign.Center)
                    Text(
                        "• Vault's Bluetooth on? Triple-tap its button.",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        "• Phone app not connected to the vault?",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "• Watch Bluetooth on?",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(c.msg, textAlign = TextAlign.Center)
                }
                Button(onClick = { vm.startScan() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Retry")
                }
            }
            else -> {
                Text(
                    if (hasCache) "Cache is ready — sync to refresh it."
                    else "Sync your vault to get started.",
                    textAlign = TextAlign.Center
                )
                Button(onClick = { vm.startScan() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (hasCache) "Sync now" else "Scan")
                }
            }
        }
    }
}

@Composable
fun PinScreen(vm: WatchViewModel) {
    val conn by vm.conn.collectAsState()
    var inputError by remember { mutableStateOf("") }

    // RemoteInput = the native watch text entry (voice, keyboard, emoji).
    val launcher = rememberRemoteInputLauncher { key, text ->
        inputError = ""
        if (key == "pin" && text.isNotEmpty()) vm.authenticate(text)
    }
    val promptPin: () -> Unit = {
        launchTextInput(launcher, "pin", "Vault PIN") { inputError = it }
    }

    // Pop the input UI the first time this screen shows.
    LaunchedEffect(Unit) { promptPin() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Vault PIN", style = MaterialTheme.typography.title3)
        Text(
            "Your ESP32 vault's PIN — not your Bitwarden password.",
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        if (inputError.isNotEmpty()) {
            Text(inputError, style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp))
        }
        when {
            conn is ConnState.Syncing -> {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                Text("Unlocking…", textAlign = TextAlign.Center)
            }
            conn is ConnState.Error -> {
                Text((conn as ConnState.Error).msg, textAlign = TextAlign.Center)
                Button(onClick = { vm.startScan() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Retry")
                }
            }
            else -> {
                Chip(
                    label = { Text("Enter PIN") },
                    onClick = { promptPin() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun MenuScreen(
    vm: WatchViewModel,
    onTotps: () -> Unit,
    onPasswords: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit
) {
    val lastSync by vm.lastSyncAt.collectAsState()
    val totps by vm.totps.collectAsState()
    val pws by vm.pwEntries.collectAsState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState()
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Esp Vault",
                    style = MaterialTheme.typography.title3,
                    modifier = Modifier.padding(4.dp)
                )
                if (lastSync > 0) {
                    Text(
                        "Synced ${formatSyncTime(lastSync)}",
                        style = MaterialTheme.typography.caption2,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
        item {
            Chip(
                label = { Text("TOTPs (${totps.size})") },
                onClick = onTotps,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                label = { Text("Passwords (${pws.size})") },
                onClick = onPasswords,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                label = { Text("Sync now") },
                onClick = onSync,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                label = { Text("Settings") },
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun TotpsScreen(vm: WatchViewModel) {
    val totps by vm.totps.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    if (copiedLabel != null) {
        LaunchedEffect(copiedLabel) {
            delay(1200)
            copiedLabel = null
        }
    }
    // Tick every second to refresh codes + countdown.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - (now % 1000L))
        }
    }
    val secsLeft = Totp.secondsLeft(now)
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState()
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "TOTPs  •  ${secsLeft}s",
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(4.dp)
                )
                if (copiedLabel != null) {
                    Text(
                        "$copiedLabel copied ✓",
                        style = MaterialTheme.typography.caption1,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
        if (totps.isEmpty()) {
            item {
                Text(
                    "Nothing synced yet",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
        itemsIndexed(totps) { _, entry ->
            val code = Totp.generate(entry.secret, now) ?: "------"
            val nextCode = Totp.generate(entry.secret, now + 30_000L) ?: "------"
            Chip(
                label = {
                    Column {
                        Text(entry.label, style = MaterialTheme.typography.caption1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(code, style = MaterialTheme.typography.title2)
                            Text(
                                "  next $nextCode",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                },
                onClick = {
                    try {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("EspVault", code))
                        copiedLabel = entry.label
                    } catch (_: Exception) {
                    }
                },
                colors = ChipDefaults.chipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** "14:32" if today, "Oct 1" otherwise. */
fun formatSyncTime(millis: Long): String {
    val date = java.util.Date(millis)
    val now = java.util.Date()
    val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        .format(date) == java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(now)
    val pattern = if (sameDay) "HH:mm" else "MMM d"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(date)
}

@Composable
fun PasswordsScreen(vm: WatchViewModel, onSelect: (Int) -> Unit) {
    val entries by vm.pwEntries.collectAsState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState()
    ) {
        item {
            Text(
                "Passwords",
                style = MaterialTheme.typography.title3,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        if (entries.isEmpty()) {
            item { Text("No passwords synced", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
        itemsIndexed(entries) { i, entry ->
            Chip(
                label = { Text(entry.label) },
                onClick = { onSelect(i) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun PwDetailScreen(vm: WatchViewModel) {
    // Served straight from the offline cache — no device needed.
    val d = vm.selectedPw.collectAsState().value
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf<String?>(null) }
    if (copied != null) {
        LaunchedEffect(copied) {
            delay(1200)
            copied = null
        }
    }
    fun copy(text: String, what: String) {
        try {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("EspVault", text))
            copied = what
        } catch (_: Exception) {
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (d == null) {
            Text("Nothing selected", textAlign = TextAlign.Center)
        } else {
            Text(d.label, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
            if (copied != null) {
                Text("$copied copied ✓", style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            if (d.username.isNotEmpty()) {
                Text("User — tap to copy", style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(top = 8.dp))
                Chip(
                    label = { Text(d.username) },
                    onClick = { copy(d.username, "Username") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("Password — tap to copy", style = MaterialTheme.typography.caption1,
                modifier = Modifier.padding(top = 8.dp))
            Chip(
                label = { Text(d.password) },
                onClick = { copy(d.password, "Password") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun PermissionGate(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Bluetooth needed?",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )
        Text(
            if (permanentlyDenied)
                "Permission was denied. To use the vault device later, open Settings > Apps > Esp Vault > Permissions and allow Bluetooth."
            else
                "Bluetooth is only needed for the optional vault device. Bitwarden sync works without it.",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )
        if (!permanentlyDenied) {
            Button(onClick = onRequest) { Text("Allow Bluetooth") }
        }
        Button(
            onClick = onSkip,
            modifier = Modifier.padding(top = 4.dp)
        ) { Text("Continue without it") }
    }
}

/** Reusable RemoteInput launcher: [onText] gets (key, entered text). */
@Composable
fun rememberRemoteInputLauncher(
    onText: (key: String, text: String) -> Unit
): androidx.activity.result.ActivityResultLauncher<android.content.Intent> =
    androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = android.app.RemoteInput.getResultsFromIntent(result.data)
        val key = results?.keySet()?.firstOrNull()
        val text = key?.let { results.getCharSequence(it)?.toString().orEmpty() }.orEmpty()
        if (key != null && text.isNotEmpty()) onText(key, text)
    }

fun launchTextInput(
    launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    key: String,
    label: String,
    onError: (String) -> Unit = {}
) {
    try {
        val remoteInput = android.app.RemoteInput.Builder(key).setLabel(label).build()
        val intent = androidx.wear.input.RemoteInputIntentHelper.createActionRemoteInputIntent()
        androidx.wear.input.RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        launcher.launch(intent)
    } catch (e: Exception) {
        onError("Couldn't open input — tap again")
    }
}

@Composable
fun SettingsScreen(
    vm: WatchViewModel,
    onImportDevice: () -> Unit,
    onBwSetup: () -> Unit
) {
    val server by vm.bwServer.collectAsState()
    val email by vm.bwEmail.collectAsState()
    val hasBw = server.isNotEmpty() && email.isNotEmpty()
    val hasVaultPin by vm.hasVaultPin.collectAsState()
    var inputError by remember { mutableStateOf("") }
    val launcher = rememberRemoteInputLauncher { key, text ->
        inputError = ""
        if (key == "vault_pin" && text.isNotEmpty()) vm.setVaultPin(text)
    }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState()
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.title3,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        item {
            Text(
                "Bitwarden",
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
        }
        if (hasBw) {
            item {
                Text(server, style = MaterialTheme.typography.caption2,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            item {
                Text(email, style = MaterialTheme.typography.caption2,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    textAlign = TextAlign.Center)
            }
            item {
                Chip(
                    label = { Text("Forget server") },
                    onClick = { vm.clearBwConfig() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            item {
                Chip(
                    label = { Text("Set up Bitwarden sync") },
                    onClick = onBwSetup,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            Text(
                "Vault device (optional)",
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
        }
        if (inputError.isNotEmpty()) {
            item {
                Text(inputError, style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            if (hasVaultPin) {
                Text(
                    "Vault PIN saved on this watch — never shown. Used automatically.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }
            Chip(
                label = { Text(if (hasVaultPin) "Change vault PIN" else "Set vault PIN") },
                onClick = {
                    launchTextInput(launcher, "vault_pin", "Vault PIN") { inputError = it }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (hasVaultPin) {
            item {
                Chip(
                    label = { Text("Forget vault PIN") },
                    onClick = { vm.clearVaultPin() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            Chip(
                label = { Text("Import from device") },
                onClick = onImportDevice,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(
                "The watch works fully offline. The device is just another place to pull a copy from.",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
        }
    }
}

@Composable
fun BwSyncScreen(vm: WatchViewModel, onDone: () -> Unit) {
    val bwState by vm.bwState.collectAsState()
    val bwStatus by vm.bwStatus.collectAsState()
    val server by vm.bwServer.collectAsState()
    val email by vm.bwEmail.collectAsState()
    val configured = server.isNotEmpty() && email.isNotEmpty()
    val hasSavedPw by vm.bwHasSavedPw.collectAsState()
    val step by vm.bwStep.collectAsState()
    val setupServer by vm.bwSetupServer.collectAsState()
    val setupEmail by vm.bwSetupEmail.collectAsState()
    var inputError by remember { mutableStateOf("") }

    // One input at a time, launched from a tap. Results only fill the field —
    // nothing chains another prompt, which is what made setup crash.
    val launcher = rememberRemoteInputLauncher { key, text ->
        inputError = ""
        when (key) {
            "bw_server" -> vm.setBwSetupServer(text)
            "bw_email" -> vm.setBwSetupEmail(text)
            "bw_pw" -> {
                val s = if (configured) server else setupServer
                val e = if (configured) email else setupEmail
                if (s.isNotEmpty() && e.isNotEmpty()) vm.syncFromBitwarden(s, e, text)
            }
        }
    }
    val onInputError: (String) -> Unit = { inputError = it }

    // Fresh state each time the screen opens.
    LaunchedEffect(Unit) { vm.resetBwState() }
    // Leave on success after a beat.
    if (bwState is ConnState.Ready) {
        LaunchedEffect(Unit) {
            delay(1200)
            vm.resetBwState()
            onDone()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Bitwarden sync", style = MaterialTheme.typography.title3)
        when (val s = bwState) {
            is ConnState.Syncing -> {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                Text(bwStatus.ifEmpty { "Syncing…" }, textAlign = TextAlign.Center)
            }
            is ConnState.Ready -> {
                Text("✓", style = MaterialTheme.typography.title1)
                Text("Synced", textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            is ConnState.Error -> {
                Text(s.msg, textAlign = TextAlign.Center)
                Text(
                    "Your entries are kept — fix and retry.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Button(onClick = { vm.clearBwError() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Back")
                }
            }
            else -> {
                if (inputError.isNotEmpty()) {
                    Text(inputError, style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp))
                }
                if (configured) {
                    Text(server, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
                    Text(email, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp))
                    if (hasSavedPw) {
                        Text(
                            "Master password is saved on this watch — never shown.",
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Chip(
                            label = { Text("Sync now") },
                            onClick = { vm.syncFromBitwardenSaved() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Chip(
                            label = { Text("Forget saved password") },
                            onClick = { vm.clearBwSavedPw() },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    } else {
                        Text(
                            "Master password is asked each time and never stored.",
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Chip(
                            label = { Text("Sync now") },
                            onClick = { launchTextInput(launcher, "bw_pw", "Master password", onInputError) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Chip(
                        label = { Text("Forget server") },
                        onClick = { vm.clearBwConfig() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                } else {
                    // One-time setup, one visible step at a time.
                    Text("Step ${step + 1} of 3", style = MaterialTheme.typography.caption1,
                        modifier = Modifier.padding(bottom = 4.dp))
                    when (step) {
                        0 -> {
                            BwWizardField(
                                label = "Server URL",
                                value = setupServer,
                                emptyHint = "vault.example.com",
                                explanation = "Your self-hosted Bitwarden address. https:// is added for you.",
                                onEnter = { launchTextInput(launcher, "bw_server", "Server URL", onInputError) }
                            )
                            BwWizardNav(
                                onBack = null,
                                onNext = { vm.bwStepNext() },
                                nextEnabled = setupServer.isNotEmpty()
                            )
                        }
                        1 -> {
                            Text(setupServer, style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp))
                            BwWizardField(
                                label = "Email",
                                value = setupEmail,
                                emptyHint = "you@example.com",
                                explanation = "The email you log into Bitwarden with.",
                                onEnter = { launchTextInput(launcher, "bw_email", "Email", onInputError) }
                            )
                            BwWizardNav(
                                onBack = { vm.bwStepBack() },
                                onNext = { vm.bwStepNext() },
                                nextEnabled = setupEmail.isNotEmpty()
                            )
                        }
                        else -> {
                            Text("Master password", style = MaterialTheme.typography.caption1,
                                textAlign = TextAlign.Center)
                            Text(setupServer, style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center)
                            Text(setupEmail, style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp))
                            Text(
                                "Saved on the watch after a successful sync, so next time is one tap. Never shown.",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Chip(
                                label = { Text("Enter master password & sync") },
                                onClick = { launchTextInput(launcher, "bw_pw", "Master password", onInputError) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Chip(
                                label = { Text("Back") },
                                onClick = { vm.bwStepBack() },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One setup field: labeled box showing the typed value, explanation, Enter/Edit chip. */
@Composable
private fun BwWizardField(
    label: String,
    value: String,
    emptyHint: String,
    explanation: String,
    onEnter: () -> Unit
) {
    Text(label, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
    Text(
        explanation,
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 2.dp)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(
                1.dp,
                MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            value.ifEmpty { emptyHint },
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center
        )
    }
    Chip(
        label = { Text(if (value.isEmpty()) "Enter $label" else "Edit $label") },
        onClick = onEnter,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Back / Next row for the setup wizard. */
@Composable
private fun BwWizardNav(
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    nextEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (onBack != null) {
            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
        }
        Button(onClick = onNext, enabled = nextEnabled, modifier = Modifier.weight(1f)) {
            Text("Next")
        }
    }
}
