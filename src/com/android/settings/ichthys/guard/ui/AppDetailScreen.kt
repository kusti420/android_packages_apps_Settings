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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.AppTag
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.Insights
import com.android.settings.ichthys.guard.model.LocalNetwork
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.RiskFix
import com.android.settings.ichthys.guard.model.RiskScorer
import com.android.settings.ichthys.guard.model.TrackerBlockLevel
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
)

/** Everything about one app: network, trackers, scan, data access, usage, live log. */
@Composable
fun AppDetailScreen(
    vm: GuardViewModel,
    packageName: String,
    onBack: () -> Unit,
    onOpenHost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = vm.repo
    val ctx = LocalContext.current
    val t = Guard.tokens
    val policies by repo.policies.collectAsStateWithLifecycle()
    val byApp by vm.eventsByApp.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val app = repo.appFor(packageName)
    val policy = policies.policyFor(packageName)
    val events = byApp[packageName].orEmpty()
    val scan by
        produceState<ScanResult?>(null, packageName) {
            value =
                withContext(Dispatchers.IO) {
                    ScanResult(
                        repo.backend.getScan(packageName),
                        repo.inspector.localScan(packageName),
                        app?.let { repo.inspector.permissionUsage(it) },
                    )
                }
        }
    fun update(change: (AppPolicy) -> AppPolicy) = repo.updatePolicy(packageName, change)
    val auto by repo.automation.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val facts by
        produceState(repo.riskFacts.value[packageName], packageName) {
            if (value == null)
                value = withContext(Dispatchers.IO) { repo.riskFactsFor(packageName) }
        }
    // Re-read after every local-network change; the service resolves the new-app default.
    val lanDenied by
        produceState<Boolean?>(null, packageName, policy.localNetwork) {
            kotlinx.coroutines.delay(400) // the behaviour push is asynchronous
            value =
                app?.let { a -> withContext(Dispatchers.IO) { repo.backend.isLanDenied(a.uid) } }
        }
    var confirmReset by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    fun applyFix(fix: RiskFix) {
        when (fix) {
            RiskFix.STRICT_TRACKERS ->
                update {
                    it.copy(
                        trackerBlock = TrackerBlockLevel.STRICT,
                        allowedHosts =
                            it.allowedHosts + Insights.learnAllowlist(events, packageName),
                    )
                }
            RiskFix.BACKGROUND_OFF -> update { it.copy(backgroundNetworkOff = true) }
            RiskFix.SCREEN_OFF -> update { it.copy(screenOffBlock = true) }
            RiskFix.BLOCK_NETWORK -> update { it.copy(network = NetworkAccess.DENY) }
            RiskFix.REVIEW_PERMISSIONS ->
                ctx.startActivity(
                    Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS)
                        .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                )
        }
    }

    LazyColumn(
        modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName, Modifier.padding(end = 12.dp), size = 48.dp)
                Column {
                    Text(app?.label ?: packageName, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        packageName,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                        color = t.muted,
                    )
                }
            }
            Row(Modifier.padding(top = 6.dp)) {
                if (policy.isDefault) Tag("SHIELDED", emphasized = true)
                if (app?.isSystem == true) Tag("SYSTEM")
                app?.let { Tag("uid ${it.uid}") }
                if (status.lockdown && repo.effectiveAccess(packageName) == NetworkAccess.DENY)
                    Tag("LOCKDOWN", danger = true)
                if (policy.dormant) Tag("PAUSED", danger = true)
                for (p in repo.profilesFor(packageName)) Tag(p.name.uppercase(), emphasized = true)
            }
        }
        facts?.let { f ->
            item {
                val risk = RiskScorer.score(f, policy, events, repo.effectiveAccess(packageName))
                GuardCard(title = "Risk") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stat(
                            "${risk.score}",
                            "${risk.level} risk",
                            Modifier.weight(1f),
                            color = if (risk.isHigh) t.danger else t.text,
                        )
                    }
                    // IchthysOS: without the install scan the embedded trackers aren't counted,
                    // so a "Low" score here may only mean "not scanned yet".
                    if (f.trackers == null) {
                        Text(
                            "Tracker scan not available yet, so embedded trackers aren't " +
                                "counted in this score.",
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    for (factor in risk.factors.sortedByDescending { it.points }) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                factor.label,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (factor.points > 0)
                                Text(
                                    "+${factor.points}",
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = t.mono
                                        ),
                                    color = t.muted,
                                )
                        }
                    }
                    // One-tap fixes; the biggest one is the card's accented action.
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        risk.fixes.forEachIndexed { i, fix ->
                            TextButton(onClick = { applyFix(fix) }) {
                                Text(fix.label, color = if (i == 0) t.accent else t.text)
                            }
                        }
                    }
                }
            }
        }
        item {
            GuardCard(title = "Network access") {
                Segmented(
                    NetworkAccess.entries,
                    policy.network,
                    label = {
                        when (it) {
                            NetworkAccess.ALLOW -> "Allow"
                            NetworkAccess.UNMETERED_ONLY -> "Wi-Fi only"
                            NetworkAccess.DENY -> "Block"
                        }
                    },
                    onSelect = { a -> update { it.copy(network = a) } },
                )
                if (app?.isSystem == true && policy.network == NetworkAccess.DENY) {
                    Text(
                        "Blocking a system app can break the OS.",
                        color = t.danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (!status.live)
                    Text(
                        "Saved; enforced once the platform service is present.",
                        color = t.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
            }
        }
        item {
            GuardCard(title = "Network behaviour") {
                val ruleCovers =
                    auto.screenOffAll &&
                        app?.isSystem != true &&
                        packageName !in auto.screenOffExempt
                ToggleRow(
                    "No network while screen off",
                    policy.screenOffBlock || ruleCovers,
                    when {
                        ruleCovers && !policy.screenOffBlock -> "On through the screen-off rule"
                        else -> "Offline while the screen is off or locked"
                    },
                ) { on ->
                    update { it.copy(screenOffBlock = on) }
                }
                if (auto.screenOffAll && app?.isSystem != true) {
                    ToggleRow(
                        "Exempt from the screen-off rule",
                        packageName in auto.screenOffExempt,
                        "Stays online with the screen off, like a messenger",
                    ) { on ->
                        repo.updateAutomation {
                            it.copy(
                                screenOffExempt =
                                    if (on) it.screenOffExempt + packageName
                                    else it.screenOffExempt - packageName
                            )
                        }
                    }
                }
                ToggleRow(
                    "Background network off",
                    policy.backgroundNetworkOff,
                    "Network only while the app is on screen",
                ) { on ->
                    update { it.copy(backgroundNetworkOff = on) }
                }
                val rules by repo.rules.collectAsStateWithLifecycle()
                ToggleRow(
                    "Keep online during lockdown",
                    packageName in rules.lockdown.allowlist,
                    "Stays reachable when Lockdown cuts the network",
                ) { on ->
                    repo.setLockdownAllowed(packageName, on)
                }
                SectionLabel("Local network", Modifier.padding(top = 8.dp))
                Segmented(
                    LocalNetwork.entries,
                    policy.localNetwork,
                    label = {
                        when (it) {
                            LocalNetwork.DEFAULT -> "Default"
                            LocalNetwork.ALLOW -> "Allow"
                            LocalNetwork.BLOCK -> "Block"
                        }
                    },
                    onSelect = { l -> update { it.copy(localNetwork = l) } },
                )
                Text(
                    // IchthysOS: without the service isLanDenied() falls back to false, which
                    // would read as "can reach devices"; say it is unknown instead.
                    if (!status.live) "Devices on your Wi-Fi: unknown while the Guard service " +
                        "isn't reachable."
                    else when (lanDenied) {
                        null -> "Devices on your Wi-Fi: checking…"
                        true ->
                            if (policy.localNetwork == LocalNetwork.DEFAULT)
                                "Blocked: apps installed after setup can't reach devices on your " +
                                    "network until you allow it."
                            else "Can't reach devices on your network (printers, casting, NAS)."
                        false -> "Can reach devices on your network as stock Android allows."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // IchthysOS: per-app tracker levels aren't enforced yet (HostRules), so the card says so
        // instead of offering a choice that does nothing.
        if (!HostRules.ENFORCED) {
            item {
                GuardCard(title = "Tracker blocking") {
                    Text(
                        HostRules.NOT_ENFORCED_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                }
            }
        } else {
            item {
                GuardCard(title = "Tracker blocking") {
                    Segmented(
                        TrackerBlockLevel.entries,
                        policy.trackerBlock,
                        label = {
                            when (it) {
                                TrackerBlockLevel.OFF -> "Off"
                                TrackerBlockLevel.KNOWN_TRACKERS -> "Known"
                                TrackerBlockLevel.STRICT -> "Strict"
                            }
                        },
                        onSelect = { l -> update { it.copy(trackerBlock = l) } },
                    )
                    Text(
                        when (policy.trackerBlock) {
                            TrackerBlockLevel.OFF -> "Only the system-wide blocklist applies."
                            TrackerBlockLevel.KNOWN_TRACKERS ->
                                "Hosts in the tracker database are blocked for this app."
                            TrackerBlockLevel.STRICT ->
                                "Only hosts you allowed are reachable (${policy.allowedHosts.size} allowed). Allow hosts from the log below."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    val learned = Insights.learnAllowlist(events, packageName) - policy.allowedHosts
                    if (learned.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                update {
                                    it.copy(
                                        trackerBlock = TrackerBlockLevel.STRICT,
                                        allowedHosts = it.allowedHosts + learned,
                                    )
                                }
                            }
                        ) {
                            Text(
                                if (policy.trackerBlock == TrackerBlockLevel.STRICT)
                                    "Allow ${learned.size} more hosts it used"
                                else "Strict with the ${learned.size} hosts it needs"
                            )
                        }
                        Text(
                            learned.sorted().take(6).joinToString(" · ") +
                                if (learned.size > 6) " …" else "",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                            color = t.muted,
                        )
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
                    // IchthysOS: a missing scan used to read "0 tracker SDKs embedded".
                    val trackers = s.trackers
                    if (trackers == null) {
                        Text(
                            if (status.live) "Tracker scan not finished yet"
                            else "Tracker scan unavailable: the Guard service isn't reachable",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(
                            if (trackers.isEmpty()) "No known tracker SDKs embedded"
                            else "${quantity(trackers.size, "tracker SDK")} embedded",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (!trackers.isNullOrEmpty())
                        Text(
                            trackers.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                        )
                    s.local?.let { l ->
                        Text(
                            "${quantity(l.dangerousPermissions.size, "dangerous permission")} " +
                                "requested",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (l.dangerousPermissions.isNotEmpty())
                            Text(
                                l.dangerousPermissions.joinToString(" · "),
                                style =
                                    MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                                color = t.muted,
                            )
                        Text(
                            "${l.exportedComponents} of ${l.totalComponents} components exported",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "Exported components can be started by other apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                        )
                    }
                }
            }
        }
        item {
            DataAccessCard(
                packageName,
                policy,
                isSystem = app?.isSystem == true,
                onPolicyChange = { change -> update(change) },
            )
        }
        item {
            GuardCard(title = "Automation") {
                SectionLabel("Tags")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (tag in AppTag.entries) {
                        Chip(tag.label, tag in policy.tags) {
                            update {
                                it.copy(
                                    tags = if (tag in it.tags) it.tags - tag else it.tags + tag
                                )
                            }
                        }
                    }
                }
                val matching = auto.profiles.filter { it.matches(packageName, policy.tags) }
                Text(
                    if (matching.isEmpty()) "No profile covers this app."
                    else
                        matching.joinToString(" · ") {
                            "${it.name} ${it.windowText}" + if (it.enabled) "" else " (off)"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ToggleRow(
                    "Never auto-tighten",
                    policy.keepActive,
                    "Keep its network even when unused for weeks",
                ) { on ->
                    update { it.copy(keepActive = on) }
                }
                if (policy.dormant) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Paused: unused for weeks, network cut.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = t.danger,
                        )
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) { repo.restoreDormant(packageName) }
                            }
                        ) {
                            Text("Restore")
                        }
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
                        Text(
                            "Usage history isn't readable right now (GET_APP_OPS_STATS). See Security & privacy › Privacy dashboard.",
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                        )
                    usage.isEmpty() ->
                        Text(
                            "No camera, microphone, location, clipboard or contacts access recorded.",
                            color = t.muted,
                        )
                    else ->
                        for (u in usage) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(u.label, Modifier.weight(1f))
                                Text(
                                    formatAgo(u.lastAccessMs) +
                                        if (u.durationMs > 0) " · ${u.durationMs / 1000}s" else "",
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = t.mono
                                        ),
                                    color = t.muted,
                                )
                            }
                        }
                }
            }
        }
        item {
            GuardCard(title = "Live log") {
                if (events.isEmpty()) Text("No network activity seen yet.", color = t.muted)
                for (e in events.takeLast(8).asReversed()) {
                    EventRow(
                        e,
                        showApp = false,
                        modifier =
                            Modifier.clickable { onOpenHost(e.event.destination) }
                                .padding(horizontal = 0.dp),
                    )
                    if (e.event.host != null) {
                        Row {
                            val h: String = e.event.host
                            if (h in policy.blockedHosts || h in policy.allowedHosts) {
                                TextButton(
                                    onClick = {
                                        update {
                                            it.copy(
                                                blockedHosts = it.blockedHosts - h,
                                                allowedHosts = it.allowedHosts - h,
                                            )
                                        }
                                    }
                                ) {
                                    Text("Reset host")
                                }
                            } else if (HostRules.ENFORCED) {
                                TextButton(onClick = { repo.blockHost(packageName, h) }) {
                                    Text("Block")
                                }
                                TextButton(onClick = { repo.allowHost(packageName, h) }) {
                                    Text("Allow")
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            GuardCard(title = "First seen domains") {
                val first = Insights.firstSeen(events, packageName)
                if (first.isEmpty()) Text("None yet.", color = t.muted)
                for ((host, time) in first.take(30)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onOpenHost(host) }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            host,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        repo.matcher.match(host)?.let { Tag(it.name, emphasized = true) }
                        Text(
                            formatDateTime(time),
                            style = MaterialTheme.typography.labelSmall,
                            color = t.muted,
                        )
                    }
                }
            }
        }
        item {
            Column {
                TextButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            )
                        )
                    }
                ) {
                    Text("Open system app info")
                }
                if (!policy.isDefault) {
                    TextButton(
                        onClick = {
                            // IchthysOS: a paused (auto-tightened) app is also suspended; lift
                            // that too, or it stays unopenable with a Shielded policy.
                            val wasDormant = policy.dormant
                            update { AppPolicy(packageName) }
                            if (wasDormant)
                                scope.launch(Dispatchers.IO) { repo.restoreDormant(packageName) }
                        }
                    ) {
                        Text("Reset to Shielded")
                    }
                }
                if (app?.isSystem != true) {
                    TextButton(onClick = { confirmReset = true }) {
                        Text("Reset identity", color = t.danger)
                    }
                    Text(
                        resetMessage
                            ?: "Clears its data and gives it a new Android ID, as on a new phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
    if (confirmReset) {
        val label = app?.label ?: packageName
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = t.raised,
            titleContentColor = t.text,
            textContentColor = t.muted,
            title = { Text("Reset $label's identity?") },
            text = {
                Text(
                    "All of its data is deleted: accounts, settings, files and caches. It also " +
                        "gets a new Android ID, so it starts as on a new phone and can't link " +
                        "itself to before. This can't be undone."
                )
            },
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
                                        else ->
                                            "Data cleared. The Android ID reset isn't " +
                                                "available on this build yet."
                                    }
                            }
                        }
                    }
                ) {
                    Text("Reset", color = t.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = t.text) }
            },
        )
    }
}
