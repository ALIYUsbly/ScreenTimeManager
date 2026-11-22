package com.aliyusbly.screentimemanager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manage many apps at once: search, tick several (or select all shown), or type/paste
 * app names / package names, then apply ONE rule to all of them.
 */
class BatchActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render { BatchScreen(onBack = { finish() }) }
    }
}

private data class AppItem(val pkg: String, val label: String)

private fun loadAllInstalled(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val selfPkg = ctx.packageName
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launchablePkgs = pm.queryIntentActivities(launcherIntent, 0)
        .map { it.activityInfo.packageName }
        .toSet()

    @Suppress("DEPRECATION")
    val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }

    return installed
        .filter { it.packageName != selfPkg }
        .distinctBy { it.packageName }
        .map { ai ->
            val label = pm.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotBlank() } ?: ai.packageName
            AppItem(
                pkg = ai.packageName,
                label = if (ai.packageName in launchablePkgs) label else "$label (${ai.packageName})"
            )
        }
        .sortedBy { it.label.lowercase() }
}

private fun isInstalled(ctx: Context, pkg: String) = try {
    ctx.packageManager.getApplicationInfo(pkg, 0); true
} catch (_: Exception) {
    false
}

/** Turns typed text (names or packages, split by comma / semicolon / new line) into packages. */
private fun matchTyped(ctx: Context, apps: List<AppItem>, text: String): Pair<Set<String>, List<String>> {
    val selfPkg = ctx.packageName
    val found = LinkedHashSet<String>()
    val missing = mutableListOf<String>()
    for (tok in text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
        val byPkg = apps.firstOrNull { it.pkg.equals(tok, true) }
        val byLabel = apps.filter { it.label.equals(tok, true) }
        val partial = apps.filter { it.label.contains(tok, true) }
        val hit = when {
            byPkg != null -> byPkg.pkg
            byLabel.size == 1 -> byLabel[0].pkg
            partial.size == 1 -> partial[0].pkg
            tok.contains('.') && tok != selfPkg && isInstalled(ctx, tok) -> tok
            else -> null
        }
        if (hit != null) found.add(hit) else missing.add(tok)
    }
    return found to missing
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var rules by remember { mutableStateOf(store.rules()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }          // 0 all, 1 managed, 2 not managed
    var selected by remember { mutableStateOf(setOf<String>()) }
    var applyDialog by remember { mutableStateOf(false) }
    var typeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadAllInstalled(ctx) }
        loading = false
    }

    val shown = remember(apps, query, filter, rules) {
        apps.filter { a ->
            (query.isBlank() || a.label.contains(query, true) || a.pkg.contains(query, true)) &&
                    when (filter) {
                        1 -> a.pkg in rules
                        2 -> a.pkg !in rules
                        else -> true
                    }
        }
    }
    val managedSelected = selected.count { it in rules }

    fun finish(msg: String) {
        rules = store.rules()
        selected = emptySet()
        ctx.toast(msg)
        Thread { Enforcer.run(ctx.applicationContext) }.start()
    }

    fun applyRule(changeLimit: Boolean, daily: Int, lock: Int) {
        // While self-locked only apps that aren't managed yet can be added.
        val targets = if (store.isSelfLocked()) selected.filter { it !in rules } else selected.toList()
        if (targets.isEmpty()) {
            ctx.toast("Locked until ${Clock.formatDate(store.selfLockUntil())}")
            return
        }
        val list = targets.map { pkg ->
            val old = rules[pkg]
            val base = old ?: AppRule(pkg, dailyLimitMin = 0)
            base.copy(
                dailyLimitMin = if (changeLimit) daily else base.dailyLimitMin,
                lockedNow = when (lock) {
                    1 -> true
                    2 -> false
                    else -> base.lockedNow
                }
            )
        }
        store.saveRules(list)
        list.forEach { store.setCooldownUntil(it.pkg, 0L) }
        finish("Updated ${list.size} app(s)")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage many apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(
                        enabled = managedSelected > 0,
                        onClick = {
                            if (store.isSelfLocked()) {
                                ctx.toast("Locked until ${Clock.formatDate(store.selfLockUntil())}")
                            } else if (store.hardening("no_debugging", false)) {
                                ctx.toast("Cannot stop managing apps while Lockdown is active")
                            } else {
                                store.removeRules(selected.filter { it in rules })
                                finish("Stopped managing $managedSelected app(s)")
                            }
                        }
                    ) { Text("Stop managing", color = MaterialTheme.colorScheme.error) }
                    Button(enabled = selected.isNotEmpty(), onClick = { applyDialog = true }) { Text("Apply rule") }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Managed", "Not managed").forEachIndexed { i, n ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(n) })
                }
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = selected + shown.map { it.pkg } }) { Text("Select all shown") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { typeDialog = true }) { Text("Type names…") }
            }
            if (loading) {
                Text("Loading apps…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.pkg }) { item ->
                    AppRow(
                        item = item,
                        checked = item.pkg in selected,
                        rule = rules[item.pkg],
                        onToggle = {
                            selected = if (item.pkg in selected) selected - item.pkg else selected + item.pkg
                        }
                    )
                }
            }
        }
    }

    if (applyDialog) {
        ApplyDialog(
            count = selected.size,
            onApply = { c, d, l -> applyDialog = false; applyRule(c, d, l) },
            onDismiss = { applyDialog = false }
        )
    }

    if (typeDialog) {
        TypeDialog(
            onDone = { text ->
                typeDialog = false
                if (text != null) {
                    val (found, missing) = matchTyped(ctx, apps, text)
                    selected = selected + found
                    ctx.toast(
                        "Selected ${found.size}" +
                                if (missing.isEmpty()) "" else ". Not found / unclear: ${missing.joinToString(", ")}"
                    )
                }
            }
        )
    }
}

@Composable
private fun AppRow(item: AppItem, checked: Boolean, rule: AppRule?, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val icon = remember(item.pkg) { try { ctx.packageManager.getApplicationIcon(item.pkg) } catch (_: Exception) { null } }
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        AppIcon(icon, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                rule?.summary() ?: item.pkg,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (rule != null) {
            Spacer(Modifier.width(8.dp))
            Pill("Managed", OkColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplyDialog(count: Int, onApply: (Boolean, Int, Int) -> Unit, onDismiss: () -> Unit) {
    var changeLimit by remember { mutableStateOf(true) }
    var daily by remember { mutableIntStateOf(60) }
    var lock by remember { mutableIntStateOf(0) }   // 0 keep, 1 lock, 2 unlock
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply to $count app${if (count == 1) "" else "s"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("Set daily limit", null, changeLimit) { changeLimit = it }
                if (changeLimit) {
                    Stepper(daily, 15, 24 * 60, "No limit") { daily = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 120).forEach { m ->
                            AssistChip(onClick = { daily = m }, label = { Text(fmtMin(m)) })
                        }
                    }
                }
                Text("Lock", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Keep", "Lock", "Unlock").forEachIndexed { i, n ->
                        FilterChip(selected = lock == i, onClick = { lock = i }, label = { Text(n) })
                    }
                }
                Text(
                    "Other settings of apps that are already managed (schedule, opens, sessions) are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton({ onApply(changeLimit, daily, lock) }) { Text("Apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TypeDialog(onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Type or paste apps") },
        text = {
            Column {
                Text(
                    "App names or package names, separated by commas or new lines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    placeholder = { Text("YouTube, TikTok\ncom.instagram.android") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Select") } },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } }
    )
}