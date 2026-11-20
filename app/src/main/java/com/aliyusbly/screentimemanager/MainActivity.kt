package com.aliyusbly.screentimemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

private data class Snap(
    val rules: Map<String, AppRule>,
    val usage: Map<String, AppUsage>,
    val reasons: Map<String, String>,
    val owner: Boolean,
    val hasUsage: Boolean,
    val alive: Boolean,
    val hasPin: Boolean,
    val focusUntil: Long,
    val pending: Int
)

class MainActivity : SecureActivity() {

    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Screen Time Manager"
        render { HomeScreen(refresh) }
    }

    override fun onUnlocked() {
        bootstrap()
        refresh++
    }

    private fun bootstrap() {
        if (!DeviceControl.isOwner(this)) return
        val store = Store(this)
        if (!store.getBool("hardened_once", false)) {
            DeviceControl.applyHardening(this)
            store.setBool("hardened_once", true)
        }
        DeviceControl.grantSelfPermissions(this)
        try {
            EnforcementService.start(this)
        } catch (_: Exception) {
        }
        Watchdog.schedule(this)
    }
}

// ───────────────────────── data loading (background thread) ─────────────────────────

private fun loadSnap(ctx: Context): Snap {
    val store = Store(ctx)
    val r = store.rules()
    val hasUsage = UsageEngine.hasAccess(ctx)
    val u = if (hasUsage)
        UsageEngine.compute(ctx, Clock.startOfDay(), System.currentTimeMillis()) {
            (r[it]?.cooldownMin ?: 5).coerceAtLeast(1) * 60_000L
        }
    else emptyMap()
    return Snap(
        rules = r,
        usage = u,
        reasons = store.reasons(),
        owner = DeviceControl.isOwner(ctx),
        hasUsage = hasUsage,
        alive = System.currentTimeMillis() - store.lastTick() < 120_000L,
        hasPin = store.hasPin(),
        focusUntil = store.focusUntil(),
        pending = store.pending().size
    )
}

private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val selfPkg = ctx.packageName
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launchablePkgs = pm.queryIntentActivities(launcherIntent, 0)
        .map { it.activityInfo.packageName }
        .toSet()

    @Suppress("DEPRECATION")
    val apps: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }

    return apps
        .filter { it.packageName != selfPkg }
        .distinctBy { it.packageName }
        .map { ai ->
            val label = pm.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotBlank() } ?: ai.packageName
            val icon = try {
                pm.getApplicationIcon(ai)
            } catch (_: Exception) {
                pm.defaultActivityIcon
            }
            AppEntry(
                pkg = ai.packageName,
                label = if (ai.packageName in launchablePkgs) label else "$label (${ai.packageName})",
                icon = icon
            )
        }
        .sortedBy { it.label.lowercase() }
}

