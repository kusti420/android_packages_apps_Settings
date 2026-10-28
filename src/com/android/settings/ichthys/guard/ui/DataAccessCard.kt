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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settings.R
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.OsSettingKey
import com.android.settings.ichthys.guard.os.ShieldSwitch
import com.android.settings.ichthys.guard.ui.theme.Guard
import com.android.settings.ichthys.protect.IchthysProtect
import org.json.JSONArray
import org.json.JSONObject

/**
 * One OS-level shield as a per-app package list in Settings.Secure, shown as one switch with one
 * polarity: on = the app gets the protected answer ([protectedText]), off = real access
 * ([realText]). Only the presentation is uniform; the stored lists keep their own meaning.
 * [listedMeansReal]: the list holds apps that get real access (an allowlist); otherwise it holds
 * apps that get the protected answer (a blocklist). [keys] are edited together. [permissions]:
 * shown only for apps that request one of them (empty: always shown). [masters]: the shield's
 * switches for every app (Guard Home); when they are all off the app gets real access whatever
 * its row says. [policyKey]: the list is mirrored in the app's Guard policy and edited through it
 * (GuardRepository.updatePolicy). [followsRead]: for a write shield, the data it covers (see
 * [WriteTarget]).
 */
private data class Shield(
    val label: String,
    val protectedText: String,
    val realText: String,
    val keys: List<String>,
    val listedMeansReal: Boolean,
    val permissions: List<String>,
    val masters: List<ShieldSwitch> = emptyList(),
    val policyKey: OsSettingKey? = null,
    val followsRead: List<WriteTarget> = emptyList(),
    /** String resources shared with the stock page that shows the same switch, if there is one. */
    val labelRes: Int = 0,
    val protectedRes: Int = 0,
    val realRes: Int = 0,
)

/**
 * One kind of data the write shield covers. The providers (IchthysShield in ContactsProvider and
 * CalendarProvider) let an app change what it may read for real: a package listed in [readKey]
 * (the read allowlist; for contacts a bare entry, i.e. real contacts) writes for real whatever
 * the write allowlists say. [permission] is the write permission; without it the app can't write
 * that data at all. [master] is the write shield's switch for every app.
 */
private data class WriteTarget(
    val noun: String,
    val permission: String,
    val readKey: String,
    val master: ShieldSwitch,
)

private val WRITE_TARGETS =
    listOf(
        WriteTarget(
            "contacts",
            Manifest.permission.WRITE_CONTACTS,
            OsSettingKey.CONTACTS.key,
            ShieldSwitch.feature("contacts_write"),
        ),
        WriteTarget(
            "calendar",
            Manifest.permission.WRITE_CALENDAR,
            "ichthys_calendar_allowed_pkgs",
            ShieldSwitch.feature("calendar_write"),
        ),
        WriteTarget(
            "call log",
            Manifest.permission.WRITE_CALL_LOG,
            "ichthys_call_log_allowed_pkgs",
            ShieldSwitch.feature("call_log_write"),
        ),
    )

/**
 * IchthysOS: the shields preinstalled apps are exempt from (the providers, telephony, Telecom and
 * the sensor service skip FLAG_SYSTEM apps). A system app's page leaves them out: their switches
 * would claim a protection it doesn't get.
 */
private val SYSTEM_EXEMPT_KEYS =
    setOf(
        OsSettingKey.CONTACTS.key,
        OsSettingKey.SENSORS.key,
        "ichthys_calendar_allowed_pkgs",
        "ichthys_call_log_allowed_pkgs",
        "ichthys_contacts_write_allowed_pkgs",
        "ichthys_calendar_write_allowed_pkgs",
        "ichthys_call_log_write_allowed_pkgs",
        "ichthys_phone_number_allowed_pkgs",
        "ichthys_sms_read_allowed_pkgs",
        "ichthys_sms_send_allowed_pkgs",
        "ichthys_mms_push_allowed_pkgs",
        "ichthys_call_allowed_pkgs",
        "ichthys_answer_calls_allowed_pkgs",
    )

