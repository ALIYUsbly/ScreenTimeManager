package com.aliyusbly.screentimemanager

import android.content.Context
import android.util.Log
import java.util.Calendar
import kotlin.math.ceil

/**
 * One pass of enforcement. Called every few seconds by EnforcementService and every
 * 15 min by the WorkManager watchdog. Fully recomputes the desired state from rules + usage,
 * so it is idempotent and self-healing.
 *
 * Blocking methods:
 *  - user apps   -> setPackagesSuspended (app shows a "paused" dialog)
 *  - system apps -> setApplicationHidden (like TestDPC "Hide": the app disappears completely)
 *  - if Android refuses to suspend an app, it is hidden instead.
 */
object Enforcer {

    private const val TAG = "STM"
    private val lock = Any()

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
        Commitment.tick(ctx)   // trusted clock, re-assert protections, expire the lock

        val rules = store.rules()
        // Never allow locking/hiding our own app; allow Settings if a rule exists or useHide is true
        val hardProtected = Safety.protectedPackages(ctx) - "com.android.settings" + ctx.packageName
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
        val failClosed = store.failClosed() || store.isSelfLocked()

        val verdicts = LinkedHashMap<String, String>()
        val hideKind = HashSet<String>()
        for ((pkg, rule) in rules) {
            if (pkg in hardProtected) continue
            val hide = pkg == "com.android.settings" || Safety.useHide(ctx, pkg)
            if (hide) hideKind.add(pkg)
            val reason = verdict(
                store, rule, usage[pkg], hasUsage,
                // Never fail-closed a hidden-type app (Settings): otherwise Usage Access
                // could not be re-granted and you would be locked out.
                failClosed && !hide,
                now, minute, dow0, focus, globalWindows
            )
            if (reason != null) verdicts[pkg] = reason
            else maybeWarn(ctx, store, rule, usage[pkg], dow0)
        }

        val suspended = store.suspended().toMutableSet()
        val hidden = store.hiddenApps().toMutableSet()

        val wantHide = verdicts.keys.filter { it in hideKind }.toSet()
        val wantSuspend = verdicts.keys.filter { it !in hideKind }.toSet()
        // apps that fell back from suspend to hide stay hidden while still blocked
        val keepHidden = wantHide + wantSuspend.filter { it in hidden }

        // 1) restore everything that is no longer blocked
        for (pkg in hidden.filter { it !in keepHidden }) {
            try {
                if (dpm.setApplicationHidden(admin, pkg, false)) hidden.remove(pkg)
                else Log.w(TAG, "could not unhide $pkg")
            } catch (e: Exception) {
                Log.w(TAG, "unhide $pkg failed", e)
            }
        }
        val restoreSuspend = suspended.filter { it !in wantSuspend }
        if (restoreSuspend.isNotEmpty()) {
            try {
                val failed = dpm.setPackagesSuspended(admin, restoreSuspend.toTypedArray(), false).toSet()
                suspended.removeAll(restoreSuspend.filter { it !in failed }.toSet())
            } catch (e: Exception) {
                Log.w(TAG, "unsuspend failed", e)
            }
        }

        // 2) hide system / Settings apps
        val problems = HashMap<String, String>()
        for (pkg in wantHide) {
            if (pkg in hidden) continue
            try {
                if (dpm.setApplicationHidden(admin, pkg, true)) hidden.add(pkg)
                else problems[pkg] = "Android refused to hide it"
            } catch (e: Exception) {
                problems[pkg] = e.message ?: "hide failed"
                Log.w(TAG, "hide $pkg failed", e)
            }
        }

        // 3) suspend user apps (fall back to hiding if refused)
        val toSuspend = wantSuspend.filter { it !in suspended && it !in hidden }
        if (toSuspend.isNotEmpty()) {
            var failed: Set<String> = emptySet()
            try {
                failed = dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true).toSet()
            } catch (e: Exception) {
                failed = toSuspend.toSet()
                Log.w(TAG, "suspend failed", e)
            }
            suspended.addAll(toSuspend.filter { it !in failed })
            for (pkg in failed) {
                try {
                    if (dpm.setApplicationHidden(admin, pkg, true)) hidden.add(pkg)
                    else problems[pkg] = "Android refused to block it"
                } catch (e: Exception) {
                    problems[pkg] = e.message ?: "block failed"
                }
            }
        }

        store.setSuspended(suspended)
        store.setHiddenApps(hidden)

        val reasons = LinkedHashMap<String, String>()
        for ((pkg, why) in verdicts) {
            reasons[pkg] = problems[pkg]?.let { "$why (not applied:$it)" } ?: why
        }
        store.setReasons(reasons)

        // Hide any NEW app that appeared (Install Guard). It was written but never called before.
        InstallGuard.reconcile(ctx, dpm, admin, store, hardProtected)
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
            Notifier.warn(ctx, rule.pkg.hashCode(), "$name: about$mins min left today")
        }
    }
}