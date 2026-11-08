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

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.os.PickedContacts
import com.android.settings.ichthys.guard.os.RealDataGrants
import com.android.settings.ichthys.guard.os.Shield
import com.android.settings.ichthys.guard.os.ShieldGroup
import com.android.settings.ichthys.guard.os.ShieldTable
import com.android.settings.ichthys.guard.ui.theme.Guard
import com.android.settings.ichthys.protect.IchthysProtect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IchthysOS: one sheet of the app page's DATA card: the per-app shields of one [ShieldGroup]
 * (Contacts · Calendar · Calls · SMS; Clipboard · Sensors · Storage; Bluetooth · NFC · Wi-Fi ·
 * UWB), each a switch where on = protected, with the "differs from preset" dot. Every row maps
 * to the Settings.Secure list it always did (ShieldTable).
 */
@Composable
fun DataSheetScreen(vm: GuardViewModel, nav: GuardNav, packageName: String, group: ShieldGroup, modifier: Modifier = Modifier) {
    val repo = vm.repo
    val context = LocalContext.current
    val t = Guard.tokens
    val policies by repo.policies.collectAsStateWithLifecycle()
    val policy = policies.policyFor(packageName)
    val app = repo.appFor(packageName)
    val isSystem = app?.isSystem == true
    var version by remember { mutableIntStateOf(0) }
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    val requested =
        remember(packageName) {
            runCatching {
                    context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet()
                }
                .getOrNull()
                .orEmpty()
        }
    val spec = policy.preset.spec
    val rows = remember(group) { ShieldTable.group(group) }
    fun update(change: (AppPolicy) -> AppPolicy) = repo.updatePolicy(packageName, change)
    // IchthysOS: roles and special access that hand the app real data past its shields
    // (notification access, call screening, companion devices, assistant, autofill).
    val grants by
        produceState(emptyList<RealDataGrants.Grant>(), packageName, group, tick) {
            value =
                if (group != ShieldGroup.PERSONAL) emptyList()
                else withContext(Dispatchers.IO) { RealDataGrants.forPackage(context, packageName) }
        }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader(group.label, nav.back, app?.label ?: packageName) }
        item {
            GuardCard {
                if (isSystem && group == ShieldGroup.PERSONAL) {
                    Text(
                        "A system app: the contacts, calendar, call, message and sensor shields don't apply to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (group == ShieldGroup.PERSONAL && !isSystem) {
                    // IchthysOS: the "only the contacts picked for it" entry, which the permission
                    // dialog also writes; the Messenger preset sets it.
                    val picked = remember(version, tick) { ShieldTable.isContactsPicked(context, packageName) }
                    val real = remember(version, tick) { IchthysProtect.isListed(context, ShieldTable.KEY_CONTACTS, packageName) }
                    ToggleRow(
                        "Only the contacts picked for it",
                        picked && !real,
                        if (real) "Has real contacts (below); picking doesn't apply"
                        else if (picked) "Sees only the contacts you pick for it"
                        else "Off: sees an empty address book unless given real contacts below",
                        enabled = !real,
                    ) { on ->
                        ShieldTable.setContactsPicked(context, packageName, on)
                        version++
                        // IchthysOS: switching it on goes straight to choosing the contacts.
                        if (on) context.startActivity(PickedContacts.pickerIntent(context, packageName))
                    }
                    if (picked && !real) {
                        val count = remember(version, tick) { PickedContacts.get(context, packageName).size }
                        TextButton(onClick = { context.startActivity(PickedContacts.pickerIntent(context, packageName)) }) {
                            Text(if (count == 0) "Choose contacts (none picked yet)" else "Choose contacts ($count picked)", color = t.accent)
                        }
                    }
                    PresetDot(spec != null && picked != spec.contactsPicked)
                }
                for (s in rows) {
                    if (isSystem && s.keys.any { it in ShieldTable.SYSTEM_EXEMPT_KEYS }) continue
                    val on =
                        if (s.policyKey != null) !s.policyKey.selector(policy)
                        else remember(version, tick, packageName) { s.isProtected(context, packageName) }
                    // Listed for the permissions the app requests; real access it was given stays
                    // visible even if it stopped asking for the data.
                    val wanted = s.permissions.isEmpty() || s.permissions.any { it in requested }
                    if (wanted || (s.listedMeansReal && !on)) {
                        val differs =
                            spec != null &&
                                when (s.id) {
                                    "sensors" -> on == spec.sensorsReal
                                    "nfc" -> on != spec.nfcBlocked
                                    "contacts" -> !on
                                    else -> s.listedMeansReal && !on
                                }
                        ShieldRow(s, on, packageName, requested, policy, version, tick, differs) { protect ->
                            if (s.policyKey != null) {
                                update { s.policyKey.assign(it, !protect) }
                            } else {
                                s.setProtected(context, packageName, protect)
                            }
                            version++
                        }
                    }
                }
                if (group == ShieldGroup.DEVICE && requested.any { it in ShieldTable.STORAGE_PERMISSIONS }) {
                    val scope = remember(version, tick, packageName) { ShieldTable.storageScope(context, packageName) }
                    ToggleRow(
                        "Storage",
                        scope != null,
                        if (scope == null) "Sees everything its permission allows"
                        else if (scope.isEmpty()) "Sees only its own files"
                        else "Sees only ${scope.joinToString()} and its own files",
                    ) { limit ->
                        ShieldTable.setStorageScope(context, packageName, if (limit) emptyList() else null)
                        version++
                    }
                    if (scope != null) {
                        var text by remember(scope) { mutableStateOf(scope.joinToString(", ")) }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Folders, comma-separated (e.g. Download, Pictures/Screenshots)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = {
                                ShieldTable.setStorageScope(context, packageName, text.split(","))
                                version++
                            }
                        ) {
                            Text("Save folders")
                        }
                    }
                }
                if (group == ShieldGroup.PERSONAL) {
                    TextButton(onClick = { context.startActivity(IchthysProtect.noLocationIntent(context, packageName)) }) {
                        Text("All apps' location shields")
                    }
                }
            }
        }
        if (grants.isNotEmpty()) {
            item {
                GuardCard(title = stringResource(com.android.settings.R.string.ichthys_guard_grants_title)) {
                    Text(
                        stringResource(com.android.settings.R.string.ichthys_guard_grants_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                    for (g in grants) {
                        Text(
                            stringResource(g.label),
                            style = MaterialTheme.typography.titleMedium,
                            color = t.danger,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(stringResource(g.detail), style = MaterialTheme.typography.bodySmall, color = t.muted)
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(g.manage.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        ) {
                            Text(stringResource(com.android.settings.R.string.ichthys_guard_grants_manage), color = t.accent)
                        }
                    }
                }
            }
        }
        item {
            Text(
                "A switch on means the app gets the protected answer. The master shields (Guard settings › Master shields) must be on for a row to apply; a row greyed out says why.",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}

@Composable
private fun ShieldRow(
    s: Shield,
    on: Boolean,
    packageName: String,
    requested: Set<String>,
    policy: AppPolicy,
    version: Int,
    tick: Int,
    differs: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    // What this write shield covers for this app, and the part of it the app already reads for
    // real: the OS saves those changes whatever the switch says.
    val covered = s.followsRead.filter { it.permission in requested }
    val real =
        remember(version, tick, packageName, policy.contactsAllowed) {
            covered.filter { IchthysProtect.isListed(context, it.readKey, packageName) }
        }
    val follows = real.isNotEmpty() && real.size == covered.size
    val masters = if (s.followsRead.isEmpty()) s.masters else covered.map { it.master }
    val masterOff = remember(tick, masters) { masters.isNotEmpty() && masters.none { it.isOn(resolver) } }
    val protectedText = if (s.protectedRes != 0) stringResource(s.protectedRes) else s.protectedText
    val realText = if (s.realRes != 0) stringResource(s.realRes) else s.realText
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            ToggleRow(
                if (s.labelRes != 0) stringResource(s.labelRes) else s.label,
                on && !follows && !masterOff,
                when {
                    masterOff -> "$realText: this shield is off for every app (Guard settings › Master shields)"
                    follows -> "Follows real access to your ${ShieldTable.nouns(real)}: what it reads for real, it can also change"
                    !on -> realText
                    real.isNotEmpty() -> "Follows real access to your ${ShieldTable.nouns(real)}. Other changes report success but are not saved"
                    else -> protectedText
                },
                enabled = !follows && !masterOff,
                onChange = onChange,
            )
        }
        PresetDot(differs)
    }
}
