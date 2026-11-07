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

import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.ui.theme.Guard

/**
 * Guard > gear > Notifications: the quiet-notification controls (ichthys.nix
 * notifications.quiet.*). Persistent and silent notifications stop filling the shade: ongoing
 * system notifications (USB debugging, charging over USB, hotspot, VPN, the screen recorder,
 * Backstage) and the ongoing notifications of the "always quiet" apps leave the stack for a strip
 * of chips or one dashed row; the Silent section shows one line per notification or folds too.
 *
 * Every row writes a Settings.Secure key that SystemUI's IchthysQuietNotificationsCoordinator
 * reads live; the defaults here are the option defaults in IchthysOptions.java. Reached from the
 * gear page (GuardActivity routes "s:quiet", i.e. nav.openPage("quiet")) or with
 * [GuardActivity.quietNotificationsIntent].
 */
@Composable
fun QuietNotificationsScreen(
    vm: GuardViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val t = Guard.tokens
    val tick = rememberSettingsTick(QUIET_URIS)
    val fold = remember(tick) { Settings.Secure.getInt(resolver, KEY_FOLD_ONGOING, FOLD_CHIPS) }
    val hideIcons = remember(tick) { Settings.Secure.getInt(resolver, KEY_HIDE_ICONS, 1) != 0 }
    val peek = remember(tick) { Settings.Secure.getInt(resolver, KEY_PEEK, 1) != 0 }
    val silent = remember(tick) { Settings.Secure.getInt(resolver, KEY_SILENT_STYLE, SILENT_ONE_LINE) }
    val quietApps = remember(tick) { readList(context, KEY_ALWAYS_QUIET) }
    val hidden = remember(tick) { readList(context, KEY_HIDDEN) }
    val apps by vm.repo.apps.collectAsStateWithLifecycle()
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }

    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹ Back", Modifier.clearAndSetSemantics { contentDescription = "Back" }) }
            Text("Notifications", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Persistent and silent notifications stop filling the shade.",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        item {
            GuardCard(title = "Fold ongoing") {
                Text(
                    "USB debugging, charging, hotspot, VPN, the screen recorder, Backstage and " +
                        "the always-quiet apps below leave the list. Chips: a strip of small " +
                        "chips above the first notification (tap opens, long-press hides that " +
                        "one). One row: a single dashed \"System · ...\" row at the end of the " +
                        "shade that unfolds on tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Segmented(
                    options = listOf(FOLD_CHIPS, FOLD_ONE_ROW, FOLD_OFF),
                    selected = fold,
                    label = {
                        when (it) {
                            FOLD_CHIPS -> "Chips"
                            FOLD_ONE_ROW -> "One row"
                            else -> "Off"
                        }
                    },
                    onSelect = { Settings.Secure.putInt(resolver, KEY_FOLD_ONGOING, it) },
                )
                ToggleRow(
                    label = "Hide their status-bar icons",
                    supporting = "The notification dot still lights for real notifications.",
                    checked = hideIcons,
                    enabled = fold != FOLD_OFF,
                ) {
                    Settings.Secure.putInt(resolver, KEY_HIDE_ICONS, if (it) 1 else 0)
                }
                ToggleRow(
                    label = "Peek on change",
                    supporting =
                        "A changed one (USB plugged, charging state) shows as a normal card " +
                            "for three seconds, then settles.",
                    checked = peek,
                    enabled = fold != FOLD_OFF,
                ) {
                    Settings.Secure.putInt(resolver, KEY_PEEK, if (it) 1 else 0)
                }
            }
        }
        item {
            GuardCard(title = "Silent section") {
                Text(
                    "One line: icon · app · title until expanded. Folded: the whole section is " +
                        "one dashed row at the end of the shade. Cards: as before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Segmented(
                    options = listOf(SILENT_ONE_LINE, SILENT_FOLDED, SILENT_CARDS),
                    selected = silent,
                    label = {
                        when (it) {
                            SILENT_ONE_LINE -> "One line"
                            SILENT_FOLDED -> "Folded"
                            else -> "Cards"
                        }
                    },
                    onSelect = { Settings.Secure.putInt(resolver, KEY_SILENT_STYLE, it) },
                )
            }
        }
        item {
            GuardCard(title = "Always quiet") {
                Text(
                    "Apps whose \"Background service running\" and other ongoing notifications " +
                        "are folded like the system's.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                if (quietApps.isEmpty() && !adding) {
                    Text(
                        "No apps yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                for (pkg in quietApps) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(pkg, size = 28.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(labels[pkg] ?: pkg, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                pkg,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                                color = t.muted,
                            )
                        }
                        TextButton(onClick = { writeList(context, KEY_ALWAYS_QUIET, quietApps - pkg) }) {
                            Text("Remove")
                        }
                    }
                }
                if (adding) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search apps") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    val matches =
                        remember(apps, query, quietApps) {
                            apps.asSequence()
                                .filter { it.packageName !in quietApps }
                                .filter {
                                    query.isBlank() ||
                                        it.label.contains(query, true) ||
                                        it.packageName.contains(query, true)
                                }
                                .sortedBy { it.label.lowercase() }
                                .take(if (query.isBlank()) 8 else 20)
                                .toList()
                        }
                    for (app in matches) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    writeList(context, KEY_ALWAYS_QUIET, quietApps + app.packageName)
                                    query = ""
                                    adding = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName, size = 28.dp)
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                        }
                    }
                    TextButton(onClick = { adding = false; query = "" }) { Text("Cancel") }
                } else {
                    TextButton(onClick = { adding = true }) { Text("Add app") }
                }
            }
        }
        if (hidden.isNotEmpty()) {
            item {
                GuardCard(title = "Hidden") {
                    Text(
                        "Folded notifications you hid for good with a long-press on their chip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                    for (entry in hidden) {
                        val pkg = entry.substringBefore(':')
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(labels[pkg] ?: pkg, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    entry,
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                                    color = t.muted,
                                )
                            }
                            TextButton(onClick = { writeList(context, KEY_HIDDEN, hidden - entry) }) {
                                Text("Show again")
                            }
                        }
                    }
                    TextButton(onClick = { writeList(context, KEY_HIDDEN, emptyList()) }) {
                        Text("Show all again")
                    }
                }
            }
        }
    }
}

