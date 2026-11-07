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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.HostPattern
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.Insights
import com.android.settings.ichthys.guard.model.RuleAction
import com.android.settings.ichthys.guard.model.RuleEngine
import com.android.settings.ichthys.guard.ui.theme.Guard

/**
 * One destination (host, or IP when no name was seen): what it is, which apps reached it and how
 * often, and the rules that apply. Every app row opens that app's page; the app pages and the
 * Activity tab's live log and top destinations (Hosts) all link here, so the views tie together.
 */
@Composable
fun DestinationScreen(
    vm: GuardViewModel,
    destination: String,
    onOpenApp: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Guard.tokens
    val repo = vm.repo
    val events by repo.events.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val rules by repo.rules.collectAsStateWithLifecycle()
    val hits = remember(events, destination) { events.filter { it.event.destination == destination } }
    val apps = remember(hits, destination) { Insights.appsForDestination(hits, destination) }
    val tracker = remember(destination) { repo.matcher.match(destination) }
    // IchthysOS: the system hosts file sinkholes this name for every app; the rule verdicts below
    // know nothing of it and would call it "ALLOWED".
    val sinkholed = remember(hits) { hits.any { it.isSinkholed() } }
    val pattern = HostPattern.normalize(destination)
    val globalRule =
        rules.rules.firstOrNull {
            it.appliesToAll && it.enabled && HostPattern.normalize(it.pattern) == pattern
        }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹ Back", Modifier.clearAndSetSemantics { contentDescription = "Back" }) }
            Text(
                destination,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = t.mono),
            )
            Row(Modifier.padding(top = 4.dp)) {
                if (tracker != null) {
                    Tag(tracker.name, emphasized = true)
                    Tag(tracker.category)
                } else {
                    Tag("not a known tracker")
                }
                if (sinkholed) Tag("system blocklist", danger = true)
                globalRule?.let {
                    // IchthysOS: a saved rule, not a block, until host rules are enforced.
                    Tag(
                        when {
                            it.action != RuleAction.BLOCK -> "allowed everywhere"
                            HostRules.ENFORCED -> "blocked everywhere"
                            else -> "block rule, not enforced"
                        },
                        danger = it.action == RuleAction.BLOCK && HostRules.ENFORCED,
                    )
                }
            }
        }

        item {
            GuardCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("${hits.size}", if (hits.size == 1) "event" else "events")
                    Stat("${apps.size}", if (apps.size == 1) "app" else "apps")
                    Stat(
                        "${hits.count { it.event.blocked }}",
                        "blocked",
                        color = if (hits.any { it.event.blocked }) t.danger else t.text,
                    )
                }
                if (hits.isNotEmpty()) {
                    Text(
                        "First seen ${formatDateTime(hits.minOf { it.event.timeMs })} · " +
                            "last ${formatAgo(hits.maxOf { it.event.timeMs })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // IchthysOS: host rules aren't enforced yet (HostRules). Only an existing rule is shown,
        // so it can be removed; no new ones are offered.
        if (HostRules.ENFORCED || globalRule != null) {
            item {
                GuardCard(title = "For every app") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (globalRule != null) {
                            OutlinedButton(onClick = { repo.removeRule(globalRule.id) }) {
                                Text("Remove rule")
                            }
                        } else {
                            OutlinedButton(onClick = { repo.blockHost(null, destination) }) {
                                Text("Block everywhere")
                            }
                            OutlinedButton(onClick = { repo.allowHost(null, destination) }) {
                                Text("Allow everywhere")
                            }
                        }
                    }
                    if (!HostRules.ENFORCED) {
                        Text(
                            HostRules.NOT_ENFORCED_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        item { SectionLabel("Apps that reached it") }
        if (apps.isEmpty()) {
            item { Text("No app in the current log.", color = t.muted) }
        }
        items(apps, key = { it.key }) { a ->
            val pkg = a.packageName
            val policy = pkg?.let { policies.policyFor(it) }
            val verdict =
                RuleEngine.decide(
                    pkg,
                    destination,
                    policy,
                    rules.rules,
                    tracker != null,
                    rules.lockdown,
                    repo.isPaused(),
                )
            // The app's own entry for exactly this destination ("=host" names just it too). A
            // broader pattern of its that merely covers this one ("example.com" on the page of
            // "ads.example.com") is not this page's to remove; the verdict tag still names it.
            val isThis = { h: String -> HostPattern.normalize(h).removePrefix("=") == pattern }
            val hasOwnEntry =
                policy != null &&
                    (policy.blockedHosts.any(isThis) || policy.allowedHosts.any(isThis))
            GuardCard(Modifier.clickable(enabled = pkg != null) { pkg?.let(onOpenApp) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pkg != null) AppIcon(pkg, Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            a.label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${quantity(a.hits, "event")} · last ${formatAgo(a.lastSeenMs)}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
                            color = t.muted,
                        )
                    }
                    val blocked = verdict.blocked || sinkholed
                    Text(
                        if (blocked) "BLOCKED" else "ALLOWED",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
                        color = if (blocked) t.danger else t.muted,
                    )
                }
                if (a.blocked > 0) {
                    Row(Modifier.padding(top = 4.dp)) { Tag("${a.blocked} blocked", danger = true) }
                }
                // IchthysOS: a sentence, not a one-line Tag (which clipped it mid-word).
                Text(
                    if (!verdict.blocked && sinkholed) SYSTEM_BLOCKLIST_VERDICT
                    else verdictText(verdict, tracker?.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (pkg != null) {
                    Row {
                        if (hasOwnEntry) {
                            TextButton(
                                onClick = {
                                    repo.updatePolicy(pkg) {
                                        it.copy(
                                            blockedHosts =
                                                it.blockedHosts.filterNot(isThis).toSet(),
                                            allowedHosts =
                                                it.allowedHosts.filterNot(isThis).toSet(),
                                        )
                                    }
                                }
                            ) {
                                Text("Reset for this app")
                            }
                        } else if (HostRules.ENFORCED) {
                            TextButton(onClick = { repo.blockHost(pkg, destination) }) {
                                Text("Block for this app")
                            }
                            TextButton(onClick = { repo.allowHost(pkg, destination) }) {
                                Text("Allow for this app")
                            }
                        }
                    }
                }
            }
        }

        item { SectionLabel("Recent events") }
        items(hits.takeLast(30).asReversed(), key = { it.event.seq }) { e ->
            EventRow(
                e,
                showApp = true,
                modifier =
                    Modifier.clickable(enabled = e.packageName != null) {
                        e.packageName?.let(onOpenApp)
                    },
            )
        }
    }
}
