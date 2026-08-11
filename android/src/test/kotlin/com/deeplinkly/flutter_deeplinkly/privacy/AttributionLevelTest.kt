package com.deeplinkly.flutter_deeplinkly.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The filter these tests cover decides what leaves the device after a user has
 * told us how much they consent to. It shipped for a release with no test at
 * all, and with Kotlin and Swift key sets that had already drifted apart.
 */
@RunWith(RobolectricTestRunner::class)
class AttributionLevelTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeeplinklyContext.app = context
        Prefs.of().edit()
            .remove("dl_attribution_level")
            .remove("tracking_disabled")
            .apply()
    }

    /** One key per tier, plus one the catalogue has never heard of. */
    private fun sample() = mapOf(
        "deeplinkly_device_id" to "device-1",  // minimal
        "click_id" to "click-1",               // minimal, identity
        "locale" to "en-GB",                   // reduced
        "os_version" to "14",                  // reduced
        "android_id" to "ssaid-1",             // full
        "advertising_id" to "gaid-1",          // full
        "screen_width" to "1080",              // full
        "some_future_signal" to "leaked",      // absent from the catalogue
    )

    // --- filter ------------------------------------------------------------

    @Test
    fun `full keeps every classified key`() {
        val out = AttributionLevel.FULL.filter(sample())

        assertEquals("device-1", out["deeplinkly_device_id"])
        assertEquals("en-GB", out["locale"])
        assertEquals("gaid-1", out["advertising_id"])
        assertEquals("1080", out["screen_width"])
    }

    /**
     * The whole point of the catalogue. Under the previous denylist a signal
     * nobody had classified survived REDUCED; now it does not survive even
     * FULL, so forgetting to classify one fails loudly instead of leaking.
     */
    @Test
    fun `an unclassified key is dropped even at full`() {
        val out = AttributionLevel.FULL.filter(sample())

        assertFalse(out.containsKey("some_future_signal"))
    }

    @Test
    fun `reduced drops the full-tier hardware signals and keeps the coarse ones`() {
        val out = AttributionLevel.REDUCED.filter(sample())

        assertFalse(out.containsKey("android_id"))
        assertFalse(out.containsKey("advertising_id"))
        assertFalse(out.containsKey("screen_width"))

        assertEquals("en-GB", out["locale"])
        assertEquals("14", out["os_version"])
        assertEquals("device-1", out["deeplinkly_device_id"])
        assertEquals("click-1", out["click_id"])
    }

    @Test
    fun `minimal keeps only the minimal tier`() {
        val out = AttributionLevel.MINIMAL.filter(sample())

        assertEquals(setOf("deeplinkly_device_id", "click_id"), out.keys)
    }

    @Test
    fun `none sends nothing at all`() {
        assertTrue(AttributionLevel.NONE.filter(sample()).isEmpty())
    }

    @Test
    fun `filter preserves null values rather than dropping the key`() {
        // The handlers build Map<String, String?> and rely on the network layer
        // — not the filter — to decide what a null means on the wire.
        val out = AttributionLevel.FULL.filter(mapOf("locale" to null))

        assertTrue(out.containsKey("locale"))
        assertNull(out["locale"])
    }

    // --- the catalogue itself ---------------------------------------------

    @Test
    fun `every tier is reachable from the level it belongs to`() {
        for ((key, spec) in SignalCatalogue.SPECS) {
            assertTrue(
                "$key is tier ${spec.tier} but is refused at FULL",
                SignalCatalogue.allows(key, AttributionLevel.FULL),
            )
            assertEquals(
                "$key is tier ${spec.tier} but REDUCED disagrees",
                spec.tier.rank <= SignalTier.REDUCED.rank,
                SignalCatalogue.allows(key, AttributionLevel.REDUCED),
            )
            assertEquals(
                "$key is tier ${spec.tier} but MINIMAL disagrees",
                spec.tier.rank <= SignalTier.MINIMAL.rank,
                SignalCatalogue.allows(key, AttributionLevel.MINIMAL),
            )
            assertFalse(
                "$key is permitted at NONE",
                SignalCatalogue.allows(key, AttributionLevel.NONE),
            )
        }
    }

    /**
     * Device signals and link attribution share one flat payload namespace, so
     * a signal named `source` or `click_id` would overwrite the attribution it
     * is meant to accompany. The generator refuses this too; asserting it here
     * as well means the invariant survives someone hand-editing the generated
     * file.
     */
    @Test
    fun `no device signal reuses a link-identity key`() {
        val identityKeys = SignalCatalogue.keysFor(SignalScope.IDENTITY)
        val deviceKeys = SignalCatalogue.keysFor(SignalScope.STATIC) +
            SignalCatalogue.keysFor(SignalScope.DYNAMIC)

        assertEquals(emptySet<String>(), identityKeys intersect deviceKeys)
    }

    /**
     * `hardware_fingerprint` was a 32-bit hashCode of manufacturer|model|
     * release|w|h|density — a derived device identifier, and the backend
     * archived 2.29M rows of it with only ~78k distinct values, so it never
     * identified anything either. Fail-closed means an attempt to collect it
     * again is dropped rather than sent, but the name is worth pinning.
     */
    @Test
    fun `the retired hardware fingerprint is not in the catalogue`() {
        assertFalse(SignalCatalogue.SPECS.containsKey("hardware_fingerprint"))
        assertFalse(
            SignalCatalogue.allows("hardware_fingerprint", AttributionLevel.FULL),
        )
    }

    @Test
    fun `every signal has exactly one scope`() {
        val byScope = SignalScope.values().map { SignalCatalogue.keysFor(it) }
        val total = byScope.sumOf { it.size }

        assertEquals(SignalCatalogue.SPECS.size, total)
    }

    // --- current -----------------------------------------------------------

    @Test
    fun `current defaults to full when nothing is configured`() {
        assertEquals(AttributionLevel.FULL, AttributionLevel.current)
    }

    @Test
    fun `current reflects the stored level`() {
        AttributionLevel.set(AttributionLevel.REDUCED)

        assertEquals(AttributionLevel.REDUCED, AttributionLevel.current)
    }

    @Test
    fun `disabling tracking overrides a stored level`() {
        AttributionLevel.set(AttributionLevel.FULL)
        TrackingPreferences.setTrackingDisabled(true)

        assertEquals(AttributionLevel.NONE, AttributionLevel.current)
    }

    @Test
    fun `fromWireName is case-insensitive and trims`() {
        assertEquals(AttributionLevel.REDUCED, AttributionLevel.fromWireName("  Reduced "))
        assertEquals(AttributionLevel.NONE, AttributionLevel.fromWireName("NONE"))
        assertNull(AttributionLevel.fromWireName("almost"))
        assertNull(AttributionLevel.fromWireName(null))
    }
}
