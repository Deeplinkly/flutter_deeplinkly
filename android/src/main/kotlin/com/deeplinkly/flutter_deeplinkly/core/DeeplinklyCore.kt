// FILE: com/deeplinkly/flutter_deeplinkly/core/DeeplinklyCore.kt
package com.deeplinkly.flutter_deeplinkly.core

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*

object DeeplinklyCore {
    // Logging disabled for production SDK
    fun d(msg: String) {
        // No-op: logging disabled
    }
    fun w(msg: String) {
        // No-op: logging disabled
    }
    fun e(msg: String, err: Throwable? = null) {
        // No-op: logging disabled
    }

    val ioScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    fun ioLaunch(block: suspend CoroutineScope.() -> Unit) {
        ioScope.launch(block = block)
    }

    fun invoke(channel: MethodChannel, method: String, args: Any?) {
        mainHandler.post {
            try {
                channel.invokeMethod(method, args)
            } catch (ex: Exception) {
                w("invoke($method) failed: ${ex.message}")
            }
        }
    }

    fun emit(sink: EventChannel.EventSink?, payload: Any?) {
        mainHandler.post {
            try {
                sink?.success(payload)
            } catch (ex: Exception) {
                w("emit failed: ${ex.message}")
            }
        }
    }

    fun wrapResult(result: MethodChannel.Result): MethodChannel.Result {
        var called = false
        return object : MethodChannel.Result {
            override fun success(res: Any?) {
                if (called) return
                called = true
                mainHandler.post {
                    try {
                        result.success(res)
                    } catch (ex: Exception) {
                        e("Result.success failed", ex)
                    }
                }
            }

            override fun error(code: String, msg: String?, details: Any?) {
                if (called) return
                called = true
                mainHandler.post {
                    try {
                        result.error(code, msg, details)
                    } catch (ex: Exception) {
                        e("Result.error failed", ex)
                    }
                }
            }

            override fun notImplemented() {
                if (called) return
                called = true
                mainHandler.post {
                    try {
                        result.notImplemented()
                    } catch (ex: Exception) {
                        e("Result.notImplemented failed", ex)
                    }
                }
            }
        }
    }
}
