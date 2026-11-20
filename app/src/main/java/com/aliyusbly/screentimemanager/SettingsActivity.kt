package com.aliyusbly.screentimemanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render { SettingsScreen(onBack = { finish() }) }
    }
}

/** One tap instead of nine checkboxes. */
private val base = setOf(
    "protect_self", "no_factory_reset", "no_time_change",
    "no_safe_boot", "no_add_user", "no_install_unknown"
)
private val levels = listOf(
    "Standard" to base,
    "Strict" to (base + "no_apps_control"),
    "Lockdown" to (base + "no_apps_control" + "no_debugging")
)
private val levelHelp = listOf(
    "Blocks uninstall, factory reset, clock changes, safe mode, extra users and unknown sources.",
    "Standard, plus no force-stop, clear-data or uninstall for any app.",
    "Strict, plus USB debugging is disabled. You lose adb access."
)

private data class SelfLockChoice(val label: String, val months: Int)

private val selfLockChoices = listOf(
    SelfLockChoice("1 month", 1),
    SelfLockChoice("2 months", 2),
    SelfLockChoice("3 months", 3),
    SelfLockChoice("1 year", 12)
)

private fun currentLevel(store: Store): Int {
    val on = DeviceControl.options.filter { store.hardening(it.key, it.defaultOn) }.map { it.key }.toSet()
    return levels.indexOfFirst { it.second == on }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    var dnsHost by remember { mutableStateOf("") }
    val dnsStatus = remember(rev) { DeviceControl.privateDnsStatus(ctx) }
    val dnsLocked = remember(rev) { store.hardening(DeviceControl.KEY_PRIVATE_DNS_LOCK, false) }

    val selfLockUntil = remember(rev) { store.selfLockUntil() }
    val selfLocked = selfLockUntil > System.currentTimeMillis()
    var pendingSelfLock by remember { mutableStateOf<SelfLockChoice?>(null) }

    val hasPin = remember(rev) { store.hasPin() }
    val level = remember(rev) { currentLevel(store) }
    var bedtime by remember { mutableStateOf(store.globalWindows()) }
    var failClosed by remember { mutableStateOf(store.failClosed()) }
    var customOpen by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    var lockdownDialog by remember { mutableStateOf(false) }
    var releaseDialog by remember { mutableStateOf(false) }

    fun lockedToast() {
        ctx.toast("Locked until ${Clock.formatDate(selfLockUntil)}")
    }

    fun applyLevel(i: Int) {
        if (selfLocked) {
            lockedToast()
            return
        }
        val keys = levels[i].second
        DeviceControl.options.forEach { opt ->
            store.setHardening(opt.key, opt.key in keys)
        }
        val errors = DeviceControl.applyHardening(ctx)
        ctx.toast(if (errors.isEmpty()) "${levels[i].first} protection on" else errors.joinToString("\n"))
        rev++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)
        ) {
            // ── self-enforce lock ──
            SectionLabel("Self-enforce lock")
            StmCard {
                Text(
                    if (selfLocked) "Locked until ${Clock.formatDate(selfLockUntil)}. Settings are frozen until this date."
                    else "Freeze your current protection settings without changing your selected mode.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selfLockChoices.take(2).forEach { choice ->
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            onClick = { pendingSelfLock = choice }
                        ) { Text(choice.label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selfLockChoices.drop(2).forEach { choice ->
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            onClick = { pendingSelfLock = choice }
                        ) { Text(choice.label) }
                    }
                }
            }

            // ── access ──
            SectionLabel("Access")
            StmCard {
                ToggleRow(
                    if (hasPin) "PIN is on" else "PIN is off",
                    "Required to open the app and edit limits. 5 wrong tries = 5 min lockout.",
                    hasPin
                ) { on ->
                    if (selfLocked) {
                        lockedToast()
                    } else if (on) {
                        pinDialog = true
                    } else {
                        store.clearPin()
                        rev++
                    }
                }
                if (hasPin) {
                    FilledTonalButton(
                        onClick = {
                            if (selfLocked) lockedToast() else pinDialog = true
                        }
                    ) { Text("Change PIN") }
                }
            }

            // ── bedtime ──
            SectionLabel("Bedtime")
            StmCard {
                Text("Blocks every managed app during these hours.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                WindowsEditor(bedtime) { updated ->
                    if (selfLocked) {
                        lockedToast()
                    } else {
                        bedtime = updated
                        store.setGlobalWindows(updated)
                    }
                }
            }

            // ── protection ──
            SectionLabel("Protection level")
            StmCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    levels.forEachIndexed { i, (n, _) ->
                        FilterChip(
                            selected = level == i,
                            onClick = {
                                if (selfLocked) lockedToast()
                                else if (i == 2) lockdownDialog = true
                                else applyLevel(i)
                            },
                            label = { Text(n) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (level >= 0) levelHelp[level] else "Custom mix. Pick a level to reset it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Expander("Customize", customOpen, { customOpen = !customOpen }) {
                    DeviceControl.options.forEach { opt ->
                        val on = remember(rev) { store.hardening(opt.key, opt.defaultOn) }
                        ToggleRow(opt.title, opt.desc, on) { next ->
                            if (selfLocked) {
                                lockedToast()
                            } else {
                                store.setHardening(opt.key, next)
                                rev++
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (selfLocked) {
                            lockedToast()
                        } else {
                            val errors = DeviceControl.applyHardening(ctx)
                            ctx.toast(if (errors.isEmpty()) "Protections applied" else errors.joinToString("\n"))
                        }
                    }) { Text("Apply") }
                }
            }

            // ── private dns ──
            SectionLabel("Private DNS")
            StmCard {
                Text("Current: $dnsStatus", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dnsHost,
                    onValueChange = { dnsHost = it.trim() },
                    label = { Text("DNS hostname") },
                    placeholder = { Text("family.cloudflare-dns.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (selfLocked) {
                            lockedToast()
                        } else {
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { DeviceControl.setPrivateDnsHost(ctx, dnsHost) }
                                ctx.toast(err ?: "Private DNS set")
                                rev++
                            }
                        }
                    }) { Text("Set host") }
                    FilledTonalButton(onClick = {
                        if (selfLocked) {
                            lockedToast()
                        } else {
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { DeviceControl.setPrivateDnsAutomatic(ctx) }
                                ctx.toast(err ?: "Private DNS set to automatic")
                                rev++
                            }
                        }
                    }) { Text("Automatic") }
                }
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    "Block changing Private DNS",
                    "Greys out the Private DNS setting so it can't be switched off. Set your host first.",
                    dnsLocked
                ) { next ->
                    if (selfLocked) {
                        lockedToast()
                    } else {
                        ctx.toast(
                            DeviceControl.setPrivateDnsLocked(ctx, next)
                                ?: if (next) "Private DNS locked" else "Private DNS unlocked"
                        )
                        rev++
                    }
                }
            }

            // ── installs ──
            SectionLabel("Installs")
            StmCard {
                Text(
                    "Allow updates for your current apps (like your bank app) but hide anything new until you approve it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { ctx.startActivity(Intent(ctx, InstallGuardActivity::class.java)) }) {
                    Text("Manage new app installs")
                }
            }

            // ── reliability ──
            SectionLabel("Reliability")
            StmCard {
                ToggleRow(
                    "Block managed apps if Usage Access is revoked",
                    "Stops the trick of turning off usage tracking.",
                    failClosed
                ) { next ->
                    if (selfLocked) {
                        lockedToast()
                    } else {
                        failClosed = next
                        store.setFailClosed(next)
                    }
                }
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = {
                    try {
                        ctx.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("Exempt from battery optimization") }
            }

            // ── danger ──
            SectionLabel("Danger zone")
            StmCard {
                Text(
                    "Releasing device owner removes every protection and un-suspends all apps. You'll need adb to set it again.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { releaseDialog = true }) {
                    Text("Release device owner", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    pendingSelfLock?.let { choice ->
        val baseMillis = maxOf(System.currentTimeMillis(), selfLockUntil)
        val untilMillis = Clock.addMonths(choice.months, baseMillis)
        AlertDialog(
            onDismissRequest = { pendingSelfLock = null },
            title = { Text("Lock settings for ${choice.label}?") },
            text = {
                Text(
                    "This freezes your current settings until ${Clock.formatDate(untilMillis)}. " +
                            "You won't be able to edit these settings or release Device Owner until then."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSelfLock = null
                    store.setSelfLockUntil(untilMillis)
                    DeviceControl.applyHardening(ctx)
                    ctx.toast("Self-lock active until ${Clock.formatDate(untilMillis)}")
                    rev++
                }) { Text("Lock") }
            },
            dismissButton = { TextButton({ pendingSelfLock = null }) { Text("Cancel") } }
        )
    }

    if (pinDialog) {
        PinSetDialog(onDone = { pin ->
            if (pin != null) {
                store.setPin(pin)
                PinGate.touch()
                ctx.toast("PIN saved")
            }
            pinDialog = false
            rev++
        })
    }

    if (lockdownDialog) {
        AlertDialog(
            onDismissRequest = { lockdownDialog = false },
            title = { Text("Lockdown") },
            text = { Text("This disables USB debugging and developer options. You won't be able to use adb on this phone until you lower the level.") },
            confirmButton = { TextButton({ lockdownDialog = false; applyLevel(2) }) { Text("Enable") } },
            dismissButton = { TextButton({ lockdownDialog = false }) { Text("Cancel") } }
        )
    }

    if (releaseDialog) {
        AlertDialog(
            onDismissRequest = { releaseDialog = false },
            title = { Text("Release device owner?") },
            text = {
                Text(
                    if (selfLocked) "Self-enforce lock is active until ${Clock.formatDate(selfLockUntil)}."
                    else "All limits stop working. You will need adb to become device owner again."
                )
            },
            confirmButton = {
                TextButton({
                    releaseDialog = false
                    ctx.toast(DeviceControl.release(ctx) ?: "Device owner released")
                    rev++
                }) { Text("Release", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ releaseDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PinSetDialog(onDone: (String?) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length >= 4 && a == b
    val opts = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Set PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    a, { a = it.filter(Char::isDigit) },
                    label = { Text("New PIN (4+ digits)") },
                    singleLine = true, keyboardOptions = opts,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    b, { b = it.filter(Char::isDigit) },
                    label = { Text("Confirm PIN") },
                    singleLine = true, keyboardOptions = opts,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = b.isNotEmpty() && a != b
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(a) }, enabled = ok) { Text("Save") } },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } }
    )
}