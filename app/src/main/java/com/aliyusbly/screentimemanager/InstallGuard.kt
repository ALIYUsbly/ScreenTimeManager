package com.aliyusbly.screentimemanager

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Allowlist for installs, without blocking updates.
 *
 *  - Turning it on trusts every user app that is installed right now (your bank app, etc.).
 *  - Updates keep the same package name, so they are always allowed (Play Store auto-update keeps working).
 *  - Any NEW package that appears (Play Store, sideload, OEM store push) is hidden within ~1 s
 *    and listed in "New app installs" until you Allow or Remove it.
 *  - While a self-lock is active, new apps cannot be approved, added via trustCurrent, or released by disabling.
 */
object InstallGuard {

    private const val KEY_ON = "guard_on"

    fun enabled(s: Store) = s.getBool(KEY_ON, false)

    private fun isSystem(ai: ApplicationInfo) =
        (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    /** Needs QUERY_ALL_PACKAGES in the manifest on Android 11+. */
    fun installedUserApps(ctx: Context): Set<String> =
        ctx.packageManager.getInstalledApplications(0)
            .filter { !isSystem(it) }
            .map { it.packageName }
            .toSet()

    fun enable(ctx: Context) = Enforcer.exclusive {
        val s = Store(ctx)
        s.setAllowed(s.allowed() + installedUserApps(ctx))
        s.setBool(KEY_ON, true)
    }

    /** Turning the guard off releases everything that was waiting (unless self-locked). */
    fun disable(ctx: Context): Boolean = Enforcer.exclusive {
        val s = Store(ctx)
        if (s.isSelfLocked()) return@exclusive false
        val d = DeviceControl.dpm(ctx)
        val a = DeviceControl.admin(ctx)
        val allowed = s.allowed().toMutableSet()
        for (p in s.pending()) {
            try { d.setApplicationHidden(a, p, false) } catch (_: Exception) {}
            allowed.add(p)
        }
        s.setAllowed(allowed)
        s.setPending(emptySet())
        s.setBool(KEY_ON, false)
        true
    }

    fun trustCurrent(ctx: Context): Boolean = Enforcer.exclusive {
        val s = Store(ctx)
        if (s.isSelfLocked()) return@exclusive false
        s.setAllowed(s.allowed() + installedUserApps(ctx))
        true
    }

    fun approve(ctx: Context, pkg: String): Boolean = Enforcer.exclusive {
        val s = Store(ctx)
        if (s.isSelfLocked()) return@exclusive false
        s.setAllowed(s.allowed() + pkg)
        s.setPending(s.pending() - pkg)
        try {
            DeviceControl.dpm(ctx).setApplicationHidden(DeviceControl.admin(ctx), pkg, false)
        } catch (_: Exception) {}
        true
    }

    /** Un-hides and silently uninstalls (device owner needs no confirmation). */
    fun remove(ctx: Context, pkg: String): Boolean = Enforcer.exclusive {
        val s = Store(ctx)
        val d = DeviceControl.dpm(ctx)
        val a = DeviceControl.admin(ctx)
        s.markRemoving(pkg)
        try {
            d.setApplicationHidden(a, pkg, false)
            val done = PendingIntent.getBroadcast(
                ctx, 0,
                Intent("com.aliyusbly.screentimemanager.UNINSTALL_DONE").setPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE
            )
            ctx.packageManager.packageInstaller.uninstall(pkg, done.intentSender)
            s.setPending(s.pending() - pkg)
            true
        } catch (_: Exception) {
            try { d.setApplicationHidden(a, pkg, true) } catch (_: Exception) {}
            false
        }
    }

    /** Called from every Enforcer pass (inside its lock). */
    fun reconcile(
        ctx: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        store: Store,
        protectedPkgs: Set<String>
    ) {
        if (!enabled(store)) return
        val allowed = store.allowed()
        val removing = store.removing()
        val pending = store.pending().toMutableSet()
        var added = 0

        for (pkg in installedUserApps(ctx)) {
            if (pkg in allowed || pkg in removing || pkg in protectedPkgs || pkg == ctx.packageName) continue
            try {
                if (dpm.setApplicationHidden(admin, pkg, true)) {
                    pending.add(pkg)
                    added++
                }
            } catch (_: Exception) {
            }
        }
        if (added > 0) {
            store.setPending(pending)
            Notifier.warn(ctx, 4242, "New app blocked. Open Screen Time Manager to approve or remove it.")
        }
    }

    fun describe(ctx: Context, pkg: String): Pair<String, Drawable?> = try {
        val pm = ctx.packageManager
        val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai)
    } catch (_: Exception) {
        pkg to null
    }
}