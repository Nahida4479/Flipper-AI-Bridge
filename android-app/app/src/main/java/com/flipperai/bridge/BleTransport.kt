package com.flipperai.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val onLine: (Transport, String) -> Unit,
    private val onStatus: (String) -> Unit
) : Transport {

    override val label = "Bluetooth"
    @Volatile override var rawSink: ((ByteArray) -> Unit)? = null
    override val connected: Boolean get() = ready

    private val handler = Handler(Looper.getMainLooper())
    private val assembler = LineAssembler { onLine(this, it) }
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var writing = false
    private var address = ""
    private var active = false
    private var ready = false
    private var statusChar: BluetoothGattCharacteristic? = null
    @Volatile var rpcActive: Boolean? = null
    @Volatile private var rxPackets = 0
    @Volatile private var rxBytes = 0
    @Volatile private var lastRx = ""
    @Volatile private var writeOk = 0
    @Volatile private var writeFail = 0
    @Volatile private var lastWriteStatus = -1

    fun debugLine(): String =
        "rx $rxPackets pkt / $rxBytes B" + (if (lastRx.isNotEmpty()) " (last $lastRx)" else "") +
            ", writes ok $writeOk fail $writeFail (last status $lastWriteStatus)" +
            ", Flipper mode " + when (rpcActive) {
                true -> "RPC (no app)"
                false -> "AI Bridge app"
                null -> "unknown"
            }

    private val reconnect = Runnable { connect() }
    private var linkUp = false
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (!ready && !linkUp) {
                onStatus("Searching for Flipper")
                gatt?.close()
                gatt = null
                connect(direct = true)
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    fun start(address: String) {
        stop()
        this.address = address
        if (address.isEmpty()) {
            onStatus("No device selected")
            return
        }
        active = true
        connect()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun reconnect(timeoutMs: Long): Boolean {
        val target = address
        if (target.isEmpty()) return false
        start(target)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ready) return true
            Thread.sleep(200)
        }
        return false
    }

    fun stop() {
        active = false
        ready = false
        linkUp = false
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(watchdog)
        queue.clear()
        writing = false
        assembler.reset()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        rx = null
        onStatus("Off")
    }

    private fun connect(direct: Boolean = false) {
        if (!active) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off")
            handler.postDelayed(reconnect, RETRY_MS)
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            onStatus("Invalid device")
            return
        }
        onStatus("Connecting")
        gatt?.close()
        gatt = device.connectGatt(context, !direct, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect(code: Int) {
        gatt?.close()
        gatt = null
        rx = null
        ready = false
        queue.clear()
        writing = false
        assembler.reset()
        if (active) {
            onStatus(if (code == 0) "Connecting" else "Retrying (error $code)")
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, RECONNECT_MS)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                linkUp = true
                onStatus("Pairing / setup")
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                linkUp = false
                handler.post { scheduleReconnect(status) }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            val rxChar = service?.getCharacteristic(RX_UUID)
            if (tx == null || rxChar == null) {
                onStatus("Flipper serial service missing")
                return
            }
            rx = rxChar
            statusChar = service.getCharacteristic(STATUS_UUID)
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onStatus("Notifications unavailable")
                return
            }
            val indicate = (tx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val enable = if (indicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enable
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                onStatus("Connected")
                val sc = statusChar
                statusReads = 0
                if (sc != null) g.readCharacteristic(sc) else onRpcStatus(null)
            } else {
                onStatus("Pairing failed ($status)")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { dispatch(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            dispatch(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            lastWriteStatus = status
            if (status == BluetoothGatt.GATT_SUCCESS) writeOk++ else writeFail++
            writing = false
            pump()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            handleRead(c, c.value, status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleRead(c, value, status)
        }
    }

    private fun handleRead(c: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
        if (c.uuid != STATUS_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.isEmpty()) {
            onRpcStatus(null)
        } else {
            onRpcStatus(value[0].toInt() != 0)
        }
    }

    private var statusReads = 0

    private fun onRpcStatus(active: Boolean?) {
        rpcActive = active
        if (active == false) {
            statusReads = 0
            onLine(this, "@app")
        } else if (active == true && statusReads < 6 && ready) {
            statusReads++
            handler.postDelayed({
                val g = gatt
                val sc = statusChar
                if (ready && g != null && sc != null) g.readCharacteristic(sc)
            }, 1500)
        }
    }

    private fun dispatch(value: ByteArray) {
        rxPackets++
        rxBytes += value.size
        lastRx = value.take(6).joinToString(" ") { "%02x".format(it) }
        val sink = rawSink
        if (sink != null) sink(value) else assembler.feed(value)
    }

    @Synchronized
    private fun pump() {
        if (writing || !ready) return
        val g = gatt ?: return
        val ch = rx ?: return
        val chunk = queue.poll() ?: return
        writing = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = chunk
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) {
            writing = false
            handler.postDelayed({ pump() }, 50)
        }
    }

    override fun send(line: String) = sendRaw((line + "\n").toByteArray(Charsets.UTF_8))

    override fun sendRaw(data: ByteArray) {
        if (!ready) return
        val size = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < data.size) {
            val end = minOf(off + size, data.size)
            queue.add(data.copyOfRange(off, end))
            off = end
        }
        pump()
    }

    companion object {
        private const val RETRY_MS = 5000L
        private const val WATCHDOG_MS = 30000L
        private const val RECONNECT_MS = 2000L
        val SERVICE_UUID: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        val TX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")
        val RX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
        val STATUS_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e64fe0000")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun adapter(context: Context): BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
}
