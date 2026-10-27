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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.AppFilter
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.LocalNetwork
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.RiskScore
import com.android.settings.ichthys.guard.model.RiskScorer
import com.android.settings.ichthys.guard.model.TrackerBlockLevel
import com.android.settings.ichthys.guard.ui.theme.Guard

/** Every app with its policy summary, searchable and filterable. Tap for the detail page. */
@Composable
fun AppsScreen(vm: GuardViewModel, onOpenApp: (String) -> Unit, modifier: Modifier = Modifier) {
    val apps by vm.repo.apps.collectAsStateWithLifecycle()
    val policies by vm.repo.policies.collectAsStateWithLifecycle()
    val byApp by vm.eventsByApp.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val riskFacts by vm.repo.riskFacts.collectAsStateWithLifecycle()
    fun riskOf(pkg: String): RiskScore? =
        riskFacts[pkg]?.let {
            RiskScorer.score(
                it,
                policies.policyFor(pkg),
                byApp[pkg].orEmpty(),
                vm.repo.effectiveAccess(pkg),
            )
        }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val t = Guard.tokens
    val recentCutoff = System.currentTimeMillis() - 3600_000L

    val shown =
        apps
            .filter { a ->
                val evs = byApp[a.packageName].orEmpty()
                val p = policies.policyFor(a.packageName)
                (showSystem || !a.isSystem) &&
                    (query.isBlank() ||
                        a.label.contains(query, true) ||
                        a.packageName.contains(query, true)) &&
                    when (filter) {
                        AppFilter.ALL -> true
                        AppFilter.HAS_TRACKERS -> evs.any { it.isTracker }
                        AppFilter.BLOCKED ->
                            p.network == NetworkAccess.DENY ||
                                vm.repo.effectiveAccess(a.packageName) == NetworkAccess.DENY ||
                                evs.any { it.event.blocked }
                        AppFilter.RECENTLY_ACTIVE -> evs.any { it.event.timeMs >= recentCutoff }
                        AppFilter.CUSTOMIZED -> !p.isDefault
                        AppFilter.HIGH_RISK -> riskOf(a.packageName)?.isHigh == true
                    }
            }
            .let { list ->
                when (filter) {
                    AppFilter.RECENTLY_ACTIVE ->
                        list.sortedByDescending {
                            byApp[it.packageName]?.lastOrNull()?.event?.timeMs ?: 0
                        }
                    AppFilter.HIGH_RISK -> list.sortedByDescending { riskOf(it.packageName)?.score }
                    else -> list
                }
            }

    LazyColumn(modifier) {
        item {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                    Chip("All", filter == AppFilter.ALL) { filter = AppFilter.ALL }
                    Chip("Has trackers", filter == AppFilter.HAS_TRACKERS) {
                        filter = AppFilter.HAS_TRACKERS
                    }
                    Chip("Blocked", filter == AppFilter.BLOCKED) { filter = AppFilter.BLOCKED }
                    Chip("Recently active", filter == AppFilter.RECENTLY_ACTIVE) {
                        filter = AppFilter.RECENTLY_ACTIVE
                    }
                    Chip("Customized", filter == AppFilter.CUSTOMIZED) {
                        filter = AppFilter.CUSTOMIZED
                    }
                    Chip("High risk", filter == AppFilter.HIGH_RISK) {
                        filter = AppFilter.HIGH_RISK
                    }
                    Chip("System apps", showSystem) { showSystem = !showSystem }
                }
                Text(
                    quantity(shown.size, "app"),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                    color = t.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // IchthysOS: an empty list said only "0 apps". Say whether the list is still loading or
        // the search and filter leave nothing.
        if (shown.isEmpty()) {
            item {
                Text(
                    when {
                        apps.isEmpty() -> "Loading apps…"
                        query.isNotBlank() -> "No app matches \"${query.trim()}\"."
                        filter != AppFilter.ALL -> "No app fits this filter."
                        else -> "No apps to show. Turn on System apps to see them."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(shown, key = { it.packageName }) { app ->
            val policy = policies.policyFor(app.packageName)
            val evs = byApp[app.packageName].orEmpty()
            val trackerHosts = evs.filter { it.isTracker }.mapNotNull { it.tracker }.distinct().size
            Column(
                Modifier.fillMaxWidth()
                    .clickable { onOpenApp(app.packageName) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName, Modifier.padding(end = 12.dp))
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (
                        status.lockdown &&
                            vm.repo.effectiveAccess(app.packageName) == NetworkAccess.DENY &&
                            policy.network != NetworkAccess.DENY
                    )
                        Tag("LOCKDOWN", danger = true)
                    if (trackerHosts > 0) Tag(quantity(trackerHosts, "tracker"), emphasized = true)
                    if (policy.dormant) Tag("PAUSED", danger = true)
                    riskOf(app.packageName)?.let { r ->
                        Tag(
                            "RISK ${r.score}",
                            emphasized = r.score >= RiskScore.MEDIUM,
                            danger = r.isHigh,
                        )
                    }
                }
                Text(
                    policySummary(policy),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                if (evs.isNotEmpty()) {
                    Text(
                        "${quantity(evs.size, "event")} · last ${formatAgo(evs.last().event.timeMs)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                        color = t.muted,
                    )
                }
            }
            HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
        }
    }
}

fun policySummary(p: AppPolicy): String =
    // IchthysOS: tracker levels and host lists aren't enforced yet (HostRules); don't call them
    // blocks.
    if (p.isDefault) {
        if (HostRules.ENFORCED) "Shielded · monitored, known trackers blocked"
        else "Shielded · monitored"
    } else {
        buildList {
                add(
                    when (p.network) {
                        NetworkAccess.ALLOW -> "Network on"
                        NetworkAccess.UNMETERED_ONLY -> "Wi-Fi only"
                        NetworkAccess.DENY -> "No network"
                    }
                )
                if (HostRules.ENFORCED)
                    add(
                        when (p.trackerBlock) {
                            TrackerBlockLevel.OFF -> "trackers allowed"
                            TrackerBlockLevel.KNOWN_TRACKERS -> "trackers blocked"
                            TrackerBlockLevel.STRICT -> "strict"
                        }
                    )
                if (p.contactsAllowed) add("real contacts")
                if (p.clipboardAllowed) add("clipboard at any time")
                if (p.sensorsExempt) add("exact sensors")
                if (p.screenOffBlock) add("offline when screen off")
                if (p.backgroundNetworkOff) add("no background network")
                if (p.localNetwork == LocalNetwork.ALLOW) add("local network")
                if (p.localNetwork == LocalNetwork.BLOCK) add("no local network")
                if (p.tags.isNotEmpty()) add(p.tags.joinToString("/") { it.label.lowercase() })
                if (p.dormant) add("paused (unused)")
                if (HostRules.ENFORCED) {
                    if (p.blockedHosts.isNotEmpty()) add("${p.blockedHosts.size} hosts blocked")
                    if (p.allowedHosts.isNotEmpty()) add("${p.allowedHosts.size} hosts allowed")
                } else if (p.blockedHosts.isNotEmpty() || p.allowedHosts.isNotEmpty()) {
                    add("host rules not enforced")
                }
            }
            .joinToString(" · ")
    }
