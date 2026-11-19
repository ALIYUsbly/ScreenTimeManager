package com.aliyusbly.screentimemanager

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** All persistent state. Cheap to construct; SharedPreferences are cached by the framework. */
class Store(ctx: Context) {

    private val app = ctx.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences("screen_time_v2", Context.MODE_PRIVATE)

    init {
        migrateLegacy()
    }

    // ---------- rules ----------

    fun rules(): Map<String, AppRule> {
        val o = JSONObject(prefs.getString("rules", "{}") ?: "{}")
        val m = LinkedHashMap<String, AppRule>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            m[k] = AppRule.fromJson(k, o.getJSONObject(k))
        }
        return m
    }

    @Synchronized
    fun saveRule(r: AppRule) {
        val o = JSONObject(prefs.getString("rules", "{}") ?: "{}")
        o.put(r.pkg, r.toJson())
        prefs.edit().putString("rules", o.toString()).apply()
    }

    @Synchronized
    fun removeRule(pkg: String) {
        val o = JSONObject(prefs.getString("rules", "{}") ?: "{}")
        o.remove(pkg)
        prefs.edit().putString("rules", o.toString()).apply()
    }

    /** Batch versions: one read + one write for any number of apps. */
    @Synchronized
    fun saveRules(list: Collection<AppRule>) {
        val o = JSONObject(prefs.getString("rules", "{}") ?: "{}")
        list.forEach { o.put(it.pkg, it.toJson()) }
        prefs.edit().putString("rules", o.toString()).apply()
    }

    @Synchronized
    fun removeRules(pkgs: Collection<String>) {
        val o = JSONObject(prefs.getString("rules", "{}") ?: "{}")
        pkgs.forEach { o.remove(it) }
        prefs.edit().putString("rules", o.toString()).apply()
    }

    // ---------- enforcement state ----------

    fun suspended(): Set<String> =
        prefs.getStringSet("suspended", emptySet())?.toSet() ?: emptySet()

    fun setSuspended(s: Set<String>) {
        prefs.edit().putStringSet("suspended", s.toSet()).apply()
    }

    fun reasons(): Map<String, String> {
        val o = JSONObject(prefs.getString("reasons", "{}") ?: "{}")
        val m = HashMap<String, String>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            m[k] = o.getString(k)
        }
        return m
    }

    fun setReasons(m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("reasons", o.toString()).apply()
    }

    fun cooldownUntil(pkg: String) = prefs.getLong("cd_$pkg", 0L)
    fun setCooldownUntil(pkg: String, t: Long) {
        prefs.edit().putLong("cd_$pkg", t).apply()
    }

    fun warnedDay(pkg: String): String = prefs.getString("warn_$pkg", "") ?: ""
    fun setWarnedDay(pkg: String, day: String) {
        prefs.edit().putString("warn_$pkg", day).apply()
    }

    fun lastTick() = prefs.getLong("last_tick", 0L)
    fun setLastTick(t: Long) {
        prefs.edit().putLong("last_tick", t).apply()
    }

    // ---------- install guard ----------

    fun allowed(): Set<String> = prefs.getStringSet("allowed_pkgs", emptySet())?.toSet() ?: emptySet()
    fun setAllowed(s: Set<String>) {
        prefs.edit().putStringSet("allowed_pkgs", s.toSet()).apply()
    }

    fun pending(): Set<String> = prefs.getStringSet("pending_pkgs", emptySet())?.toSet() ?: emptySet()
    fun setPending(s: Set<String>) {
        prefs.edit().putStringSet("pending_pkgs", s.toSet()).apply()
    }

    /** Packages being uninstalled right now; the guard leaves them alone for 60 s. */
    fun removing(): Set<String> =
        if (System.currentTimeMillis() < prefs.getLong("removing_until", 0L))
            prefs.getStringSet("removing", emptySet())?.toSet() ?: emptySet()
        else emptySet()

    fun markRemoving(pkg: String) {
        prefs.edit()
            .putStringSet("removing", removing() + pkg)
            .putLong("removing_until", System.currentTimeMillis() + 60_000L)
            .apply()
    }

    // ---------- global settings ----------

    fun focusUntil() = prefs.getLong("focus_until", 0L)
    fun setFocusUntil(t: Long) {
        prefs.edit().putLong("focus_until", t).apply()
    }

    fun globalWindows(): List<TimeWindow> {
        val arr = JSONArray(prefs.getString("global_windows", "[]") ?: "[]")
        return (0 until arr.length()).map {
            val a = arr.getJSONArray(it)
            TimeWindow(a.getInt(0), a.getInt(1))
        }
    }

    fun setGlobalWindows(ws: List<TimeWindow>) {
        val arr = JSONArray()
        ws.forEach { arr.put(JSONArray().put(it.startMin).put(it.endMin)) }
        prefs.edit().putString("global_windows", arr.toString()).apply()
    }

    /** If usage access is revoked, block every managed app instead of silently allowing everything. */
    fun failClosed() = prefs.getBoolean("fail_closed", true)
    fun setFailClosed(v: Boolean) {
        prefs.edit().putBoolean("fail_closed", v).apply()
    }

    fun getBool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun setBool(key: String, v: Boolean) {
        prefs.edit().putBoolean(key, v).apply()
    }

    fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    fun setLong(key: String, v: Long) {
        prefs.edit().putLong(key, v).apply()
    }

    fun getString(key: String, def: String) = prefs.getString(key, def) ?: def
    fun setString(key: String, v: String) {
        prefs.edit().putString(key, v).apply()
    }

    fun hardening(key: String, def: Boolean) = prefs.getBoolean("h_$key", def)
    fun setHardening(key: String, v: Boolean) {
        prefs.edit().putBoolean("h_$key", v).apply()
    }

    // ---------- PIN ----------

    fun hasPin() = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.edit().putString("pin_salt", s).putString("pin_hash", hash(s, pin)).apply()
    }

    fun clearPin() {
        prefs.edit().remove("pin_hash").remove("pin_salt").apply()
    }

    fun lockedOutUntil() = prefs.getLong("pin_lock_until", 0L)

    fun checkPin(pin: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < lockedOutUntil()) return false
        val salt = prefs.getString("pin_salt", "") ?: ""
        val ok = hash(salt, pin) == prefs.getString("pin_hash", null)
        val e = prefs.edit()
        if (ok) {
            e.putInt("pin_fails", 0)
        } else {
            val n = prefs.getInt("pin_fails", 0) + 1
            if (n >= 5) {
                e.putInt("pin_fails", 0).putLong("pin_lock_until", now + 5 * 60_000L)
            } else {
                e.putInt("pin_fails", n)
            }
        }
        e.apply()
        return ok
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---------- migration from the first version ----------

    private fun migrateLegacy() {
        if (prefs.getBoolean("migrated", false)) return
        val old = app.getSharedPreferences("screen_time", Context.MODE_PRIVATE)
        old.getStringSet("selected_apps", emptySet())?.forEach { pkg ->
            saveRule(AppRule(pkg, dailyLimitMin = old.getInt("limit_$pkg", 60)))
        }
        old.getStringSet("suspended_apps", emptySet())?.let { setSuspended(it.toSet()) }
        prefs.edit().putBoolean("migrated", true).apply()
    }
}
