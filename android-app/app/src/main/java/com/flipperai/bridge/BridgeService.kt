package com.flipperai.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: SecureStore
    private lateinit var client: ApiClient
    private var ble: BleTransport? = null
    private var usb: UsbTransport? = null
    private var job: Job? = null
    private val history = ArrayList<ChatTurn>()
    private var bleStatus = "Off"
    private var usbStatus = "Off"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SecureStore(this)
        client = ApiClient(store)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        TransportHub.service = this
        if (ble == null) {
            ble = BleTransport(this, ::onLine) { status ->
                bleStatus = status
                publishStatus()
            }
            usb = UsbTransport(this, ::onLine) { status ->
                usbStatus = status
                publishStatus()
            }
            TransportHub.ble = ble
            TransportHub.usb = usb
        }
        if (intent?.action == ACTION_RESTART_BLE) {
            ble?.start(store.bleAddress)
        } else if (intent?.action == ACTION_RESCAN_USB) {
            usb?.rescan()
        } else {
            ble?.start(store.bleAddress)
            usb?.start()
        }
        BridgeState.update { it.copy(running = true) }
        return START_STICKY
    }

    override fun onDestroy() {
        TransportHub.ble = null
        TransportHub.usb = null
        TransportHub.service = null
        job?.cancel()
        ble?.stop()
        usb?.stop()
        scope.cancel()
        BridgeState.update {
            it.copy(running = false, bleStatus = "Off", usbStatus = "Off", busy = false)
        }
        super.onDestroy()
    }

    private fun publishStatus() {
        BridgeState.update { it.copy(bleStatus = bleStatus, usbStatus = usbStatus) }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Flipper AI Bridge")
            .setContentText("Bluetooth: $bleStatus  |  USB: $usbStatus")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun onLine(source: Transport, line: String) {
        when {
            line == "P" -> source.send("H")
            line == "N" -> synchronized(history) { history.clear() }
            line.startsWith("U:") -> synchronized(history) {
                history.add(ChatTurn("user", Protocol.unescape(line.substring(2))))
            }
            line.startsWith("B:") -> synchronized(history) {
                history.add(ChatTurn("assistant", Protocol.unescape(line.substring(2))))
            }
            line.startsWith("Q:") -> handleQuestion(source, line.substring(2))
        }
    }

    private fun handleQuestion(source: Transport, payload: String) {
        val sep = payload.indexOf(':')
        if (sep < 0) return
        val lang = payload.substring(0, sep)
        val question = Protocol.unescape(payload.substring(sep + 1))
        val snapshot = synchronized(history) {
            history.add(ChatTurn("user", question))
            ArrayList(history)
        }
        job?.cancel()
        job = scope.launch {
            BridgeState.update { it.copy(busy = true) }
            source.send("S:Thinking...")
            try {
                val answer = client.ask(snapshot, lang).ifEmpty { "(empty answer)" }
                synchronized(history) { history.add(ChatTurn("assistant", answer)) }
                for (line in Protocol.answerLines(answer)) source.send(line)
                source.send("E")
                BridgeState.addLog(LogEntry(question, answer, false))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e.message ?: e.javaClass.simpleName).take(100)
                source.send("X:" + Protocol.escape(message))
                BridgeState.addLog(LogEntry(question, message, true))
            } finally {
                BridgeState.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "com.flipperai.bridge.STOP"
        const val ACTION_RESTART_BLE = "com.flipperai.bridge.RESTART_BLE"
        const val ACTION_RESCAN_USB = "com.flipperai.bridge.RESCAN_USB"

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, BridgeService::class.java)
            if (action != null) intent.action = action
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
