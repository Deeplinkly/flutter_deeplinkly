package com.deeplinkly.flutter_deeplinkly.handlers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.flutter_deeplinkly.network.isTerminalHttp
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue
import io.flutter.plugin.common.MethodChannel

object DeepLinkHandler {
    /** The mechanism this handler reports, to the backend and to Dart. */
    const val SOURCE = "deep_link"

    /** Same link, but delivered without the backend having resolved it. */
    const val FALLBACK_SOURCE = "deep_link_fallback"

    /** Marks an intent this process has already turned into a delivery. */
    private const val EXTRA_CONSUMED = "com.deeplinkly.sdk.intent_consumed"

    /**
     * True when this intent has already been delivered once.
     *
     * The launch intent is read again on every fresh attach, and Android hands
     * back the *original* VIEW intent when it restarts a killed process from
     * Recents - so a link opened, backgrounded, and restored fired a second
     * onDeepLink and a second enrichment for the same click. Two complementary
     * guards, because neither covers the other's case: the history flag is what
     * the system sets across process death (where our own extra cannot survive,
     * the intent being rebuilt from the task record), and the extra catches a
     * re-attach inside one process, where the flag is absent because the intent
     * object is the same one we already handled.
     */
    private fun isReplay(intent: Intent): Boolean =
        intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 ||
            intent.getBooleanExtra(EXTRA_CONSUMED, false)

    /**
     * Optional manifest meta-data naming the app's Deeplinkly link domains,
     * comma separated. See [carriesShortCode].
     */
    private const val MANIFEST_LINK_DOMAINS = "com.deeplinkly.sdk.link_domains"

    @Volatile private var cachedLinkDomains: Set<String>? = null

