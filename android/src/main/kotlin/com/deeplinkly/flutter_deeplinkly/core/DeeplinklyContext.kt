package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context

/**
 * Holds a global application context for SDK components.
 */
object DeeplinklyContext {
    lateinit var app: Context
        internal set
}
