package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * The Auto Backup guard.
 *
 * `deeplinkly_prefs` is backed up by default and restored onto whatever device
 * the user sets up next, so every latch the SDK writes can come back describing
 * an install that no longer exists.
 */
@RunWith(RobolectricTestRunner::class)
class InstallIdentityTest {

    private lateinit var prefs: SharedPreferences

    private val deviceA = "aaaaaaaaaaaaaaaa"
    private val deviceB = "bbbbbbbbbbbbbbbb"

    @Before
    fun setUp() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        Prefs.resetForTesting()
        prefs = DeeplinklyContext.app
            .getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    /** The state a healthy install has accumulated, as it comes back from a backup. */
    private fun seedRestoredInstall() {
        prefs.edit()
            .putString("dl_install_identity", deviceA)
            .putBoolean("install_referrer_handled", true)
            .putString("initial_attribution", """{"click_id":"old-click"}""")
            .putString("deeplinkly_device_id", "device-from-old-phone")
            .putString("dl_install_instance_id", "instance-from-old-phone")
            .putString("dl_first_open_at", "2024-01-01T00:00:00Z")
            .putString("dl_static_profile", """{"device_model":"Pixel 6"}""")
            .putString("dl_referrer_click_at", "2024-01-01T00:00:00Z")
            .putLong("dl_event_seq", 42L)
            // Decisions, not install state.
            .putString("dl_attribution_level", "reduced")
            .putBoolean("tracking_disabled", true)
            .putString("custom_user_id", "user-123")
            .commit()
    }

    /**
     * The headline failure. `install_referrer_handled` coming back true makes
     * InstallReferrerHandler return early, so the new install's referrer is
     * never read — deferred attribution failing closed, with no log and no
     * retry.
     */
    @Test
    fun `a restore onto a new device clears the referrer latch`() {
        seedRestoredInstall()

        InstallIdentity.enforce(prefs, deviceB)

        assertFalse(
            "the new install must be allowed to read its own referrer",
            prefs.getBoolean("install_referrer_handled", false),
        )
    }

    @Test
    fun `a restore clears every install-scoped key`() {
        seedRestoredInstall()

        InstallIdentity.enforce(prefs, deviceB)

        for (key in listOf(
            "install_referrer_handled",
            "initial_attribution",
            "deeplinkly_device_id",
            "dl_install_instance_id",
            "dl_first_open_at",
            "dl_static_profile",
            "dl_referrer_click_at",
            "dl_event_seq",
        )) {
            assertFalse("$key belongs to the old install and must be cleared", prefs.contains(key))
        }
    }

    /**
     * Consent is the one thing a restore must not reset. Wiping these would
     * silently put a user who chose `reduced` and disabled tracking back to
     * `full` and enabled on their new phone.
     */
    @Test
    fun `a restore preserves the user's privacy choices`() {
        seedRestoredInstall()

        InstallIdentity.enforce(prefs, deviceB)

        assertEquals("reduced", prefs.getString("dl_attribution_level", null))
        assertTrue(prefs.getBoolean("tracking_disabled", false))
        assertEquals("user-123", prefs.getString("custom_user_id", null))
    }

    @Test
    fun `a restore records the new identity so it only happens once`() {
        seedRestoredInstall()

        InstallIdentity.enforce(prefs, deviceB)
        assertEquals(deviceB, prefs.getString("dl_install_identity", null))

        // A second pass on the same device must be inert.
        prefs.edit().putBoolean("install_referrer_handled", true).commit()
        InstallIdentity.enforce(prefs, deviceB)
        assertTrue(prefs.getBoolean("install_referrer_handled", false))
    }

    /**
     * The false positive that would be worse than the bug: an OS update, or any
     * other reason the identity is re-read on the same install, must not
     * regenerate the device id or re-read the referrer. One install would be
     * reported as two.
     */
    @Test
    fun `the same identity clears nothing`() {
        seedRestoredInstall()

        InstallIdentity.enforce(prefs, deviceA)

        assertTrue(prefs.getBoolean("install_referrer_handled", false))
        assertEquals("device-from-old-phone", prefs.getString("deeplinkly_device_id", null))
        assertEquals(deviceA, prefs.getString("dl_install_identity", null))
    }

    /**
     * An install that predates this guard has all the state and none of the
     * identity. It is the current install, not a restored one — treating it as
     * a restore would wipe the device id of every user on upgrade.
     */
    @Test
    fun `an install upgrading to this guard is adopted, not cleared`() {
        prefs.edit()
            .putBoolean("install_referrer_handled", true)
            .putString("deeplinkly_device_id", "existing-device")
            .commit()

        InstallIdentity.enforce(prefs, deviceA)

        assertTrue(prefs.getBoolean("install_referrer_handled", false))
        assertEquals("existing-device", prefs.getString("deeplinkly_device_id", null))
        assertEquals(deviceA, prefs.getString("dl_install_identity", null))
    }

