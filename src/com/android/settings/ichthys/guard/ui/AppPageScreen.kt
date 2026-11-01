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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.AppPreset
import com.android.settings.ichthys.guard.model.LocalNetwork
import com.android.settings.ichthys.guard.model.LocationMode
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.PresetControl
import com.android.settings.ichthys.guard.model.ScanMode
import com.android.settings.ichthys.guard.model.ScreenOffMode
import com.android.settings.ichthys.guard.os.ShieldGroup
import com.android.settings.ichthys.guard.os.ShieldTable
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IchthysOS: one app's page. The preset picker on top writes everything in one go; below it the
 * NETWORK and DATA cards show the effective state with a dot on every control that differs from
 * the preset; the EVIDENCE card shows this app's last events with their reason, the last probe
 * verdict as its stand-in, and "Verify this app". The footer resets to the preset or opens the
 * app's About page (install scan, risk, permission usage, first-seen domains, system app info,
 * reset identity).
 */
@Composable
fun AppPageScreen(
    vm: GuardViewModel,
    nav: GuardNav,
    packageName: String,
    modifier: Modifier = Modifier,
    /**
     * IchthysOS: opened by the first-launch review's Customize (GuardReviewActivity): the app
     * stays quarantined while the user sets it up, and only the review bar's Done releases it.
     */
    firstLaunchReview: Boolean = false,
) {
    val repo = vm.repo
    val t = Guard.tokens
    val policies by repo.policies.collectAsStateWithLifecycle()
    val byApp by vm.eventsByApp.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val denyNow by repo.denyNow.collectAsStateWithLifecycle()
    val rules by repo.rules.collectAsStateWithLifecycle()
    val auto by repo.automation.collectAsStateWithLifecycle()
    val verify by repo.verify.collectAsStateWithLifecycle()
    val unfinished by repo.unfinishedReviews.collectAsStateWithLifecycle()
    val blockedToday by vm.blockedTodayByApp.collectAsStateWithLifecycle()
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val app = repo.appFor(packageName)
    val policy = policies.policyFor(packageName)
    val events = byApp[packageName].orEmpty()
    val now = System.currentTimeMillis()

    // The controls' effective values, re-read after every change here or elsewhere.
    val controls = remember(version, tick, policy, auto, rules) { repo.appControls(packageName) }
    val differs = remember(controls, policy) { policy.preset.spec?.let { controls.differences(it) } ?: emptySet() }
    val fakeOn = remember(tick) { repo.fakePlaceOn() }
    val quarantined = remember(app, version) { app != null && repo.quarantinedApps().any { it.packageName == packageName } }
    val restrictable by
        produceState(true, packageName) {
            value = app?.let { a -> withContext(Dispatchers.IO) { repo.backend.isRestrictable(a.uid) } } ?: true
        }
    val lanDenied by
        produceState<Boolean?>(null, packageName, policy.localNetwork) {
            kotlinx.coroutines.delay(400) // the behaviour push is asynchronous
            value = app?.let { a -> withContext(Dispatchers.IO) { repo.backend.isLanDenied(a.uid) } }
        }
    var confirmPreset by remember { mutableStateOf<AppPreset?>(null) }
    fun update(change: (AppPolicy) -> AppPolicy) = repo.updatePolicy(packageName, change)
    fun changed() {
        version++
    }
    val offline = denyNow[app?.uid]
    val lastVerified = remember(verify) { verify.runs.firstOrNull { it.done && it.forPackage == packageName } }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = nav.back) { Text("‹ Back") } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName, Modifier.padding(end = 12.dp), size = 48.dp)
                Column {
                    Text(app?.label ?: packageName, style = MaterialTheme.typography.headlineMedium)
                    Text(packageName, style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), color = t.muted)
                }
            }
            // The effective state now, in the Apps row's words.
            Text(
                when {
                    quarantined && firstLaunchReview -> "Quarantined · opens when you tap Done"
                    quarantined && packageName in unfinished -> "Quarantined · review not finished"
                    quarantined -> "Quarantined · pick a preset to release"
                    policy.dormant -> "Asleep · unused for weeks, no network"
                    offline != null -> "Blocked now · ${offline.sentence}"
                    status.pausedUntil > now -> "Online · rules paused"
                    policy.network == NetworkAccess.UNMETERED_ONLY -> "Wi-Fi only"
                    else -> "Online"
                } + (blockedToday[packageName]?.let { " · ${quantity(it, "block")} today" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = if (offline != null || quarantined) t.danger else t.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (app?.isSystem == true)
                Text("System app", style = MaterialTheme.typography.bodySmall, color = t.muted)
        }

        // ---- preset picker ----
        item {
            GuardCard(title = "Preset") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (p in AppPreset.PICKER) {
                        Chip(p.label, policy.preset == p) {
                            if (p == AppPreset.CUSTOM) repo.applyPreset(packageName, AppPreset.CUSTOM)
                            else confirmPreset = p
                        }
                    }
                }
                Text(
                    policy.preset.summary +
                        if (differs.isNotEmpty()) " · ${quantity(differs.size, "control")} changed (marked)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (quarantined) {
                    Text(
                        // IchthysOS: during the review, Done releases it, not a preset.
                        if (firstLaunchReview) "Changes apply right away; the app opens when you tap Done."
                        else "Picking a preset releases the app from quarantine and opens it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // ---- NETWORK ----
        item {
            GuardCard(title = "Network") {
                ControlLabel("Internet", PresetControl.NETWORK in differs)
                Segmented(
                    NetworkAccess.entries,
                    if (restrictable) policy.network else NetworkAccess.ALLOW,
                    label = {
                        when (it) {
                            NetworkAccess.ALLOW -> "Allow"
                            NetworkAccess.UNMETERED_ONLY -> "Wi-Fi only"
                            NetworkAccess.DENY -> "Block"
                        }
                    },
                    onSelect = { a -> update { it.copy(network = a) }; changed() },
                    enabled = restrictable,
                )
                if (!restrictable)
                    Note("This system app keeps its network: calls, messages and the connection itself depend on it.")
                else if (policy.network == NetworkAccess.UNMETERED_ONLY)
                    Note("Only on networks that aren't metered, usually Wi-Fi. Mobile data and metered Wi-Fi are blocked.")
                else if (app?.isSystem == true && policy.network == NetworkAccess.DENY)
                    Note("Blocking a system app can break the OS.", danger = true)
                if (!status.live) Note("Saved; enforced once the Guard service is reachable.")

                if (restrictable) {
                    ControlLabel("Screen off", PresetControl.SCREEN_OFF in differs, Modifier.padding(top = 12.dp))
                    Segmented(
                        ScreenOffMode.entries,
                        controls.screenOff,
                        label = { it.label },
                        onSelect = { m -> repo.setScreenOffMode(packageName, m); changed() },
                    )
                    Note(
                        when (controls.screenOff) {
                            ScreenOffMode.FOLLOW ->
                                if (auto.screenOffAll && app?.isSystem != true) "The screen-off rule is on: offline while the screen is off or locked."
                                else "The screen-off rule is off: online with the screen off. Turn the rule on in Guard's settings."
                            ScreenOffMode.OFFLINE -> "Offline while the screen is off or locked, whatever the rule says."
                            ScreenOffMode.STAY_ONLINE -> "Stays online with the screen off, like a messenger, even when the rule is on."
                        }
                    )

                    ControlLabel("Background", PresetControl.BACKGROUND in differs, Modifier.padding(top = 12.dp))
                    ToggleRow(
                        "Network only while on screen",
                        policy.backgroundNetworkOff,
                        "Offline whenever the app isn't in front",
                    ) { on -> update { it.copy(backgroundNetworkOff = on) }; changed() }

                    ControlLabel("Local network", PresetControl.LAN in differs, Modifier.padding(top = 8.dp))
                    Segmented(
                        listOf(LocalNetwork.ALLOW, LocalNetwork.BLOCK, LocalNetwork.DEFAULT),
                        policy.localNetwork,
                        label = {
                            when (it) {
                                LocalNetwork.DEFAULT -> "Default"
                                LocalNetwork.ALLOW -> "Allow"
                                LocalNetwork.BLOCK -> "Block"
                            }
                        },
                        onSelect = { l -> update { it.copy(localNetwork = l) }; changed() },
                    )
                    Note(
                        // IchthysOS: without the service isLanDenied() falls back to false, which
                        // would read as "can reach devices"; say it is unknown instead.
                        if (!status.live) "Devices on your Wi-Fi: unknown while the Guard service isn't reachable."
                        else when (lanDenied) {
                            null -> "Devices on your Wi-Fi: checking…"
                            true ->
                                if (policy.localNetwork == LocalNetwork.DEFAULT) "Blocked: apps installed after setup can't reach devices on your network until you allow it."
                                else "Can't reach devices on your network (printers, casting, NAS)."
                            false -> "Can reach devices on your network as stock Android allows."
                        }
                    )
                    ControlLabel("Lockdown", PresetControl.LOCKDOWN_KEEP in differs, Modifier.padding(top = 8.dp))
                    ToggleRow(
                        "Keep online during lockdown",
                        controls.lockdownKeep,
                        "Stays reachable when Lockdown cuts the network",
                    ) { on -> repo.setLockdownAllowed(packageName, on); changed() }
                }
            }
        }

        // ---- DATA ----
        item {
            GuardCard(title = "Data") {
                ControlLabel("Location", PresetControl.LOCATION in differs)
                Segmented(
                    LocationMode.entries,
                    controls.location,
                    label = { it.label },
                    onSelect = { m -> repo.setLocationMode(packageName, m); changed() },
                )
                Note(
                    when (controls.location) {
                        LocationMode.REAL -> "Gets your real position, also while the fake place is on."
                        LocationMode.FAKE ->
                            if (fakeOn) "Gets the fake place instead of your position; Wi-Fi, cell and Bluetooth scans come back empty."
                            else "The fake place is off (Guard settings › Fake place), so this app gets your real position."
                        LocationMode.NONE -> "Never gets a fix, as if indoors; scans find nothing."
                    }
                )
                ControlLabel("Wi-Fi & Bluetooth scans", PresetControl.SCANS in differs, Modifier.padding(top = 12.dp))
                Segmented(
                    ScanMode.entries,
                    if (controls.location == LocationMode.NONE) ScanMode.EMPTY else controls.scans,
                    label = { it.label },
                    onSelect = { m -> repo.setScanMode(packageName, m); changed() },
                    enabled = controls.location != LocationMode.NONE,
                )
                if (controls.location == LocationMode.NONE)
                    Note("Empty while location is None.")
                val personal = remember(version, tick) { sheetSummary(repo, packageName, ShieldGroup.PERSONAL, app?.isSystem == true) }
                val device = remember(version, tick) { sheetSummary(repo, packageName, ShieldGroup.DEVICE, app?.isSystem == true) }
                val radio = remember(version, tick) { sheetSummary(repo, packageName, ShieldGroup.RADIO, app?.isSystem == true) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NavRow(ShieldGroup.PERSONAL.label, personal, { nav.openData(packageName, ShieldGroup.PERSONAL) }, Modifier.weight(1f))
                    PresetDot(PresetControl.CONTACTS in differs || PresetControl.OTHER_SHIELDS in differs)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavRow(ShieldGroup.DEVICE.label, device, { nav.openData(packageName, ShieldGroup.DEVICE) }, Modifier.weight(1f))
                    PresetDot(PresetControl.SENSORS in differs)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavRow(ShieldGroup.RADIO.label, radio, { nav.openData(packageName, ShieldGroup.RADIO) }, Modifier.weight(1f))
                    PresetDot(PresetControl.NFC in differs)
                }
            }
        }

        // ---- EVIDENCE ----
        item {
            GuardCard(title = "Evidence") {
                val proofs = remember(journal) { journal.entries.filter { it.packageName == packageName && it.isShield }.takeLast(3).asReversed() }
                if (events.isEmpty() && proofs.isEmpty())
                    Text("No network activity or shield decision recorded for this app yet.", color = t.muted)
                for (e in events.takeLast(8).asReversed()) {
                    EventRow(
                        e,
                        showApp = false,
                        modifier = Modifier.clickable { nav.openHost(e.event.destination) }.padding(horizontal = 0.dp),
                    )
                }
                for (p in proofs) LatestJournalRow(p, now) {}
                val stand = lastVerified
                Text(
                    stand?.let { "Last verified ${formatWhen(it.finishedMs, now)} with the tester as its stand-in · ${it.verdictLine}" }
                        ?: "Not verified yet: run the tester app with this app's setup to see what such an app really gets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stand?.passed == false) t.danger else t.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(onClick = { nav.openVerify(packageName) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Verify this app")
                }
            }
        }

        // ---- footer ----
        item {
            Column {
                if (policy.preset != AppPreset.CUSTOM && differs.isNotEmpty()) {
                    TextButton(onClick = { repo.applyPreset(packageName, policy.preset); changed() }) {
                        Text("Reset to ${policy.preset.label} preset")
                    }
                }
                if (!policy.isDefault) {
                    TextButton(
                        onClick = {
                            // IchthysOS: an asleep app is also suspended; lift that too, or it
                            // stays unopenable with a default policy.
                            val wasDormant = policy.dormant
                            update { AppPolicy(packageName) }
                            if (wasDormant) scope.launch(Dispatchers.IO) { repo.restoreDormant(packageName) }
                            changed()
                        }
                    ) {
                        Text("Reset to defaults")
                    }
                }
                TextButton(onClick = { nav.openAbout(packageName) }) { Text("About this app ›") }
                Text(
                    "Install scan, risk, permission usage, first-seen domains, system app info, reset identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }

    confirmPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmPreset = null },
            containerColor = t.raised,
            titleContentColor = t.text,
            textContentColor = t.muted,
            title = { Text("Apply the ${preset.label} preset?") },
            text = {
                Text(
                    preset.summary +
                        ". Every control of this page is set at once; anything you changed before is replaced." +
                        if (quarantined && !firstLaunchReview) " The app is released from quarantine." else ""
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmPreset = null
                        scope.launch(Dispatchers.IO) {
                            repo.applyPreset(packageName, preset)
                            if (quarantined && !firstLaunchReview) releaseQuarantine(repo, packageName)
                        }
                        changed()
                    }
                ) {
                    Text("Apply", color = t.accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmPreset = null }) { Text("Cancel", color = t.text) } },
        )
    }
}

@Composable
private fun Note(text: String, danger: Boolean = false) {
    val t = Guard.tokens
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (danger) t.danger else t.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** "Contacts picked · 3 of 9 shielded" for a sheet's row. */
private fun sheetSummary(repo: com.android.settings.ichthys.guard.data.GuardRepository, pkg: String, group: ShieldGroup, isSystem: Boolean): String {
    val ctx = repo.contextForLists()
    val rows = ShieldTable.group(group).filter { !(isSystem && it.keys.any { k -> k in ShieldTable.SYSTEM_EXEMPT_KEYS }) }
    if (rows.isEmpty()) return "Doesn't apply to a system app"
    val shielded = rows.count { it.isProtected(ctx, pkg) }
    val picked = group == ShieldGroup.PERSONAL && ShieldTable.isContactsPicked(ctx, pkg)
    return buildList {
            if (picked) add("Contacts picked")
            add(if (shielded == rows.size) "All shielded" else "$shielded of ${rows.size} shielded")
            if (group == ShieldGroup.DEVICE) ShieldTable.storageScope(ctx, pkg)?.let { add("storage limited") }
        }
        .joinToString(" · ")
}

/**
 * Lifts Guard's quarantine of [pkg] and starts it (the same path the first-launch sheet uses).
 * IchthysOS: [detail] is the journal line; false when the quarantine couldn't be lifted.
 */
internal fun releaseQuarantine(
    repo: com.android.settings.ichthys.guard.data.GuardRepository,
    pkg: String,
    detail: String = "Released with a preset",
): Boolean {
    val ctx = repo.contextForLists()
    try {
        val failed =
            ctx.packageManager.setPackagesSuspended(arrayOf(pkg), false, null, null, null as android.content.pm.SuspendDialogInfo?)
        if (failed.isNullOrEmpty()) {
            // IchthysOS: released, so an unfinished first-launch review is finished too.
            repo.clearUnfinishedReview(pkg)
            repo.journal(com.android.settings.ichthys.guard.model.JournalKind.RELEASED, detail, pkg)
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return true
        }
        android.util.Log.w("IchthysGuard", "could not lift the quarantine of $pkg")
    } catch (e: RuntimeException) {
        android.util.Log.w("IchthysGuard", "could not lift the quarantine of $pkg", e)
    }
    return false
}
