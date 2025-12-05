package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Wrapper for SharedPreferences to provide easy access to SDK preferences
 */
object Prefs {
    private const val PREFS_NAME = "deeplinkly_prefs"
    
    fun of(): SharedPreferences {
        return DeeplinklyContext.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}


