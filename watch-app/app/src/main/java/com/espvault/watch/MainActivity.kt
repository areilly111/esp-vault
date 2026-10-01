package com.espvault.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    // Init once permissions are granted; the cache loads synchronously here
    // so the app is usable immediately, with or without the device.
    LaunchedEffect(granted) {
        if (granted) {
            vm.init(context)
            if (!vm.hasCache.value) vm.requestAutoScan()
            ready = true
        }
    }
    MaterialTheme {
        if (!granted) {
            PermissionGate(
                permanentlyDenied = activity?.permsPermanentlyDenied() == true,
                onRequest = { activity?.requestBlePerms() }
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
        // connect screen; everything else is served from the offline cache.
        LaunchedEffect(conn, hasCache) {
            val target = when (conn) {
                is ConnState.NeedPin -> "pin"
                is ConnState.Idle -> if (hasCache) "codes" else "connect"
                else -> "connect" // Scanning, Connecting, Syncing, Ready, Error
            }
            if (nav.currentDestination?.route != target) nav.navigate(target)
        }

        SwipeDismissableNavHost(
            navController = nav,
            startDestination = if (hasCache) "codes" else "connect"
        ) {
            composable("connect") { ConnectScreen(vm) }
            composable("pin") { PinScreen(vm) }
            composable("codes") {
                CodesScreen(
                    vm,
                    onPasswords = { nav.navigate("passwords") },
                    onSync = { nav.navigate("syncmenu") }
                )
            }
            composable("syncmenu") {
                SyncMenuScreen(
                    onBle = { vm.requestAutoScan(); nav.navigate("connect") },
                    onBw = { nav.navigate("bwsync") }
                )
            }
            composable("bwsync") {
                BwSyncScreen(vm, onDone = { nav.popBackStack("codes", false) })
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
    // Auto-scan only when a sync was explicitly requested — not on swipe-back.
    LaunchedEffect(Unit) { if (vm.consumeAutoScan()) vm.startScan() }
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Esp Vault", style = MaterialTheme.typography.title2)
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
    var attempted by remember { mutableStateOf(false) }

    // RemoteInput = the native watch text entry (voice, keyboard, emoji).
    val inputLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = android.app.RemoteInput.getResultsFromIntent(result.data)
        val pin = results?.getCharSequence("pin")?.toString().orEmpty()
        if (pin.isNotEmpty()) {
            attempted = true
            vm.authenticate(pin)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Pop the input UI the first time this screen shows.
    LaunchedEffect(Unit) {
        val remoteInputs = listOf(
            android.app.RemoteInput.Builder("pin")
                .setLabel("Vault PIN")
                .build()
        )
        val intent = androidx.wear.input.RemoteInputIntentHelper.createActionRemoteInputIntent()
        androidx.wear.input.RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
        inputLauncher.launch(intent)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Enter vault PIN", style = MaterialTheme.typography.title3)
        when {
            conn is ConnState.Syncing -> {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                Text("Unlocking…", textAlign = TextAlign.Center)
            }
            conn is ConnState.Error -> {
                Text((conn as ConnState.Error).msg, textAlign = TextAlign.Center)
                Button(onClick = { attempted = false; vm.startScan() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Retry")
                }
            }
            attempted -> {
                Text("Waiting…", textAlign = TextAlign.Center)
            }
            else -> {
                Text("Use voice or keyboard", textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun CodesScreen(
    vm: WatchViewModel,
    onPasswords: () -> Unit,
    onSync: () -> Unit
) {
    val totps by vm.totps.collectAsState()
    val lastSync by vm.lastSyncAt.collectAsState()
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
                    "Codes  •  ${secsLeft}s",
                    style = MaterialTheme.typography.caption1,
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
            Chip(
                label = {
                    Column {
                        Text(entry.label, style = MaterialTheme.typography.caption1)
                        Text(code, style = MaterialTheme.typography.title2)
                    }
                },
                onClick = { /* tap could copy — watch clipboard is limited; skip */ },
                colors = ChipDefaults.chipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                label = { Text("Passwords") },
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
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (d == null) {
            Text("Nothing selected", textAlign = TextAlign.Center)
        } else {
            Text(d.label, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
            if (d.username.isNotEmpty()) {
                Text("User", style = MaterialTheme.typography.caption1, modifier = Modifier.padding(top = 8.dp))
                Text(d.username, textAlign = TextAlign.Center)
            }
            Text("Password", style = MaterialTheme.typography.caption1, modifier = Modifier.padding(top = 8.dp))
            Text(d.password, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun PermissionGate(permanentlyDenied: Boolean, onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Bluetooth needed",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )
        Text(
            if (permanentlyDenied)
                "Permission was denied. Open Settings > Apps > Esp Vault > Permissions and allow Bluetooth, then come back."
            else
                "Esp Vault needs Bluetooth permission to find and connect to your vault device.",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )
        if (!permanentlyDenied) {
            Button(onClick = onRequest) { Text("Allow Bluetooth") }
        }
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
    label: String
) {
    val remoteInput = android.app.RemoteInput.Builder(key).setLabel(label).build()
    val intent = androidx.wear.input.RemoteInputIntentHelper.createActionRemoteInputIntent()
    androidx.wear.input.RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    launcher.launch(intent)
}

@Composable
fun SyncMenuScreen(onBle: () -> Unit, onBw: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Sync from", style = MaterialTheme.typography.title3)
        Chip(
            label = {
                Column {
                    Text("Vault device")
                    Text("Bluetooth", style = MaterialTheme.typography.caption2)
                }
            },
            onClick = onBle,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Chip(
            label = {
                Column {
                    Text("Bitwarden server")
                    Text("Wi-Fi", style = MaterialTheme.typography.caption2)
                }
            },
            onClick = onBw,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

@Composable
fun BwSyncScreen(vm: WatchViewModel, onDone: () -> Unit) {
    val bwState by vm.bwState.collectAsState()
    val bwStatus by vm.bwStatus.collectAsState()
    val server by vm.bwServer.collectAsState()
    val email by vm.bwEmail.collectAsState()
    val configured = server.isNotEmpty() && email.isNotEmpty()

    var setupServer by remember { mutableStateOf("") }
    var setupEmail by remember { mutableStateOf("") }

    // Indirect through a ref so the callback can launch follow-up inputs.
    val onTextRef = remember { mutableStateOf<(String, String) -> Unit>({ _, _ -> }) }
    val launcher = rememberRemoteInputLauncher { key, text -> onTextRef.value(key, text) }
    onTextRef.value = { key, text ->
        when (key) {
            "bw_server" -> {
                setupServer = text.trim().trimEnd('/')
                launchTextInput(launcher, "bw_email", "Email")
            }
            "bw_email" -> {
                setupEmail = text.trim()
                launchTextInput(launcher, "bw_pw", "Master password")
            }
            "bw_pw" -> {
                val s = if (configured) server else setupServer
                val e = if (configured) email else setupEmail
                if (s.isNotEmpty() && e.isNotEmpty()) vm.syncFromBitwarden(s, e, text)
            }
        }
    }

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
        modifier = Modifier.fillMaxSize().padding(8.dp),
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
                Button(onClick = { vm.resetBwState() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Back")
                }
            }
            else -> {
                if (configured) {
                    Text(server, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
                    Text(email, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Text(
                        "Master password is asked each time and never stored.",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Chip(
                        label = { Text("Sync now") },
                        onClick = { launchTextInput(launcher, "bw_pw", "Master password") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Chip(
                        label = { Text("Forget server") },
                        onClick = { vm.clearBwConfig() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                } else {
                    Text(
                        "One-time setup: server address, email, master password.",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Text(
                        "Needs Wi-Fi that can reach your server.",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Button(onClick = { launchTextInput(launcher, "bw_server", "Server URL") }) {
                        Text("Set up")
                    }
                }
            }
        }
    }
}
