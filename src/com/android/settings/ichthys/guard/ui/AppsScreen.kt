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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.AppPreset
import com.android.settings.ichthys.guard.model.BlockReason
import com.android.settings.ichthys.guard.model.LocationMode
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.VerifyRun
import com.android.settings.ichthys.guard.os.AppInfo
import com.android.settings.ichthys.guard.os.ShieldTable
import com.android.settings.ichthys.guard.ui.theme.Guard

/** IchthysOS: the Apps list's chips. */
enum class AppChip(val label: String) {
    ALL("All"),
    BLOCKED_NOW("Blocked now"),
    UNVERIFIED("Unverified"),
    UNTRUSTED("Untrusted"),
    CUSTOM("Custom"),
    SYSTEM("System apps"),
}

/**
 * Every app with its EFFECTIVE state now ("Blocked now · screen-off rule · 3 blocks today",
 * "Online · real location · verified 09:12", "Quarantined · pick a preset to release") and its
 * preset tag. Tap for the app's page.
 */
@Composable
fun AppsScreen(vm: GuardViewModel, nav: GuardNav, modifier: Modifier = Modifier) {
    val repo = vm.repo
    val apps by repo.apps.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val denyNow by repo.denyNow.collectAsStateWithLifecycle()
    val blockedToday by vm.blockedTodayByApp.collectAsStateWithLifecycle()
    val verify by repo.verify.collectAsStateWithLifecycle()
    val unfinished by repo.unfinishedReviews.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    var query by rememberSaveable { mutableStateOf("") }
    var chip by rememberSaveable { mutableStateOf(AppChip.ALL) }
    val t = Guard.tokens
    val quarantined = remember(apps) { repo.quarantinedApps().mapTo(HashSet()) { it.packageName } }
    val lastVerified = remember(verify) { verifiedAt(verify.runs) }
    val presetVerified = remember(verify) { presetVerifiedAt(verify.runs) }
    val now = System.currentTimeMillis()

    val rows =
        remember(apps, policies, denyNow, blockedToday, tick, query, chip, status, quarantined, lastVerified, unfinished) {
            apps
                .filter { a ->
                    (chip == AppChip.SYSTEM || !a.isSystem) &&
                        (query.isBlank() ||
                            a.label.contains(query, true) ||
                            a.packageName.contains(query, true))
                }
                .map { a ->
                    val p = policies.policyFor(a.packageName)
                    // Verified: a run for the app itself, or a passed "Verify my protection" run
                    // whose stand-in was this app's preset (same lists, same rule).
                    val verified = lastVerified[a.packageName] ?: presetVerified[p.preset]
                    AppRow(a, rowState(repo, a, p, denyNow[a.uid], blockedToday[a.packageName] ?: 0, a.packageName in quarantined, verified, status.pausedUntil > now, a.packageName in unfinished))
                }
                .filter { r ->
                    when (chip) {
                        AppChip.ALL, AppChip.SYSTEM -> true
                        AppChip.BLOCKED_NOW -> r.state.offline != null
                        AppChip.UNVERIFIED -> r.state.verifiedMs == null
                        AppChip.UNTRUSTED -> r.state.preset == AppPreset.UNTRUSTED || r.state.quarantined
                        AppChip.CUSTOM -> r.state.preset == AppPreset.CUSTOM && !r.state.policy.isDefault || r.state.differs
                    }
                }
                    .let { list ->
                    if (chip == AppChip.BLOCKED_NOW) list.sortedByDescending { it.state.blockedToday } else list
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
                    for (c in AppChip.entries) Chip(c.label, chip == c) { chip = c }
                }
                Text(
                    quantity(rows.size, "app"),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                    color = t.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // IchthysOS: an empty list says whether it is still loading or nothing fits.
        if (rows.isEmpty()) {
            item {
                Text(
                    when {
                        apps.isEmpty() -> "Loading apps…"
                        query.isNotBlank() -> "No app matches \"${query.trim()}\"."
                        chip != AppChip.ALL -> "No app fits this filter."
                        else -> "No apps to show."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(rows, key = { it.app.packageName }) { r ->
            Column(
                Modifier.fillMaxWidth()
                    .clickable { nav.openApp(r.app.packageName) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(r.app.packageName, Modifier.padding(end = 12.dp))
                    Text(
                        r.app.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val s = r.state
                    when {
                        s.quarantined -> Tag("QUARANTINED", danger = true)
                        s.preset != AppPreset.CUSTOM -> Tag(s.preset.label.uppercase(), emphasized = true)
                        !s.policy.isDefault -> Tag("CUSTOM")
                    }
                    if (s.differs) PresetDot(true)
                }
                Text(
                    r.state.line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.state.offline != null) t.text else t.muted,
                )
            }
            HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
        }
    }
}

private data class AppRow(val app: AppInfo, val state: RowState)

/** IchthysOS: the effective state the row shows. */
data class RowState(
    val policy: AppPolicy,
    val preset: AppPreset,
    /** Why the app is offline right now, or null when online. */
    val offline: BlockReason?,
    val blockedToday: Int,
    val quarantined: Boolean,
    val verifiedMs: Long?,
    val differs: Boolean,
    val location: LocationMode,
    val paused: Boolean,
    /** IchthysOS: quarantined, and its first-launch review was closed without a choice. */
    val reviewUnfinished: Boolean = false,
) {
    val line: String
        get() =
            buildList {
                    when {
                        quarantined && reviewUnfinished -> add("Quarantined · review not finished")
                        quarantined -> add("Quarantined · pick a preset to release")
                        policy.dormant -> add("Asleep · unused for weeks, no network")
                        offline != null -> add("Blocked now · ${offline.sentence}")
                        paused -> add("Online · rules paused")
                        policy.network == NetworkAccess.UNMETERED_ONLY -> add("Wi-Fi only")
                        else -> add("Online")
                    }
                    if (!quarantined) add(
                        when (location) {
                            LocationMode.REAL -> "real location"
                            LocationMode.FAKE -> "fake place"
                            LocationMode.NONE -> "no location"
                        }
                    )
                    if (blockedToday > 0) add(quantity(blockedToday, "block") + " today")
                    verifiedMs?.let { add("verified ${formatWhen(it)}") }
                }
                .joinToString(" · ")
}

private fun rowState(
    repo: GuardRepository,
    app: AppInfo,
    policy: AppPolicy,
    denied: BlockReason?,
    blockedToday: Int,
    quarantined: Boolean,
    verifiedMs: Long?,
    paused: Boolean,
    reviewUnfinished: Boolean,
): RowState {
    // IchthysOS: the service's deny chain is the truth; without it, the saved rule.
    val offline =
        denied
            ?: if (!repo.backend.isLive) repo.effectiveRule(app.packageName).let { if (it.access == NetworkAccess.DENY) it.reason else null }
            else null
    return RowState(
        policy = policy,
        preset = policy.preset,
        offline = offline,
        blockedToday = blockedToday,
        quarantined = quarantined,
        verifiedMs = verifiedMs,
        differs = policy.preset != AppPreset.CUSTOM && repo.presetDifferences(app.packageName).isNotEmpty(),
        location = repo.locationMode(app.packageName),
        paused = paused,
        reviewUnfinished = reviewUnfinished,
    )
}

/** When each app was last verified by a passed run for the app itself (newest first in [runs]). */
private fun verifiedAt(runs: List<VerifyRun>): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (r in runs) {
        if (!r.done || !r.passed) continue
        r.forPackage?.let { out.putIfAbsent(it, r.finishedMs) }
    }
    return out
}

/** When each preset was last a passed stand-in of a "Verify my protection" run. */
private fun presetVerifiedAt(runs: List<VerifyRun>): Map<AppPreset, Long> {
    val out = HashMap<AppPreset, Long>()
    for (r in runs) {
        if (!r.done || r.forPackage != null) continue
        for (p in r.phases) if (p.done && !p.failed) out.putIfAbsent(p.standIn, r.finishedMs)
    }
    return out
}
