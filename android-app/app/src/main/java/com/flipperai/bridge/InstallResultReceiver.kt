package com.flipperai.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppModel.reportInstall("Updated")
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"
                val hint = when {
                    reason.contains("VERIFICATION", ignoreCase = true) ->
                        "Blocked by Google Play Protect. Open More details and choose Install anyway, or allow the app in Play Store > Play Protect settings."
                    reason.contains("INCOMPATIBLE", ignoreCase = true) || reason.contains("signature", ignoreCase = true) ->
                        "The new version is signed with a different key. Uninstall the app once and install the new version. See the signing key section in the README."
                    else -> reason
                }
                AppModel.reportInstall("Install failed: $hint")
            }
        }
    }
}
