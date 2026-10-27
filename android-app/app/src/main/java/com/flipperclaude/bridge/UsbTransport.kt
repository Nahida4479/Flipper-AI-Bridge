package com.flipperclaude.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

class UsbTransport(
    private val context: Context,
    private val onLine: (Transport, String) -> Unit,
    private val onStatus: (String) -> Unit
) : Transport {

    override val label = "USB"
    @Volatile override var rawSink: ((ByteArray) -> Unit)? = null
    override val connected: Boolean get() = running

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val assembler = LineAssembler { onLine(this, it) }
    private val lock = Any()
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var controlIface: UsbInterface? = null
    private var dataIface: UsbInterface? = null
    private var inEp: UsbEndpoint? = null
    private var outEp: UsbEndpoint? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val dev = usbDevice(intent) ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        open(dev)
                    } else {
                        onStatus("Permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = usbDevice(intent) ?: return
                    if (isFlipper(dev)) request(dev)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = usbDevice(intent) ?: return
                    if (dev.deviceId == device?.deviceId) close()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun isFlipper(dev: UsbDevice) = dev.vendorId == VID && dev.productId == PID

    fun start() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        onStatus("Waiting for cable")
        manager.deviceList.values.firstOrNull { isFlipper(it) }?.let { request(it) }
    }

    fun rescan() {
        if (running) return
        manager.deviceList.values.firstOrNull { isFlipper(it) }?.let { request(it) }
    }

    fun stop() {
        close()
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
            }
            registered = false
        }
        onStatus("Off")
    }

    private fun request(dev: UsbDevice) {
        if (running) return
        if (manager.hasPermission(dev)) {
            open(dev)
            return
        }
        onStatus("Waiting for permission")
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        manager.requestPermission(dev, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    private fun open(dev: UsbDevice) {
        synchronized(lock) {
            if (running) return
            var control: UsbInterface? = null
            var data: UsbInterface? = null
            for (i in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM && control == null) control = iface
                if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && data == null) data = iface
            }
            if (data == null) {
                onStatus("CDC interface missing")
                return
            }
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until data.endpointCount) {
                val ep = data.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
            }
            if (bulkIn == null || bulkOut == null) {
                onStatus("Endpoints missing")
                return
            }
            val conn = manager.openDevice(dev)
            if (conn == null) {
                onStatus("Cannot open device")
                return
            }
            if (control != null) conn.claimInterface(control, true)
            if (!conn.claimInterface(data, true)) {
                conn.close()
                onStatus("Cannot claim interface")
                return
            }
            if (control != null) {
                conn.controlTransfer(0x21, 0x22, 0x03, control.id, null, 0, 500)
            }
            connection = conn
            device = dev
            controlIface = control
            dataIface = data
            inEp = bulkIn
            outEp = bulkOut
            assembler.reset()
            running = true
            reader = Thread({ readLoop(conn, bulkIn) }, "usb-reader").also { it.start() }
        }
        onStatus("Connected")
        onLine(this, "@ready")
    }

    private fun readLoop(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = ByteArray(512)
        while (running) {
            val n = conn.bulkTransfer(ep, buf, buf.size, 500)
            if (n > 0) {
                val sink = rawSink
                if (sink != null) sink(buf.copyOf(n)) else assembler.feed(buf.copyOf(n))
            }
            if (n < 0 && running) {
                if (manager.deviceList.values.none { it.deviceId == device?.deviceId }) {
                    break
                }
                Thread.sleep(50)
            }
        }
        if (running) close()
    }

    private fun close() {
        synchronized(lock) {
            if (!running && connection == null) return
            running = false
            val conn = connection
            try {
                controlIface?.let { conn?.controlTransfer(0x21, 0x22, 0x00, it.id, null, 0, 200) }
                dataIface?.let { conn?.releaseInterface(it) }
                controlIface?.let { conn?.releaseInterface(it) }
            } catch (e: Exception) {
            }
            conn?.close()
            connection = null
            device = null
            controlIface = null
            dataIface = null
            inEp = null
            outEp = null
            assembler.reset()
        }
        onStatus("Waiting for cable")
    }

    override fun send(line: String) = sendRaw((line + "\n").toByteArray(Charsets.UTF_8))

    override fun sendRaw(data: ByteArray) {
        val conn: UsbDeviceConnection
        val ep: UsbEndpoint
        synchronized(lock) {
            conn = connection ?: return
            ep = outEp ?: return
        }
        var off = 0
        while (off < data.size) {
            val len = minOf(64, data.size - off)
            val n = conn.bulkTransfer(ep, data, off, len, 1000)
            if (n < 0) return
            off += n
        }
    }

    companion object {
        const val VID = 0x0483
        const val PID = 0x5740
        private const val ACTION_PERMISSION = "com.flipperclaude.bridge.USB_PERMISSION"
    }
}
