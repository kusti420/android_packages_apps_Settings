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

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.PeriodReport
import com.android.settings.ichthys.guard.os.SHIELD_SECTIONS
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.delay

/** Every OS master switch on the shields card, for the live re-read. */
private val SHIELD_URIS: List<Uri> by lazy {
    SHIELD_SECTIONS.flatMap { it.second }.flatMap { it.switches }.map { it.uri }
}

/**
 * The OS shields' master switches, the only place they are shown: a handful of rows, each
 * switching the shields of one kind of data together (see [SHIELD_SECTIONS]). On = protected for
 * every app; one app's real access is on its own page (Data access).
 */
@Composable
private fun ShieldsCard(repo: GuardRepository) {
    val resolver = LocalContext.current.contentResolver
    val t = Guard.tokens
    // Re-read after every change: here (version) and anywhere else (tick).
    var version by remember { mutableIntStateOf(0) }
    val tick = rememberSettingsTick(SHIELD_URIS)
    GuardCard(title = "Shields for every app") {
        Text(
            "What apps get of your data unless you give one real access on its own page (Apps, " +
                "Data access). System apps and your default phone and SMS apps are exempt.",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        for ((section, rows) in SHIELD_SECTIONS) {
            SectionLabel(section, Modifier.padding(top = 12.dp))
            for (row in rows) {
                val states = remember(version, tick, row) { row.switches.map { it.isOn(resolver) } }
                val allOn = states.all { it }
                ToggleRow(
                    row.label,
                    allOn,
                    when {
                        allOn -> row.protectedText
                        states.any { it } -> "Partly on: switch on to protect all of it"
                        else -> "Off: every app gets real access"
                    },
                ) { on ->
                    repo.setShields(row.switches, on)
                    version++
                }
            }
        }
    }
}

/** Overview: today's score and counts, the shields, pause and lockdown, top tracker companies. */
@Composable
fun HomeScreen(
    vm: GuardViewModel,
    onOpenApp: (String) -> Unit,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.today.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val t = Guard.tokens
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    var confirmLockdown by remember { mutableStateOf(false) }

    LazyColumn(
        modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!status.live) {
            item {
                GuardCard(title = "Service unavailable") {
                    Text(
                        "The ichthys_guard platform service isn't reachable, so no live traffic is shown and network rules aren't enforced. Network policies are still saved and applied once it is back; the shields and data access don't depend on it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                }
            }
        }
        if (!status.osWritable) {
            item {
                GuardCard(title = "Shields not applied") {
                    Text(
                        "Settings couldn't write the shield settings (WRITE_SECURE_SETTINGS " +
                            "denied), so the last change to a shield or to an app's data " +
                            "access wasn't saved.",
                        color = t.danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            GuardCard(title = "Privacy today") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${s.score}",
                        style = MaterialTheme.typography.displayLarge.copy(fontFamily = t.display),
                        color = t.text,
                    )
                    Text(
                        " / 100 · ${s.grade}",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = t.mono),
                        color = t.muted,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                // The score bar is this group's one accented element.
                ShareBar(s.score / 100f, Modifier.padding(vertical = 8.dp), color = t.accent)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Stat("${s.queries}", "lookups")
                    Stat("${s.trackersBlocked}", "trackers blocked")
                    Stat("${s.trackersSeen}", "tracker contacts")
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Stat("${s.appsContactingTrackers}", "apps reached trackers")
                    Stat("${s.activeApps}", "apps online")
                    Stat("${s.blockedTotal}", "blocked total")
                }
            }
        }
        if (week.any { it.total > 0 }) {
            item {
                GuardCard(title = "Last 7 days") {
                    Row(
                        Modifier.fillMaxWidth().height(96.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        val today = week.lastOrNull()?.epochDay
                        for (d in week) {
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    if (d.total > 0) "${d.score}" else "–",
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = t.mono
                                        ),
                                    color = t.muted,
                                )
                                androidx.compose.foundation.layout.Box(
                                    Modifier.fillMaxWidth()
                                        .height(
                                            (56 * (if (d.total > 0) d.score else 0) / 100)
                                                .coerceAtLeast(2)
                                                .dp
                                        )
                                        .background(
                                            when {
                                                d.total <= 0 -> t.track
                                                // Today's bar is the accented one; past days are
                                                // muted.
                                                d.epochDay == today -> t.accent
                                                else -> t.muted
                                            },
                                            androidx.compose.foundation.shape.RoundedCornerShape(
                                                t.radiusSmall / 2
                                            ),
                                        )
                                )
                                Text(
                                    java.time.LocalDate.ofEpochDay(d.epochDay)
                                        .dayOfWeek
                                        .getDisplayName(
                                            java.time.format.TextStyle.NARROW,
                                            java.util.Locale.getDefault(),
                                        ),
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = t.mono
                                        ),
                                    color = t.muted,
                                )
                            }
                        }
                    }
                    val w = PeriodReport(week)
                    Text(
                        "${w.trackersBlocked} of ${w.trackersSeen} tracker contacts blocked this week",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item {
            val paused = status.pausedUntil > now
            GuardCard(title = "Protection") {
                if (paused) {
                    val left = (status.pausedUntil - now) / 1000
                    Text(
                        "Paused · resumes in %d:%02d:%02d"
                            .format(left / 3600, (left / 60) % 60, left % 60),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = t.mono),
                        color = t.danger,
                    )
                    Text(
                        "Monitoring continues; blocking is off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                    Button(
                        onClick = { vm.repo.pause(0) },
                        modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = t.accent,
                                contentColor = t.onAccent,
                            ),
                    ) {
                        Text("Resume protection now")
                    }
                } else {
                    Text(
                        "On · every app monitored, known trackers blocked",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { vm.repo.pause(5) },
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Text("Pause 5 min")
                        }
                        OutlinedButton(
                            onClick = { vm.repo.pause(60) },
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Text("Pause 1 h")
                        }
                    }
                }
            }
        }
        item {
            GuardCard(title = "Lockdown") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (status.lockdown) "Lockdown is ON" else "Panic switch",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (status.lockdown) t.danger else t.text,
                        )
                        Text(
                            "Cuts the network for every app except your allowlist (edit it in Rules). Emergency calls are never affected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                        )
                    }
                    Switch(
                        checked = status.lockdown,
                        onCheckedChange = { on ->
                            if (on) confirmLockdown = true else vm.repo.setLockdown(false)
                        },
                    )
                }
            }
        }
        item { ShieldsCard(vm.repo) }
        item {
            GuardCard(title = "Top tracker companies today") {
                if (s.topCompanies.isEmpty())
                    Text("No tracker contacts yet today.", color = t.muted)
                val max = s.topCompanies.maxOfOrNull { it.hits } ?: 1
                for (c in s.topCompanies) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${c.blocked}/${c.hits} · ${c.apps} apps",
                                style =
                                    MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
                                color = t.muted,
                            )
                        }
                        c.category?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = t.muted)
                        }
                        ShareBar(c.hits.toFloat() / max, Modifier.padding(top = 4.dp))
                    }
                }
                TextButton(onClick = onOpenActivity) { Text("Open live activity") }
            }
        }
        if (s.topApps.any { it.trackerHits > 0 }) {
            item {
                GuardCard(title = "Noisiest apps") {
                    for (a in s.topApps.filter { it.trackerHits > 0 }) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = a.packageName != null) {
                                    a.packageName?.let(onOpenApp)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                a.label,
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Tag("${a.trackerHits} trackers", emphasized = true)
                            Tag("${a.blocked} blocked")
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (confirmLockdown) {
        AlertDialog(
            onDismissRequest = { confirmLockdown = false },
            title = { Text("Turn on lockdown?") },
            text = {
                Text(
                    "Every app except ${vm.repo.rules.value.lockdown.allowlist.size.takeIf { it > 0 }?.let { "the $it allowlisted" } ?: "your messaging and phone"} apps loses network access until you turn it off."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.repo.setLockdown(true)
                        confirmLockdown = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = t.danger),
                ) {
                    Text("Lock down")
                }
            },
            dismissButton = { TextButton(onClick = { confirmLockdown = false }) { Text("Cancel") } },
        )
    }
}
