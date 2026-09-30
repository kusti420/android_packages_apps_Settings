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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.Insights
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.OsSettingKey
import com.android.settings.ichthys.guard.model.TrackerBlockLevel
import com.android.settings.ichthys.guard.os.LocalScan
import com.android.settings.ichthys.guard.os.OpUsage
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ScanResult(
    val trackers: List<String>,
    val local: LocalScan?,
    val usage: List<OpUsage>?,
)

/** Everything about one app: network, trackers, scan, live log, privacy exceptions, usage. */
@Composable
fun AppDetailScreen(
    vm: GuardViewModel,
    packageName: String,
    onBack: () -> Unit,
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
                        repo.backend.getScan(packageName).orEmpty(),
                        repo.inspector.localScan(packageName),
                        app?.let { repo.inspector.permissionUsage(it) },
                    )
                }
        }
    fun update(change: (AppPolicy) -> AppPolicy) = repo.updatePolicy(packageName, change)

    LazyColumn(
        modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Apps") }
            }
            Text(app?.label ?: packageName, style = MaterialTheme.typography.headlineMedium)
            Text(
                packageName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                color = t.muted,
            )
            Row(Modifier.padding(top = 6.dp)) {
                if (policy.isDefault) Tag("SHIELDED", emphasized = true)
                if (app?.isSystem == true) Tag("SYSTEM")
                app?.let { Tag("uid ${it.uid}") }
                if (status.lockdown && repo.effectiveAccess(packageName) == NetworkAccess.DENY)
                    Tag("LOCKDOWN", danger = true)
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
        item {
            GuardCard(title = "Install scan") {
                val s = scan
                if (s == null) {
                    Text("Scanning…", color = t.muted)
                } else {
                    Text(
                        "${s.trackers.size} tracker SDKs embedded",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (s.trackers.isNotEmpty())
                        Text(
                            s.trackers.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = t.muted,
                        )
                    s.local?.let { l ->
                        Text(
                            "${l.dangerousPermissions.size} dangerous permissions requested",
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
            GuardCard(title = "Privacy exceptions") {
                ToggleRow(
                    "Can see contacts",
                    policy.contactsAllowed,
                    "Exempt from the contacts lockdown",
                ) { on ->
                    update { it.copy(contactsAllowed = on) }
                }
                ToggleRow(
                    "Clipboard without a tap",
                    policy.clipboardAllowed,
                    "May read the clipboard in the background",
                ) { on ->
                    update { it.copy(clipboardAllowed = on) }
                }
                ToggleRow(
                    "Exact sensor data",
                    policy.sensorsExempt,
                    if (OsSettingKey.SENSORS.writtenByGuard) "Skips sensor anti-fingerprinting"
                    else "Saved; applied once the OS side lands (#30)",
                ) { on ->
                    update { it.copy(sensorsExempt = on) }
                }
                ToggleRow(
                    "Unrestricted background",
                    policy.unrestrictedBackground,
                    if (OsSettingKey.UNRESTRICTED_BG.writtenByGuard) "Keeps running without limits"
                    else "Saved; applied once the OS side lands (#39)",
                ) { on ->
                    update { it.copy(unrestrictedBackground = on) }
                }
                ToggleRow(
                    "Keep online during lockdown",
                    packageName in repo.rules.value.lockdown.allowlist,
                ) { on ->
                    repo.setLockdownAllowed(packageName, on)
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
                    EventRow(e, showApp = false, modifier = Modifier.padding(horizontal = 0.dp))
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
                            } else {
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
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
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
                    TextButton(onClick = { update { AppPolicy(packageName) } }) {
                        Text("Reset to Shielded")
                    }
                }
            }
        }
    }
}
