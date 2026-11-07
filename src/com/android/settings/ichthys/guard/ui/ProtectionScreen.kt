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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.android.settings.ichthys.guard.model.BlockReason
import com.android.settings.ichthys.guard.model.JournalEntry
import com.android.settings.ichthys.guard.model.JournalKind
import com.android.settings.ichthys.guard.model.LabeledEvent
import com.android.settings.ichthys.guard.model.ShieldProof
import com.android.settings.ichthys.guard.os.ShieldTable
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.delay

/**
 * IchthysOS: the home destination. The big number is what Guard did today; every engine card
 * carries its state and its LAST EVIDENCE (the newest event or proof); problems are a red card at
 * the top, never a hidden banner; "Verify my protection" runs the tester app.
 */
@Composable
fun ProtectionScreen(vm: GuardViewModel, nav: GuardNav, modifier: Modifier = Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val context = LocalContext.current
    val s by vm.today.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val events by repo.events.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val denyNow by repo.denyNow.collectAsStateWithLifecycle()
    val blockedToday by vm.blockedTodayByApp.collectAsStateWithLifecycle()
    val verify by repo.verify.collectAsStateWithLifecycle()
    val running by vm.verifier.current.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    var confirmLockdown by remember { mutableStateOf(false) }
    val since = GuardViewModel.startOfDay(now)

    // Evidence for the cards.
    val lastBlock = remember(events) { events.lastOrNull { it.event.blocked && it.event.reason != BlockReason.BLOCKLIST } }
    val offlineApps = remember(denyNow, apps) { apps.filter { it.uid in denyNow } }
    val masters = remember(tick) { repo.masterShieldsOn() }
    val lastProof = remember(journal) { journal.entries.lastOrNull { it.isShield && it.shield?.isLocation == false } }
    val lastLocation = remember(journal) { journal.entries.lastOrNull { it.shield?.isLocation == true } }
    val realList = remember(tick) { repo.realLocationApps().size }
    val fakeOn = remember(tick) { repo.fakePlaceOn() }
    // IchthysOS: the spoofed values that go with the fake place (Gear › Spoofed values).
    val spoofSummary = remember(tick) { repo.spoofProfile()?.summary(repo.fakePlaceName()) }
    val quarantined by repo.quarantined.collectAsStateWithLifecycle()
    val lastVerify = verify.last
    val paused = status.pausedUntil > now

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- problems: red, at the top ----
        if (status.problems.isNotEmpty()) {
            item {
                Surface(
                    Modifier.fillMaxWidth().clickable { nav.openPage("health") },
                    shape = RoundedCornerShape(t.radius),
                    color = t.card,
                    border = BorderStroke(t.hairlineWidth, t.danger),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (!status.live) "Guard isn't enforcing" else "Guard needs attention",
                            style = MaterialTheme.typography.titleMedium,
                            color = t.danger,
                        )
                        for (p in status.problems)
                            Text(p, style = MaterialTheme.typography.bodySmall, color = t.muted)
                        Text(
                            "Service health ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = t.danger,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        if (paused) {
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(t.radius),
                    color = t.card,
                    border = BorderStroke(t.hairlineWidth, t.danger),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val left = (status.pausedUntil - now) / 1000
                        Text(
                            "Paused · resumes in %d:%02d:%02d".format(left / 3600, (left / 60) % 60, left % 60),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = t.mono),
                            color = t.danger,
                        )
                        // IchthysOS: a pause lifts the uid firewall (Block, Wi-Fi only, lockdown,
                        // screen-off, background); the hosts file and the shields aren't part of it.
                        Text(
                            "Network rules and lockdown are off; the system blocklist and the data shields still apply.",
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                        )
                        Button(
                            onClick = { repo.pause(0) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent),
                        ) {
                            Text("Resume now")
                        }
                    }
                }
            }
        }

        // ---- the number ----
        item {
            GuardCard {
                val blockedApps = blockedToday.size
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${s.blockedTotal}",
                        style = MaterialTheme.typography.displayLarge.copy(fontFamily = t.display),
                        color = t.text,
                    )
                    Text(
                        " blocked today · ${quantity(blockedApps, "app")}",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = t.mono),
                        color = t.muted,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    buildString {
                        append(quantity(s.queries + s.connects, "attempt"))
                        append(" seen · ")
                        append("${s.trackersBlocked} of ${s.trackersSeen} tracker contacts stopped")
                        if (s.queries + s.connects == 0)
                            append(" · nothing recorded yet today")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }

        // ---- engine cards ----
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineCard(
                    title = "Firewall",
                    state =
                        when {
                            !status.live -> "unknown"
                            paused -> "paused"
                            else -> "${quantity(offlineApps.size, "app")} offline now"
                        },
                    evidence =
                        lastBlock?.let {
                            "Last block ${formatWhen(context, it.event.timeMs, now)} · ${it.appLabel} · ${it.event.reason.sentence}"
                        } ?: "No block recorded yet",
                    danger = status.live && status.health and com.android.settings.ichthys.guard.backend.Health.FIREWALL == 0,
                ) { nav.openTab(GuardTab.ACTIVITY) }
                EngineCard(
                    title = "Shields",
                    state = "${masters.first} of ${masters.second} on",
                    evidence =
                        lastProof?.let { "Last proof ${formatWhen(context, it.timeMs, now)} · ${it.appLabel ?: "an app"} · ${it.shield?.label}" }
                            ?: lastVerify?.let { "Verified ${formatWhen(context, it.finishedMs, now)} · ${it.verdictLine}" }
                            ?: "No proof yet · run Verify",
                    danger = masters.first < masters.second,
                ) { nav.openPage("shields") }
                EngineCard(
                    title = "Location",
                    state =
                        when {
                            !fakeOn -> "Real for everyone"
                            spoofSummary != null -> spoofSummary
                            else -> "Fake place · ${quantity(realList, "app")} see the real one"
                        },
                    evidence =
                        lastLocation?.let {
                            "Last fix ${formatWhen(context, it.timeMs, now)} · ${it.appLabel ?: "an app"} · ${it.shield?.label}"
                        } ?: "No fix delivered yet",
                ) { nav.openPage("spoofed") }
                EngineCard(
                    title = "Quarantine",
                    state = if (quarantined.isEmpty()) "No app waiting" else "${quantity(quarantined.size, "app")} waiting",
                    evidence =
                        journal.entries.lastOrNull { it.journal == JournalKind.QUARANTINE }?.let {
                            "Last ${formatWhen(context, it.timeMs, now)} · ${it.appLabel ?: "an app"}"
                        } ?: "New apps are held until you pick a preset",
                ) { nav.openPage("newapps") }
            }
        }

        // ---- verify ----
        item {
            Column {
                Button(
                    onClick = { nav.openVerify(null) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent),
                ) {
                    Text(if (running?.done == false) "Verification running…" else "Verify my protection")
                }
                Text(
                    lastVerify?.let { "Last run ${formatWhen(context, it.finishedMs, now)} · ${it.verdictLine}" }
                        ?: "Runs the tester app as a blocked app, a messenger and a maps app, and shows what each really got.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lastVerify?.passed == false) t.danger else t.muted,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }
        }

        // ---- pause / lockdown ----
        item {
            GuardCard(title = "Pause and lockdown") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // IchthysOS: without the service a pause does nothing, so don't start a countdown.
                    OutlinedButton(onClick = { repo.pause(5) }, enabled = status.live && !paused, modifier = Modifier.weight(1f)) {
                        Text("Pause 5 min")
                    }
                    OutlinedButton(onClick = { repo.pause(60) }, enabled = status.live && !paused, modifier = Modifier.weight(1f)) {
                        Text("Pause 1 h")
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (status.lockdown) "Lockdown is on" else "Lockdown",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (status.lockdown) t.danger else t.text,
                        )
                        Text(
                            "Every app goes offline except the ones set to keep their network (Gear › Lockdown keeps online).",
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                        )
                        if (status.lockdown && paused)
                            Text("Not enforced while paused.", style = MaterialTheme.typography.bodySmall, color = t.danger)
                    }
                    Switch(
                        checked = status.lockdown,
                        onCheckedChange = { on -> if (on) confirmLockdown = true else repo.setLockdown(false) },
                    )
                }
            }
        }

        // ---- latest: events and journal mixed ----
        item {
            GuardCard(title = "Latest") {
                val latest = remember(events, journal) { latestMixed(events, journal.entries, since, 10) }
                if (latest.isEmpty()) Text("Nothing yet today.", color = t.muted)
                for (item in latest) {
                    when (item) {
                        is Latest.Net -> LatestNetRow(item.e, now) { item.e.packageName?.let(nav.openApp) }
                        is Latest.Log -> LatestJournalRow(item.j, now) { item.j.packageName?.let(nav.openApp) }
                    }
                }
                TextButton(onClick = { nav.openTab(GuardTab.ACTIVITY) }) { Text("Open activity") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (confirmLockdown) {
        AlertDialog(
            onDismissRequest = { confirmLockdown = false },
            title = { Text("Turn on lockdown?") },
            text = {
                // IchthysOS: name the list that will really be used. An allowlist the user emptied
                // stays empty (it is seeded only once).
                val kept = remember { repo.lockdownAllowlistPreview().size }
                Text(
                    if (kept > 0)
                        "Every app loses network access until you turn it off, except ${quantity(kept, "app")} set to keep it."
                    else "Every app loses network access until you turn it off. No app is set to keep it."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repo.setLockdown(true)
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

@Composable
private fun EngineCard(
    title: String,
    state: String,
    evidence: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val t = Guard.tokens
    Surface(
        Modifier.width(220.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(t.radius),
        color = t.card,
        border = BorderStroke(t.hairlineWidth, if (danger) t.danger else t.hairline),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionLabel(title)
            Text(
                state,
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) t.danger else t.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                evidence,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                color = t.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private sealed interface Latest {
    val timeMs: Long

    data class Net(val e: LabeledEvent) : Latest {
        override val timeMs: Long
            get() = e.event.timeMs
    }

    data class Log(val j: JournalEntry) : Latest {
        override val timeMs: Long
            get() = j.timeMs
    }
}

/** The newest blocked events and journal entries of today, newest first. */
private fun latestMixed(
    events: List<LabeledEvent>,
    journal: List<JournalEntry>,
    since: Long,
    n: Int,
): List<Latest> {
    val net = events.asReversed().asSequence().filter { it.event.blocked && it.event.timeMs >= since }.take(n).map { Latest.Net(it) }
    val log = journal.asReversed().asSequence().filter { it.timeMs >= since }.take(n).map { Latest.Log(it) }
    return (net + log).sortedByDescending { it.timeMs }.take(n).toList()
}

@Composable
private fun LatestNetRow(e: LabeledEvent, now: Long, onClick: () -> Unit) {
    val t = Guard.tokens
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(formatWhen(context, e.event.timeMs, now), style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono), color = t.muted)
        Text(
            "${e.appLabel} → ${e.event.destination}",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        VerdictTag("BLOCKED", e.event.reason.label, danger = true)
    }
}

@Composable
fun LatestJournalRow(j: JournalEntry, now: Long, onClick: () -> Unit) {
    val t = Guard.tokens
    val context = LocalContext.current
    val kind = j.journal
    val proof: ShieldProof? = j.shield
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(formatWhen(context, j.timeMs, now), style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono), color = t.muted)
        Text(
            buildString {
                if (j.appLabel != null) append(j.appLabel).append(" · ")
                append(j.detail ?: proof?.label ?: kind?.label ?: "")
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (proof != null) VerdictTag(if (proof.protective) "SHIELDED" else "REAL", proof.label.substringBefore(' '), emphasized = true)
        else if (kind != null) Tag(kind.label.uppercase(), danger = kind.isWarning, emphasized = !kind.isWarning)
    }
}
