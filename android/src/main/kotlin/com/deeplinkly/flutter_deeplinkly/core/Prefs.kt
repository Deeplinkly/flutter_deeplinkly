package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Wrapper for SharedPreferences to provide easy access to SDK preferences
 */
object Prefs {
    private const val PREFS_NAME = "deeplinkly_prefs"

    @Volatile
    private var instance: SharedPreferences? = null

    /**
     * The context [instance] was opened against.
     *
     * In production this never changes, and the field looks redundant. It is
     * not: Robolectric builds a fresh Application per test method, so a cache
     * keyed on nothing hands the second test the first test's preferences —
     * which surfaced as an unrelated test failing intermittently depending on
     * execution order. Keying the cache on identity makes it self-invalidating
     * rather than something every test has to remember to reset.
     */
    @Volatile
    private var owner: Context? = null

    private val lock = Any()

    /**
     * The SDK's preferences, checked once per process for a backup restore.
     *
     * The check lives here because this is the only way to guarantee it runs
     * before anything reads a value. [InstallIdentity] clears state belonging
     * to a previous install, and doing that after, say, the install-referrer
     * handler had already read `install_referrer_handled` would accomplish
     * nothing — the early return has already happened.
     *
     * Single-flight, so the check runs once rather than on every access.
     * `getSharedPreferences` already returns a cached instance, so holding one
     * here costs nothing beyond the flag it lets us hang the check on.
     */
    fun of(): SharedPreferences {
        val context = DeeplinklyContext.app
        instance?.let { if (owner === context) return it }
        return synchronized(lock) {
            instance?.let { if (owner === context) return@synchronized it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Passed in, never re-entering of(): this is that call.
            InstallIdentity.enforce(prefs)
            owner = context
            instance = prefs
            prefs
        }
    }

    /** Drops the cached instance so the next [of] re-runs the restore check. */
    internal fun resetForTesting() {
        synchronized(lock) {
            instance = null
            owner = null
        }
    }
}
