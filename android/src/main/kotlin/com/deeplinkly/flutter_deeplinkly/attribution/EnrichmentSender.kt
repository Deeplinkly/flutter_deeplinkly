// FILE: com/deeplinkly/flutter_deeplinkly/attribution/EnrichmentSender.kt
package com.deeplinkly.flutter_deeplinkly.attribution

import android.content.Context
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyCore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EnrichmentSender {
    suspend fun sendOnce(
        context: Context,
        enrichmentData: MutableMap<String, String?>,
        source: String,
        apiKey: String
    ) = withContext(Dispatchers.IO) {
        DeeplinklyUtils.guardTracking {
            val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
            if (enrichmentData["advertising_id"].isNullOrEmpty()) {
                prefs.getString("advertising_id", null)?.let {
                    enrichmentData["advertising_id"] = it
                    DeeplinklyCore.d("Using cached advertising_id: $it")
                }
            }
            val hasAttribution = listOf(
                "click_id", "utm_source", "utm_medium", "utm_campaign", "gclid", "fbclid", "ttclid", "code",
            ).any { !enrichmentData[it].isNullOrBlank() }
            if (hasAttribution) {
                DeeplinklyCore.d("Sending enrichment for $source")
                DeeplinklyNetwork.sendEnrichment(enrichmentData, apiKey)
            } else {
                DeeplinklyCore.d("Skipping enrichment: no attribution data")
            }
        }
    }
}
