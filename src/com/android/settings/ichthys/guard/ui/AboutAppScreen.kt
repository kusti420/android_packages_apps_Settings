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

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.AppTag
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.Insights
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.RiskFix
import com.android.settings.ichthys.guard.model.RiskScorer
import com.android.settings.ichthys.guard.os.LocalScan
import com.android.settings.ichthys.guard.os.OpUsage
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ScanResult(
    /** IchthysOS: null while the install scan is unknown (pending, or no service). */
    val trackers: List<String>?,
    val local: LocalScan?,
    val usage: List<OpUsage>?,
    /** IchthysOS: the scan's completeness and run-time code flags; null while unknown. */
    val info: com.android.settings.ichthys.guard.backend.ScanInfo? = null,
)

/**
 * IchthysOS: everything about one app that isn't a control: install scan, risk, permission usage,
 * first-seen domains, tags and sleep, system app info, reset identity. Reached from the app
 * page's footer.
 */
@Composable
fun AboutAppScreen(vm: GuardViewModel, nav: GuardNav, packageName: String, modifier: Modifier = Modifier) {
    val repo = vm.repo
    val ctx = LocalContext.current
    val t = Guard.tokens
    val policies by repo.policies.collectAsStateWithLifecycle()
    val byApp by vm.eventsByApp.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val auto by repo.automation.collectAsStateWithLifecycle()
    val app = repo.appFor(packageName)
    val policy = policies.policyFor(packageName)
    val events = byApp[packageName].orEmpty()
    val scope = rememberCoroutineScope()
    val scan by
        produceState<ScanResult?>(null, packageName) {
            value = null
            value =
                withContext(Dispatchers.IO) {
                    val info = repo.backend.getScanInfo(packageName)
                    ScanResult(
                        info?.knownTrackers,
                        repo.inspector.localScan(packageName),
                        app?.let { repo.inspector.permissionUsage(it) },
                        info,
                    )
                }
        }
    val facts by
        produceState(repo.riskFacts.value[packageName], packageName) {
            value = repo.riskFacts.value[packageName]
            if (value == null) value = withContext(Dispatchers.IO) { repo.riskFactsFor(packageName) }
        }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    // IchthysOS: cutting the network is confirmed first, and a fix applied here stays in the row
    // as done, so the tap has visible feedback (the score changing alone says little).
    var confirmBlock by rememberSaveable { mutableStateOf(false) }
    var applied by remember { mutableStateOf(setOf<RiskFix>()) }
    fun update(change: (com.android.settings.ichthys.guard.model.AppPolicy) -> com.android.settings.ichthys.guard.model.AppPolicy) =
        repo.updatePolicy(packageName, change)
    fun applyFix(fix: RiskFix) {
        when (fix) {
            RiskFix.STRICT_TRACKERS ->
                update {
                    it.copy(
                        trackerBlock = com.android.settings.ichthys.guard.model.TrackerBlockLevel.STRICT,
                        allowedHosts = it.allowedHosts + Insights.learnAllowlist(events, packageName),
                    )
                }
            RiskFix.BACKGROUND_OFF -> update { it.copy(backgroundNetworkOff = true) }
            RiskFix.SCREEN_OFF -> update { it.copy(screenOffBlock = true) }
            RiskFix.BLOCK_NETWORK -> update { it.copy(network = NetworkAccess.DENY) }
            RiskFix.REVIEW_PERMISSIONS ->
                ctx.startActivity(Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, packageName))
        }
        if (fix != RiskFix.REVIEW_PERMISSIONS) applied = applied + fix
    }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader(app?.label ?: packageName, nav.back, "About this app") }
        facts?.let { f ->
            item {
                val risk = RiskScorer.score(f, policy, events, repo.effectiveAccess(packageName))
                GuardCard(title = "Risk") {
                    Stat("${risk.score}", "${risk.level} risk", color = if (risk.isHigh) t.danger else t.text)
                    // IchthysOS: without the install scan the embedded trackers aren't counted.
                    if (f.trackers == null)
                        Text("Tracker scan not available yet, so embedded trackers aren't counted in this score.", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    for (factor in risk.factors.sortedByDescending { it.points }) {
                        KeyValue(factor.label, if (factor.points > 0) "+${factor.points}" else "")
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        val offered = risk.fixes.filter { it != RiskFix.STRICT_TRACKERS || HostRules.ENFORCED }
                        // A fix applied here leaves the score's list; it stays, disabled, as done.
                        (offered + applied).distinct().forEachIndexed { i, fix ->
                            val done = fix in applied
                            TextButton(
                                onClick = { if (fix == RiskFix.BLOCK_NETWORK) confirmBlock = true else applyFix(fix) },
                                enabled = !done,
                            ) {
                                Text(if (done) doneLabel(fix) else fix.label, color = if (done) t.muted else if (i == 0) t.accent else t.text)
                            }
                        }
                    }
                }
            }
        }
        item {
            GuardCard(title = "Install scan") {
                val s = scan
                if (s == null) {
                    Text("Scanning…", color = t.muted)
                } else {
                    val trackers = s.trackers
                    val incomplete = s.info?.complete == false
                    Text(
                        when {
                            // IchthysOS: T1: part of the code couldn't be read: unknown, not clean.
                            trackers == null && incomplete -> "Trackers unknown: the scan couldn't read all of this app's code"
                            trackers == null && status.live -> "Tracker scan not finished yet"
                            trackers == null -> "Tracker scan unavailable: the Guard service isn't reachable"
                            trackers.isEmpty() -> "No known tracker SDKs embedded"
                            incomplete -> "At least ${quantity(trackers.size, "tracker SDK")} embedded"
                            else -> "${quantity(trackers.size, "tracker SDK")} embedded"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!trackers.isNullOrEmpty())
                        Text(trackers.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = t.muted)
                    if (incomplete && !trackers.isNullOrEmpty())
                        Text("The scan couldn't read all of the code, so there may be more.", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    if (s.info?.loadsCodeAtRuntime == true) {
                        Text("Loads code at runtime", style = MaterialTheme.typography.titleMedium, color = t.danger, modifier = Modifier.padding(top = 8.dp))
                        Text("It ships code as data or uses a dex class loader: code it downloads or unpacks later is never scanned, trackers included.", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    }
                    s.local?.let { l ->
                        Text("${quantity(l.dangerousPermissions.size, "dangerous permission")} requested", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        if (l.dangerousPermissions.isNotEmpty())
                            Text(l.dangerousPermissions.joinToString(" · "), style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), color = t.muted)
                        Text("${l.exportedComponents} of ${l.totalComponents} components exported", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        Text("Exported components can be started by other apps.", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    }
                }
            }
        }
        item {
            GuardCard(title = "Permission usage") {
                val usage = scan?.usage
                when {
                    scan == null -> Text("Loading…", color = t.muted)
                    usage == null ->
                        Text("Usage history isn't readable right now (GET_APP_OPS_STATS). See Security & privacy › Privacy dashboard.", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    usage.isEmpty() -> Text("No camera, microphone, location, clipboard or contacts access recorded.", color = t.muted)
                    else -> for (u in usage) KeyValue(u.label, formatAgo(u.lastAccessMs) + if (u.durationMs > 0) " · ${u.durationMs / 1000}s" else "")
                }
            }
        }
        item {
            GuardCard(title = "First seen domains") {
                val first = Insights.firstSeen(events, packageName)
                if (first.isEmpty()) Text("None yet.", color = t.muted)
                for ((host, time) in first.take(30)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { nav.openHost(host) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        repo.matcher.match(host)?.let { Tag(it.name, emphasized = true) }
                        Text(formatDateTime(time), style = MaterialTheme.typography.labelSmall, color = t.muted)
                    }
                }
            }
        }
        item {
            GuardCard(title = "Schedules and sleep") {
                SectionLabel("Tags")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (tag in AppTag.entries) {
                        Chip(tag.label, tag in policy.tags) {
                            update { it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag) }
                        }
                    }
                }
                val matching = auto.profiles.filter { it.matches(packageName, policy.tags) }
                Text(
                    if (matching.isEmpty()) "No schedule covers this app."
                    else matching.joinToString(" · ") { "${it.name} ${it.windowText}" + if (it.enabled) "" else " (off)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ToggleRow("Never put to sleep", policy.keepActive, "Keeps its network even when unused for weeks") { on ->
                    update { it.copy(keepActive = on) }
                }
                if (policy.dormant) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Asleep: unused for weeks, network cut.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = t.danger)
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { repo.restoreDormant(packageName) } }) { Text("Wake") }
                    }
                }
            }
        }
        // IchthysOS: saved host rules of this app, kept but not enforced (HostRules).
        if (policy.allowedHosts.isNotEmpty() || policy.blockedHosts.isNotEmpty()) {
            item {
                GuardCard(title = "Per-app host rules · coming") {
                    Text(HostRules.NOT_ENFORCED_NOTE, style = MaterialTheme.typography.bodySmall, color = t.muted)
                    for (h in policy.blockedHosts.sorted()) KeyValue(h, "block")
                    for (h in policy.allowedHosts.sorted()) KeyValue(h, "allow")
                    TextButton(onClick = { update { it.copy(allowedHosts = emptySet(), blockedHosts = emptySet()) } }) { Text("Forget these host rules") }
                }
            }
        }
        item {
            Column {
                TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }) {
                    Text("Open system app info")
                }
                if (app?.isSystem != true) {
                    TextButton(onClick = { confirmReset = true }) { Text("Reset identity", color = t.danger) }
                    Text(
                        resetMessage ?: "Clears its data and gives it a new Android ID, as on a new phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
    if (confirmBlock) {
        val label = app?.label ?: packageName
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            containerColor = t.raised,
            titleContentColor = t.text,
            textContentColor = t.muted,
            title = { Text("Block $label's network?") },
            text = { Text("It loses every network connection until you set Internet to Allow again on its page. Nothing else about it changes.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBlock = false
                        applyFix(RiskFix.BLOCK_NETWORK)
                    }
                ) {
                    Text("Block", color = t.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel", color = t.text) } },
        )
    }
    if (confirmReset) {
        val label = app?.label ?: packageName
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = t.raised,
            titleContentColor = t.text,
            textContentColor = t.muted,
            title = { Text("Reset $label's identity?") },
            text = { Text("All of its data is deleted: accounts, settings, files and caches. It also gets a new Android ID, so it starts as on a new phone and can't link itself to before. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        resetMessage = "Resetting…"
                        repo.resetIdentity(packageName) { cleared, ssaid ->
                            // The result arrives on a binder thread; UI state changes on main.
                            scope.launch(Dispatchers.Main) {
                                resetMessage =
                                    when {
                                        !cleared -> "Couldn't clear its data."
                                        ssaid -> "Done: data cleared, new Android ID."
                                        else -> "Data cleared. The Android ID reset isn't available on this build yet."
                                    }
                            }
                        }
                    }
                ) {
                    Text("Reset", color = t.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = t.text) } },
        )
    }
}

/** What a fix's button says once it was applied from this page. */
private fun doneLabel(fix: RiskFix): String =
    when (fix) {
        RiskFix.STRICT_TRACKERS -> "Strict blocking on"
        RiskFix.BACKGROUND_OFF -> "Background off"
        RiskFix.SCREEN_OFF -> "Screen-off block on"
        RiskFix.BLOCK_NETWORK -> "Blocked"
        RiskFix.REVIEW_PERMISSIONS -> fix.label
    }