private fun readList(context: Context, key: String): List<String> =
    Settings.Secure.getString(context.contentResolver, key)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        .orEmpty()

private fun writeList(context: Context, key: String, values: List<String>) {
    Settings.Secure.putString(
        context.contentResolver,
        key,
        values.distinct().joinToString(",").ifEmpty { null },
    )
}

// Settings.Secure keys of ichthys.nix notifications.quiet.* (IchthysOptions.java); SystemUI's
// IchthysQuietNotificationsRepository reads the same keys.
private const val KEY_FOLD_ONGOING = "ichthys_quiet_fold_ongoing"
private const val KEY_HIDE_ICONS = "ichthys_quiet_hide_icons"
private const val KEY_PEEK = "ichthys_quiet_peek"
private const val KEY_SILENT_STYLE = "ichthys_quiet_silent_style"
private const val KEY_ALWAYS_QUIET = "ichthys_quiet_notif_pkgs"
private const val KEY_HIDDEN = "ichthys_quiet_hidden"

private const val FOLD_CHIPS = 0
private const val FOLD_ONE_ROW = 1
private const val FOLD_OFF = 2
private const val SILENT_ONE_LINE = 0
private const val SILENT_FOLDED = 1
private const val SILENT_CARDS = 2

private val QUIET_URIS: List<Uri> =
    listOf(KEY_FOLD_ONGOING, KEY_HIDE_ICONS, KEY_PEEK, KEY_SILENT_STYLE, KEY_ALWAYS_QUIET, KEY_HIDDEN)
        .map { Settings.Secure.getUriFor(it) }
