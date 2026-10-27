package com.flipperai.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUi(
    val checking: Boolean = false,
    val latestTag: String = "",
    val appUpdate: Boolean = false,
    val appBusy: Boolean = false,
    val appProgress: Int = -1,
    val appMessage: String = "",
    val fapStatus: String = "",
    val fapUpdate: Boolean = false,
    val fapBusy: Boolean = false,
    val fapProgress: Int = -1,
    val fapMessage: String = ""
)

object AppModel {
    val state = MutableStateFlow(UpdateUi())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var release: ReleaseInfo? = null
    const val INSTALL_ACTION = "com.flipperai.bridge.INSTALL_RESULT"
    private const val FAP_DIR = "/ext/apps/Tools"
    private const val LEGACY_FAP_NAME = "claude_bridge.fap"

    private fun includeBeta(store: SecureStore) = store.betaUpdates || BuildConfig.VERSION_NAME.contains("-")

    fun reportInstall(message: String) = state.update { it.copy(appBusy = false, appProgress = -1, appMessage = message) }

    fun check(context: Context) {
        if (state.value.checking) return
        val store = SecureStore(context)
        state.update { it.copy(checking = true, appMessage = "", fapMessage = "") }
        scope.launch {
            try {
                val latest = Releases.latest(includeBeta(store))
                release = latest
                if (latest == null) {
                    state.update { it.copy(checking = false, appMessage = "No release found") }
                    return@launch
                }
                val newer = Versions.compare(latest.tag, BuildConfig.VERSION_NAME) > 0 &&
                    latest.asset(Releases.APK_NAME) != null
                val fapAsset = latest.asset(Releases.FAP_NAME)
                val installedSha = store.flipperFapSha
                val fapStatus: String
                val fapUpdate: Boolean
                when {
                    fapAsset == null -> {
                        fapStatus = "No Flipper app in the latest release"
                        fapUpdate = false
                    }
                    installedSha.isEmpty() -> {
                        fapStatus = "Not installed from this phone yet"
                        fapUpdate = true
                    }
                    fapAsset.sha256.isNotEmpty() && fapAsset.sha256 != installedSha -> {
                        fapStatus = "Update available for the Flipper app"
                        fapUpdate = true
                    }
                    else -> {
                        fapStatus = "Flipper app is up to date"
                        fapUpdate = false
                    }
                }
                state.update {
                    it.copy(
                        checking = false,
                        latestTag = latest.tag,
                        appUpdate = newer,
                        appMessage = if (newer) "" else "Phone app is up to date",
                        fapStatus = fapStatus,
                        fapUpdate = fapUpdate
                    )
                }
            } catch (e: Exception) {
                state.update { it.copy(checking = false, appMessage = "Update check failed: ${e.message}") }
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
                commitInstall(app, file)
            } catch (e: Exception) {
                state.update { it.copy(appBusy = false, appProgress = -1, appMessage = "Update failed: ${e.message}") }
            }
        }
    }

    private fun commitInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
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
                state.update { it.copy(fapMessage = "Checksum OK, talking to the Flipper") }
                val client = FlipperRpc(transport)
                rpc = client
                client.open(startCli = transport is UsbTransport)
                try {
                    client.ping()
                } catch (e: RpcException) {
                    throw RpcException(
                        "Flipper does not answer (${e.message}). Close AI Bridge on the Flipper, check the connection and try again"
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
                val where = target.removePrefix("/ext/")
                state.update {
                    it.copy(
                        fapBusy = false,
                        fapProgress = -1,
                        fapUpdate = false,
                        fapStatus = "Installed on the Flipper: $where",
                        fapMessage = "Done. Chats and settings were kept."
                    )
                }
            } catch (e: Exception) {
                state.update { it.copy(fapBusy = false, fapProgress = -1, fapMessage = "Install failed: ${e.message}") }
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
