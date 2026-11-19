package com.aliyusbly.screentimemanager

import android.content.Context
import java.util.Calendar
import kotlin.math.ceil

/**
 * One pass of enforcement. Called every few seconds by EnforcementService and every
 * 15 min by the WorkManager watchdog. Fully recomputes the desired state from rules + usage,
 * so it is idempotent and self-healing.
 */
object Enforcer {

    private val lock = Any()

    /** Lets other code (InstallGuard UI actions) change shared state without racing a pass. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    fun run(ctx: Context) {
        synchronized(lock) { runLocked(ctx.applicationContext) }
    }

    private fun runLocked(ctx: Context) {
        val dpm = DeviceControl.dpm(ctx)
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return
        val admin = DeviceControl.admin(ctx)
        val store = Store(ctx)
        val now = System.currentTimeMillis()
        store.setLastTick(now)

        try {
            InstallGuard.reconcile(ctx, dpm, admin, store, Safety.protectedPackages(ctx))
        } catch (t: Throwable) {
            android.util.Log.e("STM", "install guard failed", t)
        }

        val rules = store.rules()
        val protectedPkgs = Safety.protectedPackages(ctx)
        val hasUsage = UsageEngine.hasAccess(ctx)

        val usage = if (hasUsage && rules.isNotEmpty()) {
            UsageEngine.compute(ctx, Clock.startOfDay(), now) { pkg ->
                (rules[pkg]?.cooldownMin ?: 5).coerceAtLeast(1) * 60_000L
            }
        } else emptyMap()

        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dow0 = cal.get(Calendar.DAY_OF_WEEK) - 1
        val focus = store.focusUntil() > now
        val globalWindows = store.globalWindows()
        val failClosed = store.failClosed()

        val verdicts = LinkedHashMap<String, String>()
        for ((pkg, rule) in rules) {
            if (pkg in protectedPkgs) continue
            val reason = verdict(
                store, rule, usage[pkg], hasUsage, failClosed,
                now, minute, dow0, focus, globalWindows
            )
            if (reason != null) verdicts[pkg] = reason
            else maybeWarn(ctx, store, rule, usage[pkg], dow0)
        }

        // Apply: suspend newly blocked, restore anything we suspended that is no longer blocked.
        val suspended = store.suspended().toMutableSet()

        val toSuspend = verdicts.keys.filter { it !in suspended }
        if (toSuspend.isNotEmpty()) {
            try {
                val failed = dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true).toSet()
                suspended.addAll(toSuspend.filter { it !in failed })
            } catch (_: Exception) {
            }
        }

        val toRestore = suspended.filter { it !in verdicts.keys }
        if (toRestore.isNotEmpty()) {
            try {
                val failed = dpm.setPackagesSuspended(admin, toRestore.toTypedArray(), false).toSet()
                suspended.removeAll(toRestore.filter { it !in failed }.toSet())
            } catch (_: Exception) {
            }
        }

        store.setSuspended(suspended)
        store.setReasons(verdicts)
    }

    /** Returns the reason an app must be blocked right now, or null if it may run. */
    private fun verdict(
        store: Store,
        rule: AppRule,
        u: AppUsage?,
        hasUsage: Boolean,
        failClosed: Boolean,
        now: Long,
        minute: Int,
        dow0: Int,
        focus: Boolean,
        globalWindows: List<TimeWindow>
    ): String? {
        if (rule.lockedNow) return "Locked manually"
        if (focus) return "Locked (focus mode)"
        if (globalWindows.any { it.contains(minute) }) return "Bedtime schedule"
        if (((rule.blockedDays shr dow0) and 1) == 1) return "Blocked today"

        if (rule.windows.isNotEmpty()) {
            val inAny = rule.windows.any { it.contains(minute) }
            if (rule.windowMode == 0 && inAny) return "Blocked at this time"
            if (rule.windowMode == 1 && !inAny) return "Outside allowed hours"
        }

        if (!hasUsage) {
            return if (failClosed) "Usage access revoked" else null
        }

        val used = u?.totalMs ?: 0L
        val limit = rule.limitFor(dow0)
        if (limit > 0 && used >= limit * 60_000L) return "Daily limit reached"

        if (rule.launchLimit > 0 && u != null && u.launches >= rule.launchLimit && !u.open)
            return "Open limit reached (${rule.launchLimit}/day)"

        if (store.cooldownUntil(rule.pkg) > now) return "Taking a break"
        if (rule.sessionLimitMin > 0 && u != null && u.open &&
            u.sessionMs >= rule.sessionLimitMin * 60_000L
        ) {
            store.setCooldownUntil(rule.pkg, now + rule.cooldownMin.coerceAtLeast(1) * 60_000L)
            return "Session limit reached"
        }
        return null
    }

    /** One heads-up per app per day when 5 minutes of the daily limit remain. */
    private fun maybeWarn(ctx: Context, store: Store, rule: AppRule, u: AppUsage?, dow0: Int) {
        val limit = rule.limitFor(dow0)
        if (limit <= 0 || u == null || !u.open) return
        val remaining = limit * 60_000L - u.totalMs
        if (remaining in 1..300_000L && store.warnedDay(rule.pkg) != Clock.dayKey()) {
            store.setWarnedDay(rule.pkg, Clock.dayKey())
            val pm = ctx.packageManager
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(rule.pkg, 0)).toString()
            } catch (_: Exception) {
                rule.pkg
            }
            val mins = ceil(remaining / 60_000.0).toInt()
            Notifier.warn(ctx, rule.pkg.hashCode(), "$name: about $mins min left today")
        }
    }
}
