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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
            MaterialTheme {
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
        var permsOk by remember { mutableStateOf(hasPermissions()) }
        val context = LocalContext.current

        LaunchedEffect(Unit) { AppModel.check(context) }

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permsOk = hasPermissions() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(28.dp)) }
            item {
                Text("Flipper AI Bridge", style = MaterialTheme.typography.headlineSmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(labeled("Service: ", if (ui.running) "running" else "stopped"))
                        Text(labeled("Bluetooth: ", "Bluetooth Low Energy (BLE) - ${ui.bleStatus}"))
                        Text(labeled("USB: ", ui.usbStatus))
                        if (ui.busy) Text("Asking the AI...")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!permsOk) {
                                Button(onClick = { launcher.launch(requiredPermissions()) }) {
                                    Text("Grant permissions")
                                }
                            } else if (!ui.running) {
                                Button(onClick = { BridgeService.start(context) }) { Text("Start") }
                            } else {
                                OutlinedButton(onClick = { BridgeService.stop(context) }) { Text("Stop") }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Updates", style = MaterialTheme.typography.titleMedium)
                        Text("Installed version: ${BuildConfig.VERSION_NAME}")
                        if (upd.appUpdate) {
                            Text("New version available: ${upd.latestTag}")
                            if (upd.appBusy && upd.appProgress >= 0) {
                                LinearProgressIndicator(
                                    progress = { upd.appProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Button(
                                enabled = !upd.appBusy,
                                onClick = { AppModel.installApk(context) }
                            ) { Text("Download, verify checksum and install") }
                        }
                        if (upd.appMessage.isNotEmpty()) Text(upd.appMessage)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Switch(checked = beta, onCheckedChange = {
                                beta = it
                                store.betaUpdates = it
                            })
                            Text("Include beta versions")
                        }
                        OutlinedButton(
                            enabled = !upd.checking,
                            onClick = { AppModel.check(context) }
                        ) { Text(if (upd.checking) "Checking..." else "Check for updates") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Anthropic API", style = MaterialTheme.typography.titleMedium)
                        if (apiKeySaved && !editingKey) {
                            Text(labeled("API key: ", "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { editingKey = true }) { Text("Change key") }
                                TextButton(onClick = {
                                    store.apiKey = ""
                                    apiKeySaved = false
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
                                Button(
                                    enabled = apiKey.isNotBlank(),
                                    onClick = {
                                        store.apiKey = apiKey.trim()
                                        apiKey = ""
                                        apiKeySaved = true
                                        editingKey = false
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
                        OutlinedButton(onClick = { modelMenu = true }) { Text("Pick a model") }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            SecureStore.MODEL_IDS.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(MODEL_NAMES[m] ?: m) },
                                    onClick = {
                                        model = m
                                        store.model = m
                                        modelMenu = false
                                    }
                                )
                            }
                        }
                        Text(labeled("Effort: ", effort.replaceFirstChar { it.uppercase() }))
                        OutlinedButton(onClick = { effortMenu = true }) { Text("Pick effort") }
                        DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                            SecureStore.EFFORTS.forEach { e ->
                                DropdownMenuItem(
                                    text = { Text(e.replaceFirstChar { it.uppercase() }) },
                                    onClick = {
                                        effort = e
                                        store.effort = e
                                        effortMenu = false
                                    }
                                )
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Flipper", style = MaterialTheme.typography.titleMedium)
                        Text(if (bleName.isEmpty()) "No Flipper selected" else "Selected: $bleName")
                        Text("Bluetooth Low Energy (BLE). Paired devices are listed too. Pick your Flipper from the list.")
                        Button(
                            enabled = permsOk && !scanning,
                            onClick = { startScan() }
                        ) { Text(if (scanning) "Scanning..." else "Scan for Flipper") }
                        found.forEach { (name, address) ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    stopScan()
                                    store.bleAddress = address
                                    store.bleName = name
                                    bleName = name
                                    if (ui.running) {
                                        BridgeService.start(context, BridgeService.ACTION_RESTART_BLE)
                                    }
                                }
                            ) { Text("$name  ($address)") }
                        }
                        Text("USB: just plug the Flipper in. USB has priority over Bluetooth.")
                        Text("Flipper app", style = MaterialTheme.typography.titleSmall)
                        if (upd.fapStatus.isNotEmpty()) Text(upd.fapStatus)
                        if (upd.fapBusy && upd.fapProgress >= 0) {
                            LinearProgressIndicator(
                                progress = { upd.fapProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (upd.fapMessage.isNotEmpty()) Text(upd.fapMessage)
                        Button(
                            enabled = ui.running && !upd.fapBusy,
                            onClick = { AppModel.installFap(context) }
                        ) { Text(if (upd.fapUpdate && upd.fapStatus.startsWith("Update")) "Update on Flipper" else "Install on Flipper") }
                        Text(
                            "Start the service first and connect the Flipper. Close AI Bridge on the Flipper before installing. Chats and settings on the Flipper are kept.",
                            style = MaterialTheme.typography.bodySmall
                        )
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
                Card(Modifier.fillMaxWidth()) {
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
