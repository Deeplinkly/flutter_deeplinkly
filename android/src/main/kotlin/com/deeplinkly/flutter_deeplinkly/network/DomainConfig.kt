package com.deeplinkly.flutter_deeplinkly.network

object DomainConfig {
    const val API_BASE_URL = "https://deeplinkly.com"
    const val ENRICH_ENDPOINT = "$API_BASE_URL/api/v1/enrich"
    const val ERROR_ENDPOINT = "$API_BASE_URL/api/v1/sdk-error"
    const val RESOLVE_CLICK_ENDPOINT = "$API_BASE_URL/api/v1/resolve"
    const val GENERATE_LINK_ENDPOINT = "$API_BASE_URL/api/v1/generate-url"
}
