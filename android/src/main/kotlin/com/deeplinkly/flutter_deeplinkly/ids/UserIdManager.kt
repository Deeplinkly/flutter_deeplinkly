package com.deeplinkly.flutter_deeplinkly.ids

import com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import kotlinx.coroutines.launch

object UserIdManager {

    /** Call whenever your app sets/updates the custom user id (from Flutter). */
    fun updateCustomUserId(newId: String?, apiKey: String) {
        val prev = Prefs.getCustomUserId()
        if (prev == newId) return

        Prefs.setCustomUserId(newId)

        // Avoid duplicate enrichment for the same value
        val lastEnriched = Prefs.getLastEnrichedForCustomId()
        if (lastEnriched == newId) return

        SdkRuntime.ioScope.launch {
            try {
                EnrichmentSender.sendOnce(
                    context = DeeplinklyContext.app,
                    enrichmentData = mutableMapOf<String, String?>().apply {
                        put("custom_user_id", newId)
                    },
                    source = "custom_user_id_changed",
                    apiKey = apiKey
                )

                Prefs.setLastEnrichedForCustomId(newId)
                Logger.d("UserIdManager: enrichment sent for custom user id change.")
            } catch (t: Throwable) {
                Logger.e("UserIdManager: enrichment on user id change failed", t)
            }
        }
    }
}
