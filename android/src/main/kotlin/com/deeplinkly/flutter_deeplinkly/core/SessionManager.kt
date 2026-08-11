package com.deeplinkly.flutter_deeplinkly.core

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Groups everything one visit produces under a single id.
 *
 * A session is a 30-minute inactivity window: any open or event inside it
 * extends it, and the first one past it starts a new session. That is the same
 * convention analytics products converge on, and it is what makes an event
 * joinable to the device sample taken alongside it — without which a
 * `DeviceSignalSample` and an `SdkEvent` from the same visit have nothing but a
 * timestamp in common.
 *
 * Persisted rather than held in memory, so a session survives the process being
 * killed and restarted inside the window.
 */
object SessionManager {
    private const val KEY_SESSION_ID = "dl_session_id"
    private const val KEY_SESSION_LAST_AT = "dl_session_last_at"

    /** How long a session survives with no activity. */
    internal const val SESSION_WINDOW_MS = 30L * 60 * 1000

    private val lock = ReentrantLock()

    /**
     * The current session id, starting a new one if the window has lapsed.
     *
     * Touches the activity timestamp, so simply asking extends the session —
     * which is what "any open or event extends it" means in practice.
     */
    fun currentSessionId(now: Long = System.currentTimeMillis()): String = lock.withLock {
        val prefs = Prefs.of()
        val lastAt = prefs.getLong(KEY_SESSION_LAST_AT, 0L)
        val existing = prefs.getString(KEY_SESSION_ID, null)

        val id = if (existing == null || now - lastAt > SESSION_WINDOW_MS) {
            UUID.randomUUID().toString().also {
                Logger.d("Started session $it")
            }
        } else {
            existing
        }

        prefs.edit()
            .putString(KEY_SESSION_ID, id)
            .putLong(KEY_SESSION_LAST_AT, now)
            .apply()
        id
    }

    /** Whether a fresh call would start a new session. Does not touch state. */
    internal fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        val prefs = Prefs.of()
        if (prefs.getString(KEY_SESSION_ID, null) == null) return true
        return now - prefs.getLong(KEY_SESSION_LAST_AT, 0L) > SESSION_WINDOW_MS
    }
}