    private fun linkDomains(context: Context): Set<String> {
        cachedLinkDomains?.let { return it }
        val parsed = try {
            val info = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            info.metaData?.getString(MANIFEST_LINK_DOMAINS)
                ?.split(',')
                ?.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
                ?.toSet()
                .orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
        cachedLinkDomains = parsed
        return parsed
    }

    /**
     * Whether this URI's first path segment may be read as a Deeplinkly code.
     *
     * A link that came through the redirect always carries a click_id, so the
     * code is only ever needed for the App Link bypass: the OS routing
     * `https://<link domain>/<code>` straight to the app, which means the
     * backend never saw the click and has no ClickEvent for it yet.
     *
     * Treating *any* first path segment as a code - which is what this used to
     * do - meant every VIEW intent the app handled was resolved against the
     * backend. For an app with its own custom-scheme routes,
     * `myapp://settings/notifications` was resolved as code "notifications",
     * came back 404, and 404 is terminal: the handler delivered a fallback, so
     * opening an in-app screen fired onDeepLink carrying a URI that had nothing
     * to do with Deeplinkly.
     *
     * Custom schemes are therefore out, and they lose nothing - the intent://
     * fallback the backend builds is `<scheme>://open?click_id=...`, which is
     * matched on the click_id and has no path segment to read anyway.
     *
     * For http(s), naming the link domains in the manifest is what makes this
     * exact. Without them the rule stays permissive rather than guessing, since
     * rejecting a real link is worse than a spurious resolve - but an app that
     * App Links its marketing site alongside its link domain wants them set.
     */
    private fun carriesShortCode(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val domains = linkDomains(context)
        return domains.isEmpty() || uri.host?.lowercase() in domains
    }

    fun handleIntent(
        context: Context,
        intent: Intent?,
        channel: MethodChannel?,
        apiKey: String
    ) {
        try {
            Logger.d("handleIntent with $intent")

            if (intent != null && isReplay(intent)) {
                Logger.d("Intent was already delivered (relaunch from history); skipping")
                return
            }

            val data = intent?.data

            // Extract raw (local) params from the URI if present
            val localParams: Map<String, String?> = data?.let { uri ->
                buildMap<String, String?> {
                    for (name in uri.queryParameterNames) {
                        put(name, uri.getQueryParameter(name))
                    }
                }
            } ?: emptyMap()

            // SAFETY: data is nullable; use safe calls everywhere
            // Priority 1: Try Intent extras first (from #Intent; section)
            // Priority 2: Fall back to URI query parameters
            val clickId: String? = intent?.getStringExtra("click_id")
                ?: data?.getQueryParameter("click_id")
            // Only read a code off a URI that could actually be a Deeplinkly
            // link; see carriesShortCode.
            val code: String? = data
                ?.takeIf { carriesShortCode(context, it) }
                ?.pathSegments?.firstOrNull()
            if (clickId == null && code == null) {
                Logger.d("No click_id or Deeplinkly code in intent, skipping")
                return
            }

            // Claimed before any of the async work below, so a second attach
            // that races the first cannot start a duplicate resolve. Marked
            // only once there is something to deliver, leaving an intent we
            // skipped free to be reconsidered.
            intent?.putExtra(EXTRA_CONSUMED, true)

            SdkRuntime.ioLaunch {
                // Link identity only. The device description is assembled by
                // EnrichmentSender at send time, off this thread and out of the
                // queue — a resolve can sit here for days, and a device snapshot
                // taken now would be replayed then as if it were current.
                val attributionData = mutableMapOf<String, String?>()
                clickId?.let { attributionData["click_id"] = it }
                if (clickId == null && code != null) attributionData["code"] = code
                // Name the mechanism. InstallReferrerHandler carries the referrer
                // itself, and this one alone left the backend to infer it from
                // the absence of one. An inferred claim is treated as a guess and
                // can be overwritten by any later report, so a genuine deep link
                // opened from the OS was the weakest claim we made rather than
                // one of the strongest. Mirrors iOS, which has always sent this.
                attributionData["source"] = SOURCE
                // When the link was actually opened, so a sample that only
                // reaches the backend after several retries is still dated to
                // the event rather than to the retry that finally succeeded.
                //
                // The key is the catalogued one. It used to be "event_at",
                // which is in no catalogue on either platform - and the
                // catalogue is fail-closed, so EnrichmentSender's filter
                // dropped it at every level including FULL. The timestamp was
                // collected, carried through the durable queue by name, and
                // then discarded a step before the wire. iOS's mirror is
                // ios_reported_at, set the same way at the same moment.
                attributionData["android_reported_at"] = System.currentTimeMillis().toString()

                // Queue for resolution if not immediately resolvable
                val pendingResolve = DeepLinkQueue.PendingResolve(
                    clickId = clickId,
                    code = code,
                    uri = data?.toString(),
                    localParams = localParams,
                    attributionData = attributionData,
                    source = SOURCE
                )

                // Whoever holds the claim owns the resolve *and* the delivery
                // that follows it. If a processor tick picked this click up
                // first - possible when an earlier attempt left it queued and
                // the same link is opened again - resolving it here as well is
                // exactly the duplicate delivery the claim exists to prevent.
                if (!DeepLinkQueue.claimResolve(pendingResolve)) {
                    Logger.d("Resolve already in flight; leaving this click to the queue processor")
                    return@ioLaunch
                }
                try {
                    // Try immediate resolution with fast retry. localParams
                    // rides along: resolving by `code` makes the backend create
                    // the ClickEvent, and it reads the UTMs off the query
                    // string - see DeeplinklyNetwork.resolveUrl.
                    //
                    // No device fingerprint is sent. /resolve reads click_id and
                    // code and nothing else - it has never matched a click to an
                    // install on device signals - so building one here only
                    // shipped a description of the user's device for nothing.
                    // iOS now agrees; device signals go to /enrich, gated by
                    // AttributionLevel.
                    val (_, json) = DeeplinklyNetwork.resolveClickWithRetry(
                        DeeplinklyNetwork.resolveUrl(clickId, code, localParams),
                        apiKey,
                        maxRetries = 2,
                        initialDelayMs = 50
                    )

                    // An unknown click id comes back 200 with stale: true.
                    // Delivering it would fire a deep link carrying no params at
                    // all, on every cold start until the cached id was cleared.
                    if (DeeplinklyNetwork.isStale(json)) {
                        Logger.w("Resolve returned a stale click; suppressing delivery.")
                        DeepLinkQueue.removeResolve(pendingResolve)
                        return@ioLaunch
                    }

                    val dartMap = DeeplinklyNetwork.extractParamsFromJson(json, clickId)

                    // Ensure enrichment has the click_id we actually resolved
                    (dartMap["click_id"] as? String)?.let { attributionData["click_id"] = it }

                    // Normalized attribution snapshot we want to persist (stable keys)
                    val normalized = DeeplinklyNetwork.attributionSnapshot(
                        dartMap, source = SOURCE, fallbackClickId = clickId
                    )

                    // Persist attribution
                    AttributionStore.saveOnce(normalized)

                    // Create delivery item
                    val delivery = DeepLinkQueue.PendingDelivery(
                        resolvedData = dartMap,
                        attributionData = attributionData,
                        source = SOURCE
                    )

                    // Always enqueue for reliability (handles Flutter not ready, app restart, etc.)
                    DeepLinkQueue.enqueueDelivery(delivery)

                    // Delivered straight away when Flutter is listening, and left
                    // in the queue for the processor when it is not - the check
                    // lives inside deliverDeepLink, on the main thread, where the
                    // answer cannot go stale between test and use.
                    if (channel != null) {
                        SdkRuntime.deliverDeepLink(delivery)
                    }

                    // Fire enrichment (respecting your "sendOnce" semantics)
                    com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
                        .sendOnce(attributionData, SOURCE, apiKey)

                    Logger.d("Successfully processed deep link: clickId=$clickId, code=$code")

                } catch (e: Exception) {
                    // A fallback delivery and a queued retry are alternatives,
                    // not companions. Doing both - which is what this used to
                    // do - delivered the link twice for any transient failure:
                    // once immediately carrying only the query params off the
                    // URI, and again seconds later once QueueProcessor resolved
                    // it properly. Dart has no dedupe, so the host app saw
                    // onDeepLink fire twice for one tap.
                    //
                    // So the retry owns the delivery whenever a retry can still
                    // succeed, and the fallback is delivered only when nothing
                    // better is coming.
                    val terminal = e.isTerminalHttp()
                    if (!terminal) {
                        Logger.w("Resolve failed, queueing for retry: ${e.message}")
                        DeepLinkQueue.enqueueResolve(pendingResolve)
                        DeeplinklyNetwork.reportError(
                            apiKey,
                            "resolve exception",
                            e.stackTraceToString(),
                            clickId
                        )
                        return@ioLaunch
                    }

                    // Terminal: a revoked key, a suspended tenant, or a code the
                    // backend does not have. Retrying is pointless, so deliver
                    // what we can read off the URI and stop.
                    Logger.e("Resolve rejected (terminal), using fallback with preserved data", e)

                    // Same {click_id, params} envelope as a resolved link, so Dart
                    // reads an unresolved deep link exactly like a resolved one.
                    val fallback = DeeplinklyNetwork.fallbackPayload(clickId, localParams)

                    // Save attribution with fallback data
                    AttributionStore.saveOnce(
                        DeeplinklyNetwork.attributionSnapshot(
                            fallback, source = SOURCE, fallbackClickId = clickId
                        )
                    )

                    // Create delivery item
                    val fallbackDelivery = DeepLinkQueue.PendingDelivery(
                        resolvedData = fallback,
                        attributionData = attributionData, // Preserve link identity
                        source = FALLBACK_SOURCE
                    )

                    // Always enqueue for reliability
                    DeepLinkQueue.enqueueDelivery(fallbackDelivery)

                    if (channel != null) {
                        SdkRuntime.deliverDeepLink(fallbackDelivery)
                    }

                    // Nothing left to retry, so it must not stay queued.
                    DeepLinkQueue.removeResolve(pendingResolve)

                    DeeplinklyNetwork.reportError(
                        apiKey,
                        "resolve exception",
                        e.stackTraceToString(),
                        clickId
                    )
                } finally {
                    DeepLinkQueue.releaseResolve(pendingResolve)
                }
            }
        } catch (e: Exception) {
            Logger.e("handleIntent outer crash", e)
            DeeplinklyNetwork.reportError(
                apiKey,
                "handleIntent outer crash",
                e.stackTraceToString()
            )
        }
    }
}
