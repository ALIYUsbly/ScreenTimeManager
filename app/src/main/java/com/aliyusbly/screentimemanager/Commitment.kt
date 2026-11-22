package com.aliyusbly.screentimemanager

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Self-lock engine. Store.isSelfLocked() / selfLockUntil() delegate here, so every screen that
 * already checks the lock now uses a clock that can't be moved forward by changing the date.
 *
 * While active:
 *  - Store refuses every settings change (rules, PIN, bedtime, protections, fail-closed ...)
 *  - DeviceControl.release() refuses
 *  - your protection level (Standard / Strict / Lockdown) is re-applied every minute and after reboot
 *  - the clock lock is forced on (every level includes it) and blocking continues if Usage Access is revoked
 *  - USB debugging / adb is NOT touched: only Lockdown blocks it
 * It ends by itself. It can be extended, never shortened.
 */
object Commitment {

    /** Forced ON during a lock. Every protection level already contains it. */
    private val FORCED = setOf("no_time_change")

    @Volatile private var lastAssertEl = 0L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("commitment", Context.MODE_PRIVATE)

    private fun bootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    /**
     * Within one boot, time advances with elapsedRealtime, which ignores date/time changes.
     * After a reboot the wall clock is trusted, but never allowed to go backwards.
     */
    fun trustedNow(ctx: Context): Long {
        val s = prefs(ctx)
        val base = s.getLong("t_base", 0L)
        val wall = System.currentTimeMillis()
        if (base == 0L) return wall
        val el = SystemClock.elapsedRealtime()
        val baseEl = s.getLong("t_el", 0L)
        val sameBoot = bootCount(ctx) == s.getInt("t_boot", -2) && el >= baseEl
        return if (sameBoot) base + (el - baseEl) else maxOf(base, wall)
    }

    private fun saveBase(ctx: Context, t: Long) {
        prefs(ctx).edit()
            .putLong("t_base", t)
            .putLong("t_el", SystemClock.elapsedRealtime())
            .putInt("t_boot", bootCount(ctx))
            .apply()
    }

    private fun endAt(ctx: Context): Long = prefs(ctx).getLong("end", 0L)

    fun isLocked(ctx: Context): Boolean {
        val e = endAt(ctx)
        return e > 0L && trustedNow(ctx) < e
    }

    fun remainingMs(ctx: Context): Long = (endAt(ctx) - trustedNow(ctx)).coerceAtLeast(0L)

    /** Wall-clock end time for display, or 0 when not locked. */
    fun endWall(ctx: Context): Long =
        if (isLocked(ctx)) System.currentTimeMillis() + remainingMs(ctx) else 0L

    /** Protection keys forced ON right now (empty when no lock is active). */
    fun forcedKeys(ctx: Context): Set<String> = if (isLocked(ctx)) FORCED else emptySet()

    /** A lock started by the old build (stored as a plain date) must not silently disappear. */
    fun adoptLegacy(ctx: Context, legacyEndWall: Long) {
        val wall = System.currentTimeMillis()
        if (legacyEndWall <= wall || endAt(ctx) != 0L) return
        prefs(ctx).edit().clear()
            .putLong("t_base", wall)
            .putLong("t_el", SystemClock.elapsedRealtime())
            .putInt("t_boot", bootCount(ctx))
            .putLong("end", legacyEndWall)
            .commit()
    }

    /**
     * Starts a lock of [durationMs], or extends the running one by that much.
     * Returns an error message, or null on success.
     */
    fun start(ctx: Context, durationMs: Long): String? {
        val app = ctx.applicationContext
        if (durationMs <= 0L) return "Invalid duration"
        val s = prefs(app)

        if (isLocked(app)) {
            s.edit().putLong("end", endAt(app) + durationMs).commit()
            return null
        }

        if (!DeviceControl.isOwner(app)) return "Device owner isn't set, so nothing could be enforced"
        if (!UsageEngine.hasAccess(app)) return "Grant Usage Access first, otherwise limits can't be measured"
        val store = Store(app)
        if (store.rules().isEmpty()) return "Set limits for at least one app first"
        if (!store.hardening("protect_self", true))
            return "Turn on \"Protect this app\" in Protection level first, otherwise the lock could simply be uninstalled"

        store.setFailClosed(true)

        val wall = System.currentTimeMillis()
        s.edit().clear()
            .putLong("t_base", wall)
            .putLong("t_el", SystemClock.elapsedRealtime())
            .putInt("t_boot", bootCount(app))
            .putLong("end", wall + durationMs)
            .commit()

        // Apply protections NOW; if the required one fails, undo instead of locking with a hole.
        val titles = DeviceControl.options.filter { it.key in FORCED }.map { it.title }
        val failures = DeviceControl.applyHardening(app).filter { e -> titles.any { e.startsWith(it) } }
        if (failures.isNotEmpty()) {
            s.edit().clear().commit()
            DeviceControl.applyHardening(app)
            return "Couldn't apply a required protection, so the lock was not started:\n" + failures.first()
        }

        try {
            EnforcementService.start(app)
        } catch (_: Exception) {
        }
        Thread { Enforcer.run(app) }.start()
        return null
    }

    /** Called on every enforcement pass: keep the clock base fresh, re-assert protections, expire. */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        val s = prefs(app)
        val end = s.getLong("end", 0L)
        if (end == 0L) return

        val now = trustedNow(app)
        if (now >= end) {
            s.edit().clear().commit()
            DeviceControl.applyHardening(app)
            return
        }

        val el = SystemClock.elapsedRealtime()
        val baseEl = s.getLong("t_el", 0L)
        if (el - baseEl > 30_000L || el < baseEl || bootCount(app) != s.getInt("t_boot", -2)) {
            saveBase(app, now)
        }
        if (el - lastAssertEl > 60_000L) {
            lastAssertEl = el
            DeviceControl.applyHardening(app)
        }
    }
}
