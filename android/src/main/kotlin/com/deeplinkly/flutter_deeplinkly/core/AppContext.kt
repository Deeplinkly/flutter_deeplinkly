package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context

/**
 * Holds a global application context for SDK components.
 */
object DeeplinklyContext {
    lateinit var app: Context
        internal set
}

object Prefs {
    private const val PREFS_NAME = "deeplinkly_prefs"
    fun of(): android.content.SharedPreferences =
        DeeplinklyContext.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
