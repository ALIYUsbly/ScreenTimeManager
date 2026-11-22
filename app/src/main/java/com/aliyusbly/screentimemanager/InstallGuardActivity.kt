package com.aliyusbly.screentimemanager

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "New app installs": on/off switch + the list of apps waiting for approval. */
class InstallGuardActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render { GuardScreen(onBack = { finish() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuardScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(ctx) }
    var rev by remember { mutableIntStateOf(0) }

    // New installs show up without leaving the screen.
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            rev++
        }
    }

    val on = remember(rev) { InstallGuard.enabled(store) }
    val selfLocked = remember(rev) { store.isSelfLocked() }
    val pending = remember(rev) { store.pending().toList().sorted() }
    val trusted = remember(rev) { store.allowed().size }

    fun showSelfLocked() {
        ctx.toast("Locked until ${Clock.formatDate(store.selfLockUntil())}")
    }

    fun runAsync(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                block()
                Enforcer.run(ctx.applicationContext)
            }
            rev++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New app installs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)
        ) {
            if (selfLocked) {
                StmCard {
                    Text(
                        "Self-lock active until ${Clock.formatDate(store.selfLockUntil())}. New apps cannot be approved or added to the trusted list while locked.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                "Updates to apps you already have always go through.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

            StmCard {
                ToggleRow(
                    "Block new installs",
                    "Everything installed on this phone right now is trusted and keeps updating. " +
                            "Anything new is hidden until you approve it here.",
                    on
                ) { enable ->
                    if (!DeviceControl.isOwner(ctx)) {
                        ctx.toast("Device owner is required")
                    } else if (!enable && selfLocked) {
                        showSelfLocked()
                    } else {
                        runAsync {
                            if (enable) InstallGuard.enable(ctx)
                            else InstallGuard.disable(ctx)
                        }
                    }
                }
                Text("$trusted apps trusted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionLabel(if (pending.isEmpty()) "Waiting for approval" else "Waiting for approval (${pending.size})")
            if (pending.isEmpty()) {
                StmCard {
                    Text(
                        if (on) "Nothing new. If you install an app, it appears here."
                        else "Turn the guard on to start catching new installs.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            pending.forEach { pkg ->
                PendingCard(
                    pkg = pkg,
                    allowEnabled = !selfLocked,
                    onAllow = {
                        if (selfLocked) {
                            showSelfLocked()
                        } else {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val allowed = InstallGuard.approve(ctx, pkg)
                                    if (allowed) Enforcer.run(ctx.applicationContext)
                                    allowed
                                }
                                if (!ok) showSelfLocked()
                                rev++
                            }
                        }
                    },
                    onRemove = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { InstallGuard.remove(ctx, pkg) }
                            if (!ok) ctx.toast("Couldn't remove it. It stays hidden.")
                            rev++
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }

            if (on) {
                SectionLabel("Maintenance")
                StmCard {
                    Text(
                        "Installed something you want to keep? Trust it without waiting for it to be caught.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(
                        enabled = !selfLocked,
                        onClick = {
                            if (selfLocked) {
                                showSelfLocked()
                            } else {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        val trustedOk = InstallGuard.trustCurrent(ctx)
                                        if (trustedOk) Enforcer.run(ctx.applicationContext)
                                        trustedOk
                                    }
                                    if (ok) ctx.toast("Done") else showSelfLocked()
                                    rev++
                                }
                            }
                        }
                    ) { Text("Trust all apps installed now") }
                }
            }

            Text(
                "Tip: in Play Store → Settings → Network preferences → Auto-update apps, " +
                        "choose “Over any network” so your bank app keeps itself current.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp)
            )
        }
    }
}

@Composable
private fun PendingCard(
    pkg: String,
    allowEnabled: Boolean,
    onAllow: () -> Unit,
    onRemove: () -> Unit
) {
    val ctx = LocalContext.current
    val (name, icon) = remember(pkg) { InstallGuard.describe(ctx, pkg) }
    StmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = allowEnabled, onClick = onAllow, modifier = Modifier.weight(1f)) { Text("Allow") }
            FilledTonalButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}