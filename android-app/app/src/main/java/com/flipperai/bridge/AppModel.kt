package com.flipperai.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class FapState { Unknown, NotInstalled, UpToDate, UpdateAvailable }

data class UpdateInfo(
    val latestTag: String,
    val appUpdate: Boolean,
    val fapState: FapState
)

data class UpdateUi(
    val checking: Boolean = false,
    val latestTag: String = "",
    val appUpdate: Boolean = false,
    val appBusy: Boolean = false,
    val appProgress: Int = -1,
    val appMessage: String = "",
    val fapState: FapState = FapState.Unknown,
    val fapInstalledTag: String = "",
    val fapBusy: Boolean = false,
    val fapProgress: Int = -1,
    val fapMessage: String = ""
)

object AppModel {
    val state = MutableStateFlow(UpdateUi())
    val toast = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var release: ReleaseInfo? = null
    const val INSTALL_ACTION = "com.flipperai.bridge.INSTALL_RESULT"
    private const val FAP_DIR = "/ext/apps/Tools"
    private const val LEGACY_FAP_NAME = "claude_bridge.fap"

    fun includeBeta(store: SecureStore) = store.betaUpdates || BuildConfig.VERSION_NAME.contains("-")

    fun showToast(message: String) {
        toast.value = message
        scope.launch {
            delay(3200)
            toast.compareAndSet(message, null)
        }
    }

    fun reportInstall(message: String) = state.update { it.copy(appBusy = false, appProgress = -1, appMessage = message) }

    fun evaluate(latest: ReleaseInfo, store: SecureStore): UpdateInfo {
        val newer = Versions.compare(latest.tag, BuildConfig.VERSION_NAME) > 0 &&
            latest.asset(Releases.APK_NAME) != null
        val fapAsset = latest.asset(Releases.FAP_NAME)
        val installedSha = store.flipperFapSha
        val fap = when {
            fapAsset == null -> FapState.Unknown
            installedSha.isEmpty() -> FapState.NotInstalled
            fapAsset.sha256.isNotEmpty() && fapAsset.sha256 != installedSha -> FapState.UpdateAvailable
            else -> FapState.UpToDate
        }
        return UpdateInfo(latest.tag, newer, fap)
    }

    fun check(context: Context, announce: Boolean = false) {
        if (state.value.checking) return
        val store = SecureStore(context)
        state.update { it.copy(checking = true, appMessage = "") }
        scope.launch {
            try {
                val latest = Releases.latest(includeBeta(store))
                release = latest
                if (latest == null) {
                    state.update { it.copy(checking = false, appMessage = "No release found") }
                    return@launch
                }
                val info = evaluate(latest, store)
                state.update {
                    it.copy(
                        checking = false,
                        latestTag = latest.tag,
                        appUpdate = info.appUpdate,
                        appMessage = if (info.appUpdate) "" else "Phone app is up to date",
                        fapState = info.fapState,
                        fapInstalledTag = store.flipperFapTag
                    )
                }
                if (announce) {
                    showToast(
                        if (info.appUpdate || info.fapState == FapState.UpdateAvailable) "Update available: ${latest.tag}"
                        else "Everything is up to date"
                    )
                }
            } catch (e: Exception) {
                state.update { it.copy(checking = false, appMessage = "Update check failed: ${e.message}") }
                if (announce) showToast("Update check failed")
            }
        }
    }

