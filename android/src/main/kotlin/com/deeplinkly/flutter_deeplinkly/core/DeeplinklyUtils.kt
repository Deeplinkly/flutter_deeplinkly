// FILE: com/deeplinkly/flutter_deeplinkly/core/DeeplinklyUtils.kt
package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.text.SimpleDateFormat
import java.util.*

object DeeplinklyUtils {
    /**
     * Delegates to [Prefs] rather than opening the file itself.
     *
     * It used to call `getSharedPreferences("deeplinkly_prefs", …)` directly —
     * the same file, reached a second way. That mattered once [Prefs] became
     * the place the backup-restore check runs: `getOrCreateDeviceId` is one of
     * the earliest reads in the process, so the one accessor that skipped the
     * check was also the one most likely to hand back a previous install's
     * device id before the check could clear it.
     */
    private val prefs: SharedPreferences
        get() = Prefs.of()

    private const val CUSTOM_USER_ID_KEY = "custom_user_id"
    private const val LEGACY_CUSTOM_USER_ID_KEY = "dl_custom_user_id"

    fun getCustomUserId(): String? {
        var v = prefs.getString(CUSTOM_USER_ID_KEY, null)
        if (v == null) {
            v = prefs.getString(LEGACY_CUSTOM_USER_ID_KEY, null)
            if (v != null) {
                prefs.edit()
                    .putString(CUSTOM_USER_ID_KEY, v)
                    .remove(LEGACY_CUSTOM_USER_ID_KEY)
                    .apply()
            }
        }
        return v
    }

    fun setCustomUserId(id: String?) = prefs.edit().putString(CUSTOM_USER_ID_KEY, id).apply()

    /**
     * Delegates rather than reading the pref itself. There were two
     * implementations of this over the same key, which is one more than can
     * stay correct.
     */
    fun isTrackingDisabled(): Boolean =
        com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences.isTrackingDisabled()

    inline fun guardTracking(block: () -> Unit) {
        if (!isTrackingDisabled()) block()
    }

    private const val DEVICE_ID_KEY = "deeplinkly_device_id"
    fun getOrCreateDeviceId(): String {
        return prefs.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(DEVICE_ID_KEY, it).apply()
        }
    }

    // There is deliberately no collectEnrichment() here any more. Assembling a
    // payload happens in exactly one place — EnrichmentSender — from
    // DeviceProfile plus DynamicSignals. A second assembly path is how the two
    // platforms drifted apart in the first place.
}
