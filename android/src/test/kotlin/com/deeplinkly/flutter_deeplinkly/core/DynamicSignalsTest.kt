package com.deeplinkly.flutter_deeplinkly.core

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class DynamicSignalsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeeplinklyContext.app = context
        Prefs.of().edit().clear().apply()
    }

    private fun shadowApp(): ShadowApplication =
        Shadows.shadowOf(context as android.app.Application)

    /**
     * The SDK does not declare ACCESS_NETWORK_STATE — it would land in every
     * host app's manifest and Play listing for one reduced-tier reporting
     * field. An app that has not granted it loses connection_type and nothing
     * else, so the rest of the payload must still be intact.
     */
    @Test
    fun `connection type is omitted when the host app lacks the permission`() {
        shadowApp().denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE)

        val signals = DynamicSignals.collect()

        assertNull(signals["connection_type"])
        // Everything else still collected.
        assertNotNull(signals["timezone"])
        assertNotNull(signals["last_opened_at"])
        assertNotNull(signals["session_id"])
        assertNotNull(signals["ui_mode_night"])
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `connection type is reported when the host app has the permission`() {
        shadowApp().grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)

        val signals = DynamicSignals.collect()

        assertEquals("wifi", signals["connection_type"])
    }

    /**
     * getActiveNetwork is API 23 and minSdk here is 21, so the modern path
     * has to be guarded. Without the guard this throws NoSuchMethodError —
     * caught by the collector's runCatching, which means the field would
     * silently never appear on 21-22 rather than falling back.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.LOLLIPOP])
    fun `connection type falls back to the legacy api below M`() {
        shadowApp().grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)

        val signals = DynamicSignals.collect()

        assertNotNull(
            "the pre-M fallback should still produce a value",
            signals["connection_type"],
        )
    }

    /**
     * Google Play's Advertising ID policy: the advertising id "must not be
     * connected to persistent device identifiers (for example: SSAID, MAC
     * address, IMEI, etc.)". Both in one payload is exactly that connection.
     *
     * Branch enforces this at collection time — it never even reads the SSAID
     * when an ad id exists. We enforce it at assembly, because our static
     * profile is cached for the life of an app version while the ad id is
     * re-read on every send: a user re-enabling ad personalisation would
     * otherwise keep shipping the SSAID the profile was collected with.
     */
    @Test
    fun `the ssaid is dropped when an advertising id is present`() {
        val enforced = DynamicSignals.applyIdentifierPolicy(
            mapOf(
                "advertising_id" to "gaid-1",
                "android_id" to "ssaid-1",
                "device_model" to "Pixel 8 Pro",
            )
        )

        assertFalse(
            "advertising_id and android_id must never ship together",
            enforced.containsKey("android_id"),
        )
        // Everything else is untouched — this drops one key, it is not a filter.
        assertEquals("gaid-1", enforced["advertising_id"])
        assertEquals("Pixel 8 Pro", enforced["device_model"])
    }

    @Test
    fun `the ssaid survives when there is no advertising id`() {
        // The common case on a device with ad personalisation off: the SSAID is
        // the only durable identifier we have, and dropping it unconditionally
        // would lose attribution for exactly those users.
        val blank = DynamicSignals.applyIdentifierPolicy(
            mapOf("advertising_id" to "", "android_id" to "ssaid-1")
        )
        val absent = DynamicSignals.applyIdentifierPolicy(
            mapOf("android_id" to "ssaid-1")
        )
        val nulled = DynamicSignals.applyIdentifierPolicy(
            mapOf("advertising_id" to null, "android_id" to "ssaid-1")
        )

        assertEquals("ssaid-1", blank["android_id"])
        assertEquals("ssaid-1", absent["android_id"])
        assertEquals("ssaid-1", nulled["android_id"])
    }

    @Test
    fun `the assembled payload goes through the identifier policy`() {
        // No Play Services on the test runner, so there is never a real ad id
        // here — this proves the wiring, and the two tests above prove the rule.
        val assembled = DynamicSignals.assemble(mapOf("android_id" to "ssaid-1"))

        assertNull(assembled["advertising_id"])
        assertEquals("ssaid-1", assembled["android_id"])
    }

    @Test
    fun `memory and storage are not collected`() {
        // Neither Branch nor Linkrunner collects either, and on iOS disk space
        // is a required-reason API that forbids sending the value off-device.
        val assembled = DynamicSignals.assemble(DeviceProfile.get())

        assertFalse(assembled.containsKey("device_memory_mb"))
        assertFalse(assembled.containsKey("device_storage_mb"))
    }

    @Test
    fun `unidentified device is true when no durable identifier is present`() {
        val signals = DynamicSignals.collect(staticProfile = emptyMap())

        assertEquals("true", signals["unidentified_device"])
    }

    @Test
    fun `unidentified device is false when the android id is known`() {
        val signals = DynamicSignals.collect(mapOf("android_id" to "ssaid-1"))

        assertEquals("false", signals["unidentified_device"])
    }

    /**
     * Removed on both platforms. Events carry _dl_client_elapsed_ms, a delta
     * since SDK init, which orders events from a device with a wrong wall
     * clock without reporting how long the device has been booted.
     */
    @Test
    fun `boot time is not collected`() {
        assertFalse(DynamicSignals.collect().containsKey("boot_time"))
    }

    @Test
    fun `the user chosen device name is not collected`() {
        assertFalse(DeviceProfile.get().containsKey("device_name"))
    }

    /**
     * play-services-ads-identifier is compileOnly, so the host app opts into
     * advertising-ID collection by adding it — which keeps its AD_ID permission
     * out of apps that never wanted one.
     *
     * The check must happen before the class is touched: a missing class raises
     * NoClassDefFoundError, an Error rather than an Exception, so the catch
     * around the lookup would not stop it crashing the host app.
     */
    @Test
    fun `a missing optional dependency is detected rather than thrown`() {
        assertFalse(Dependencies.classExists("com.example.NotOnTheClasspath"))
        // Present here because the test configuration adds it back.
        assertTrue(Dependencies.classExists(Dependencies.ADVERTISING_ID_CLIENT))
    }

    @Test
    fun `the class presence answer is cached`() {
        val first = Dependencies.classExists("com.example.StillNotHere")
        val second = Dependencies.classExists("com.example.StillNotHere")

        assertFalse(first)
        assertEquals(first, second)
    }
}
