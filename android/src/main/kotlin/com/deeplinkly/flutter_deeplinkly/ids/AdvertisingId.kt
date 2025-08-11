package com.deeplinkly.flutter_deeplinkly.ids

import android.content.Context
import com.deeplinkly.flutter_deeplinkly.core.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.ads.identifier.AdvertisingIdClient

suspend fun getAdvertisingId(context: Context): String? = withContext(Dispatchers.IO) {
    try {
        val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
        if (!info.isLimitAdTrackingEnabled) info.id else null
    } catch (e: Exception) {
        Logger.w("Failed to get Advertising ID: ${e.message}")
        null
    }
}
