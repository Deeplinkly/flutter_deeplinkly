package com.deeplinkly.flutter_deeplinkly.enrichment

import android.os.SystemClock
import com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.util.EnrichmentUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

object StartupEnrichment {
    @Volatile private var sentThisProcess = false

    /** Call once from the plugin after you’ve set DeeplinklyContext.app and have apiKey. */
    fun schedule(apiKey: String) {
        if (sentThisProcess) {
            Logger.d("StartupEnrichment: already sent in this process.")
            return
        }
        SdkRuntime.ioLaunch {
            val ready = waitUntilAttribution(timeoutMs = 30_000L)
            if (!ready) {
                Logger.d("StartupEnrichment: no attribution within timeout; skipping.")
                return@ioLaunch
            }
            try {
                // Build enrichment data: base + first-touch attribution
                val base = EnrichmentUtils.collect().toMutableMap()
                val attr = AttributionStore.get()
                base.putAll(attr.mapValues { it.value }) // Map<String,String> -> MutableMap<String,String?>
                EnrichmentSender.sendOnce(
                    context = DeeplinklyContext.app,
                    enrichmentData = base,
                    source = "app_start",
                    apiKey = apiKey
                )
                sentThisProcess = true
                Logger.d("StartupEnrichment: sent.")
            } catch (t: Throwable) {
                Logger.e("StartupEnrichment failure", t)
            }
        }
    }

    private suspend fun waitUntilAttribution(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val attr = AttributionStore.get() // Map<String,String>
            val hasUtm  = !attr["utm_source"].isNullOrBlank()
            val hasIds  = !attr["gclid"].isNullOrBlank() || !attr["fbclid"].isNullOrBlank() || !attr["ttclid"].isNullOrBlank()
            if (hasUtm || hasIds) return true
            delay(1_000)
        }
        return false
    }
}
