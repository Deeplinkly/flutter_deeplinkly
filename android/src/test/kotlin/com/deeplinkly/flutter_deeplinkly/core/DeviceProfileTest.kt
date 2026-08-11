package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.privacy.SignalCatalogue
import com.deeplinkly.flutter_deeplinkly.privacy.SignalScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class DeviceProfileTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeeplinklyContext.app = context
        Prefs.of().edit().clear().apply()
        DeviceProfile.invalidate()
    }

    @After
    fun tearDown() {
        DeviceProfile.invalidate()
        Prefs.of().edit().clear().apply()
    }

    // --- caching -----------------------------------------------------------

    @Test
    fun `the profile is collected once and then served from memory`() {
        val first = DeviceProfile.get()
        val second = DeviceProfile.get()

        // Same instance, not merely equal: a second collection would build a
        // new map, and collection is what costs a PackageManager lookup, a
        // content-resolver query and a Play Services round trip.
        assertSame(first, second)
    }

    @Test
    fun `concurrent cold callers share one collection`() {
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val results = AtomicReference(mutableListOf<Map<String, String?>>())

        repeat(threads) {
            Thread {
                ready.countDown()
                go.await()
                val profile = DeviceProfile.get()
                synchronized(results) { results.get().add(profile) }
                done.countDown()
            }.start()
        }

        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        assertTrue("threads did not finish", done.await(10, TimeUnit.SECONDS))

        val collected = results.get()
        assertEquals(threads, collected.size)
        val first = collected.first()
        for (profile in collected) {
            assertSame("a concurrent caller collected its own profile", first, profile)
        }
    }

    @Test
    fun `the profile survives a cold start through prefs`() {
        val first = DeviceProfile.get()
        val stamp = first["static_profile_version"]

        // Simulate a new process: the in-memory cache is gone, prefs are not.
        dropMemoryCacheOnly()
        val second = DeviceProfile.get()

        assertNotSame(first, second)
        assertEquals(stamp, second["static_profile_version"])
        assertEquals(first["device_model"], second["device_model"])
    }

    @Test
    fun `a changed stamp forces a re-collect`() {
        val first = DeviceProfile.get()

        // What an app upgrade looks like from here: the stored stamp no longer
        // matches the one computed from the current build.
        Prefs.of().edit().putString("dl_static_profile_stamp", "stale-stamp").apply()
        dropMemoryCacheOnly()
        val second = DeviceProfile.get()

        assertNotEquals("stale-stamp", second["static_profile_version"])
        assertEquals(first["static_profile_version"], second["static_profile_version"])
    }

    // --- contents ----------------------------------------------------------

    /**
     * The trap the fail-closed catalogue sets for us. A signal added to the
     * collector but not to tool/signals.json is silently dropped by
     * AttributionLevel.filter at every level — collected, carried, and thrown
     * away at the last step, with nothing to show for it.
     */
    @Test
    fun `every collected key is classified in the catalogue`() {
        val profile = DeviceProfile.get()

        val unclassified = profile.keys.filterNot { SignalCatalogue.SPECS.containsKey(it) }
        assertEquals(
            "collected but absent from tool/signals.json: $unclassified",
            emptyList<String>(),
            unclassified,
        )
    }

    @Test
    fun `every collected key is a static signal`() {
        val profile = DeviceProfile.get()
        val staticKeys = SignalCatalogue.keysFor(SignalScope.STATIC)

        val misfiled = profile.keys.filterNot { staticKeys.contains(it) }
        assertEquals(
            "collected by DeviceProfile but not scoped static: $misfiled",
            emptyList<String>(),
            misfiled,
        )
    }

    @Test
    fun `the profile carries the identity and build signals`() {
        val profile = DeviceProfile.get()

        assertEquals("android", profile["platform"])
        assertEquals(SdkInfo.VERSION, profile["sdk_version"])
        assertEquals(context.packageName, profile["app_id"])
        assertFalse(profile["deeplinkly_device_id"].isNullOrBlank())
        assertFalse(profile["install_instance_id"].isNullOrBlank())
        assertFalse(profile["static_profile_version"].isNullOrBlank())
    }

    @Test
    fun `the profile carries screen geometry`() {
        val profile = DeviceProfile.get()

        assertNotNull(profile["screen_width"])
        assertNotNull(profile["screen_height"])
        assertNotNull(profile["screen_dpi"])
        assertNotNull(profile["pixel_ratio"])
        assertTrue(profile["screen_width"]!!.toInt() > 0)
        assertTrue(profile["screen_height"]!!.toInt() > 0)
    }

    /**
     * Robolectric reports "robolectric" for every Build field, which is
     * neither an emulator nor a real handset, so the heuristic has to be driven
     * with the values a real AVD reports.
     */
    @Test
    fun `an emulator is recognised by its build fingerprint`() {
        ReflectionHelpers.setStaticField(
            Build::class.java, "FINGERPRINT",
            "generic/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:user/release-keys",
        )
        dropMemoryCacheOnly()
        Prefs.of().edit().remove("dl_static_profile_stamp").apply()

        assertEquals("true", DeviceProfile.get()["is_emulator"])
    }

    @Test
    fun `a real handset is not reported as an emulator`() {
        ReflectionHelpers.setStaticField(
            Build::class.java, "FINGERPRINT",
            "google/husky/husky:14/AP1A.240505.005/11677807:user/release-keys",
        )
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Pixel 8 Pro")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "google")
        ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", "husky")
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "husky")
        dropMemoryCacheOnly()
        Prefs.of().edit().remove("dl_static_profile_stamp").apply()

        assertEquals("false", DeviceProfile.get()["is_emulator"])
    }

    @Test
    fun `the profile holds no dynamic signal`() {
        val profile = DeviceProfile.get()

        // These change while the app stays installed. A cached copy is exactly
        // the value that must never be replayed as current.
        assertFalse(profile.containsKey("advertising_id"))
        assertFalse(profile.containsKey("last_opened_at"))
        assertFalse(profile.containsKey("locale"))
        assertFalse(profile.containsKey("timezone"))
    }

    // --- latches -----------------------------------------------------------

    @Test
    fun `the install instance id survives a profile re-collect`() {
        val first = DeviceProfile.get()["install_instance_id"]

        DeviceProfile.invalidate()
        val second = DeviceProfile.get()["install_instance_id"]

        // invalidate() drops the profile, not the install's identity. A new id
        // here would read as a reinstall on every SDK upgrade.
        assertEquals(first, second)
    }

    /**
     * `first_open_at` rather than `first_app_version`, because Robolectric's
     * PackageInfo carries no versionName and a latch over null is a no-op. The
     * timestamp exercises the same code path and is always present.
     */
    @Test
    fun `the first open timestamp is latched against later collections`() {
        val first = DeviceProfile.get()["first_open_at"]
        assertNotNull(first)

        DeviceProfile.invalidate()
        val second = DeviceProfile.get()["first_open_at"]

        assertEquals(first, second)
    }

    @Test
    fun `a latched value is not overwritten by a later one`() {
        Prefs.of().edit().putString("dl_first_app_version", "1.0.0").apply()

        // What an upgrade looks like: the app now reports a newer version, but
        // the install cohort is still 1.0.0.
        assertEquals("1.0.0", DeviceProfile.get()["first_app_version"])
    }

    // --- enrichment seam ---------------------------------------------------

    @Test
    fun `the two halves together describe the device`() {
        val profile = DeviceProfile.get()
        val enrichment = profile + DynamicSignals.collect(profile)

        // From the cached profile...
        assertEquals("android", enrichment["platform"])
        assertNotNull(enrichment["device_model"])
        // ...and collected fresh on this call.
        assertNotNull(enrichment["last_opened_at"])
        assertNotNull(enrichment["timezone"])
    }

    @Test
    fun `neither half emits anything the catalogue has not classified`() {
        val profile = DeviceProfile.get()
        val enrichment = profile + DynamicSignals.collect(profile)

        val unclassified = enrichment.keys.filterNot { SignalCatalogue.SPECS.containsKey(it) }
        assertEquals(
            "collected but absent from tool/signals.json: $unclassified",
            emptyList<String>(),
            unclassified,
        )
    }

    @Test
    fun `every dynamic key is scoped dynamic`() {
        val dynamic = DynamicSignals.collect(DeviceProfile.get())
        val dynamicKeys = SignalCatalogue.keysFor(SignalScope.DYNAMIC)

        val misfiled = dynamic.keys.filterNot { dynamicKeys.contains(it) }
        assertEquals(
            "collected by DynamicSignals but not scoped dynamic: $misfiled",
            emptyList<String>(),
            misfiled,
        )
    }

    /**
     * The advertising id is the one identifier a user can reset or revoke at
     * any moment, so it must be re-read on every send rather than served from
     * a cache that could replay a value they have since opted out of.
     */
    @Test
    fun `the advertising id is collected dynamically, not cached`() {
        assertFalse(DeviceProfile.get().containsKey("advertising_id"))
        assertTrue(DynamicSignals.collect().containsKey("advertising_id"))
    }

    /**
     * Clears the in-memory cache while leaving SharedPreferences intact, which
     * is what a new process looks like. `invalidate()` would clear both.
     */
    private fun dropMemoryCacheOnly() {
        val field = DeviceProfile::class.java.getDeclaredField("cached")
        field.isAccessible = true
        field.set(DeviceProfile, null)
    }
}
