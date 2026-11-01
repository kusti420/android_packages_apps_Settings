/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.ichthys.guard.ui

import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.settings.ichthys.guard.model.BlockReason
import com.android.settings.ichthys.guard.model.LabeledEvent
import com.android.settings.ichthys.guard.ui.theme.Guard
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One group: the Shoal card on the ink ground. Outlined by a hairline with theme.surface =
 * hairline, a filled card without a stroke when it is tonal.
 */
@Composable
fun GuardCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = Guard.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(t.radius),
        color = t.card,
        border = if (t.hairlineSurfaces) BorderStroke(t.hairlineWidth, t.hairline) else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                SectionLabel(title)
            }
            content()
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Guard.tokens.mono),
        color = Guard.tokens.muted,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun Stat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Guard.tokens.mono),
            color = color,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Guard.tokens.muted)
    }
}

/**
 * Small pill label (tracker name, BLOCKED, DNS …). Tags repeat down lists, so they never take the
 * accent: emphasized ones are full-strength text on the outline, the rest muted on the hairline.
 */
@Composable
fun Tag(text: String, emphasized: Boolean = false, danger: Boolean = false) {
    val t = Guard.tokens
    val fg =
        when {
            danger -> t.danger
            emphasized -> t.text
            else -> t.muted
        }
    val line =
        when {
            danger -> t.danger.copy(alpha = 0.6f)
            emphasized -> t.outline
            else -> t.hairline
        }
    Box(
        Modifier.padding(end = 4.dp)
            .border(t.hairlineWidth, line, RoundedCornerShape(t.radiusSmall))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    supporting: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = Guard.tokens.muted,
                )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * A counter that goes up whenever one of the settings behind [uris] changes, on this page or
 * anywhere else. Key the `remember` that reads those settings on it and the row follows the
 * setting live. [uris] must be a stable list (a top-level value).
 */
@Composable
fun rememberSettingsTick(uris: List<Uri>): Int {
    val resolver = LocalContext.current.contentResolver
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(resolver, uris) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    tick++
                }
            }
        for (uri in uris) resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return tick
}

/**
 * Material 3 segmented control over an enum-ish list. The selected segment is the group's one
 * accented element (accent tint fill, accent outline); the others are muted on hairlines.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = Guard.tokens
    val colors =
        SegmentedButtonDefaults.colors(
            activeContainerColor = t.accentTint,
            activeContentColor = t.text,
            activeBorderColor = t.accent,
            inactiveContainerColor = Color.Transparent,
            inactiveContentColor = t.muted,
            inactiveBorderColor = t.hairline,
        )
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = colors,
                icon = {},
            ) {
                Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp),
    )
}

/**
 * Horizontal share bar on the token track colour. Muted by default; pass the accent only for the
 * one bar that is the group's active element.
 */
@Composable
fun ShareBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Guard.tokens.muted) {
    val t = Guard.tokens
    Box(modifier.fillMaxWidth().height(4.dp).background(t.track, RoundedCornerShape(2.dp))) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
    }
}

/** One event row, shared by Activity and the per-app live mini-log. */
@Composable
fun EventRow(e: LabeledEvent, showApp: Boolean, modifier: Modifier = Modifier) {
    val t = Guard.tokens
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                formatClock(e.event.timeMs),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                color = t.muted,
            )
            Text(
                e.event.destination,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono),
                color = if (e.event.blocked) t.muted else t.text,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag(e.event.type.name)
            // IchthysOS: the reason rides on the verdict: "BLOCKED · lockdown".
            if (e.event.blocked)
                VerdictTag(
                    "BLOCKED",
                    e.event.reason.takeIf { it != BlockReason.NONE }?.label,
                    danger = true,
                )
            if (e.tracker != null) Tag(e.tracker, emphasized = true)
            if (e.event.port != 0) Tag(":${e.event.port}")
            if (showApp) {
                Text(
                    e.appLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val clockFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

fun formatClock(ms: Long): String = synchronized(clockFormat) { clockFormat.format(Date(ms)) }

fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

/** IchthysOS: "1 app", "3 apps": counts on Guard's pages read as English, not "1 apps". */
fun quantity(n: Int, one: String, other: String = one + "s"): String =
    "$n ${if (n == 1) one else other}"

fun formatBytes(b: Long): String =
    when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f kB".format(b / 1024.0)
        else -> "$b B"
    }

fun formatAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ms).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "${d}s ago"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        else -> "${d / 86400} d ago"
    }
}

