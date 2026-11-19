package com.aliyusbly.screentimemanager

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Per-app rules, simplified:
 *  Daily limit (stepper + presets, optional weekend limit) · Lock now · Block days · Advanced
 */
class AppRuleActivity : SecureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("pkg") ?: run {
            finish()
            return
        }
        val name = intent.getStringExtra("label") ?: pkg
        val existing = Store(this).rules()[pkg]
        render { RuleScreen(pkg, name, existing, onBack = { finish() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleScreen(pkg: String, name: String, existing: AppRule?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rule = remember { existing ?: AppRule(pkg, dailyLimitMin = 60) }

    var daily by remember { mutableIntStateOf(rule.dailyLimitMin) }
    var weekendOn by remember { mutableStateOf(rule.weekendLimitMin >= 0) }
    var weekend by remember {
        mutableIntStateOf(if (rule.weekendLimitMin >= 0) rule.weekendLimitMin else rule.dailyLimitMin)
    }
    var locked by remember { mutableStateOf(rule.lockedNow) }
    var mask by remember { mutableIntStateOf(rule.blockedDays) }
    var launches by remember { mutableIntStateOf(rule.launchLimit) }
    var session by remember { mutableIntStateOf(rule.sessionLimitMin) }
    var breakMin by remember { mutableIntStateOf(rule.cooldownMin) }
    var mode by remember { mutableIntStateOf(rule.windowMode) }
    var windows by remember { mutableStateOf(rule.windows) }
    var advOpen by remember {
        mutableStateOf(rule.launchLimit > 0 || rule.sessionLimitMin > 0 || rule.windows.isNotEmpty())
    }
    val icon = remember { try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null } }

    fun save() {
        val st = Store(ctx)
        st.saveRule(
            AppRule(
                pkg = pkg,
                dailyLimitMin = daily,
                weekendLimitMin = if (weekendOn) weekend else -1,
                blockedDays = mask,
                launchLimit = launches,
                sessionLimitMin = session,
                cooldownMin = breakMin.coerceAtLeast(1),
                windowMode = mode,
                windows = windows,
                lockedNow = locked
            )
        )
        st.setCooldownUntil(pkg, 0L)
        Thread { Enforcer.run(ctx.applicationContext) }.start()
        ctx.toast("Saved")
        onBack()
    }

    fun remove() {
        Store(ctx).removeRule(pkg)
        Thread { Enforcer.run(ctx.applicationContext) }.start()
        ctx.toast("Removed")
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(icon, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (existing != null) {
                        TextButton(onClick = { remove() }) {
                            Text("Stop managing", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── daily limit ──
            StmCard {
                Text("Daily time limit", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Stepper(daily, 15, 24 * 60, "No limit") { daily = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        AssistChip(onClick = { daily = m }, label = { Text(fmtMin(m)) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                ToggleRow("Different limit on weekends", null, weekendOn) { weekendOn = it }
                if (weekendOn) {
                    Text("Saturday & Sunday", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Stepper(weekend, 15, 24 * 60, "Unlimited") { weekend = it }
                }
            }

            // ── lock now ──
            StmCard {
                ToggleRow("Lock now", "Blocks this app until you switch it off.", locked) { locked = it }
            }

            // ── blocked days ──
            StmCard {
                Text("Block completely on", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                DayRow(mask) { mask = it }
            }

            // ── advanced ──
            StmCard {
                Expander("Advanced", advOpen, { advOpen = !advOpen }) {
                    Spacer(Modifier.height(4.dp))
                    Text("Max opens per day", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Stepper(launches, 1, 99, "No limit", { "$it / day" }) { launches = it }

                    Spacer(Modifier.height(12.dp))
                    Text("Longest continuous session", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Stepper(session, 5, 240, "No limit") { session = it }

                    Spacer(Modifier.height(12.dp))
                    Text("Forced break afterwards", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Stepper(breakMin, 5, 240, "1m") { breakMin = it }

                    Spacer(Modifier.height(16.dp))
                    Text("Schedule", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Block during") })
                        FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Allow only during") })
                    }
                    Spacer(Modifier.height(4.dp))
                    WindowsEditor(windows) { windows = it }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Mon..Sun round toggles. Bit 0 = Sunday ... bit 6 = Saturday (same as AppRule.blockedDays). */
@Composable
private fun DayRow(mask: Int, onChange: (Int) -> Unit) {
    val days = listOf("M" to 1, "T" to 2, "W" to 3, "T" to 4, "F" to 5, "S" to 6, "S" to 0)
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { (n, bit) ->
            val on = ((mask shr bit) and 1) == 1
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (on) scheme.primary else scheme.surfaceVariant)
                    .clickable { onChange(mask xor (1 shl bit)) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    n,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) scheme.onPrimary else scheme.onSurfaceVariant
                )
            }
        }
    }
}
