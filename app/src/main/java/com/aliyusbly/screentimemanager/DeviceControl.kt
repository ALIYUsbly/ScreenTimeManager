package com.aliyusbly.screentimemanager

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import androidx.work.WorkManager

/** Everything that makes the app hard to bypass (device-owner only). */
object DeviceControl {

    data class Option(val key: String, val title: String, val desc: String, val defaultOn: Boolean)

    val options = listOf(
        Option(
            "protect_self", "Protect this app",
            "Blocks uninstall, force-stop and clear-data of Screen Time Manager.", true
        ),
        Option(
            "no_factory_reset", "Block factory reset from Settings",
            "Hides the reset option in Settings. Hardware recovery-mode reset can't be blocked by any app.", true
        ),
        Option(
            "no_time_change", "Lock date & time (forces automatic time)",
            "Stops the 'change the clock to reset my limit' trick.", true
        ),
        Option(
            "no_safe_boot", "Block safe mode",
            "Safe mode disables third-party apps; this prevents that escape.", true
        ),
        Option(
            "no_add_user", "Block extra users / guest",
            "A second user would have its own, unlimited apps.", true
        ),
        Option(
            "no_install_unknown", "Block installing from unknown sources",
            "Stops sideloaded clones and alternative browsers.", true
        ),
        Option(
            "no_apps_control", "Block force-stop / clear-data / uninstall for ALL apps",
            "Strict. Also stops people from clearing data on managed apps.", false
        ),
        Option(
            "no_debugging", "Block USB debugging & developer options",
            "WARNING: you will lose adb access to this phone. Only enable when finished testing.", false
        )
    )

    fun admin(ctx: Context) = ComponentName(ctx, ScreenTimeAdminReceiver::class.java)

    fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    private fun restriction(key: String): String? = when (key) {
        "no_factory_reset" -> UserManager.DISALLOW_FACTORY_RESET
        "no_safe_boot" -> UserManager.DISALLOW_SAFE_BOOT
        "no_add_user" -> UserManager.DISALLOW_ADD_USER
        "no_install_unknown" ->
            if (Build.VERSION.SDK_INT >= 29) UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
            else UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
        "no_apps_control" -> UserManager.DISALLOW_APPS_CONTROL
        "no_debugging" -> UserManager.DISALLOW_DEBUGGING_FEATURES
        "no_time_change" ->
            if (Build.VERSION.SDK_INT >= 28) UserManager.DISALLOW_CONFIG_DATE_TIME else null
        else -> null
    }

    // ---------- Private DNS (same controls as TestDPC) ----------

    /** Stored via Store.hardening(); kept out of [options] so the protection levels don't turn "Custom". */
    const val KEY_PRIVATE_DNS_LOCK = "no_private_dns"

    fun privateDnsStatus(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "Needs Android 10+"
        return try {
            val d = dpm(ctx)
            val a = admin(ctx)
            when (d.getGlobalPrivateDnsMode(a)) {
                DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME -> d.getGlobalPrivateDnsHost(a) ?: "Custom host"
                DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC -> "Automatic"
                DevicePolicyManager.PRIVATE_DNS_MODE_OFF -> "Off"
                else -> "Not set by this app"
            }
        } catch (e: Exception) {
            "Unavailable"
        }
    }

    /** Does network I/O (the system checks the host speaks DNS-over-TLS). Call off the main thread. */
    fun setPrivateDnsHost(ctx: Context, host: String): String? {
        if (!isOwner(ctx)) return "This app is not the device owner."
        if (Build.VERSION.SDK_INT < 29) return "Private DNS control needs Android 10+."
        val h = host.trim().lowercase()
        if (h.isEmpty() || !h.contains('.')) return "Enter a hostname, e.g. dns.google"
        return try {
            when (dpm(ctx).setGlobalPrivateDnsModeSpecifiedHost(admin(ctx), h)) {
                DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> null
                DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING ->
                    "That host didn't answer as a DNS-over-TLS server (or there is no internet)."
                else -> "Couldn't set Private DNS."
            }
        } catch (e: Exception) {
            e.message ?: "Failed"
        }
    }

    fun setPrivateDnsAutomatic(ctx: Context): String? {
        if (!isOwner(ctx)) return "This app is not the device owner."
        if (Build.VERSION.SDK_INT < 29) return "Private DNS control needs Android 10+."
        return try {
            if (dpm(ctx).setGlobalPrivateDnsModeOpportunistic(admin(ctx)) ==
                DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            ) null else "Couldn't set Private DNS."
        } catch (e: Exception) {
            e.message ?: "Failed"
        }
    }

