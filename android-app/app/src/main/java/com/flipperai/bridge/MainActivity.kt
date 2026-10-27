package com.flipperai.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import kotlinx.coroutines.delay
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var store: SecureStore
    private val handler = Handler(Looper.getMainLooper())
    private val found = mutableStateListOf<Pair<String, String>>()
    private var scanning by mutableStateOf(false)

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            val address = result.device.address
            val hasSerial = result.scanRecord?.serviceUuids?.any { it.uuid == BleTransport.SERVICE_UUID } == true
            if (!hasSerial && !name.contains("Flipper", ignoreCase = true)) return
            if (found.none { it.second == address }) found.add(name to address)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        handleIntent(intent)
        setContent {
            BridgeTheme {
                Screen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && store.apiKey.isNotEmpty()) {
            BridgeService.start(this, BridgeService.ACTION_RESCAN_USB)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = BleTransport.adapter(this)
        if (adapter == null) return
        val scanner = adapter.bluetoothLeScanner ?: return
        found.clear()
        adapter.bondedDevices?.forEach { d ->
            found.add((d.name ?: "Unnamed") + " (paired)" to d.address)
        }
        scanning = true
        scanner.startScan(scanCallback)
        handler.postDelayed({ stopScan() }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            BleTransport.adapter(this)?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @Composable
    private fun Screen() {
        val ui by BridgeState.state.collectAsState()
        val upd by AppModel.state.collectAsState()
        val toast by AppModel.toast.collectAsState()
        var apiKey by remember { mutableStateOf("") }
        var apiKeySaved by remember { mutableStateOf(store.apiKey.isNotEmpty()) }
        var editingKey by remember { mutableStateOf(false) }
        var model by remember { mutableStateOf(store.model) }
        var effort by remember { mutableStateOf(store.effort) }
        var extra by remember { mutableStateOf(store.extraPrompt) }
        var bleName by remember { mutableStateOf(store.bleName) }
        var beta by remember { mutableStateOf(store.betaUpdates) }
        var modelMenu by remember { mutableStateOf(false) }
        var effortMenu by remember { mutableStateOf(false) }
        var confirmFap by remember { mutableStateOf(false) }
        var permsOk by remember { mutableStateOf(hasPermissions()) }
        var debugText by remember { mutableStateOf("") }
        var wasBle by remember { mutableStateOf(false) }
        var wasUsb by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val bleConnected = ui.bleStatus == "Connected"
        val usbConnected = ui.usbStatus == "Connected"
        val connected = bleConnected || usbConnected

        LaunchedEffect(bleConnected) {
            if (bleConnected && !wasBle) AppModel.showToast("Connected to ${bleName.ifEmpty { "Flipper" }}")
            wasBle = bleConnected
        }
        LaunchedEffect(usbConnected) {
            if (usbConnected && !wasUsb) AppModel.showToast("Connected to Flipper over USB")
            wasUsb = usbConnected
        }
        LaunchedEffect(ui.running) {
            while (true) {
                debugText = TransportHub.ble?.debugLine() ?: ""
                delay(1000)
            }
        }

        LaunchedEffect(Unit) {
            AppModel.check(context)
            UpdateWorker.schedule(context)
        }

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            permsOk = hasPermissions()
            if (permsOk) AppModel.showToast("Permissions granted")
        }

        if (confirmFap) {
            val update = upd.fapState == FapState.UpdateAvailable
            AlertDialog(
                onDismissRequest = { confirmFap = false },
                title = { Text(if (update) "Update the Flipper app?" else "Install the Flipper app?") },
                text = {
                    Text(
                        "Close AI Bridge on the Flipper first. The old app is replaced; chats and settings on the Flipper are kept."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmFap = false
                        AppModel.installFap(context)
                    }) { Text(if (update) "Update" else "Install") }
                },
                dismissButton = { TextButton(onClick = { confirmFap = false }) { Text("Cancel") } }
            )
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item { Spacer(Modifier.height(28.dp)) }
                    item {
                        Appear {
                            Text("Flipper AI Bridge", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    item {
                        Appear(60) {
                            SectionCard {
                                Text(labeled("Service: ", if (ui.running) "running" else "stopped"))
                                Text(labeled("Bluetooth: ", "Bluetooth Low Energy (BLE) - ${ui.bleStatus}"))
                                Text(labeled("USB: ", ui.usbStatus))
                                if (ui.busy) Text("Asking the AI...")
                                if (ui.running && debugText.isNotEmpty()) {
                                    Text("Details: $debugText", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!permsOk) {
                                        PressButton(onClick = { launcher.launch(requiredPermissions()) }) {
                                            Text("Grant permissions")
                                        }
                                    } else if (!ui.running) {
                                        PressButton(onClick = {
                                            store.serviceWanted = true
                                            BridgeService.start(context)
                                            AppModel.showToast("Service started")
                                        }) { Text("Start") }
                                    } else {
                                        PressButton(outlined = true, onClick = {
                                            store.serviceWanted = false
                                            BridgeService.stop(context)
                                            AppModel.showToast("Service stopped")
                                        }) { Text("Stop") }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = connected,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ConnectionBadge(connected)
                                        Column {
                                            Text(
                                                "Connected to Flipper",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                if (usbConnected) "Over USB" else (if (bleName.isEmpty()) "Over Bluetooth Low Energy" else "$bleName, Bluetooth Low Energy"),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    if (bleConnected) {
                                        PressButton(outlined = true, onClick = {
                                            BridgeService.start(context, BridgeService.ACTION_DISCONNECT_BLE)
                                            bleName = ""
                                            AppModel.showToast("Disconnected from the Flipper")
                                        }) { Text("Disconnect") }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Appear(120) {
                            SectionCard {
                                Text("Updates", style = MaterialTheme.typography.titleMedium)
                                Text(labeled("Phone app: ", "version ${BuildConfig.VERSION_NAME}"))
                                if (upd.appUpdate) {
                                    Text("New version available: ${upd.latestTag}", color = MaterialTheme.colorScheme.primary)
                                    ProgressBar(upd.appBusy, upd.appProgress)
                                    PressButton(
                                        enabled = !upd.appBusy,
                                        onClick = { AppModel.installApk(context) }
                                    ) { Text("Download update") }
                                }
                                if (upd.appMessage.isNotEmpty()) Text(upd.appMessage, style = MaterialTheme.typography.bodySmall)

                                HorizontalDivider()

                                val fapLine = when (upd.fapState) {
                                    FapState.UpToDate -> "up to date" + if (upd.fapInstalledTag.isNotEmpty()) " (${upd.fapInstalledTag})" else ""
                                    FapState.UpdateAvailable -> "update available (${upd.latestTag})"
                                    FapState.NotInstalled -> "not installed from this phone yet"
                                    FapState.Unknown -> "unknown"
                                }
                                Text(labeled("Flipper app: ", fapLine))
                                ProgressBar(upd.fapBusy, upd.fapProgress)
                                if (upd.fapMessage.isNotEmpty()) Text(upd.fapMessage, style = MaterialTheme.typography.bodySmall)
                                val needsInstall = upd.fapState == FapState.UpdateAvailable || upd.fapState == FapState.NotInstalled
                                PressButton(
                                    outlined = !needsInstall,
                                    enabled = ui.running && !upd.fapBusy,
                                    onClick = { confirmFap = true }
                                ) {
                                    Text(
                                        when (upd.fapState) {
                                            FapState.UpdateAvailable -> "Update on Flipper"
                                            FapState.UpToDate -> "Reinstall on Flipper"
                                            else -> "Install on Flipper"
                                        }
                                    )
                                }
                                if (!ui.running) {
                                    Text("Start the service and connect the Flipper to install.", style = MaterialTheme.typography.bodySmall)
                                }

                                HorizontalDivider()

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Switch(checked = beta, onCheckedChange = {
                                        beta = it
                                        store.betaUpdates = it
                                        AppModel.showToast(if (it) "Beta versions included" else "Beta versions off")
                                    })
                                    Text("Include beta versions")
                                }
                                PressButton(
                                    outlined = true,
                                    enabled = !upd.checking,
                                    onClick = { AppModel.check(context, announce = true) }
                                ) { Text(if (upd.checking) "Checking..." else "Check for updates") }
                                Text(
                                    "New versions are checked automatically every 12 hours and you get a notification.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    item {
                        Appear(180) {
                            SectionCard {
                                Text("Anthropic API", style = MaterialTheme.typography.titleMedium)
                                if (apiKeySaved && !editingKey) {
                                    Text(labeled("API key: ", "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PressButton(outlined = true, onClick = { editingKey = true }) { Text("Change key") }
                                        TextButton(onClick = {
                                            store.apiKey = ""
                                            apiKeySaved = false
                                            AppModel.showToast("API key removed")
                                        }) { Text("Remove key") }
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = apiKey,
                                        onValueChange = { apiKey = it },
                                        label = { Text("API key") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PressButton(
                                            enabled = apiKey.isNotBlank(),
                                            onClick = {
                                                store.apiKey = apiKey.trim()
                                                apiKey = ""
                                                apiKeySaved = true
                                                editingKey = false
                                                AppModel.showToast("API key saved")
                                            }
                                        ) { Text("Save key") }
                                        if (apiKeySaved) {
                                            TextButton(onClick = {
                                                apiKey = ""
                                                editingKey = false
                                            }) { Text("Cancel") }
                                        }
                                    }
                                }
                                Text(labeled("Model: ", MODEL_NAMES[model] ?: model))
                                Box {
                                    PressButton(outlined = true, onClick = { modelMenu = true }) { Text("Pick a model") }
                                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                        SecureStore.MODEL_IDS.forEach { m ->
                                            DropdownMenuItem(
                                                text = { Text(MODEL_NAMES[m] ?: m) },
                                                onClick = {
                                                    model = m
                                                    store.model = m
                                                    modelMenu = false
                                                    AppModel.showToast("Model: ${MODEL_NAMES[m] ?: m}")
                                                }
                                            )
                                        }
                                    }
                                }
                                Text(labeled("Effort: ", effort.replaceFirstChar { it.uppercase() }))
                                Box {
                                    PressButton(outlined = true, onClick = { effortMenu = true }) { Text("Pick effort") }
                                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                                        SecureStore.EFFORTS.forEach { e ->
                                            DropdownMenuItem(
                                                text = { Text(e.replaceFirstChar { it.uppercase() }) },
                                                onClick = {
                                                    effort = e
                                                    store.effort = e
                                                    effortMenu = false
                                                    AppModel.showToast("Effort: ${e.replaceFirstChar { it.uppercase() }}")
                                                }
                                            )
                                        }
                                    }
                                }
                                Text("Lower effort answers faster and cheaper.", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = extra,
                                    onValueChange = {
                                        extra = it
                                        store.extraPrompt = it
                                    },
                                    label = { Text("Extra system prompt (optional)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = !connected,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                        Appear(240) {
                            SectionCard {
                                Text("Find your Flipper", style = MaterialTheme.typography.titleMedium)
                                Text(if (bleName.isEmpty()) "No Flipper selected" else "Selected: $bleName")
                                Text("Bluetooth Low Energy (BLE). Paired devices are listed too. Pick your Flipper from the list.")
                                PressButton(
                                    enabled = permsOk && !scanning,
                                    onClick = { startScan() }
                                ) { Text(if (scanning) "Scanning..." else "Scan for Flipper") }
                                found.forEach { (name, address) ->
                                    PressButton(
                                        outlined = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            stopScan()
                                            store.bleAddress = address
                                            store.bleName = name
                                            bleName = name
                                            AppModel.showToast("Selected $name")
                                            if (ui.running) {
                                                BridgeService.start(context, BridgeService.ACTION_RESTART_BLE)
                                            }
                                        }
                                    ) { Text("$name  ($address)") }
                                }
                                Text("USB: just plug the Flipper in. USB has priority over Bluetooth.")
                            }
                        }
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Recent requests", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { BridgeState.clearLog() }) { Text("Clear") }
                        }
                    }
                    items(ui.log) { entry ->
                        Card(
                            Modifier.fillMaxWidth().animateContentSize(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Q: ${entry.question}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    (if (entry.error) "Error: " else "A: ") + entry.answer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                ToastBanner(toast, Modifier.align(Alignment.TopCenter))
            }
        }
    }

    @Composable
    private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
        Card(
            Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }

    @Composable
    private fun ProgressBar(visible: Boolean, percent: Int) {
        AnimatedVisibility(visible = visible && percent >= 0) {
            val animated by animateFloatAsState(
                targetValue = (percent.coerceIn(0, 100)) / 100f,
                animationSpec = tween(300),
                label = "progress"
            )
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
        }
    }

    private fun labeled(label: String, value: String) = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
        append(value)
    }

    companion object {
        private val MODEL_NAMES = mapOf(
            "claude-sonnet-5-5" to "Claude Sonnet 5.5",
            "claude-haiku-5-5" to "Claude Haiku 5.5",
            "claude-opus-5-5" to "Claude Opus 5.5"
        )
    }
}
