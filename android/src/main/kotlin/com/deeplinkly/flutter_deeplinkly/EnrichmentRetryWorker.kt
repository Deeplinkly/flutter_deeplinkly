package com.deeplinkly.flutter_deeplinkly

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EnrichmentRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Process may be cold-started: ensure singletons have a Context
        DeeplinklyContext.app = applicationContext

        val apiKey = inputData.getString(KEY_API) ?: readApiKeyFromManifest() ?: return Result.failure()

        return try {
            SdkRetryQueue.retryAll(apiKey)   // suspend; OK in CoroutineWorker
            Result.success()
        } catch (e: Exception) {
            Logger.e("Retry worker failed", e)
            Result.retry()
        }
    }

    private fun readApiKeyFromManifest(): String? = try {
        val pm = applicationContext.packageManager
        val appInfo = pm.getApplicationInfo(
            applicationContext.packageName,
            PackageManager.GET_META_DATA
        )
        appInfo.metaData?.getString("com.deeplinkly.sdk.api_key")
    } catch (_: Exception) {
        null
    }

    companion object {
        const val KEY_API = "deeplinkly_api_key"
    }
}
