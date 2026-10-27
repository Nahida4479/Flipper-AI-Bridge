package com.flipperai.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = SecureStore(context)
        if (store.serviceWanted) {
            try {
                BridgeService.start(context)
            } catch (e: Exception) {
            }
        }
        Notifier.updated(context, BuildConfig.VERSION_NAME)
        UpdateWorker.schedule(context)
    }
}