    /** Fails open: wrongly wiping a live install is worse than missing a restore. */
    @Test
    fun `an unknowable identity clears nothing`() {
        seedRestoredInstall()

        InstallIdentity.enforce(prefs, null)

        assertTrue(prefs.getBoolean("install_referrer_handled", false))
        assertEquals(deviceA, prefs.getString("dl_install_identity", null))
    }

    /**
     * The identity has to be derivable from what a real device reports, or the
     * guard fails open everywhere and none of the above runs in production.
     *
     * Robolectric supplies neither an SSAID nor a `firstInstallTime` of its own
     * accord — both come back null/0 — so the test states them, which is also
     * what lets the two below vary one component at a time.
     */
    @Test
    fun `an identity is derived from the SSAID and the install time`() {
        setPlatformIdentity(ssaid = "ssaid-device-a", firstInstallTime = 1_700_000_000_000L)

        assertNotNull(InstallIdentity.currentIdentity())
    }

    /**
     * The whole point of the guard: a different phone must produce a different
     * identity. Both components change on a restore — the SSAID is scoped to
     * the device, and `firstInstallTime` is when Play installed the app *there*.
     */
    @Test
    fun `a different device yields a different identity`() {
        setPlatformIdentity(ssaid = "ssaid-device-a", firstInstallTime = 1_700_000_000_000L)
        val onDeviceA = InstallIdentity.currentIdentity()

        setPlatformIdentity(ssaid = "ssaid-device-b", firstInstallTime = 1_800_000_000_000L)
        val onDeviceB = InstallIdentity.currentIdentity()

        assertNotEquals(onDeviceA, onDeviceB)
    }

    /**
     * The reason this guard does not use `Build.FINGERPRINT` the way
     * [DeviceProfile]'s cache stamp does.
     *
     * FINGERPRINT changes on an OS update, and a false positive here is not a
     * cheap re-collect — it regenerates `deeplinkly_device_id` and re-reads the
     * install referrer, so one install is reported as two and one click is
     * resolved twice, on every OTA. Neither component of this identity moves
     * when only the OS does.
     */
    @Test
    fun `an OS update does not change the identity`() {
        setPlatformIdentity(ssaid = "ssaid-device-a", firstInstallTime = 1_700_000_000_000L)
        val before = InstallIdentity.currentIdentity()

        setOsVersion("14", fingerprint = "brand/product/device:14/UP1A/1234:user/release-keys")
        val afterOsUpdate = InstallIdentity.currentIdentity()

        assertEquals(
            "the identity must survive an OTA or every update looks like a new install",
            before,
            afterOsUpdate,
        )
    }

    /** Prefs is where the check is wired in; the wiring is the part worth pinning. */
    @Test
    fun `Prefs runs the check before handing out the instance`() {
        setPlatformIdentity(ssaid = "ssaid-device-b", firstInstallTime = 1_800_000_000_000L)
        seedRestoredInstall()
        // Store an identity that cannot match what the device now reports.
        prefs.edit().putString("dl_install_identity", "not-this-device").commit()
        Prefs.resetForTesting()

        assertFalse(
            "Prefs.of() must clear a restored install before any caller reads it",
            Prefs.of().getBoolean("install_referrer_handled", false),
        )
    }

    /**
     * `getOrCreateDeviceId` is among the earliest reads in the process, and it
     * used to reach the file through its own accessor rather than [Prefs] —
     * the one path that skipped the check was the one most likely to hand back
     * a previous install's id before the check could clear it.
     */
    @Test
    fun `the device id read does not bypass the check`() {
        setPlatformIdentity(ssaid = "ssaid-device-b", firstInstallTime = 1_800_000_000_000L)
        seedRestoredInstall()
        prefs.edit().putString("dl_install_identity", "not-this-device").commit()
        Prefs.resetForTesting()

        assertNotEquals(
            "device-from-old-phone",
            DeeplinklyUtils.getOrCreateDeviceId(),
        )
    }

    // --- platform doubles ---------------------------------------------------

    private fun setPlatformIdentity(ssaid: String, firstInstallTime: Long) {
        Settings.Secure.putString(
            DeeplinklyContext.app.contentResolver, Settings.Secure.ANDROID_ID, ssaid
        )
        val pm = shadowOf(DeeplinklyContext.app.packageManager)
        pm.getInternalMutablePackageInfo(DeeplinklyContext.app.packageName)
            .firstInstallTime = firstInstallTime
    }

    private fun setOsVersion(release: String, fingerprint: String) {
        ReflectionHelpers.setStaticField(
            Build.VERSION::class.java, "RELEASE", release
        )
        ReflectionHelpers.setStaticField(
            Build::class.java, "FINGERPRINT", fingerprint
        )
    }
}