// ───────────────────────── screen ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(refresh: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<Snap?>(null) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var showLock by remember { mutableStateOf(false) }

    LaunchedEffect(tick, refresh) {
        var cache = apps
        while (true) {
            val (s, list) = withContext(Dispatchers.IO) {
                val l = cache.ifEmpty { loadApps(ctx) }
                loadSnap(ctx) to l
            }
            cache = list
            apps = list
            snap = s
            delay(15_000)
        }
    }

    fun enforce() {
        scope.launch {
            withContext(Dispatchers.IO) { Enforcer.run(ctx.applicationContext) }
            tick++
        }
    }

    val s = snap
    val filtered = remember(apps, query, s?.rules) {
        val q = query.trim().lowercase()
        val managed = s?.rules ?: emptyMap()
        apps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
            .sortedWith(compareBy<AppEntry>({ it.pkg !in managed }, { it.label.lowercase() }))
    }
    val focusActive = (s?.focusUntil ?: 0L) > System.currentTimeMillis()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Screen Time") },
                actions = {
                    IconButton(onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                scrollBehavior = scroll
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { if (s != null) StatusCard(s, ctx) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showLock = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Lock, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (focusActive)
                                "Until " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s!!.focusUntil))
                            else "Lock all apps"
                        )
                    }
                    FilledTonalButton(
                        onClick = { ctx.startActivity(Intent(ctx, InstallGuardActivity::class.java)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Installs") }
                }
            }

            item {
                FilledTonalButton(
                    onClick = { ctx.startActivity(Intent(ctx, BatchActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Manage many apps at once") }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(28.dp)
                )
            }

            items(filtered, key = { it.pkg }) { e -> AppRow(e, s) }
        }
    }

    if (showLock) {
        val labels = buildList {
            addAll(listOf("15 minutes", "30 minutes", "1 hour", "2 hours", "4 hours", "Until midnight"))
            if (focusActive) add("End lock now")
        }
        val mins = listOf(15, 30, 60, 120, 240, -1)
        AlertDialog(
            onDismissRequest = { showLock = false },
            title = { Text("Lock all managed apps") },
            text = {
                Column {
                    labels.forEachIndexed { i, l ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showLock = false
                                val st = Store(ctx)
                                if (i >= mins.size) st.setFocusUntil(0L)
                                else st.setFocusUntil(
                                    if (mins[i] < 0) Clock.nextMidnight()
                                    else System.currentTimeMillis() + mins[i] * 60_000L
                                )
                                enforce()
                            }
                        ) { Text(l, Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ showLock = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatusCard(s: Snap, ctx: Context) {
    val healthy = s.owner && s.hasUsage && s.alive
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (healthy) scheme.primaryContainer else scheme.errorContainer,
            contentColor = if (healthy) scheme.onPrimaryContainer else scheme.onErrorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (healthy) Icons.Default.CheckCircle else Icons.Default.Warning, null)
                Spacer(Modifier.width(10.dp))
                Text(if (healthy) "Protected" else "Needs attention", style = MaterialTheme.typography.titleLarge)
            }

            if (healthy) {
                val n = s.rules.size
                Spacer(Modifier.height(6.dp))
                Text(
                    if (n == 0) "No apps managed yet. Tap an app below." else "$n app${if (n == 1) "" else "s"} managed",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (!s.owner) {
                val cmd = "adb shell dpm set-device-owner ${ctx.packageName}/.ScreenTimeAdminReceiver"
                Spacer(Modifier.height(10.dp))
                Text("Device owner isn't set. Run this on a PC with the phone connected:", style = MaterialTheme.typography.bodyMedium)
                Text(cmd, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("cmd", cmd))
                    ctx.toast("Copied")
                }) { Text("Copy command") }
            } else if (!s.alive) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Enforcement isn't running. Open Settings and exempt the app from battery optimization.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (!s.hasUsage) {
                Spacer(Modifier.height(10.dp))
                Text("Usage Access is needed to measure app time.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }) { Text("Grant Usage Access") }
            }

            if (!s.hasPin) {
                Spacer(Modifier.height(8.dp))
                Text("Tip: set a PIN in Settings so limits can't be edited.", style = MaterialTheme.typography.bodySmall)
            }

            if (s.pending > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("${s.pending} new app${if (s.pending == 1) "" else "s"} waiting", WarnColor)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { ctx.startActivity(Intent(ctx, InstallGuardActivity::class.java)) }) {
                        Text("Review")
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(e: AppEntry, s: Snap?) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val rule = s?.rules?.get(e.pkg)
    val u = s?.usage?.get(e.pkg)
    val reason = s?.reasons?.get(e.pkg)

    Card(
        onClick = {
            ctx.startActivity(
                Intent(ctx, AppRuleActivity::class.java)
                    .putExtra("pkg", e.pkg)
                    .putExtra("label", e.label)
            )
        },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp)) {
            AppIcon(e.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (rule != null) {
                        Spacer(Modifier.width(8.dp))
                        if (reason != null) Pill("Blocked", scheme.error) else Pill("Active", OkColor)
                    }
                }
                if (rule == null) {
                    Text("Not managed · tap to set limits", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                } else {
                    Text(rule.summary(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    val dow0 = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
                    val limit = rule.limitFor(dow0)
                    val used = ((u?.totalMs ?: 0L) / 60_000L).toInt()
                    if (limit > 0) {
                        val frac = (used.toFloat() / limit).coerceIn(0f, 1f)
                        val color = when {
                            reason != null -> scheme.error
                            frac >= 0.8f -> WarnColor
                            else -> scheme.primary
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { frac },
                            color = color,
                            trackColor = scheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                        )
                        val opens = if (u != null && rule.launchLimit > 0) " · ${u.launches} opens" else ""
                        Text("$used of $limit min today$opens", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        Text("Used today: ${used}m", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (reason != null) {
                        Text(reason, style = MaterialTheme.typography.labelMedium, color = scheme.error, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}