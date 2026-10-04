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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.Insights
import com.android.settings.ichthys.guard.os.AppInfo
import com.android.settings.ichthys.guard.os.UidTraffic
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TrafficRange(val label: String, val days: Int) {
    TODAY("Today", 0),
    WEEK("7 days", 7),
    MONTH("30 days", 30);

    fun sinceMs(now: Long = System.currentTimeMillis()): Long =
        if (days == 0) GuardViewModel.startOfDay(now) else now - days * 86_400_000L
}

/** Wraps the NetworkStats result so "loading" and "no permission" (null map) differ. */
private sealed interface TrafficLoad {
    data object Loading : TrafficLoad

    data class Done(val byUid: Map<Int, UidTraffic>?) : TrafficLoad
}

private data class TrafficRow(val uid: Int, val app: AppInfo?, val traffic: UidTraffic)

/**
 * The totals sections of the Activity tab over a chosen period: [ActivitySection.DATA] (per-app
 * bytes, NetworkStatsManager), [ActivitySection.HOSTS] (top destinations) and
 * [ActivitySection.TRACKERS] (tracker share per app), the last two from the event log. The
 * period is shared by the three.
 */
@Composable
fun TrafficSection(
    vm: GuardViewModel,
    section: ActivitySection,
    onOpenApp: (String) -> Unit,
    onOpenHost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Guard.tokens
    var range by rememberSaveable { mutableStateOf(TrafficRange.TODAY) }
    val apps by vm.repo.apps.collectAsStateWithLifecycle()
    val events by vm.repo.events.collectAsStateWithLifecycle()

    val load by
        produceState<TrafficLoad>(TrafficLoad.Loading, range) {
            value = TrafficLoad.Loading
            value =
                TrafficLoad.Done(
                    withContext(Dispatchers.IO) { vm.repo.inspector.trafficByUid(range.sinceMs()) }
                )
        }

    val rows =
        remember(load, apps) {
            val byUid = (load as? TrafficLoad.Done)?.byUid.orEmpty()
            val appByUid = apps.associateBy { it.uid }
            byUid.values
                .filter { it.total > 0 }
                .map { TrafficRow(it.uid, appByUid[it.uid], it) }
                .sortedByDescending { it.traffic.total }
                .take(50)
        }
    val rangeEvents =
        remember(events, range) {
            val since = range.sinceMs()
            events.filter { it.event.timeMs >= since }
        }
    val destinations = remember(rangeEvents) { Insights.topDestinations(rangeEvents, 20) }
    val trackerShare =
        remember(rangeEvents) {
            Insights.byApp(rangeEvents)
                .filter { it.trackerHits > 0 }
                .sortedByDescending { it.trackerHits.toFloat() / it.events }
                .take(15)
        }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Segmented(
                options = TrafficRange.entries.toList(),
                selected = range,
                label = { it.label },
                onSelect = { range = it },
            )
        }

        if (section == ActivitySection.DATA) {
            item {
                GuardCard(title = "Data per app") {
                    when (val l = load) {
                        TrafficLoad.Loading ->
                            Text(
                                "Loading…",
                                color = t.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        is TrafficLoad.Done ->
                            if (l.byUid == null) {
                                Text(
                                    "Per-app byte counts need READ_NETWORK_USAGE_HISTORY, which " +
                                        "Settings normally holds. Network usage history isn't " +
                                        "readable right now.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = t.muted,
                                )
                            } else if (rows.isEmpty()) {
                                Text(
                                    "No data used in this period.",
                                    color = t.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                val max = rows.first().traffic.total.coerceAtLeast(1)
                                for (r in rows) TrafficAppRow(r, max, onOpenApp)
                            }
                    }
                }
            }
        }

        if (section == ActivitySection.HOSTS) {
            item {
                GuardCard(title = "Top destinations") {
                    if (destinations.isEmpty()) {
                        Text(
                            "No connections logged in this period.",
                            color = t.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        val max = destinations.first().hits.coerceAtLeast(1)
                        for (d in destinations) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { onOpenHost(d.host) }
                                    .padding(vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        d.host,
                                        style =
                                            MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = t.mono
                                            ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${d.hits} ›",
                                        style =
                                            MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = t.mono
                                            ),
                                        color = t.muted,
                                    )
                                }
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Tag(if (d.apps == 1) "1 app" else "${d.apps} apps")
                                    if (d.tracker != null) Tag(d.tracker, emphasized = true)
                                    if (d.blocked > 0) Tag("${d.blocked} blocked", danger = true)
                                }
                                ShareBar(
                                    d.hits.toFloat() / max,
                                    color = if (d.tracker != null) t.text else t.muted,
                                )
                            }
                        }
                    }
                    Text(
                        "From the last ${events.size} monitored events. Per-destination bytes come with the BPF accounting phase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (section == ActivitySection.TRACKERS) {
            item {
                GuardCard(title = "Tracker share per app") {
                    if (trackerShare.isEmpty()) {
                        Text(
                            "No app contacted a known tracker in this period.",
                            color = t.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        for (a in trackerShare) {
                            val share = a.trackerHits.toFloat() / a.events.coerceAtLeast(1)
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = a.packageName != null) {
                                        a.packageName?.let(onOpenApp)
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    a.packageName?.let {
                                        AppIcon(it, Modifier.padding(end = 10.dp), size = 28.dp)
                                    }
                                    Text(
                                        a.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${(share * 100).toInt()}% · ${a.trackerHits}/${a.events}",
                                        style =
                                            MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = t.mono
                                            ),
                                        color = t.muted,
                                    )
                                }
                                ShareBar(share, Modifier.padding(top = 4.dp), color = t.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficAppRow(r: TrafficRow, max: Long, onOpenApp: (String) -> Unit) {
    val t = Guard.tokens
    val pkg = r.app?.packageName
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = pkg != null) { pkg?.let(onOpenApp) }
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pkg != null) AppIcon(pkg, Modifier.padding(end = 10.dp), size = 28.dp)
            Text(
                r.app?.label ?: vmUidLabel(r.uid),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatBytes(r.traffic.total),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
            )
        }
        ShareBar(r.traffic.total.toFloat() / max, Modifier.padding(vertical = 4.dp))
        Text(
            "↓ ${formatBytes(r.traffic.rxBytes)}  ↑ ${formatBytes(r.traffic.txBytes)}  ·  " +
                "Wi-Fi ${formatBytes(r.traffic.wifiBytes)}  ·  mobile ${formatBytes(r.traffic.mobileBytes)}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
            color = t.muted,
        )
    }
}

private fun vmUidLabel(uid: Int): String =
    when (uid) {
        -4 -> "Removed apps"
        -5 -> "Tethering"
        0 -> "root"
        1000 -> "Android system"
        else -> "uid $uid"
    }
