package com.flipperclaude.bridge

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
        var apiKey by remember { mutableStateOf("") }
        var apiKeySaved by remember { mutableStateOf(store.apiKey.isNotEmpty()) }
        var model by remember { mutableStateOf(store.model) }
        var extra by remember { mutableStateOf(store.extraPrompt) }
        var bleName by remember { mutableStateOf(store.bleName) }
        var modelMenu by remember { mutableStateOf(false) }
        var permsOk by remember { mutableStateOf(hasPermissions()) }
        val context = LocalContext.current

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
            item {
                Text("Flipper Claude Bridge", style = MaterialTheme.typography.headlineSmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (ui.running) "Service: running" else "Service: stopped")
                        Text("Bluetooth: ${ui.bleStatus}")
                        Text("USB: ${ui.usbStatus}")
                        if (ui.busy) Text("Asking Claude...")
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
                        Text("Claude API", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(if (apiKeySaved) "API key (saved, enter new to replace)" else "API key") },
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
                                }
                            ) { Text("Save key") }
                            if (apiKeySaved) {
                                TextButton(onClick = {
                                    store.apiKey = ""
                                    apiKeySaved = false
                                }) { Text("Remove key") }
                            }
                        }
                        OutlinedTextField(
                            value = model,
                            onValueChange = {
                                model = it
                                store.model = it.trim()
                            },
                            label = { Text("Model") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = { modelMenu = true }) { Text("Pick a model") }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            MODELS.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        model = m
                                        store.model = m
                                        modelMenu = false
                                    }
                                )
                            }
                        }
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
                        Text("Flipper (Bluetooth)", style = MaterialTheme.typography.titleMedium)
                        Text(if (bleName.isEmpty()) "No Flipper selected" else "Selected: $bleName")
                        Text("Paired devices are listed too. Pick your Flipper from the list. Start the Claude Bridge app on the Flipper first.")
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
            item { Text("") }
        }
    }

    companion object {
        private val MODELS = listOf(
            "claude-sonnet-5-5",
            "claude-haiku-5-5",
            "claude-opus-5-5",
            "claude-fable-5-1"
        )
    }
}