    /** DISALLOW_CONFIG_PRIVATE_DNS: greys out Settings > Private DNS. */
    fun setPrivateDnsLocked(ctx: Context, on: Boolean): String? {
        if (!isOwner(ctx)) return "This app is not the device owner."
        if (Build.VERSION.SDK_INT < 29) return "Private DNS control needs Android 10+."
        return try {
            if (on) dpm(ctx).addUserRestriction(admin(ctx), UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            else dpm(ctx).clearUserRestriction(admin(ctx), UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            Store(ctx).setHardening(KEY_PRIVATE_DNS_LOCK, on)
            null
        } catch (e: Exception) {
            e.message ?: "Failed"
        }
    }

    /** Silently grants what a device owner is allowed to grant itself. */
    fun grantSelfPermissions(ctx: Context) {
        if (!isOwner(ctx)) return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                dpm(ctx).setPermissionGrantState(
                    admin(ctx), ctx.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        } catch (_: Exception) {
        }
    }

    /** Applies every toggle from Settings. Returns a list of human-readable failures. */
    @Suppress("DEPRECATION")
    fun applyHardening(ctx: Context): List<String> {
        if (!isOwner(ctx)) return listOf("This app is not the device owner.")
        val d = dpm(ctx)
        val a = admin(ctx)
        val store = Store(ctx)
        val errors = mutableListOf<String>()

        // Old versions blocked ALL installs; that also breaks Play Store updates. InstallGuard replaces it.
        try { d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS) } catch (_: Exception) {}

        for (opt in options) {
            val on = store.hardening(opt.key, opt.defaultOn)
            try {
                when (opt.key) {
                    "protect_self" -> {
                        d.setUninstallBlocked(a, ctx.packageName, on)
                        if (Build.VERSION.SDK_INT >= 30) {
                            d.setUserControlDisabledPackages(
                                a, if (on) listOf(ctx.packageName) else emptyList()
                            )
                        }
                    }

                    "no_time_change" -> {
                        if (on) {
                            if (Build.VERSION.SDK_INT >= 30) {
                                d.setAutoTimeEnabled(a, true)
                                d.setAutoTimeZoneEnabled(a, true)
                            } else {
                                d.setAutoTimeRequired(a, true)
                            }
                        } else if (Build.VERSION.SDK_INT < 30) {
                            d.setAutoTimeRequired(a, false)
                        }
                        restriction(opt.key)?.let {
                            if (on) d.addUserRestriction(a, it) else d.clearUserRestriction(a, it)
                        }
                    }

                    else -> restriction(opt.key)?.let {
                        if (on) d.addUserRestriction(a, it) else d.clearUserRestriction(a, it)
                    }
                }
            } catch (e: Exception) {
                errors += "${opt.title}: ${e.message}"
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (store.hardening(KEY_PRIVATE_DNS_LOCK, false))
                    d.addUserRestriction(a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                else d.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            } catch (e: Exception) {
                errors += "Private DNS lock: ${e.message}"
            }
        }
        grantSelfPermissions(ctx)
        return errors
    }

    /** Undo everything and give up device-owner status. Returns an error message or null. */
    @Suppress("DEPRECATION")
    fun release(ctx: Context): String? {
        val d = dpm(ctx)
        val a = admin(ctx)
        if (!d.isDeviceOwnerApp(ctx.packageName)) return "Not device owner"
        return try {
            val store = Store(ctx)
            val sus = store.suspended()
            if (sus.isNotEmpty()) d.setPackagesSuspended(a, sus.toTypedArray(), false)
            store.setSuspended(emptySet())
            for (p in store.pending()) {
                try { d.setApplicationHidden(a, p, false) } catch (_: Exception) {}
            }
            store.setPending(emptySet())
            d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)
            for (o in options) restriction(o.key)?.let { d.clearUserRestriction(a, it) }
            if (Build.VERSION.SDK_INT >= 29) d.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            d.setUninstallBlocked(a, ctx.packageName, false)
            if (Build.VERSION.SDK_INT >= 30) d.setUserControlDisabledPackages(a, emptyList())
            ctx.stopService(Intent(ctx, EnforcementService::class.java))
            WorkManager.getInstance(ctx).cancelUniqueWork(AppLimitWorker.UNIQUE_WORK_NAME)
            d.clearDeviceOwnerApp(ctx.packageName)
            null
        } catch (e: Exception) {
            e.message ?: "Failed"
        }
    }
}
