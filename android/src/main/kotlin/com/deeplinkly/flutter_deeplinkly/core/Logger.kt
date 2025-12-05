package com.deeplinkly.flutter_deeplinkly.core

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logger with SDK-level debug mode control
 * Defaults to false (no logging) for production
 */
object Logger {
    private const val TAG = "Deeplinkly"
    private val isDebugMode = AtomicBoolean(false)
    
    /**
     * Enable or disable debug logging
     * @param enabled true to enable logging, false to disable (default)
     */
    fun setDebugMode(enabled: Boolean) {
        isDebugMode.set(enabled)
    }
    
    /**
     * Check if debug mode is enabled
     */
    fun isDebugMode(): Boolean = isDebugMode.get()
    
    fun d(msg: String) {
        if (isDebugMode.get()) {
            Log.d(TAG, msg)
        }
    }
    
    fun w(msg: String) {
        if (isDebugMode.get()) {
            Log.w(TAG, msg)
        }
    }
    
    fun e(msg: String, e: Throwable? = null) {
        if (isDebugMode.get()) {
            if (e != null) {
                Log.e(TAG, msg, e)
            } else {
                Log.e(TAG, msg)
            }
        }
    }
}

