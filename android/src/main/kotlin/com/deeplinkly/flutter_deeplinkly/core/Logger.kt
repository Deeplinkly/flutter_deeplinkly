package com.deeplinkly.flutter_deeplinkly.core

import android.util.Log

object Logger {
    private const val TAG = "Deeplinkly"
    fun d(msg: String) = Log.d(TAG, "✅ $msg")
    fun w(msg: String) = Log.w(TAG, "⚠️ $msg")
    fun e(msg: String, e: Throwable? = null) = Log.e(TAG, "❌ $msg", e)
}
