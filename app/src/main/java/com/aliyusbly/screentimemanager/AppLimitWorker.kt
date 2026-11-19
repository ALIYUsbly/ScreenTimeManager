package com.aliyusbly.screentimemanager

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Safety net only. The real-time work is done by EnforcementService; this runs every
 * 15 minutes, enforces once itself, and revives the service if the OS killed it.
 */
class AppLimitWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "screen_time_enforcement"
    }

    override fun doWork(): Result {
        Enforcer.run(applicationContext)
        try {
            EnforcementService.start(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }
}
