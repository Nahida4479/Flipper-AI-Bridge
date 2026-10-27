package com.flipperclaude.bridge

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

    private val reconnect = Runnable { connect() }

    fun start(address: String) {
        stop()
        this.address = address
        if (address.isEmpty()) {
            onStatus("No device selected")
            return
        }
        active = true
        connect()
    }

    fun stop() {
        active = false
        ready = false
        handler.removeCallbacks(reconnect)
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

    private fun connect() {
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
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        gatt?.close()
        gatt = null
        rx = null
        ready = false
        queue.clear()
        writing = false
        assembler.reset()
        if (active) {
            onStatus("Connecting")
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, RECONNECT_MS)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("Pairing / setup")
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post { scheduleReconnect() }
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
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onStatus("Notifications unavailable")
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                onStatus("Connected")
            } else {
                onStatus("Pairing failed ($status)")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { assembler.feed(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            assembler.feed(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing = false
            pump()
        }
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

    override fun send(line: String) {
        if (!ready) return
        val data = (line + "\n").toByteArray(Charsets.UTF_8)
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
        private const val RECONNECT_MS = 2000L
        val SERVICE_UUID: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        val TX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")
        val RX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun adapter(context: Context): BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
}