/** Keyed by package and pixel size: the same app is drawn at 28, 40 and 48 dp. */
private val iconCache = android.util.LruCache<String, ImageBitmap>(160)

/**
 * The app's launcher icon, loaded off the main thread and cached. Draws nothing until loaded (and
 * for packages without an icon), keeping the row's layout stable.
 */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val cacheKey = "$packageName@$px"
    val icon by
        produceState(iconCache.get(cacheKey), packageName, px) {
            // produceState keeps its value when the keys change: start from this package's.
            value = iconCache.get(cacheKey)
            if (value == null) {
                value =
                    withContext(Dispatchers.IO) {
                        runCatching {
                                context.packageManager
                                    .getApplicationIcon(packageName)
                                    .toIconBitmap(px)
                            }
                            .getOrNull()
                            ?.also { iconCache.put(cacheKey, it) }
                    }
            }
        }
    Box(modifier.size(size)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(size)) }
    }
}

private fun Drawable.toIconBitmap(px: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, px, px)
    draw(canvas)
    return bmp.asImageBitmap()
}


// ---- IchthysOS: shared pieces of the redesigned pages -------------------------------------------

/**
 * A row that opens a page: title, a one-line value or summary, and a chevron. The whole gear page
 * and the app page's sheets are made of these.
 */
@Composable
fun NavRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    danger: Boolean = false,
) {
    val t = Guard.tokens
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) t.danger else t.text,
            )
            if (summary != null)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
        if (trailing != null)
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
                color = t.muted,
                modifier = Modifier.padding(end = 8.dp),
            )
        Text("›", color = t.muted, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The reason tag of a stream row: "BLOCKED · lockdown", "ALLOWED", "SHIELDED · contacts".
 * [reason] is the sentence after the verdict; null for a plain verdict.
 */
@Composable
fun VerdictTag(verdict: String, reason: String?, danger: Boolean = false, emphasized: Boolean = false) {
    Tag(if (reason.isNullOrEmpty()) verdict else "$verdict · $reason", emphasized = emphasized, danger = danger)
}

/** The small dot that marks a control changed away from its preset. */
@Composable
fun PresetDot(shown: Boolean) {
    if (!shown) return
    val t = Guard.tokens
    Box(
        Modifier.padding(start = 6.dp)
            .size(8.dp)
            .background(t.accent, androidx.compose.foundation.shape.CircleShape)
    )
}

/** A label with an optional [PresetDot], above a control. */
@Composable
fun ControlLabel(text: String, differs: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(text, Modifier.padding(bottom = 0.dp))
        PresetDot(differs)
    }
}

/** A line of a card: a label on the left, a mono value on the right. */
@Composable
fun KeyValue(label: String, value: String, danger: Boolean = false) {
    val t = Guard.tokens
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
            color = if (danger) t.danger else t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A page header: a back button and a title, then the content below (the pushed pages). */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    val t = Guard.tokens
    TextButton(onClick = onBack) { Text("‹ Back") }
    Text(title, style = MaterialTheme.typography.headlineMedium)
    if (subtitle != null)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted)
}

/** "09:12" today, "Mon 09:12" within the week, else a short date. */
fun formatWhen(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = now - ms
    return when {
        d < 0 || d >= 6 * 86_400_000L -> formatDateTime(ms)
        d >= 86_400_000L ->
            java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(Date(ms))
        else -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(Date(ms))
    }
}
