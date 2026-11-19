package com.aliyusbly.screentimemanager

import android.graphics.drawable.Drawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

// ───────────────────────── theme ─────────────────────────

val OkColor = Color(0xFF22A06B)
val WarnColor = Color(0xFFF5A524)

/** Material 3. Uses wallpaper colours ("Material You") on Android 12+. */
@Composable
fun StmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9DB0FF))
        else -> lightColorScheme(primary = Color(0xFF4F6BFF))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ───────────────────────── secure activity base ─────────────────────────

/** Every screen extends this: shows the PIN screen until unlocked, then your content. */
abstract class SecureActivity : ComponentActivity() {

    protected var unlocked by mutableStateOf(false)

    /** Called every time the screen becomes unlocked (start, resume, after PIN). */
    protected open fun onUnlocked() {}

    private fun unlock() {
        unlocked = true
        onUnlocked()
    }

    override fun onResume() {
        super.onResume()
        if (PinGate.isOpen(this)) unlock()
    }

    override fun onStop() {
        unlocked = false
        super.onStop()
    }

    protected fun render(content: @Composable () -> Unit) {
        enableEdgeToEdge()
        setContent {
            StmTheme {
                if (unlocked) {
                    content()
                } else if (Store(this).hasPin()) {
                    PinScreen(
                        check = { PinGate.submit(this, it) },
                        onOk = { unlock() },
                        onCancel = { finish() }
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                }
            }
        }
    }
}

@Composable
fun PinScreen(check: (String) -> PinResult, onOk: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text("Enter PIN", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit); msg = null },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = msg != null,
            supportingText = { msg?.let { Text(it) } }
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = pin.length >= 4,
            onClick = {
                when (check(pin)) {
                    PinResult.OK -> onOk()
                    PinResult.WRONG -> { msg = "Wrong PIN"; pin = "" }
                    PinResult.LOCKED -> { msg = "Too many tries. Wait 5 minutes."; pin = "" }
                }
            }
        ) { Text("Unlock") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

// ───────────────────────── building blocks ─────────────────────────

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp)
    )
}

@Composable
fun StmCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), shape = CircleShape) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AppIcon(d: Drawable?, size: Dp = 46.dp) {
    val bmp = remember(d) { d?.toBitmap(112, 112)?.asImageBitmap() }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(13.dp)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
fun ToggleRow(title: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** − value + control. value 0 shows zeroLabel. */
@Composable
fun Stepper(
    value: Int,
    step: Int,
    max: Int,
    zeroLabel: String,
    format: (Int) -> String = ::fmtMin,
    onChange: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onChange((value - step).coerceAtLeast(0)) }) {
            Text("−", fontSize = 22.sp)
        }
        Text(
            if (value <= 0) zeroLabel else format(value),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall
        )
        FilledTonalIconButton(onClick = { onChange((value + step).coerceAtMost(max)) }) {
            Icon(Icons.Default.Add, "More")
        }
    }
}

/** Collapsible section header + content. */
@Composable
fun Expander(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
        }
        AnimatedVisibility(visible = open) { Column(content = content) }
    }
}

// ───────────────────────── time windows ─────────────────────────

private const val NO_EDIT = -2

/** Hour (1-12) + minute (0-59) typed by hand, plus AM/PM chips. Reports minutes-from-midnight, or null if invalid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEntry(label: String, initial: Int, onValue: (Int?) -> Unit) {
    val h24 = (initial / 60) % 24
    var hour by remember { mutableStateOf((if (h24 % 12 == 0) 12 else h24 % 12).toString()) }
    var min by remember { mutableStateOf("%02d".format(initial % 60)) }
    var pm by remember { mutableStateOf(h24 >= 12) }
    val h = hour.toIntOrNull()
    val m = min.toIntOrNull()
    val hOk = h != null && h in 1..12
    val mOk = m != null && m in 0..59
    val value = if (hOk && mOk) ((h!! % 12) + if (pm) 12 else 0) * 60 + m!! else null
    SideEffect { onValue(value) }

    val keys = KeyboardOptions(keyboardType = KeyboardType.Number)
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = hour,
                onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.width(72.dp),
                singleLine = true,
                isError = !hOk,
                keyboardOptions = keys
            )
            Text(":", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = min,
                onValueChange = { min = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.width(72.dp),
                singleLine = true,
                isError = !mOk,
                keyboardOptions = keys
            )
            FilterChip(selected = !pm, onClick = { pm = false }, label = { Text("AM") })
            FilterChip(selected = pm, onClick = { pm = true }, label = { Text("PM") })
        }
    }
}

/** Add / edit one window: From and To, both typed with AM/PM. */
@Composable
private fun WindowDialog(initial: TimeWindow?, onDone: (TimeWindow) -> Unit, onDismiss: () -> Unit) {
    val s0 = initial?.startMin ?: (22 * 60)
    val e0 = initial?.endMin ?: (7 * 60)
    var start by remember { mutableStateOf<Int?>(s0) }
    var end by remember { mutableStateOf<Int?>(e0) }
    val ok = start != null && end != null && start != end
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add time window" else "Edit time window") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TimeEntry("From", s0) { start = it }
                TimeEntry("To", e0) { end = it }
                val s = start
                val e = end
                Text(
                    when {
                        s == null || e == null -> "Hour 1–12, minutes 00–59."
                        s == e -> "Start and end must be different."
                        e < s -> "${TimeWindow.fmt12h(s)} → ${TimeWindow.fmt12h(e)} (overnight, ends next day)"
                        else -> "${TimeWindow.fmt12h(s)} → ${TimeWindow.fmt12h(e)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onDone(TimeWindow(start!!, end!!)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

/** Editable list of time windows (per-app schedule and global bedtime). Tap a row to edit it. */
@Composable
fun WindowsEditor(windows: List<TimeWindow>, onChange: (List<TimeWindow>) -> Unit) {
    var editing by remember { mutableIntStateOf(NO_EDIT) }   // NO_EDIT none, -1 new, >=0 index
    Column {
        windows.forEachIndexed { i, w ->
            Row(
                Modifier.fillMaxWidth().clickable { editing = i },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(w.toString(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (w.endMin < w.startMin) "Overnight · tap to edit" else "Tap to edit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onChange(windows.filterIndexed { j, _ -> j != i }) }) {
                    Icon(Icons.Default.Close, "Remove")
                }
            }
        }
        OutlinedButton(onClick = { editing = -1 }) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add time window")
        }
    }
    if (editing != NO_EDIT) {
        val idx = editing
        WindowDialog(
            initial = windows.getOrNull(idx),
            onDone = { w ->
                onChange(
                    if (idx in windows.indices) windows.mapIndexed { j, x -> if (j == idx) w else x }
                    else windows + w
                )
                editing = NO_EDIT
            },
            onDismiss = { editing = NO_EDIT }
        )
    }
}
