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

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.ActivityFilter
import com.android.settings.ichthys.guard.model.EventRun
import com.android.settings.ichthys.guard.model.EventType
import com.android.settings.ichthys.guard.model.GuardEvent
import com.android.settings.ichthys.guard.model.Grouping
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.Insights
import com.android.settings.ichthys.guard.model.LabeledEvent
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.RuleEngine
import com.android.settings.ichthys.guard.model.Verdict
import com.android.settings.ichthys.guard.model.VerdictSource
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live log state: filter, grouping and a pausable (frozen) view of the live stream. */
class ActivityViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GuardRepository.get(app)

    val filter = MutableStateFlow(ActivityFilter())
    val grouping = MutableStateFlow(Grouping.NONE)

    /** Snapshot of the stream taken when the user paused it; null while live. */
    private val frozen = MutableStateFlow<List<LabeledEvent>?>(null)

    val paused: StateFlow<Boolean> =
        frozen.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPaused(p: Boolean) {
        frozen.value = if (p) repo.events.value else null
    }

    val newWhilePaused: StateFlow<Int> =
        combine(repo.events, frozen) { live, snap ->
                if (snap == null) {
                    0
                } else {
                    val last = snap.lastOrNull()?.event?.seq ?: 0L
                    live.count { it.event.seq > last }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Filtered, newest first. */
    val visible: StateFlow<List<LabeledEvent>> =
        combine(repo.events, frozen, filter) { live, snap, f -> f.apply(snap ?: live).reversed() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * IchthysOS: [visible] with consecutive repeats of one attempt collapsed, for the Stream view.
     * The events themselves stay (export, counts and the other views use [visible]).
     */
    val runs: StateFlow<List<EventRun>> =
        visible
            .map { EventRun.collapse(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** (package, label) of every app present in the event window. */
    val appsInEvents: StateFlow<List<Pair<String, String>>> =
        repo.events
            .map { evs ->
                evs.mapNotNull { e -> e.packageName?.let { it to e.appLabel } }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateFilter(change: (ActivityFilter) -> ActivityFilter) {
        filter.value = change(filter.value)
    }
}

private data class EventGroup(
    val key: String,
    val title: String,
    val packageName: String?,
    val events: List<LabeledEvent>,
) {
    val trackerHits = events.count { it.isTracker }
    val blocked = events.count { it.event.blocked }
}

private fun group(events: List<LabeledEvent>, grouping: Grouping): List<EventGroup> =
    when (grouping) {
        Grouping.NONE -> emptyList()
        Grouping.APP ->
            events
                .groupBy { it.packageName ?: "uid:${it.event.uid}" }
                .map { (k, evs) ->
                    EventGroup(k, evs.first().appLabel, evs.first().packageName, evs)
                }
                .sortedByDescending { it.events.size }
        Grouping.DOMAIN ->
            events
                .groupBy { it.event.destination }
                .map { (k, evs) -> EventGroup(k, k, null, evs) }
                .sortedByDescending { it.events.size }
    }

/** The sections of the Activity tab: the live log and the totals over a period. */
enum class ActivitySection(val label: String) {
    LIVE("Live"),
    DATA("Data"),
    HOSTS("Hosts"),
    TRACKERS("Trackers"),
}

/**
 * The Activity tab: what the apps do on the network, as one page with four sections. Live is the
 * DNS/connect log; Data (bytes per app), Hosts (top destinations) and Trackers (tracker share per
 * app) are the totals over a period (see [TrafficSection]).
 */
@Composable
fun ActivityScreen(
    vm: GuardViewModel,
    onOpenApp: (String) -> Unit,
    onOpenHost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by rememberSaveable { mutableStateOf(ActivitySection.LIVE) }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
            Text("Activity", style = MaterialTheme.typography.headlineSmall)
            Segmented(
                options = ActivitySection.entries.toList(),
                selected = section,
                label = { it.label },
                onSelect = { section = it },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (section == ActivitySection.LIVE) {
            LiveLog(vm, onOpenApp, onOpenHost)
        } else {
            TrafficSection(vm, section, onOpenApp, onOpenHost)
        }
    }
}

/** Live DNS/connect stream with filters, grouping and per-row actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveLog(
    vm: GuardViewModel,
    onOpenApp: (String) -> Unit,
    onOpenHost: (String) -> Unit,
) {
    val avm: ActivityViewModel = viewModel()
    val filter by avm.filter.collectAsStateWithLifecycle()
    val grouping by avm.grouping.collectAsStateWithLifecycle()
    val paused by avm.paused.collectAsStateWithLifecycle()
    val newCount by avm.newWhilePaused.collectAsStateWithLifecycle()
    val visible by avm.visible.collectAsStateWithLifecycle()
    val runs by avm.runs.collectAsStateWithLifecycle()
    val apps by avm.appsInEvents.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val t = Guard.tokens

    var selected by remember { mutableStateOf<LabeledEvent?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // IchthysOS: which collapsed runs of the Stream are opened, by EventRun.id.
    val expandedRuns = remember { mutableStateMapOf<Long, Boolean>() }
    val groups = remember(visible, grouping) { group(visible, grouping) }
    val exportCtx = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri
            ->
            if (uri != null) {
                val rows = visible.reversed()
                val csv = Insights.toCsv(rows)
                // IchthysOS: the export used to fail silently; say how it went.
                exportScope.launch {
                    val ok =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                    exportCtx.contentResolver.openOutputStream(uri, "wt")!!.use {
                                        it.write(csv.toByteArray())
                                    }
                                }
                                .isSuccess
                        }
                    Toast.makeText(
                            exportCtx,
                            if (ok) "Exported ${quantity(rows.size, "event")}"
                            else "Export failed",
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            }
        }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // IchthysOS: this pauses the list only, not protection; say "log".
                    when {
                        !paused -> quantity(visible.size, "event")
                        newCount > 0 -> "Log paused · $newCount new"
                        else -> "Log paused"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
                    color = t.muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { exporter.launch("guard-activity.csv") },
                    enabled = visible.isNotEmpty(),
                ) {
                    Text("Export")
                }
                FilledTonalButton(onClick = { avm.setPaused(!paused) }) {
                    Text(if (paused) "Resume log" else "Pause log")
                }
            }
            OutlinedTextField(
                value = filter.query,
                onValueChange = { q -> avm.updateFilter { it.copy(query = q) } },
                placeholder = { Text("Search host, IP, app, tracker") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppFilterButton(apps, filter.packageName) { pkg ->
                    avm.updateFilter { it.copy(packageName = pkg) }
                }
                Chip("Trackers only", filter.trackersOnly) {
                    avm.updateFilter { it.copy(trackersOnly = !it.trackersOnly) }
                }
                Chip("Blocked only", filter.blockedOnly) {
                    avm.updateFilter { it.copy(blockedOnly = !it.blockedOnly) }
                }
                for (type in EventType.entries) {
                    Chip(if (type == EventType.DNS) "DNS" else "Connect", type in filter.types) {
                        avm.updateFilter { f ->
                            val next = if (type in f.types) f.types - type else f.types + type
                            if (next.isEmpty()) f else f.copy(types = next)
                        }
                    }
                }
                if (filter.isActive) {
                    TextButton(onClick = { avm.updateFilter { ActivityFilter() } }) {
                        Text("Clear")
                    }
                }
            }
            Segmented(
                options = Grouping.entries.toList(),
                selected = grouping,
                label = {
                    when (it) {
                        Grouping.NONE -> "Stream"
                        Grouping.APP -> "By app"
                        Grouping.DOMAIN -> "By domain"
                    }
                },
                onSelect = { avm.grouping.value = it },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    when {
                        filter.isActive -> "Nothing matches these filters."
                        // IchthysOS: the log comes from the service; without it, it stays empty.
                        !status.live ->
                            "No live activity: the Guard platform service isn't reachable."
                        else -> "No network activity yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.muted,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (grouping == Grouping.NONE) {
                    // IchthysOS: an app retrying the same thing is one row ("×7", first – last
                    // time) that opens to its single attempts; a lone event is a plain row.
                    for (run in runs) {
                        if (run.count == 1) {
                            item(key = "e:${run.head.event.seq}") {
                                val e = run.head
                                EventRow(
                                    e,
                                    showApp = true,
                                    modifier = Modifier.clickable { selected = e },
                                )
                                HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
                            }
                            continue
                        }
                        val open = expandedRuns[run.id] == true
                        item(key = "r:${run.id}") {
                            RunRow(run, open) { expandedRuns[run.id] = !open }
                        }
                        if (open) {
                            items(run.events.take(200), key = { "e:${it.event.seq}" }) { e ->
                                EventRow(
                                    e,
                                    showApp = false,
                                    modifier =
                                        Modifier.clickable { selected = e }.padding(start = 12.dp),
                                )
                            }
                        }
                        item(key = "rd:${run.id}") {
                            HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
                        }
                    }
                } else {
                    for (g in groups) {
                        val open = expanded[g.key] == true
                        item(key = "h:${g.key}") {
                            GroupHeader(g, open) { expanded[g.key] = !open }
                        }
                        if (open) {
                            items(g.events.take(200), key = { "g:${g.key}:${it.event.seq}" }) { e ->
                                EventRow(
                                    e,
                                    showApp = grouping != Grouping.APP,
                                    modifier =
                                        Modifier.clickable { selected = e }.padding(start = 12.dp),
                                )
                            }
                        }
                        item(key = "d:${g.key}") {
                            HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
                        }
                    }
                }
            }
        }
    }

    selected?.let { e ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            EventActions(vm, e, onOpenApp, onOpenHost) { selected = null }
        }
    }
}

@Composable
private fun AppFilterButton(
    apps: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.padding(end = 6.dp)) {
            Text(
                apps.firstOrNull { it.first == selected }?.second ?: "All apps",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All apps") },
                onClick = {
                    onSelect(null)
                    open = false
                },
            )
            for ((pkg, label) in apps) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(pkg)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * IchthysOS: one row for a run of repeated attempts: the event as [EventRow] shows it, with the
 * span of time it covers and a "×N" count. Tapping opens the single attempts (each with the
 * usual actions sheet).
 */
@Composable
private fun RunRow(run: EventRun, open: Boolean, onToggle: () -> Unit) {
    val t = Guard.tokens
    val e = run.head
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (open) "▾" else "▸",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono),
                color = t.muted,
            )
            Text(
                "${formatClock(run.firstMs)}–${formatClock(run.lastMs)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                color = t.muted,
            )
            Text(
                e.event.destination,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono),
                color = if (e.event.blocked) t.muted else t.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Tag("×${run.count}", emphasized = true)
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag(e.event.type.name)
            if (e.event.blocked) Tag("BLOCKED", danger = true)
            if (e.tracker != null) Tag(e.tracker, emphasized = true)
            if (e.event.port != 0) Tag(":${e.event.port}")
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

@Composable
private fun GroupHeader(g: EventGroup, open: Boolean, onToggle: () -> Unit) {
    val t = Guard.tokens
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (open) "▾" else "▸",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono),
            color = t.muted,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                g.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 2.dp)) {
                Tag(quantity(g.events.size, "event"))
                if (g.trackerHits > 0)
                    Tag(quantity(g.trackerHits, "tracker contact"), emphasized = true)
                if (g.blocked > 0) Tag("${g.blocked} blocked", danger = true)
            }
        }
    }
}

