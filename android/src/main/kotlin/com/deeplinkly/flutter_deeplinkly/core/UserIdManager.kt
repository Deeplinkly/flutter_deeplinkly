package com.deeplinkly.flutter_deeplinkly.core

import com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender

object UserIdManager {
    fun updateCustomUserId(newId: String?, apiKey: String) {
        val previous = DeeplinklyUtils.getCustomUserId()
        if (previous == newId) return
        DeeplinklyUtils.setCustomUserId(newId)
        Logger.d("UserIdManager: updated custom user ID → ${newId ?: "nil"}")
        SdkRuntime.ioLaunch {
            // The new id is already stored, so the sender reads it back with
            // the rest of the payload. Nothing to pass but the source.
            EnrichmentSender.sendOnce(emptyMap(), "custom_user_id", apiKey)
        }
    }
}
