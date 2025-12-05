package com.deeplinkly.flutter_deeplinkly.network

/**
 * Domain configuration for API endpoints
 */
object DomainConfig {
    private const val BASE = "https://deeplinkly.com/api/v1"
    
    const val RESOLVE_CLICK_ENDPOINT = "$BASE/resolve"
    const val GENERATE_LINK_ENDPOINT = "$BASE/generate-url"
    const val ENRICH_ENDPOINT = "$BASE/enrich"
    const val ERROR_ENDPOINT = "$BASE/error/report"
}