private val SHIELDS =
    listOf(
        Shield(
            "Contacts",
            "Sees an empty address book",
            "Sees your real contacts",
            listOf(OsSettingKey.CONTACTS.key),
            true,
            listOf(Manifest.permission.READ_CONTACTS),
            masters = listOf(ShieldSwitch.CONTACTS),
            policyKey = OsSettingKey.CONTACTS,
        ),
        Shield(
            "Calendar",
            "Sees an empty calendar",
            "Sees your real calendar",
            listOf("ichthys_calendar_allowed_pkgs"),
            true,
            listOf(Manifest.permission.READ_CALENDAR),
            masters = listOf(ShieldSwitch.feature("calendar")),
        ),
        Shield(
            "Call log",
            "Sees an empty call log",
            "Sees your real call log",
            listOf("ichthys_call_log_allowed_pkgs"),
            true,
            listOf(Manifest.permission.READ_CALL_LOG),
            masters = listOf(ShieldSwitch.feature("call_log")),
        ),
        Shield(
            "Saving contacts, calendar and calls",
            "Changes report success but are not saved",
            "Its changes are saved",
            listOf(
                "ichthys_contacts_write_allowed_pkgs",
                "ichthys_calendar_write_allowed_pkgs",
                "ichthys_call_log_write_allowed_pkgs",
            ),
            true,
            WRITE_TARGETS.map { it.permission },
            followsRead = WRITE_TARGETS,
        ),
        Shield(
            "Phone number",
            "Gets no phone number",
            "Gets your phone number",
            listOf("ichthys_phone_number_allowed_pkgs"),
            true,
            listOf(
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_SMS,
            ),
            masters = listOf(ShieldSwitch.feature("phone_number")),
        ),
        Shield(
            "Text messages",
            "Reads an empty inbox",
            "Reads your text messages",
            listOf("ichthys_sms_read_allowed_pkgs"),
            true,
            listOf(Manifest.permission.READ_SMS),
            masters = listOf(ShieldSwitch.feature("sms_read")),
        ),
        Shield(
            "Sending texts",
            "You confirm every text it sends",
            "Sends texts without asking you",
            listOf("ichthys_sms_send_allowed_pkgs"),
            true,
            listOf(Manifest.permission.SEND_SMS),
            masters = listOf(ShieldSwitch.feature("sms_send")),
        ),
        Shield(
            "MMS and WAP push",
            "Doesn't receive them",
            "Receives them",
            listOf("ichthys_mms_push_allowed_pkgs"),
            true,
            listOf(Manifest.permission.RECEIVE_MMS, Manifest.permission.RECEIVE_WAP_PUSH),
            masters = listOf(ShieldSwitch.feature("mms_push")),
        ),
        Shield(
            "Placing calls",
            "You confirm every call it places",
            "Places calls without asking you",
            listOf("ichthys_call_allowed_pkgs"),
            true,
            listOf(Manifest.permission.CALL_PHONE),
            masters = listOf(ShieldSwitch.feature("call")),
        ),
        Shield(
            "Answering and ending calls",
            "Can't answer or end calls",
            "Can answer and end calls",
            listOf("ichthys_answer_calls_allowed_pkgs"),
            true,
            listOf(Manifest.permission.ANSWER_PHONE_CALLS),
            masters = listOf(ShieldSwitch.feature("answer_calls")),
        ),
        Shield(
            "Clipboard",
            "Reads the clipboard only right after you tap in it",
            "Reads the clipboard at any time, also in the background",
            listOf(OsSettingKey.CLIPBOARD.key),
            true,
            emptyList(),
            masters = listOf(ShieldSwitch.CLIPBOARD),
            policyKey = OsSettingKey.CLIPBOARD,
        ),
        Shield(
            "Motion sensors",
            "Gets rounded, slowed sensor data",
            "Gets exact sensor data",
            listOf(OsSettingKey.SENSORS.key),
            true,
            emptyList(),
            masters = listOf(ShieldSwitch.SENSORS),
            policyKey = OsSettingKey.SENSORS,
        ),
        // Also on the Usage access page (IchthysUsageHistory): same key, same strings.
        Shield(
            "Hide usage history",
            "Sees an empty usage history",
            "Sees which apps you used and when",
            listOf(IchthysProtect.KEY_USAGE_FULL_PKGS),
            true,
            listOf(Manifest.permission.PACKAGE_USAGE_STATS),
            labelRes = R.string.ichthys_shield_usage_title,
            protectedRes = R.string.ichthys_shield_usage_on,
            realRes = R.string.ichthys_shield_usage_off,
        ),
        // Also on the No location page (LocationNoFixSettings): same key, same strings.
        Shield(
            "Location",
            "Never gets a location, as if indoors",
            "Gets your location when allowed",
            listOf(IchthysProtect.KEY_LOCATION_NOFIX_PKGS),
            false,
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            protectedRes = R.string.ichthys_shield_location_on,
            realRes = R.string.ichthys_shield_location_off,
        ),
        Shield(
            "Bluetooth and Wi-Fi scans",
            "Scans find nothing",
            "Scans find the devices around you",
            listOf("ichthys_scan_empty_pkgs"),
            false,
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        ),
        Shield(
            "Bluetooth advertising",
            "Can't make the phone a beacon",
            "Can make the phone a beacon",
            listOf("ichthys_ble_advertise_pkgs"),
            true,
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE),
        ),
        Shield(
            "NFC",
            "Can't read tags or emulate cards",
            "Can read tags and emulate cards",
            listOf("ichthys_nfc_blocked_pkgs"),
            false,
            listOf(Manifest.permission.NFC),
        ),
        Shield(
            "Wi-Fi control",
            "Can't switch or change Wi-Fi",
            "Can switch and change Wi-Fi",
            listOf("ichthys_wifi_control_pkgs"),
            true,
            listOf(Manifest.permission.CHANGE_WIFI_STATE),
        ),
        Shield(
            "UWB ranging",
            "Can't measure distance to nearby devices",
            "Can measure distance to nearby devices",
            listOf("ichthys_uwb_pkgs"),
            true,
            listOf(Manifest.permission.UWB_RANGING, "android.permission.RANGING"),
        ),
    )

