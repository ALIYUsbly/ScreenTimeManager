package com.aliyusbly.screentimemanager

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Minutes-from-midnight window. end < start means it crosses midnight (e.g. 22:00 -> 07:00). */
data class TimeWindow(val startMin: Int, val endMin: Int) {
    fun contains(minute: Int): Boolean =
        if (startMin <= endMin) minute in startMin until endMin
        else minute >= startMin || minute < endMin

    override fun toString() = "${fmt12h(startMin)} – ${fmt12h(endMin)}"

    companion object {
        fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)

        fun fmt12h(m: Int): String {
            val h24 = (m / 60) % 24
            val min = m % 60
            val amPm = if (h24 < 12) "AM" else "PM"
            val h12 = when {
                h24 == 0 -> 12
                h24 > 12 -> h24 - 12
                else -> h24
            }
            return "%d:%02d %s".format(h12, min, amPm)
        }
    }
}

/**
 * Everything LockMeOut-style that can be set per app.
 * 0 = "no limit" for every numeric field except weekendLimitMin (-1 = same as daily).
 * blockedDays: bit 0 = Sunday ... bit 6 = Saturday.
 * windowMode: 0 = blocked during windows, 1 = allowed only during windows.
 */
data class AppRule(
    val pkg: String,
    val dailyLimitMin: Int = 0,
    val weekendLimitMin: Int = -1,
    val blockedDays: Int = 0,
    val launchLimit: Int = 0,
    val sessionLimitMin: Int = 0,
    val cooldownMin: Int = 15,
    val windowMode: Int = 0,
    val windows: List<TimeWindow> = emptyList(),
    val lockedNow: Boolean = false
) {
    /** dow0: 0 = Sunday ... 6 = Saturday */
    fun limitFor(dow0: Int): Int {
        val weekend = dow0 == 0 || dow0 == 6
        return if (weekend && weekendLimitMin >= 0) weekendLimitMin else dailyLimitMin
    }

    fun summary(): String {
        val p = mutableListOf<String>()
        if (lockedNow) p += "locked"
        if (dailyLimitMin > 0) p += "${dailyLimitMin}m/day"
        if (weekendLimitMin >= 0 && weekendLimitMin != dailyLimitMin)
            p += if (weekendLimitMin == 0) "weekend unlimited" else "weekend ${weekendLimitMin}m"
        if (launchLimit > 0) p += "$launchLimit opens/day"
        if (sessionLimitMin > 0) p += "${sessionLimitMin}m sessions, ${cooldownMin}m break"
        if (blockedDays != 0) p += "${Integer.bitCount(blockedDays)} day(s) blocked"
        if (windows.isNotEmpty())
            p += (if (windowMode == 0) "blocked " else "only ") + windows.joinToString(", ")
        return if (p.isEmpty()) "No limits set" else p.joinToString(" • ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("daily", dailyLimitMin)
        put("weekend", weekendLimitMin)
        put("days", blockedDays)
        put("launches", launchLimit)
        put("session", sessionLimitMin)
        put("cooldown", cooldownMin)
        put("mode", windowMode)
        put("locked", lockedNow)
        put("windows", JSONArray().apply {
            windows.forEach { put(JSONArray().put(it.startMin).put(it.endMin)) }
        })
    }

    companion object {
        fun fromJson(pkg: String, o: JSONObject): AppRule {
            val ws = mutableListOf<TimeWindow>()
            o.optJSONArray("windows")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    ws.add(TimeWindow(a.getInt(0), a.getInt(1)))
                }
            }
            return AppRule(
                pkg = pkg,
                dailyLimitMin = o.optInt("daily", 0),
                weekendLimitMin = o.optInt("weekend", -1),
                blockedDays = o.optInt("days", 0),
                launchLimit = o.optInt("launches", 0),
                sessionLimitMin = o.optInt("session", 0),
                cooldownMin = o.optInt("cooldown", 15),
                windowMode = o.optInt("mode", 0),
                windows = ws,
                lockedNow = o.optBoolean("locked", false)
            )
        }
    }
}

object Clock {
    fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun nextMidnight(): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay()
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
}
