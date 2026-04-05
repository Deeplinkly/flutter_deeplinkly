package com.deeplinkly.flutter_deeplinkly.core

import com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender

object UserIdManager {
    fun updateCustomUserId(newId: String?, apiKey: String) {
        val previous = DeeplinklyUtils.getCustomUserId()
        if (previous == newId) return
        DeeplinklyUtils.setCustomUserId(newId)
        Logger.d("UserIdManager: updated custom user ID → ${newId ?: "nil"}")
        SdkRuntime.ioLaunch {
            val enrichmentData = DeeplinklyUtils.collectEnrichment().toMutableMap()
            enrichmentData["custom_user_id"] = newId
            EnrichmentSender.sendOnce(
                DeeplinklyContext.app,
                enrichmentData,
                "custom_user_id",
                apiKey
            )
        }
    }
}
