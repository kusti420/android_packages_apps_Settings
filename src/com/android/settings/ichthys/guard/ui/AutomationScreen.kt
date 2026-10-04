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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.AlertType
import com.android.settings.ichthys.guard.model.AppTag
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.Profile
import com.android.settings.ichthys.guard.ui.theme.Guard
import java.text.DateFormatSymbols
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guard's automation page (W8b decisions 9, 13, 18, 35): scheduled profiles, the global
 * "no network while screen off" rule with its exempt list, the behaviour-alert switches and
 * auto-tighten for unused apps. Opened from the Rules tab.
 */
@Composable
fun AutomationScreen(
    vm: GuardViewModel,
    onOpenApp: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = vm.repo
    val t = Guard.tokens
    val scope = rememberCoroutineScope()
    val auto by repo.automation.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val active = auto.activeProfiles().map { it.id }.toSet()

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Automation", style = MaterialTheme.typography.headlineMedium)
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            GuardCard(title = "Profiles") {
                Text(
                    "While a profile is on, apps with its tags lose their network. Tag apps on " +
                        "their page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                auto.profiles.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
                    ProfileEditor(
                        p,
                        active = p.id in active,
                        taggedApps =
                            apps.count { a ->
                                p.matches(a.packageName, policies.policyFor(a.packageName).tags)
                            },
                        onChange = { next ->
                            repo.updateAutomation { s ->
                                s.copy(profiles = s.profiles.map { if (it.id == p.id) next else it })
                            }
                        },
                        onDelete =
                            if (Profile.PREMADE.any { it.id == p.id }) null
                            else {
                                {
                                    repo.updateAutomation { s ->
                                        s.copy(profiles = s.profiles.filter { it.id != p.id })
                                    }
                                }
                            },
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
                    Text("Add profile")
                }
            }
        }
        item {
            GuardCard(title = "Screen off") {
                ToggleRow(
                    "No network while the screen is off",
                    auto.screenOffAll,
                    "Every user app is offline while the screen is off or locked, except the " +
                        "apps below",
                ) { on ->
                    repo.updateAutomation { it.copy(screenOffAll = on) }
                }
                val exempt = apps.filter { it.packageName in auto.screenOffExempt }
                SectionLabel("Exempt · ${exempt.size}", Modifier.padding(top = 8.dp))
                for (a in exempt) {
                    AppLine(a.packageName, a.label, onOpenApp) {
                        TextButton(
                            onClick = {
                                repo.updateAutomation {
                                    it.copy(screenOffExempt = it.screenOffExempt - a.packageName)
                                }
                            }
                        ) {
                            Text("Remove")
                        }
                    }
                }
                Text(
                    "Common messengers are exempt by default. Add more from an app's page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
        item {
            GuardCard(title = "Alerts") {
                for (type in AlertType.entries) {
                    ToggleRow(type.label, auto.alertOn(type), type.summary) { on ->
                        repo.updateAutomation {
                            it.copy(
                                alertMask =
                                    if (on) it.alertMask or type.bit
                                    else it.alertMask and type.bit.inv()
                            )
                        }
                    }
                }
                Text(
                    "Alerts only: Guard notifies you and never blocks on these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
        item {
            GuardCard(title = "Auto-tighten") {
                ToggleRow(
                    "Pause unused apps",
                    auto.autoTighten,
                    "After ${auto.autoTightenDays} days unused an app loses its network. " +
                        "Opening it again shows Guard's review.",
                ) { on ->
                    repo.updateAutomation { it.copy(autoTighten = on) }
                }
                val dormant = apps.filter { policies.policyFor(it.packageName).dormant }
                if (dormant.isNotEmpty())
                    SectionLabel("Paused · ${dormant.size}", Modifier.padding(top = 8.dp))
                for (a in dormant) {
                    AppLine(a.packageName, a.label, onOpenApp) {
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) { repo.restoreDormant(a.packageName) }
                            }
                        ) {
                            Text("Restore")
                        }
                    }
                }
                // Off by default; a check does nothing while it is off.
                if (auto.autoTighten) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val paused = withContext(Dispatchers.IO) { repo.runMaintenance() }
                                message =
                                    if (paused.isEmpty()) "No unused apps to pause."
                                    else "Paused: " + paused.joinToString()
                            }
                        }
                    ) {
                        Text("Check now")
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLine(
    pkg: String,
    label: String,
    onOpenApp: (String) -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpenApp(pkg) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg, Modifier.padding(end = 10.dp), size = 28.dp)
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
private fun ProfileEditor(
    p: Profile,
    active: Boolean,
    taggedApps: Int,
    onChange: (Profile) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val t = Guard.tokens
    val ctx = LocalContext.current
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    if (active) Tag("ACTIVE", emphasized = true)
                }
                Text(
                    "${p.windowText} · ${daysText(p.days)} · $taggedApps apps",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
                    color = t.muted,
                )
            }
            Switch(checked = p.enabled, onCheckedChange = { onChange(p.copy(enabled = it)) })
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
            for (tag in AppTag.entries) {
                Chip(tag.label, tag in p.blockTags) {
                    onChange(
                        p.copy(
                            blockTags =
                                if (tag in p.blockTags) p.blockTags - tag else p.blockTags + tag
                        )
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    TimePickerDialog(
                            ctx,
                            { _, h, m -> onChange(p.copy(startMinute = h * 60 + m)) },
                            p.startMinute / 60,
                            p.startMinute % 60,
                            true,
                        )
                        .show()
                }
            ) {
                Text("Starts %02d:%02d".format(p.startMinute / 60, p.startMinute % 60))
            }
            TextButton(
                onClick = {
                    TimePickerDialog(
                            ctx,
                            { _, h, m -> onChange(p.copy(endMinute = h * 60 + m)) },
                            p.endMinute / 60,
                            p.endMinute % 60,
                            true,
                        )
                        .show()
                }
            ) {
                Text("Ends %02d:%02d".format(p.endMinute / 60, p.endMinute % 60))
            }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        }
        // What the profile does to matching apps: no network at all, or network only while
        // the app is on screen.
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
    val order =
        listOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY,
            Calendar.SUNDAY,
        )
    return order.filter { it in days }.joinToString(" ") { names[it] }
}
