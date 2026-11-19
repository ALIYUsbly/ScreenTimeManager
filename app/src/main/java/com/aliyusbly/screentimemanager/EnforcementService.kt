package com.aliyusbly.screentimemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Notifier {
    const val CH_SERVICE = "enforcement"
    const val CH_WARN = "warnings"
    const val FG_ID = 1001

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Enforcement", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, "Limit warnings", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun foreground(ctx: Context): Notification {
        ensureChannels(ctx)
        return Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Screen Time Manager")
            .setContentText("Enforcement active")
            .setOngoing(true)
            .build()
    }

    fun warn(ctx: Context, id: Int, text: String) {
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, CH_WARN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Screen time")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }
}

/** Polls every 5 s (30 s while the screen is off) so limits trigger within seconds, not 15 minutes. */
class EnforcementService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var screenReceiver: BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                Enforcer.run(this@EnforcementService)
            } catch (t: Throwable) {
                Log.e("STM", "enforce failed", t)
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            handler.postDelayed(this, if (pm.isInteractive) 5_000L else 30_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("stm-enforcer").also { it.start() }
        handler = Handler(thread.looper)

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, f)
        screenReceiver = r

        // New package installed -> run a pass right away so InstallGuard can hide it.
        val pf = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, pf, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, pf)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.foreground(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.FG_ID, n)
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) {
            ctx.applicationContext.startForegroundService(
                Intent(ctx.applicationContext, EnforcementService::class.java)
            )
        }
    }
}

/** Restarts enforcement after reboot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            EnforcementService.start(context)
        } catch (_: Exception) {
        }
        Watchdog.schedule(context)
    }
}

object Watchdog {
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<AppLimitWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(
            AppLimitWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }
}
