package com.deeplinkly.flutter_deeplinkly.core

/**
 * Identity of the SDK build itself.
 *
 * Hand-maintained rather than read from BuildConfig: this is an Android library
 * whose version is owned by pubspec.yaml, and the AAR's own BuildConfig carries
 * the host app's version, not ours. Bump [VERSION] with pubspec.yaml.
 *
 * Reported as `sdk_version` and folded into the static-profile stamp, so an SDK
 * upgrade re-collects the device profile — which is what makes a signal added
 * in a new release actually get collected on existing installs.
 */
object SdkInfo {
    const val VERSION = "1.9.0"
    const val PLATFORM = "android"

    /**
     * Monotonic reference captured when the SDK attached, held in memory only.
     *
     * Events report [elapsedSinceInit] — milliseconds since this point — rather
     * than a raw monotonic clock reading. The delta is what orders events from
     * a device whose wall clock is wrong; the absolute reading additionally
     * revealed how long the device had been booted, which is a device
     * correlator we have no use for.
     *
     * Reset per process, deliberately. It is meaningful within a session and
     * meaningless across one, which matches what it is for.
     */
    private val initElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime()

    /** Milliseconds since the SDK initialised in this process. */
    fun elapsedSinceInit(): Long =
        android.os.SystemClock.elapsedRealtime() - initElapsedRealtime
}
