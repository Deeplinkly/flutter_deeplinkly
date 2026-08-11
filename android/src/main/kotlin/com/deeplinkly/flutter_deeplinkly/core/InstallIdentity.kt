package com.deeplinkly.flutter_deeplinkly.core

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Detects a SharedPreferences file that was restored onto a different install
 * and drops the state that belongs to the old one.
 *
 * Android Auto Backup is on by default and needs no opt-in from the host app:
 * `deeplinkly_prefs` is backed up and restored onto whatever device the user
 * sets up next. Without this, everything the SDK latched on the old phone comes
 * back and is treated as current. Three consequences, worst first:
 *
 *  - `install_referrer_handled` returns true, so [InstallReferrerHandler]
 *    returns early and the genuinely new install's referrer is **never read**.
 *    That is the deferred-attribution path failing closed and silent — the one
 *    failure mode with no log line and no retry.
 *  - `initial_attribution` is write-once, so the new install permanently
 *    inherits the old device's first-touch attribution.
 *  - `deeplinkly_device_id` is reused, so two physical devices report as one
 *    install.
 *
 * [DeviceProfile] already solves its own version of this by putting
 * `Build.FINGERPRINT` in its cache stamp. The latches above had no such guard.
 *
 * ### Why not Build.FINGERPRINT here
 *
 * Because it changes on an OS update, and the two uses have opposite tolerances
 * for a false positive. A spurious re-collect of the device profile costs one
 * PackageManager lookup. A spurious "this is a new install" would regenerate
 * `deeplinkly_device_id` and re-read the install referrer on every OTA — the
 * same install reported as two, and the same click resolved twice. The signal
 * has to survive an OS update and not survive a restore, which FINGERPRINT gets
 * exactly backwards.
 *
 * `ANDROID_ID` and `firstInstallTime` both do. SSAID is scoped to the signing
 * key, user and device, so a different phone yields a different value while an
 * OTA leaves it alone; `firstInstallTime` is the moment Play installed the app
 * *on this device*, which a restore necessarily sets afresh and an app update
 * leaves alone (that is `lastUpdateTime`).
 *
 * Fails open. If neither component can be read, nothing is cleared: wrongly
 * wiping a live install is worse than missing a restore.
 */
internal object InstallIdentity {
    private const val KEY_INSTALL_IDENTITY = "dl_install_identity"

    /**
     * Keys that survive a restore, because they record a *decision* rather than
     * the state of an install.
     *
     * Everything else is cleared. Clear-by-default is deliberate: a key nobody
     * classified is install state far more often than it is consent, and the
     * dangerous direction of this guard is preserving something stale (silent,
     * permanent attribution loss) rather than dropping something recoverable.
     *
     * So: add a key here only when losing it would override a choice the user
     * or the host app made. `dl_attribution_level` and `tracking_disabled` are
     * the user's privacy settings, and dropping them would silently restore
     * them to `full`/enabled on a new phone — consent the user never gave
     * twice. `custom_user_id` is the host app's own identifier for the person
     * using it, and it is the same person after a restore.
     */
    private val PRESERVED_KEYS = setOf(
        "dl_attribution_level",
        "tracking_disabled",
        "custom_user_id",
        "dl_custom_user_id",
    )

    /**
     * Clears install-scoped state when [prefs] came from another install.
     *
     * Called from [Prefs] while it builds the singleton, so it runs before any
     * SDK code can read a value. Takes the instance rather than calling
     * `Prefs.of()` for the same reason — that would re-enter initialisation.
     */
    fun enforce(prefs: SharedPreferences) = enforce(prefs, currentIdentity())

    /**
     * The testable core. Split from [currentIdentity] because the identity is
     * read from the OS and a test needs to state it outright.
     */
    internal fun enforce(prefs: SharedPreferences, identity: String?) {
        // Nothing to compare against. Better to under-detect than to wipe a
        // live install on a device that will not tell us who it is.
        if (identity == null) return

        val stored = prefs.getString(KEY_INSTALL_IDENTITY, null)

        if (stored == identity) return

        if (stored == null) {
            // Either a genuinely fresh install, or an existing one upgrading to
            // the first SDK version that writes this. Both are the current
            // install and neither may be cleared — an upgrade that wiped the
            // device id would look exactly like the bug this class prevents.
            prefs.edit().putString(KEY_INSTALL_IDENTITY, identity).apply()
            return
        }

        Logger.w("Preferences restored from another install; clearing install-scoped state")
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key !in PRESERVED_KEYS) editor.remove(key)
        }
        editor.putString(KEY_INSTALL_IDENTITY, identity)
        editor.apply()

        // The profile caches its own copy in memory, and this may not be the
        // first thing to touch prefs in a warm process (a test, or a re-attach).
        DeviceProfile.invalidate()
    }

    /**
     * What identifies this install on this device, or null if unknowable.
     *
     * Hashed rather than stored raw: the components are only ever compared for
     * equality, and the SSAID is a `full`-tier signal that has no business
     * sitting in preferences under a second key at every attribution level.
     */
    internal fun currentIdentity(): String? {
        val ssaid = runCatching {
            Settings.Secure.getString(
                DeeplinklyContext.app.contentResolver, Settings.Secure.ANDROID_ID
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val firstInstall = runCatching {
            val ctx = DeeplinklyContext.app
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(
                    ctx.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }
            info.firstInstallTime
        }.getOrNull()?.takeIf { it > 0 }

        if (ssaid == null && firstInstall == null) return null
        return hash("${ssaid.orEmpty()}|${firstInstall ?: 0}")
    }

    /** FNV-1a, 64-bit. Not a security boundary — just a short stable key. */
    private fun hash(value: String): String {
        var h = -0x340d631b7bdddcdbL
        for (byte in value.toByteArray()) {
            h = h xor (byte.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }
}
