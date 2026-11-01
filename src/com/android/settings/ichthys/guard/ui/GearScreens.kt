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

import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.backend.Health
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.AlertType
import com.android.settings.ichthys.guard.model.AppTag
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.JournalKind
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.Profile
import com.android.settings.ichthys.guard.model.RuleAction
import com.android.settings.ichthys.guard.os.ShieldTable
import com.android.settings.ichthys.guard.ui.theme.Guard
import java.text.DateFormatSymbols
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IchthysOS: the gear page: everything global, one row each, every row a page of its own. Every
 * control of the old Rules and Automation pages has a home here (nothing dropped).
 */
@Composable
fun GearScreen(vm: GuardViewModel, nav: GuardNav, modifier: Modifier = Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val rules by repo.rules.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    val masters = remember(tick) { repo.masterShieldsOn() }
    val fakeOn = remember(tick) { repo.fakePlaceOn() }
    val realCount = remember(tick) { repo.realLocationApps().size }
    val quarantined = remember(apps) { repo.quarantinedApps().size }
    val asleep = remember(policies) { policies.policies.values.count { it.dormant } }
    val hostRules = remember(policies, rules) { rules.rules.size + policies.policies.values.count { it.allowedHosts.isNotEmpty() || it.blockedHosts.isNotEmpty() } }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Guard settings", nav.back) }
        item {
            GuardCard {
                NavRow("Master shields", "What every app gets of your data unless its page says otherwise", { nav.openPage("shields") }, trailing = "${masters.first} of ${masters.second} on")
                NavRow("Shared values", "Home city, trips and units: weather, sunset and Night Light use them", { nav.openPage("shared") })
                NavRow("Fake place", if (fakeOn) "On · ${quantity(realCount, "app")} see the real location" else "Off · every app gets the real location", { nav.openPage("fakeplace") })
                NavRow("Screen-off rule", if (auto.screenOffAll) "On · ${quantity(auto.screenOffExempt.size, "app")} stay online" else "Off", { nav.openPage("screenoff") })
                NavRow("Schedules", auto.activeProfiles().takeIf { it.isNotEmpty() }?.let { "Active now: " + it.joinToString { p -> p.name } } ?: "${quantity(auto.profiles.count { it.enabled }, "schedule")} on", { nav.openPage("schedules") })
                NavRow("Lockdown keeps online", "Set on each app's page", { nav.openPage("lockdown") }, trailing = quantity(rules.lockdown.allowlist.size, "app"))
                NavRow("New apps", if (quarantined > 0) "${quantity(quarantined, "app")} waiting for a preset" else "Quarantined until you pick a preset · local network blocked", { nav.openPage("newapps") })
                NavRow("Notifications", "Ongoing and silent notifications folded into chips or one row", { nav.openPage("quiet") })
                NavRow("Alerts", "Camera, mic and location in the background, wake-ups, boot starts · kept in the journal", { nav.openPage("alerts") }, trailing = "${AlertType.entries.count { auto.alertOn(it) }} of ${AlertType.entries.size}")
                NavRow("Put unused apps to sleep", if (auto.autoTighten) "On · after ${auto.autoTightenDays} days" + (if (asleep > 0) " · ${quantity(asleep, "app")} asleep" else "") else "Off", { nav.openPage("sleep") })
                NavRow("Service health", if (status.problems.isEmpty()) "All parts working" else status.problems.first(), { nav.openPage("health") }, danger = status.problems.isNotEmpty())
                NavRow("Backup", "Export or import presets, every shield list, the fake place, schedules and storage scopes", { nav.openPage("backup") })
                NavRow("Per-app tracker host rules — coming", if (hostRules > 0) "${quantity(hostRules, "saved rule")}, kept, not enforced yet" else "Saved rules are kept until the engine lands", { nav.openPage("trackers") })
            }
        }
    }
}