    fun installApk(context: Context) {
        val rel = release ?: return
        if (state.value.appBusy) return
        val app = context.applicationContext
        state.update { it.copy(appBusy = true, appProgress = 0, appMessage = "Downloading") }
        scope.launch {
            try {
                val data = Releases.fetchAndVerify(rel, Releases.APK_NAME) { p ->
                    state.update { it.copy(appProgress = p) }
                }
                state.update { it.copy(appProgress = 100, appMessage = "Checksum OK, installing") }
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "update.apk")
                file.writeBytes(data)
                showToast("Checksum verified")
                commitInstall(app, file)
            } catch (e: Exception) {
                state.update { it.copy(appBusy = false, appProgress = -1, appMessage = "Update failed: ${e.message}") }
                showToast("Update failed")
            }
        }
    }

    private fun commitInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(INSTALL_ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    fun installFap(context: Context) {
        if (state.value.fapBusy) return
        if (state.value.appUpdate) {
            showToast("Update the phone app first")
            return
        }
        val store = SecureStore(context)
        state.update { it.copy(fapBusy = true, fapProgress = 0, fapMessage = "Preparing") }
        scope.launch {
            var rpc: FlipperRpc? = null
            try {
                val transport = TransportHub.active()
                    ?: throw RpcException("Start the service and connect the Flipper over USB or Bluetooth")
                val rel = release ?: Releases.latest(includeBeta(store))
                    ?: throw RpcException("No release found. If only beta releases exist, turn on Include beta versions")
                release = rel
                state.update { it.copy(fapMessage = "Downloading Flipper app") }
                val data = Releases.fetchAndVerify(rel, Releases.FAP_NAME) { p ->
                    state.update { it.copy(fapProgress = p / 4) }
                }
                if (transport is BleTransport) {
                    state.update { it.copy(fapMessage = "Reconnecting Bluetooth") }
                    if (!transport.reconnect(25000)) {
                        throw RpcException("Bluetooth did not reconnect. Check that the Flipper is on and not running AI Bridge")
                    }
                    Thread.sleep(1500)
                    if (transport.rpcActive == false) {
                        throw RpcException("The Flipper is running an app. Close AI Bridge on the Flipper and try again")
                    }
                }
                state.update { it.copy(fapMessage = "Checksum OK, talking to the Flipper") }
                val client = FlipperRpc(transport)
                rpc = client
                client.open(startCli = transport is UsbTransport)
                var pinged = false
                var lastError = ""
                for (attempt in 1..4) {
                    try {
                        client.ping()
                        pinged = true
                        break
                    } catch (e: RpcException) {
                        lastError = e.message ?: ""
                        if (lastError.contains("busy")) break
                        Thread.sleep(800)
                    }
                }
                if (!pinged) {
                    throw RpcException(
                        "Flipper does not answer ($lastError). Close AI Bridge on the Flipper, disconnect it from the computer and try again"
                    )
                }
                val existing = findExisting(client)
                val dir = existing?.dir ?: FAP_DIR
                val target = "$dir/${Releases.FAP_NAME}"
                if (existing == null) {
                    state.update { it.copy(fapMessage = "Creating Tools folder") }
                    client.mkdir("/ext/apps")
                    client.mkdir(FAP_DIR)
                }
                val temp = "$target.new"
                state.update { it.copy(fapMessage = "Uploading") }
                val delay = if (transport is UsbTransport) 0L else 30L
                val chunk = if (transport is UsbTransport) 480 else 200
                client.write(temp, data, chunk, delay) { p ->
                    state.update { it.copy(fapProgress = 25 + p * 7 / 10) }
                }
                state.update { it.copy(fapMessage = "Verifying", fapProgress = 96) }
                val remote = client.md5(temp)
                if (!remote.equals(Releases.md5(data), ignoreCase = true)) {
                    try {
                        client.delete(temp)
                    } catch (e: RpcException) {
                    }
                    throw RpcException("Upload verification failed, nothing was changed")
                }
                if (existing?.current == true) client.delete(target)
                if (existing?.legacy == true) client.delete("$dir/$LEGACY_FAP_NAME")
                client.rename(temp, target)
                store.flipperFapSha = Releases.sha256(data)
                store.flipperFapTag = rel.tag
                val where = target.removePrefix("/ext/")
                state.update {
                    it.copy(
                        fapBusy = false,
                        fapProgress = -1,
                        fapState = FapState.UpToDate,
                        fapInstalledTag = rel.tag,
                        fapMessage = "Installed $where. Chats and settings were kept."
                    )
                }
                showToast("Flipper app installed")
            } catch (e: Exception) {
                state.update { it.copy(fapBusy = false, fapProgress = -1, fapMessage = "Install failed: ${e.message}") }
                showToast("Flipper install failed")
            } finally {
                rpc?.close()
            }
        }
    }

    private class Existing(val dir: String, val current: Boolean, val legacy: Boolean)

    private fun findExisting(rpc: FlipperRpc): Existing? {
        val root = try {
            rpc.list("/ext/apps")
        } catch (e: RpcException) {
            return null
        }
        fun scan(dir: String, entries: List<Pair<String, Boolean>>): Existing? {
            val current = entries.any { it.first == Releases.FAP_NAME && !it.second }
            val legacy = entries.any { it.first == LEGACY_FAP_NAME && !it.second }
            return if (current || legacy) Existing(dir, current, legacy) else null
        }
        scan("/ext/apps", root)?.let { return it }
        for ((name, isDir) in root) {
            if (!isDir) continue
            val inner = try {
                rpc.list("/ext/apps/$name")
            } catch (e: RpcException) {
                continue
            }
            scan("/ext/apps/$name", inner)?.let { return it }
        }
        return null
    }
}