private fun isProtected(context: Context, shield: Shield, pkg: String): Boolean {
    val listed = shield.keys.all { IchthysProtect.isListed(context, it, pkg) }
    return if (shield.listedMeansReal) !listed else listed
}

private fun setProtected(context: Context, shield: Shield, pkg: String, protect: Boolean) {
    val listed = if (shield.listedMeansReal) !protect else protect
    for (key in shield.keys) IchthysProtect.setListed(context, key, pkg, listed)
}

/** "contacts", "contacts and calendar", "contacts, calendar and call log". */
private fun nouns(targets: List<WriteTarget>): String =
    when (targets.size) {
        0 -> ""
        1 -> targets[0].noun
        else -> targets.dropLast(1).joinToString { it.noun } + " and " + targets.last().noun
    }

private const val STORAGE_SCOPES_KEY = "ichthys_storage_scopes"

private val STORAGE_PERMISSIONS =
    setOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
    )

private fun readScopes(context: Context): JSONObject =
    runCatching {
            JSONObject(Settings.Secure.getString(context.contentResolver, STORAGE_SCOPES_KEY) ?: "{}")
        }
        .getOrElse { JSONObject() }

/** The app's allowed folders, or null when it isn't scoped. */
private fun storageScope(context: Context, pkg: String): List<String>? {
    val arr = readScopes(context).optJSONArray(pkg) ?: return null
    return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
}

/** Stores the folders (relative to shared storage, e.g. "Download/"), or removes the scope. */
private fun setStorageScope(context: Context, pkg: String, folders: List<String>?) {
    val scopes = readScopes(context)
    if (folders == null) {
        scopes.remove(pkg)
    } else {
        scopes.put(
            pkg,
            JSONArray(
                folders
                    .map { it.trim().trim('/') }
                    .filter { it.isNotEmpty() }
                    .map { "$it/" }
            ),
        )
    }
    Settings.Secure.putString(
        context.contentResolver,
        STORAGE_SCOPES_KEY,
        if (scopes.length() == 0) null else scopes.toString(),
    )
}

