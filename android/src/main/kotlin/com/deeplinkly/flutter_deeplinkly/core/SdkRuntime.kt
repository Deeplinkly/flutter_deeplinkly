package com.deeplinkly.flutter_deeplinkly.core

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*

object SdkRuntime {
    lateinit var ioScope: CoroutineScope
    lateinit var mainHandler: Handler

    fun ioLaunch(block: suspend CoroutineScope.() -> Unit) =
        (if (::ioScope.isInitialized) ioScope else CoroutineScope(Dispatchers.IO)).launch(block = block)

    fun postToFlutter(channel: MethodChannel, method: String, args: Any?) {
        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }
        mainHandler.post {
            try {
                channel.invokeMethod(method, args)
            } catch (e: Exception) {
                Logger.w("invoke failed: ${e.message}")
            }
        }
    }
}
