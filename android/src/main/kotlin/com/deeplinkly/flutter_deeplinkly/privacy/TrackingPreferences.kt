package com.deeplinkly.flutter_deeplinkly.privacy

import com.deeplinkly.flutter_deeplinkly.core.Prefs

object TrackingPreferences {
    private const val TRACKING_DISABLED_KEY = "tracking_disabled"
    fun isTrackingDisabled(): Boolean = Prefs.of().getBoolean(TRACKING_DISABLED_KEY, false)
    fun setTrackingDisabled(disabled: Boolean) = Prefs.of().edit().putBoolean(TRACKING_DISABLED_KEY, disabled).apply()
}

internal fun guardTracking(block: () -> Unit) {
    if (!TrackingPreferences.isTrackingDisabled()) block()
}
