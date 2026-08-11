package com.deeplinkly.flutter_deeplinkly.core

/**
 * Presence checks for dependencies the SDK compiles against but does not bundle.
 *
 * Some integrations are opt-in for the host app rather than imposed by us —
 * usually because the library's own manifest declares a permission we have no
 * business adding to somebody else's app. Those are declared `compileOnly`, so
 * the class may simply not exist at runtime.
 *
 * The check has to happen *before* the class is touched. A missing class raises
 * `NoClassDefFoundError`, which is an [Error] and not an [Exception] — so the
 * usual `catch (e: Exception)` around an optional integration does not catch it
 * and the host app crashes instead of degrading.
 */
internal object Dependencies {
    const val ADVERTISING_ID_CLIENT = "com.google.android.gms.ads.identifier.AdvertisingIdClient"

    private val cache = mutableMapOf<String, Boolean>()

    /** Whether [className] is on the runtime classpath. Cached; this is reflection. */
    fun classExists(className: String): Boolean = synchronized(cache) {
        cache.getOrPut(className) {
            try {
                Class.forName(className)
                true
            } catch (_: ClassNotFoundException) {
                Logger.d(
                    "$className is not on the classpath. If you expected it, add the " +
                        "dependency to your app's build.gradle."
                )
                false
            }
        }
    }
}
