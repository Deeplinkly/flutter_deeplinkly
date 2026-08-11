package com.deeplinkly.flutter_deeplinkly.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {
    private val window = SessionManager.SESSION_WINDOW_MS

    @Before
    fun setUp() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        Prefs.of().edit().clear().apply()
    }

    @Test
    fun `the first call starts a session`() {
        val id = SessionManager.currentSessionId()

        assertTrue(id.isNotBlank())
    }

    @Test
    fun `activity inside the window keeps the same session`() {
        val start = 1_000_000L
        val first = SessionManager.currentSessionId(start)
        val second = SessionManager.currentSessionId(start + window / 2)

        assertEquals(first, second)
    }

    @Test
    fun `a gap longer than the window starts a new session`() {
        val start = 1_000_000L
        val first = SessionManager.currentSessionId(start)
        val second = SessionManager.currentSessionId(start + window + 1)

        assertNotEquals(first, second)
    }

    /**
     * The window is measured from the last activity, not from when the session
     * began — otherwise a session would expire mid-use after 30 minutes of
     * continuous activity.
     */
    @Test
    fun `each call extends the window`() {
        val start = 1_000_000L
        val first = SessionManager.currentSessionId(start)

        var now = start
        repeat(5) {
            now += window - 1
            assertEquals(first, SessionManager.currentSessionId(now))
        }
    }

    @Test
    fun `a session survives a process restart inside the window`() {
        val start = 1_000_000L
        val first = SessionManager.currentSessionId(start)

        // Nothing to reset: the state lives in prefs, not in memory, which is
        // the point — a process killed and relaunched inside the window is one
        // visit, not two.
        assertEquals(first, SessionManager.currentSessionId(start + 1000))
    }

    @Test
    fun `isExpired reports the boundary without moving it`() {
        val start = 1_000_000L
        SessionManager.currentSessionId(start)

        assertFalse(SessionManager.isExpired(start + window - 1))
        assertTrue(SessionManager.isExpired(start + window + 1))
        // Asking must not have extended the session.
        assertTrue(SessionManager.isExpired(start + window + 1))
    }
}
