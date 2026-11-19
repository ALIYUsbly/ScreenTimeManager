package com.aliyusbly.screentimemanager

import android.content.Context
import android.widget.Toast

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

fun fmtMin(v: Int): String = when {
    v < 60 -> "${v}m"
    v % 60 == 0 -> "${v / 60}h"
    else -> "${v / 60}h ${v % 60}m"
}

enum class PinResult { OK, WRONG, LOCKED }

/** 5-minute PIN unlock shared by every screen. The PIN screen itself lives in Components.kt. */
object PinGate {
    private var unlockedUntil = 0L

    /** True if no PIN is set or the PIN was entered in the last 5 minutes. */
    fun isOpen(ctx: Context): Boolean {
        val store = Store(ctx)
        if (!store.hasPin()) return true
        val now = System.currentTimeMillis()
        if (now < unlockedUntil) {
            unlockedUntil = now + 5 * 60_000L
            return true
        }
        return false
    }

    /** Call after setting a new PIN so the user isn't asked again straight away. */
    fun touch() {
        unlockedUntil = System.currentTimeMillis() + 5 * 60_000L
    }

    fun submit(ctx: Context, pin: String): PinResult {
        val store = Store(ctx)
        if (System.currentTimeMillis() < store.lockedOutUntil()) return PinResult.LOCKED
        if (store.checkPin(pin)) {
            touch()
            return PinResult.OK
        }
        return if (System.currentTimeMillis() < store.lockedOutUntil()) PinResult.LOCKED else PinResult.WRONG
    }
}
