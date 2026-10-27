package com.flipperclaude.bridge

import android.app.Service

object TransportHub {
    @Volatile var ble: BleTransport? = null
    @Volatile var usb: UsbTransport? = null
    @Volatile var service: Service? = null

    fun active(): Transport? {
        val u = usb
        if (u != null && u.connected) return u
        val b = ble
        if (b != null && b.connected) return b
        return null
    }
}
