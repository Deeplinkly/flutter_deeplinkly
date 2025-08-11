package com.deeplinkly.flutter_deeplinkly.util

import com.deeplinkly.flutter_deeplinkly.core.Prefs
import java.util.UUID

object DeviceIdManager {
    private const val DEVICE_ID_KEY = "deeplinkly_device_id"
    fun getOrCreateDeviceId(): String {
        val prefs = Prefs.of()
        return prefs.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(DEVICE_ID_KEY, it).apply()
        }
    }
}
