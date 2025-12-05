package com.deeplinkly.flutter_deeplinkly.helpers

import android.content.Intent
import android.net.Uri

/**
 * Helper class to build test intents for deep link testing
 */
object TestIntentBuilder {
    /**
     * Create an intent with a deep link URI
     */
    fun createDeepLinkIntent(
        scheme: String = "https",
        host: String = "deeplink.example.com",
        path: String? = null,
        clickId: String? = null,
        code: String? = null,
        vararg queryParams: Pair<String, String?>
    ): Intent {
        val uriBuilder = Uri.Builder()
            .scheme(scheme)
            .authority(host)
        
        if (code != null) {
            uriBuilder.appendPath(code)
        } else if (path != null) {
            uriBuilder.appendPath(path)
        }
        
        clickId?.let { uriBuilder.appendQueryParameter("click_id", it) }
        
        queryParams.forEach { (key, value) ->
            value?.let { uriBuilder.appendQueryParameter(key, it) }
        }
        
        return Intent(Intent.ACTION_VIEW, uriBuilder.build())
    }

    /**
     * Create an intent with click_id
     */
    fun createClickIdIntent(
        clickId: String,
        vararg queryParams: Pair<String, String?>
    ): Intent {
        return createDeepLinkIntent(
            clickId = clickId,
            queryParams = *queryParams
        )
    }

    /**
     * Create an intent with code (path segment)
     */
    fun createCodeIntent(
        code: String,
        vararg queryParams: Pair<String, String?>
    ): Intent {
        return createDeepLinkIntent(
            code = code,
            queryParams = *queryParams
        )
    }

    /**
     * Create an intent with UTM parameters
     */
    fun createUtmIntent(
        clickId: String,
        utmSource: String? = null,
        utmMedium: String? = null,
        utmCampaign: String? = null,
        utmTerm: String? = null,
        utmContent: String? = null
    ): Intent {
        val params = mutableListOf<Pair<String, String?>>()
        utmSource?.let { params.add("utm_source" to it) }
        utmMedium?.let { params.add("utm_medium" to it) }
        utmCampaign?.let { params.add("utm_campaign" to it) }
        utmTerm?.let { params.add("utm_term" to it) }
        utmContent?.let { params.add("utm_content" to it) }
        
        return createClickIdIntent(clickId, *params.toTypedArray())
    }

    /**
     * Create an intent with tracking IDs
     */
    fun createTrackingIntent(
        clickId: String,
        gclid: String? = null,
        fbclid: String? = null,
        ttclid: String? = null
    ): Intent {
        val params = mutableListOf<Pair<String, String?>>()
        gclid?.let { params.add("gclid" to it) }
        fbclid?.let { params.add("fbclid" to it) }
        ttclid?.let { params.add("ttclid" to it) }
        
        return createClickIdIntent(clickId, *params.toTypedArray())
    }

    /**
     * Create an invalid intent (no click_id or code)
     */
    fun createInvalidIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/page"))
    }

    /**
     * Create an intent with null data
     */
    fun createNullDataIntent(): Intent {
        return Intent(Intent.ACTION_VIEW)
    }
}

