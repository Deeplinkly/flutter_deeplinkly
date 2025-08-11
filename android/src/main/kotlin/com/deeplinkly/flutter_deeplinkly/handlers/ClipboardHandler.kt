package com.deeplinkly.flutter_deeplinkly.handlers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import com.deeplinkly.flutter_deeplinkly.util.retrieveAppLinkHosts
import io.flutter.plugin.common.MethodChannel

object ClipboardHandler {
    private var cachedAppLinkDomains: List<String>? = null

    private fun getCachedAppLinkDomains(context: Context): List<String> {
        if (cachedAppLinkDomains == null) cachedAppLinkDomains = retrieveAppLinkHosts(context)
        return cachedAppLinkDomains ?: emptyList()
    }

    fun checkClipboard(channel: MethodChannel, apiKey: String) {
        val context = DeeplinklyContext.app
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) return
            val clip = clipboard.primaryClip ?: return
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: return
            val lowerText = text.lowercase()
            val domains = getCachedAppLinkDomains(context)
            val matched = domains.any { domain ->
                lowerText.startsWith(domain) ||
                lowerText.startsWith("https://$domain") ||
                lowerText.startsWith("http://$domain")
            }
            if (!matched) return
            Logger.d("Found deep link in clipboard: $text")
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(text))
            DeepLinkHandler.handleIntent(context, deepLinkIntent, channel, apiKey)
            clipboard.setPrimaryClip(ClipData.newPlainText("", "")) // clear clipboard
        } catch (e: Exception) {
            NetworkUtils.reportError(apiKey, "Clipboard fallback failed", e.stackTraceToString())
        }
    }
}
