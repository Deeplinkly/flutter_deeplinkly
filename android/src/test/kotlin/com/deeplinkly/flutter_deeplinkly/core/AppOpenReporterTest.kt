package com.deeplinkly.flutter_deeplinkly.core

import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rate limit here is not a nicety. TenantUser is rewritten on every open
 * and is documented as the hottest write path in the product, so an
 * unthrottled ping would multiply that by the number of foreground transitions
 * per session — dozens, on a chat-style app.
 */
@RunWith(RobolectricTestRunner::class)
class AppOpenReporterTest {
    private val window = SessionManager.SESSION_WINDOW_MS

    @Before
    fun setUp() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        Prefs.of().edit().clear().apply()
    }

    @Test
    fun `the first open of a cold start is reported`() {
        assertTrue(AppOpenReporter.shouldPing())
    }

    @Test
    fun `a second open inside the window is suppressed`() {
        val start = 1_000_000L
        AppOpenReporter.report(apiKey = "test-key", now = start)

        assertFalse(AppOpenReporter.shouldPing(start + 1000))
        assertFalse(AppOpenReporter.shouldPing(start + window - 1))
    }

    @Test
    fun `an open past the window is reported again`() {
        val start = 1_000_000L
        AppOpenReporter.report(apiKey = "test-key", now = start)

        assertTrue(AppOpenReporter.shouldPing(start + window))
    }

    /**
     * Twenty rapid foreground transitions — switching to another app and back,
     * answering a call, pulling down the notification shade — are one visit,
     * not twenty writes to the hottest table in the product.
     */
    @Test
    fun `rapid foreground transitions produce one ping`() {
        val start = 1_000_000L
        var reported = 0
        var now = start

        repeat(20) {
            if (AppOpenReporter.shouldPing(now)) {
                reported++
                AppOpenReporter.report(apiKey = "test-key", now = now)
            }
            now += 5_000  // back in five seconds, twenty times over
        }

        assertEquals(1, reported)
    }

    @Test
    fun `an open is not reported when tracking is disabled`() {
        TrackingPreferences.setTrackingDisabled(true)
        val start = 1_000_000L

        AppOpenReporter.report(apiKey = "test-key", now = start)

        // Nothing was sent, so nothing consumed the window either.
        assertTrue(AppOpenReporter.shouldPing(start + 1000))
    }

    @Test
    fun `an open is not reported without an api key`() {
        val start = 1_000_000L

        AppOpenReporter.report(apiKey = "", now = start)

        assertTrue(AppOpenReporter.shouldPing(start + 1000))
    }
}