/** Every setting the card shows, so it follows changes made on other pages while it is open. */
private val OBSERVED: List<Uri> by lazy {
    (SHIELDS.flatMap { it.keys } + STORAGE_SCOPES_KEY).map { Settings.Secure.getUriFor(it) } +
        (SHIELDS.flatMap { it.masters } + WRITE_TARGETS.map { it.master }).map { it.uri }
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
    val masterOff =
        remember(tick, masters) { masters.isNotEmpty() && masters.none { it.isOn(resolver) } }
    val protectedText = if (s.protectedRes != 0) stringResource(s.protectedRes) else s.protectedText
    val realText = if (s.realRes != 0) stringResource(s.realRes) else s.realText
    ToggleRow(
        if (s.labelRes != 0) stringResource(s.labelRes) else s.label,
        on && !follows && !masterOff,
        when {
            masterOff -> "$realText: this shield is off for every app (Guard Home)"
            follows ->
                "Follows real access to your ${nouns(real)}: what it reads for real, it can " +
                    "also change"
            !on -> realText
            real.isNotEmpty() ->
                "Follows real access to your ${nouns(real)}. Other changes report success but " +
                    "are not saved"
            else -> protectedText
        },
        enabled = !follows && !masterOff,
        onChange = onChange,
    )
}

/**
 * What the app gets of your data, one switch per kind and one polarity for all of them: on = the
 * protected answer (empty data, a confirmation, or a refusal), off = real access; the line under
 * each says which it is. Only shields for permissions the app requests are listed.
 *
 * A switch is shown off and locked when it can't protect: the shield is off for every app (Guard
 * Home), or, for the write shield, the app reads that data for real (the OS then saves its
 * changes too). The rows read their settings live, so a change made on another page (the Usage
 * access page, No location, the permission dialog) shows here at once.
 */
@Composable
fun DataAccessCard(
    packageName: String,
    policy: AppPolicy,
    isSystem: Boolean,
    onPolicyChange: ((AppPolicy) -> AppPolicy) -> Unit,
) {
    val context = LocalContext.current
    val t = Guard.tokens
    // Re-read after every change: here (version) and anywhere else (tick).
    var version by remember { mutableIntStateOf(0) }
    val tick = rememberSettingsTick(OBSERVED)
    val requested =
        remember(packageName) {
            runCatching {
                    context.packageManager
                        .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions
                        ?.toSet()
                }
                .getOrNull()
                .orEmpty()
        }

    GuardCard(title = "Data access") {
        if (isSystem) {
            Text(
                "A system app: the contacts, calendar, call, message and sensor shields don't " +
                    "apply to it, so they aren't listed.",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        for (s in SHIELDS) {
            if (isSystem && s.keys.any { it in SYSTEM_EXEMPT_KEYS }) continue
            val on =
                if (s.policyKey != null) !s.policyKey.selector(policy)
                else remember(version, tick, packageName) { isProtected(context, s, packageName) }
            // Listed for the permissions the app requests; real access it was given stays
            // visible even if it stopped asking for the data.
            val wanted = s.permissions.isEmpty() || s.permissions.any { it in requested }
            if (wanted || (s.listedMeansReal && !on)) {
                ShieldRow(s, on, packageName, requested, policy, version, tick) { protect ->
                    if (s.policyKey != null) {
                        onPolicyChange { s.policyKey.assign(it, !protect) }
                    } else {
                        setProtected(context, s, packageName, protect)
                        version++
                    }
                }
            }
        }
        if (requested.any { it in STORAGE_PERMISSIONS }) {
            val scope = remember(version, tick, packageName) { storageScope(context, packageName) }
            ToggleRow(
                "Storage",
                scope != null,
                if (scope == null) "Sees everything its permission allows"
                else if (scope.isEmpty()) "Sees only its own files"
                else "Sees only ${scope.joinToString()} and its own files",
            ) { limit ->
                setStorageScope(context, packageName, if (limit) emptyList() else null)
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
                        setStorageScope(context, packageName, text.split(","))
                        version++
                    }
                ) {
                    Text("Save folders")
                }
            }
        }
        TextButton(
            onClick = {
                context.startActivity(IchthysProtect.noLocationIntent(context, packageName))
            }
        ) {
            Text("All apps' location shields")
        }
    }
}
