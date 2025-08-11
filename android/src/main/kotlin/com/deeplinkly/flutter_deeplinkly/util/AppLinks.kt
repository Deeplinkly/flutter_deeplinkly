package com.deeplinkly.flutter_deeplinkly.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

fun retrieveAppLinkHosts(context: Context): List<String> {
    val hosts = mutableSetOf<String>()
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        data = Uri.parse("https://example.com")
    }
    val resolveInfos = pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
    for (info in resolveInfos) {
        if (info.activityInfo.packageName != context.packageName) continue
        val filter = info.filter ?: continue
        val isAppLinkIntent = filter.hasAction(Intent.ACTION_VIEW) &&
                filter.hasCategory(Intent.CATEGORY_DEFAULT) &&
                filter.hasCategory(Intent.CATEGORY_BROWSABLE)
        if (!isAppLinkIntent) continue
        for (i in 0 until filter.countDataSchemes()) {
            if (filter.getDataScheme(i) != "https") continue
            for (j in 0 until filter.countDataAuthorities()) {
                val host = filter.getDataAuthority(j)?.host
                if (!host.isNullOrBlank()) hosts.add(host)
            }
        }
    }
    return hosts.toList()
}