@Composable
private fun EventActions(
    vm: GuardViewModel,
    e: LabeledEvent,
    onOpenApp: (String) -> Unit,
    onOpenHost: (String) -> Unit,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val t = Guard.tokens
    val repo = vm.repo
    val policies by repo.policies.collectAsStateWithLifecycle()
    val rules by repo.rules.collectAsStateWithLifecycle()
    val host = e.event.host ?: e.event.ip ?: ""
    val pkg = e.packageName
    val tracker = remember(host) { repo.matcher.match(host) }
    val verdict =
        RuleEngine.decide(
            pkg,
            host,
            pkg?.let { policies.policyFor(it) },
            rules.rules,
            tracker != null,
            rules.lockdown,
            repo.isPaused(),
        )

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text(host, style = MaterialTheme.typography.titleMedium.copy(fontFamily = t.mono))
        Text(
            buildString {
                append(e.appLabel)
                append(" · ").append(e.event.type.name)
                if (e.event.ip != null) append(" · ").append(e.event.ip)
                if (e.event.port != 0) append(":").append(e.event.port)
                if (e.event.ips.isNotEmpty()) append(" → ").append(e.event.ips.joinToString())
                append(" · ").append(formatDateTime(e.event.timeMs))
            },
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        // IchthysOS: what happened to this event comes from the event itself (the firewall or the
        // hosts file); the verdict line explains what the current rules would do.
        val sinkholed = e.isSinkholed()
        if (e.event.blocked) {
            Text(
                if (sinkholed) "This one was blocked by the system-wide blocklist"
                else "This one was blocked by the firewall",
                style = MaterialTheme.typography.bodyMedium,
                color = t.danger,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        // IchthysOS: the hosts file answers this name with a sinkhole for every app, so "Allowed:
        // no rule matches" would contradict the line above.
        val blockedNext = verdict.blocked || sinkholed
        Text(
            if (!verdict.blocked && sinkholed) SYSTEM_BLOCKLIST_VERDICT
            else verdictText(verdict, tracker?.name),
            style = MaterialTheme.typography.bodyMedium,
            color = if (blockedNext) t.danger else t.accent,
            modifier = Modifier.padding(top = 12.dp),
        )
        // IchthysOS: the verdict covers host rules and blocks; a Wi-Fi-only app is also cut off on
        // mobile data, which "Allowed" alone would hide.
        val access = pkg?.let { repo.effectiveAccess(it) }
        if (!blockedNext && access == NetworkAccess.UNMETERED_ONLY) {
            Text(
                "Unmetered only: on mobile data or a metered hotspot this app has no network.",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (host.isNotEmpty()) {
            ActionRow("Every app that reached ${e.event.destination}") {
                dismiss()
                onOpenHost(e.event.destination)
            }
            // IchthysOS: host rules aren't enforced yet (HostRules), so don't offer to make them.
            if (HostRules.ENFORCED) {
                if (pkg != null) {
                    ActionRow("Block host for ${e.appLabel}") {
                        repo.blockHost(pkg, host)
                        dismiss()
                    }
                    ActionRow("Allow for ${e.appLabel}") {
                        repo.allowHost(pkg, host)
                        dismiss()
                    }
                }
                ActionRow("Block everywhere") {
                    repo.blockHost(null, host)
                    dismiss()
                }
                ActionRow("Allow everywhere") {
                    repo.allowHost(null, host)
                    dismiss()
                }
            }
            ActionRow("Copy host") {
                context
                    .getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("host", host))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
        if (pkg != null) {
            ActionRow("Open ${e.appLabel}") {
                dismiss()
                onOpenApp(pkg)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

/** IchthysOS: the system hosts file (HaGeZi) answered this lookup or connect with a sinkhole. */
fun LabeledEvent.isSinkholed(): Boolean = GuardEvent.isSinkholed(event.type, event.ips, event.ip)

/** IchthysOS: the verdict line for a destination the system hosts file blocks for every app. */
const val SYSTEM_BLOCKLIST_VERDICT =
    "Blocked by the system-wide blocklist (HaGeZi Light), for every app"

/**
 * Human explanation of a [Verdict] ("Would be blocked: this app has no network access"). A rule
 * that isn't enforced yet ([Verdict.notEnforced]) is named, but never as a block.
 */
fun verdictText(v: Verdict, trackerName: String?): String {
    val head =
        when {
            v.blocked -> "Would be blocked"
            v.notEnforced -> "Not blocked (host rules aren't enforced yet)"
            else -> "Allowed"
        }
    val why =
        when (v.source) {
            VerdictSource.PAUSED -> "protection is paused"
            VerdictSource.LOCKDOWN -> "lockdown is on and this app isn't on the allowlist"
            VerdictSource.NETWORK_POLICY -> "this app has no network access"
            VerdictSource.CUSTOM_RULE ->
                "custom rule \"${v.rule?.pattern}\"" +
                    if (v.rule?.appliesToAll == true) " (all apps)" else " (this app)"
            VerdictSource.APP_ALLOWED_HOST -> "host is on this app's allow list"
            VerdictSource.APP_BLOCKED_HOST -> "host is on this app's block list"
            VerdictSource.TRACKER -> "known tracker" + (trackerName?.let { " ($it)" } ?: "")
            VerdictSource.STRICT -> "strict mode allows only listed hosts"
            VerdictSource.DEFAULT ->
                if (trackerName != null && HostRules.ENFORCED)
                    "tracker blocking is off for this app ($trackerName)"
                else "no rule matches"
        }
    return "$head: $why"
}
