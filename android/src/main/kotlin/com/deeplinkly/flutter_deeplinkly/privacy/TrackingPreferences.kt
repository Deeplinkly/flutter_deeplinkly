package com.deeplinkly.flutter_deeplinkly.privacy

import com.deeplinkly.flutter_deeplinkly.core.Prefs

/**
 * Manages tracking preferences and privacy settings
 */
object TrackingPreferences {
    private const val KEY_TRACKING_DISABLED = "tracking_disabled"
    
    /**
     * Check if tracking is disabled
     */
    fun isTrackingDisabled(): Boolean {
        return Prefs.of().getBoolean(KEY_TRACKING_DISABLED, false)
    }
    
    /**
     * Set tracking disabled state
     */
    fun setTrackingDisabled(disabled: Boolean) {
        Prefs.of().edit().putBoolean(KEY_TRACKING_DISABLED, disabled).apply()
    }
}