/** One gear sub-page by id. */
@Composable
fun GearSubPage(vm: GuardViewModel, nav: GuardNav, id: String, modifier: Modifier = Modifier) {
    when (id) {
        "shields" -> MasterShieldsPage(vm, nav, modifier)
        "fakeplace" -> FakePlacePage(vm, nav, modifier)
        "screenoff" -> ScreenOffPage(vm, nav, modifier)
        "schedules" -> SchedulesPage(vm, nav, modifier)
        "lockdown" -> LockdownKeepsPage(vm, nav, modifier)
        "newapps" -> NewAppsPage(vm, nav, modifier)
        "alerts" -> AlertsPage(vm, nav, modifier)
        "sleep" -> SleepPage(vm, nav, modifier)
        "health" -> HealthPage(vm, nav, modifier)
        "backup" -> BackupPage(vm, nav, modifier)
        "trackers" -> TrackerRulesPage(vm, nav, modifier)
        else -> GearScreen(vm, nav, modifier)
    }
}

// ---- Master shields ----------------------------------------------------------------------------

@Composable
private fun MasterShieldsPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val resolver = LocalContext.current.contentResolver
    val t = Guard.tokens
    var version by remember { mutableIntStateOf(0) }
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Master shields", nav.back, "What apps get of your data unless you give one real access on its own page. System apps and your default phone and SMS apps are exempt.") }
        item {
            GuardCard {
                for ((section, rows) in com.android.settings.ichthys.guard.os.SHIELD_SECTIONS) {
                    SectionLabel(section, Modifier.padding(top = 12.dp))
                    for (row in rows) {
                        val states = remember(version, tick, row) { row.switches.map { it.isOn(resolver) } }
                        val allOn = states.all { it }
                        ToggleRow(
                            row.label,
                            allOn,
                            when {
                                allOn -> row.protectedText
                                states.any { it } -> "Partly on: switch on to protect all of it"
                                else -> "Off: every app gets real access"
                            },
                        ) { on ->
                            repo.setShields(row.switches, on)
                            version++
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Evidence of these shields arrives in Activity › Journal as apps query contacts, calendar, call log or texts; a verification run proves each one with the tester app.",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}

// ---- Fake place -------------------------------------------------------------------------------

@Composable
private fun FakePlacePage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val context = LocalContext.current
    val t = Guard.tokens
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    var version by remember { mutableIntStateOf(0) }
    val on = remember(tick, version) { repo.fakePlaceOn() }
    val name = remember(tick, version) { android.provider.Settings.Secure.getString(context.contentResolver, ShieldTable.KEY_SPOOF_FAKE_NAME) }
    val real = remember(tick, version) { repo.realLocationApps() }
    val apps by repo.apps.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val lastFake = remember(journal) { journal.entries.lastOrNull { it.shield == com.android.settings.ichthys.guard.model.ShieldProof.LOCATION_FAKE } }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Fake place", nav.back) }
        item {
            GuardCard {
                ToggleRow(
                    "Fake place for every app",
                    on,
                    if (on) "Apps get ${name ?: "the fake place"} instead of your position, and no Wi-Fi, cell or Bluetooth scans. Apps set to Real on their page are the exception."
                    else "Off: every app gets your real position.",
                ) { v ->
                    com.android.settings.ichthys.guard.os.ShieldSwitch(ShieldTable.KEY_SPOOF_ON, com.android.settings.ichthys.guard.model.SettingsTable.SECURE).setOn(context.contentResolver, v)
                    version++
                }
                Text(
                    "The place itself (and your home city and trips) is set in Settings › IchthysOS › Shared values.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                TextButton(onClick = { context.startActivity(SharedValuesActivity.intent(context)) }) { Text("Open Shared values") }
                lastFake?.let {
                    Text("Last fake fix delivered ${formatWhen(it.timeMs)} to ${it.appLabel ?: "an app"}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), color = t.muted)
                }
            }
        }
        item {
            GuardCard(title = "See the real location · ${real.size}") {
                if (real.isEmpty()) Text("No app. Set Location: Real on an app's page.", color = t.muted)
                for (pkg in real.sorted()) {
                    val label = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                    AppLine(pkg, label, nav.openApp) {
                        TextButton(onClick = { repo.setLocationMode(pkg, com.android.settings.ichthys.guard.model.LocationMode.FAKE); version++ }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

// ---- Screen-off rule --------------------------------------------------------------------------

@Composable
private fun ScreenOffPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Screen-off rule", nav.back) }
        item {
            GuardCard {
                ToggleRow(
                    "No network while the screen is off",
                    auto.screenOffAll,
                    "Every user app is offline while the screen is off or locked, except the apps below. An app's page can set it to Offline or Stay online on its own.",
                ) { on -> repo.updateAutomation { it.copy(screenOffAll = on) } }
            }
        }
        item {
            GuardCard(title = "Stay online · ${auto.screenOffExempt.count { p -> apps.any { it.packageName == p } }}") {
                val exempt = apps.filter { it.packageName in auto.screenOffExempt }
                if (exempt.isEmpty()) Text("No app.", color = t.muted)
                for (a in exempt) {
                    AppLine(a.packageName, a.label, nav.openApp) {
                        TextButton(onClick = { repo.setScreenOffMode(a.packageName, com.android.settings.ichthys.guard.model.ScreenOffMode.FOLLOW) }) { Text("Remove") }
                    }
                }
                Text("Common messengers stay online by default. Add more from an app's page (Screen off: Stay online).", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
    }
}

// ---- Schedules (ex Profiles) ------------------------------------------------------------------

@Composable
private fun SchedulesPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val active = auto.activeProfiles().map { it.id }.toSet()
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Schedules", nav.back, "While a schedule is on, apps with its tags lose their network, or keep it only while on screen. Tag apps on their About page. Starts and ends are written to the journal.") }
        item {
            GuardCard {
                auto.profiles.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
                    ProfileEditor(
                        p,
                        active = p.id in active,
                        taggedApps = apps.count { a -> p.matches(a.packageName, policies.policyFor(a.packageName).tags) },
                        onChange = { next -> repo.updateAutomation { s -> s.copy(profiles = s.profiles.map { if (it.id == p.id) next else it }) } },
                        onDelete =
                            if (Profile.PREMADE.any { it.id == p.id }) null
                            else { { repo.updateAutomation { s -> s.copy(profiles = s.profiles.filter { it.id != p.id }) } } },
                    )
                }
                TextButton(
                    onClick = {
                        repo.updateAutomation { s ->
                            s.copy(
                                profiles =
                                    s.profiles +
                                        Profile(
                                            GuardRepository.newId(),
                                            "Custom ${s.profiles.size - 1}",
                                            startMinute = 12 * 60,
                                            endMinute = 13 * 60,
                                            blockTags = setOf(AppTag.SOCIAL),
                                        )
                            )
                        }
                    }
                ) {
                    Text("Add schedule")
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(p: Profile, active: Boolean, taggedApps: Int, onChange: (Profile) -> Unit, onDelete: (() -> Unit)?) {
    val t = Guard.tokens
    val ctx = LocalContext.current
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                    if (active) Tag("ACTIVE", emphasized = true)
                }
                Text("${p.windowText} · ${daysText(p.days)} · ${quantity(taggedApps, "app")}", style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono), color = t.muted)
            }
            Switch(checked = p.enabled, onCheckedChange = { onChange(p.copy(enabled = it)) })
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
            for (tag in AppTag.entries) {
                Chip(tag.label, tag in p.blockTags) {
                    onChange(p.copy(blockTags = if (tag in p.blockTags) p.blockTags - tag else p.blockTags + tag))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { TimePickerDialog(ctx, { _, h, m -> onChange(p.copy(startMinute = h * 60 + m)) }, p.startMinute / 60, p.startMinute % 60, true).show() }) {
                Text("Starts %02d:%02d".format(p.startMinute / 60, p.startMinute % 60))
            }
            TextButton(onClick = { TimePickerDialog(ctx, { _, h, m -> onChange(p.copy(endMinute = h * 60 + m)) }, p.endMinute / 60, p.endMinute % 60, true).show() }) {
                Text("Ends %02d:%02d".format(p.endMinute / 60, p.endMinute % 60))
            }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        }
        // What the schedule does to matching apps: no network at all, or only while on screen.
        Segmented(
            listOf(true, false),
            p.network == NetworkAccess.DENY,
            label = { block -> if (block) "No network" else "Only on screen" },
            onSelect = { block ->
                onChange(
                    if (block) p.copy(network = NetworkAccess.DENY, backgroundNetworkOff = false)
                    else p.copy(network = NetworkAccess.ALLOW, backgroundNetworkOff = true)
                )
            },
        )
    }
}

private fun daysText(days: Set<Int>): String {
    if (days.isEmpty() || days.size == 7) return "every day"
    val names = DateFormatSymbols.getInstance().shortWeekdays
    val order = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
    return order.filter { it in days }.joinToString(" ") { names[it] }
}

// ---- Lockdown keeps online ---------------------------------------------------------------------

@Composable
private fun LockdownKeepsPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val rules by repo.rules.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Lockdown keeps online", nav.back, "These apps keep their network when Lockdown is on. Set on each app's page (Network › Keep online during lockdown); the Messenger preset sets it.") }
        item {
            GuardCard(title = "Apps · ${rules.lockdown.allowlist.size}") {
                val kept = apps.filter { it.packageName in rules.lockdown.allowlist }
                if (kept.isEmpty()) Text("No app keeps its network in lockdown.", color = t.muted)
                for (a in kept) AppLine(a.packageName, a.label, nav.openApp) { Text("›", color = t.muted) }
                TextButton(onClick = { pick = !pick }) { Text(if (pick) "Hide the full list" else "Choose from all apps") }
                if (pick) {
                    apps.sortedWith(compareBy({ it.packageName !in rules.lockdown.allowlist }, { it.label.lowercase() })).forEach { a ->
                        Row(Modifier.fillMaxWidth().clickable { repo.setLockdownAllowed(a.packageName, a.packageName !in rules.lockdown.allowlist) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = a.packageName in rules.lockdown.allowlist, onCheckedChange = { repo.setLockdownAllowed(a.packageName, it) })
                            Text(a.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---- New apps ---------------------------------------------------------------------------------

@Composable
private fun NewAppsPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val apps by repo.apps.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val waiting = remember(apps, journal) { repo.quarantinedApps() }
    val scope = rememberCoroutineScope()
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("New apps", nav.back) }
        item {
            GuardCard {
                Text("An app you install is quarantined until you open it and pick a preset: it can't run jobs, alarms or services meanwhile. Its local network is blocked until you allow it. Apps from Ichthys Apps and the ROM's own are left alone.", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
        item {
            GuardCard(title = "Waiting · ${waiting.size}") {
                if (waiting.isEmpty()) Text("No app is waiting.", color = t.muted)
                for (a in waiting) {
                    AppLine(a.packageName, a.label, nav.openApp) {
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { releaseQuarantine(repo, a.packageName) } }) { Text("Release as is") }
                    }
                }
                Text("Open an app to pick its preset; that releases it too.", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
    }
}

// ---- Alerts -----------------------------------------------------------------------------------

@Composable
private fun AlertsPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val recent = remember(journal) { journal.entries.asReversed().filter { it.journal == JournalKind.ALERT }.take(8) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Alerts", nav.back, "Alerts only: Guard notifies you and never blocks on these. Every alert is kept in Activity › Journal.") }
        item {
            GuardCard {
                for (type in AlertType.entries) {
                    ToggleRow(type.label, auto.alertOn(type), type.summary) { on ->
                        repo.updateAutomation { it.copy(alertMask = if (on) it.alertMask or type.bit else it.alertMask and type.bit.inv()) }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                GuardCard(title = "Recent") {
                    val now = System.currentTimeMillis()
                    for (j in recent) LatestJournalRow(j, now) { j.packageName?.let(nav.openApp) }
                }
            }
        }
    }
}

// ---- Sleep (ex auto-tighten) ------------------------------------------------------------------

@Composable
private fun SleepPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Put unused apps to sleep", nav.back) }
        item {
            GuardCard {
                ToggleRow(
                    "Sleep after ${auto.autoTightenDays} days unused",
                    auto.autoTighten,
                    "An app you haven't used for ${auto.autoTightenDays} days loses its network and is paused; opening it shows Guard's review. Off unless you turn it on.",
                ) { on -> repo.updateAutomation { it.copy(autoTighten = on) } }
                val dormant = apps.filter { policies.policyFor(it.packageName).dormant }
                if (dormant.isNotEmpty()) SectionLabel("Asleep · ${dormant.size}", Modifier.padding(top = 8.dp))
                for (a in dormant) {
                    AppLine(a.packageName, a.label, nav.openApp) {
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { repo.restoreDormant(a.packageName) } }) { Text("Wake") }
                    }
                }
                if (auto.autoTighten) {
                    // IchthysOS: the check reads usage stats for every app and can take a while.
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            message = null
                            scope.launch {
                                val paused = try { withContext(Dispatchers.IO) { repo.runMaintenance() } } finally { checking = false }
                                message = if (paused.isEmpty()) "No unused apps to put to sleep." else "Put to sleep: " + paused.joinToString()
                            }
                        },
                    ) {
                        Text(if (checking) "Checking…" else "Check now")
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.muted) }
            }
        }
    }
}

// ---- Service health ---------------------------------------------------------------------------

@Composable
private fun HealthPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val status by vm.status.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val denyNow by repo.denyNow.collectAsStateWithLifecycle()
    val events by repo.events.collectAsStateWithLifecycle()
    val failures = remember(journal) { journal.entries.asReversed().filter { it.journal == JournalKind.FIREWALL_FAILED }.take(5) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Service health", nav.back, "What the ichthys_guard platform service reports about itself.") }
        item {
            GuardCard {
                val h = status.health
                fun bit(flag: Int) = status.live && (h and flag) != 0
                KeyValue("Service reachable", if (status.live) "yes" else "NO", danger = !status.live)
                KeyValue("Firewall chain applied", if (bit(Health.FIREWALL)) "yes" else "NO", danger = !bit(Health.FIREWALL))
                KeyValue("Wi-Fi-only rules applied", if (bit(Health.METERED)) "yes" else "NO", danger = !bit(Health.METERED))
                KeyValue("Traffic monitor registered", if (bit(Health.MONITOR)) "yes" else "NO", danger = !bit(Health.MONITOR))
                KeyValue("Saved rules loaded", if (bit(Health.POLICY_LOADED)) "yes" else "NO", danger = !bit(Health.POLICY_LOADED))
                KeyValue("Tracker database", if (bit(Health.TRACKER_DB)) "loaded" else "not loaded")
                KeyValue("Shield settings writable", if (status.osWritable) "yes" else "NO", danger = !status.osWritable)
                KeyValue("Apps offline now", "${denyNow.size}")
                KeyValue("Events in memory", "${events.size}")
                if (!status.live)
                    Text("Without the service no live traffic is shown and network rules, lockdown and pause aren't enforced. Rules are saved and sent at the next restart or change. The data shields don't depend on it.", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (failures.isNotEmpty()) {
            item {
                GuardCard(title = "Firewall failures") {
                    val now = System.currentTimeMillis()
                    for (j in failures) LatestJournalRow(j, now) {}
                }
            }
        }
    }
}

// ---- Backup -----------------------------------------------------------------------------------

@Composable
private fun BackupPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val context = LocalContext.current
    val t = Guard.tokens
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null)
                scope.launch {
                    message =
                        try {
                            val json = withContext(Dispatchers.IO) { repo.exportJson() }
                            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
                            "Exported the whole setup"
                        } catch (e: Exception) {
                            "Export failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null)
                scope.launch {
                    message =
                        try {
                            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                            val n = withContext(Dispatchers.Default) { repo.importJson(text) }
                            "Backup imported: every app's preset and setup, the shield lists, the fake place, schedules" + (if (n > 0) " and ${quantity(n, "host rule")}" else "")
                        } catch (e: IllegalArgumentException) {
                            e.message ?: "Not a Guard backup"
                        } catch (e: Exception) {
                            "Import failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                }
        }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Backup", nav.back) }
        item {
            GuardCard {
                Text(
                    "Export saves every app's preset and controls, lockdown and its list, schedules, the screen-off rule, alerts, sleep, EVERY shield list (contacts, calendar, call log, texts, clipboard, sensors, usage, radios, location, scans, NFC), the storage scopes, the master shields and the fake place as one JSON file. Import replaces the setup with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("guard-backup.json") }) { Text("Export") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Import") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

// ---- Tracker host rules (coming) ---------------------------------------------------------------

@Composable
private fun TrackerRulesPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val book by repo.rules.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }
    val hostLists = policies.policies.values.filter { it.allowedHosts.isNotEmpty() || it.blockedHosts.isNotEmpty() }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Per-app tracker host rules", nav.back, "Coming") }
        item {
            GuardCard {
                Text(HostRules.NOT_ENFORCED_NOTE, style = MaterialTheme.typography.bodySmall, color = t.muted)
                Text(
                    "Everything saved here (custom rules, per-app host lists, blocklist sources, tracker levels) is kept and will apply once the engine lands. Nothing is offered as a control until then.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (book.rules.isNotEmpty()) {
            item {
                GuardCard(title = "Saved custom rules · ${book.rules.size}") {
                    book.rules.sortedWith(compareBy({ it.packageName != com.android.settings.ichthys.guard.model.CustomRule.ANY_APP }, { it.pattern })).forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.pattern, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Tag(r.action.name, emphasized = r.action == RuleAction.ALLOW, danger = r.action == RuleAction.BLOCK)
                                    Text(if (r.appliesToAll) "All apps" else labels[r.packageName] ?: r.packageName, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            TextButton(onClick = { repo.removeRule(r.id) }) { Text("Delete") }
                        }
                        HorizontalDivider(color = t.hairline)
                    }
                }
            }
        }
        if (hostLists.isNotEmpty()) {
            item {
                GuardCard(title = "Saved per-app host lists") {
                    hostLists.sortedBy { labels[it.packageName] ?: it.packageName }.forEach { p ->
                        Text(labels[p.packageName] ?: p.packageName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).clickable { nav.openApp(p.packageName) })
                        (p.blockedHosts.map { it to RuleAction.BLOCK } + p.allowedHosts.map { it to RuleAction.ALLOW }).sortedBy { it.first }.forEach { (host, action) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Tag(action.name, emphasized = action == RuleAction.ALLOW, danger = action == RuleAction.BLOCK)
                                Text(host, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = {
                                    repo.updatePolicy(p.packageName) {
                                        if (action == RuleAction.BLOCK) it.copy(blockedHosts = it.blockedHosts - host) else it.copy(allowedHosts = it.allowedHosts - host)
                                    }
                                }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
        item {
            GuardCard(title = "Blocklist sources") {
                for (s in book.sources) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            Text(if (s.lastUpdatedMs > 0) "updated ${formatAgo(s.lastUpdatedMs)} · ${s.entryCount} entries" else "never downloaded", style = MaterialTheme.typography.bodySmall, color = t.muted)
                        }
                        Switch(checked = s.enabled, onCheckedChange = { repo.setSourceEnabled(s.id, it) })
                    }
                }
                Text("The system hosts file (HaGeZi Light) always stays on. These sources will add to it per app once enforced.", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---- shared -----------------------------------------------------------------------------------

@Composable
internal fun AppLine(pkg: String, label: String, onOpenApp: (String) -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpenApp(pkg) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg, Modifier.padding(end = 10.dp), size = 28.dp)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}
