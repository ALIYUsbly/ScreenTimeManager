package com.aliyusbly.screentimemanager

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

class AppUsage {
    var totalMs = 0L        // foreground time since the start of the range
    var launches = 0        // number of distinct launches
    var sessionMs = 0L      // continuous-use time (resets after a real break)
    var segStart = 0L       // != 0 while the app is currently in the foreground
    var lastEnd = 0L
    val open get() = segStart != 0L
}

object UsageEngine {

    private const val RESUMED = 1
    private const val PAUSED = 2
    private const val SCREEN_OFF = 16
    private const val STOPPED = 23
    private const val DESTROYED = 24

    /** Re-opening within this window counts as the same launch. */
    private const val LAUNCH_MERGE_MS = 3_000L

    @Suppress("DEPRECATION")
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Exact per-app foreground time, launch count and session length from raw usage events.
     * (queryAndAggregateUsageStats is bucketed and can be off by many minutes.)
     * breakMs(pkg) = how long an app must be away before "continuous session" resets.
     */
    fun compute(
        ctx: Context,
        from: Long,
        to: Long,
        breakMs: (String) -> Long
    ): Map<String, AppUsage> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(from, to) ?: return emptyMap()
        val ev = UsageEvents.Event()
        val out = HashMap<String, AppUsage>()
        val open = HashMap<String, MutableSet<String>>()
        val afterScreenOff = HashSet<String>()

        fun close(u: AppUsage, ts: Long) {
            if (u.segStart != 0L) {
                val d = (ts - u.segStart).coerceAtLeast(0L)
                u.totalMs += d
                u.sessionMs += d
                u.lastEnd = ts
                u.segStart = 0L
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val ts = ev.timeStamp
            when (ev.eventType) {
                RESUMED -> {
                    val pkg = ev.packageName ?: continue
                    val classes = open.getOrPut(pkg) { HashSet() }
                    val u = out.getOrPut(pkg) { AppUsage() }
                    if (classes.isEmpty()) {
                        val gap = if (u.lastEnd == 0L) Long.MAX_VALUE else ts - u.lastEnd
                        val fromScreenOff = pkg in afterScreenOff
                        if (gap > LAUNCH_MERGE_MS && !fromScreenOff) u.launches++
                        if (gap >= breakMs(pkg)) u.sessionMs = 0L
                        u.segStart = ts
                        afterScreenOff.clear()
                    }
                    classes.add(ev.className ?: "")
                }

                PAUSED, STOPPED, DESTROYED -> {
                    val pkg = ev.packageName ?: continue
                    val classes = open[pkg] ?: continue
                    classes.remove(ev.className ?: "")
                    if (classes.isEmpty()) out[pkg]?.let { close(it, ts) }
                }

                SCREEN_OFF -> {
                    for ((pkg, classes) in open) {
                        if (classes.isNotEmpty()) {
                            classes.clear()
                            out[pkg]?.let { close(it, ts) }
                            afterScreenOff.add(pkg)
                        }
                    }
                }
            }
        }

        // Add the still-running segment of whatever is in the foreground right now.
        for (u in out.values) {
            if (u.open) {
                val d = (to - u.segStart).coerceAtLeast(0L)
                u.totalMs += d
                u.sessionMs += d
            }
        }
        return out
    }
}

/** Packages that must never be suspended (it would brick the phone or lock you out). */
object Safety {
    private val FIXED = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )

    /**
     * System apps (Settings, Play Store, Chrome pre-installed...) are blocked by HIDING them,
     * exactly like TestDPC's "Hide". Suspending system apps is refused or unreliable on MIUI.
     * MATCH_UNINSTALLED_PACKAGES so a package we already hid is still found.
     */
    @Suppress("DEPRECATION")
    fun useHide(ctx: Context, pkg: String): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
        (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Exception) {
        false
    }

    fun protectedPackages(ctx: Context): Set<String> {
        val s = HashSet<String>(FIXED)
        s.add(ctx.packageName)
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { s.add(it) }
        } catch (_: Exception) {
        }
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
                .defaultDialerPackage?.let { s.add(it) }
        } catch (_: Exception) {
        }
        try {
            Telephony.Sms.getDefaultSmsPackage(ctx)?.let { s.add(it) }
        } catch (_: Exception) {
        }
        try {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.forEach { s.add(it.packageName) }
        } catch (_: Exception) {
        }
        return s
    }
}
