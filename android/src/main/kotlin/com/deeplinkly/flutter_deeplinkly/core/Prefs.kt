package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object Prefs {
    private const val PREFS_NAME = "deeplinkly_prefs"

    private const val KEY_SESSION_ID = "dl_session_id"
    private const val KEY_SENT_ENRICHMENT_THIS_SESSION = "dl_sent_enrichment_this_session"
    private const val KEY_CUSTOM_USER_ID = "dl_custom_user_id"
    private const val KEY_LAST_ENRICH_FOR_CUSTOM_ID = "dl_last_enrich_for_custom_id"

    // Central SP accessor
    private val prefs: SharedPreferences
        get() = DeeplinklyContext.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markNewSession() {
        val s = UUID.randomUUID().toString()
        prefs.edit()
            .putString(KEY_SESSION_ID, s)
            .putBoolean(KEY_SENT_ENRICHMENT_THIS_SESSION, false)
            .apply()
    }

    fun markSessionEnrichmentSent() {
        prefs.edit().putBoolean(KEY_SENT_ENRICHMENT_THIS_SESSION, true).apply()
    }

    fun wasSessionEnrichmentSent(): Boolean =
        prefs.getBoolean(KEY_SENT_ENRICHMENT_THIS_SESSION, false)

    fun getSessionId(): String? =
        prefs.getString(KEY_SESSION_ID, null)

    fun getCustomUserId(): String? =
        prefs.getString(KEY_CUSTOM_USER_ID, null)

    fun setCustomUserId(newId: String?) {
        prefs.edit().putString(KEY_CUSTOM_USER_ID, newId).apply()
    }

    fun getLastEnrichedForCustomId(): String? =
        prefs.getString(KEY_LAST_ENRICH_FOR_CUSTOM_ID, null)

    fun setLastEnrichedForCustomId(id: String?) {
        prefs.edit().putString(KEY_LAST_ENRICH_FOR_CUSTOM_ID, id).apply()
    }

    /** Expose SP if you really need it elsewhere. */
    fun of(): SharedPreferences = prefs
}
