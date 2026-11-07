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

package com.android.settings.ichthys.guard.os

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.android.settings.R
import com.android.settings.ichthys.guard.model.OsSettingKey
import com.android.settings.ichthys.guard.model.SpoofProfile
import com.android.settings.ichthys.protect.IchthysProtect
import org.json.JSONArray
import org.json.JSONObject

/**
 * IchthysOS: the per-app data shields of the app page, in one table. Every row is one or more
 * Settings.Secure lists (the keys of IchthysShields, the table of truth in frameworks/base) plus
 * the location lists. Presets, the app page's sheets, the Apps filter and the backup all read it,
 * so the keys and their meaning can't drift between them. No key is new here.
 */
enum class ShieldGroup(val label: String) {
    /** Contacts · Calendar · Calls · SMS. */
    PERSONAL("Contacts · Calendar · Calls · SMS"),
    /** Clipboard · Sensors · Storage · Usage. */
    DEVICE("Clipboard · Sensors · Storage"),
    /** Bluetooth advertising · NFC · Wi-Fi control · UWB. */
    RADIO("Bluetooth · NFC · Wi-Fi · UWB"),
    /** Location and scans: shown as their own controls, not in a sheet. */
    LOCATION("Location"),
}

data class Shield(
    val id: String,
    val label: String,
    val protectedText: String,
    val realText: String,
    /** The Settings.Secure list keys; all must agree for the row to read "protected". */
    val keys: List<String>,
    /** ALLOW polarity: listed = real data. PROTECT polarity: listed = protected. */
    val listedMeansReal: Boolean,
    /** Shown only for apps requesting one of these (or already given real access). */
    val permissions: List<String>,
    val group: ShieldGroup,
    val masters: List<ShieldSwitch> = emptyList(),
    /** The three lists mirrored into AppPolicy (contacts, clipboard, sensors). */
    val policyKey: OsSettingKey? = null,
    /** Write shields that follow real read access (see [WriteTarget]). */
    val followsRead: List<WriteTarget> = emptyList(),
    /** String resources shared with the stock page that shows the same switch, if there is one. */
    val labelRes: Int = 0,
    val protectedRes: Int = 0,
    val realRes: Int = 0,
) {
    /** True when the app gets the protected answer from every key of this shield. */
    fun isProtected(context: Context, pkg: String): Boolean =
        // ALLOW polarity: protected only while on none of the keys (a multi-key row such as
        // "writes" is lifted as soon as one list names the app); PROTECT: listed on all.
        if (listedMeansReal) keys.none { IchthysProtect.isListed(context, it, pkg) }
        else keys.all { IchthysProtect.isListed(context, it, pkg) }

    fun setProtected(context: Context, pkg: String, protect: Boolean) {
        val listed = if (listedMeansReal) !protect else protect
        for (key in keys) IchthysProtect.setListed(context, key, pkg, listed)
    }

    /**
     * IchthysOS: true when the shield really applies to the app right now: [isProtected] and
     * every master switch of the shield is on (its own [masters], and for the "writes" row the
     * switches of the write shields it stands for). A shield switched off protects nobody,
     * whatever the app's list says; Verify sends only enforced shields to the probe.
     */
    fun isEnforced(context: Context, pkg: String): Boolean {
        if (!isProtected(context, pkg)) return false
        val cr = context.contentResolver
        return masters.all { it.isOn(cr) } && followsRead.all { it.master.isOn(cr) }
    }
}

/**
 * A write shield that an app's real read access also lifts (ContactsProvider and
 * CalendarProvider: "an app the user gave real access to the data may also change it").
 */
data class WriteTarget(
    val noun: String,
    val permission: String,
    val readKey: String,
    val master: ShieldSwitch,
)

object ShieldTable {
    const val KEY_CONTACTS = "ichthys_contacts_allowed_pkgs"
    const val KEY_CALENDAR = "ichthys_calendar_allowed_pkgs"
    const val KEY_CALL_LOG = "ichthys_call_log_allowed_pkgs"
    const val KEY_CONTACTS_WRITE = "ichthys_contacts_write_allowed_pkgs"
    const val KEY_CALENDAR_WRITE = "ichthys_calendar_write_allowed_pkgs"
    const val KEY_CALL_LOG_WRITE = "ichthys_call_log_write_allowed_pkgs"
    const val KEY_PHONE_NUMBER = "ichthys_phone_number_allowed_pkgs"
    const val KEY_SMS_READ = "ichthys_sms_read_allowed_pkgs"
    const val KEY_SMS_SEND = "ichthys_sms_send_allowed_pkgs"
    const val KEY_MMS_PUSH = "ichthys_mms_push_allowed_pkgs"
    const val KEY_CALL = "ichthys_call_allowed_pkgs"
    const val KEY_ANSWER_CALLS = "ichthys_answer_calls_allowed_pkgs"
    const val KEY_CLIPBOARD = "ichthys_clipboard_allowed_pkgs"
    const val KEY_SENSORS = "ichthys_sensor_exempt_pkgs"
    const val KEY_USAGE = IchthysProtect.KEY_USAGE_FULL_PKGS
    const val KEY_BLE_ADVERTISE = "ichthys_ble_advertise_pkgs"
    const val KEY_WIFI_CONTROL = "ichthys_wifi_control_pkgs"
    const val KEY_UWB = "ichthys_uwb_pkgs"
    const val KEY_SCAN_EMPTY = "ichthys_scan_empty_pkgs"
    const val KEY_LOCATION_NOFIX = IchthysProtect.KEY_LOCATION_NOFIX_PKGS
    const val KEY_NFC_BLOCKED = "ichthys_nfc_blocked_pkgs"
    const val KEY_LOCATION_REAL = "ichthys_location_real_pkgs"
    const val KEY_STORAGE_SCOPES = "ichthys_storage_scopes"
    const val KEY_A11Y_SCOPES = IchthysProtect.KEY_A11Y_SCOPES
    /** The fake place (Settings.Secure): switch, coordinates, name. */
    const val KEY_SPOOF_ON = "ichthys_location_spoof"
    const val KEY_SPOOF_FAKE = "ichthys_location_fake"
    const val KEY_SPOOF_FAKE_NAME = "ichthys_location_fake_name"
    /**
     * The spoofed values that go with the fake place (one JSON string, [SpoofProfile]): country,
     * time zone, carrier, language, sales code and whether spoofed apps need a VPN. Unset =
     * only the position is faked.
     */
    const val KEY_SPOOF_PROFILE = "ichthys_location_profile"

    /** The fake place and its spoofed values, for the backup and the observers. */
    val SPOOF_KEYS: List<String> =
        listOf(KEY_SPOOF_ON, KEY_SPOOF_FAKE, KEY_SPOOF_FAKE_NAME, KEY_SPOOF_PROFILE)

    /** The contacts list's "only the contacts picked for it" entry suffix (IchthysShields). */
    const val PICKED_SUFFIX = ":picked"

    val WRITE_TARGETS =
        listOf(
            WriteTarget(
                "contacts",
                Manifest.permission.WRITE_CONTACTS,
                KEY_CONTACTS,
                ShieldSwitch.feature("contacts_write"),
            ),
            WriteTarget(
                "calendar",
                Manifest.permission.WRITE_CALENDAR,
                KEY_CALENDAR,
                ShieldSwitch.feature("calendar_write"),
            ),
            WriteTarget(
                "call log",
                Manifest.permission.WRITE_CALL_LOG,
                KEY_CALL_LOG,
                ShieldSwitch.feature("call_log_write"),
            ),
        )

    /**
     * Lists the platform never applies to system apps (contacts, calendar, calls, messages,
     * sensors); their rows are left out for those.
     */
    val SYSTEM_EXEMPT_KEYS =
        setOf(
            KEY_CONTACTS,
            KEY_SENSORS,
            KEY_CALENDAR,
            KEY_CALL_LOG,
            KEY_CONTACTS_WRITE,
            KEY_CALENDAR_WRITE,
            KEY_CALL_LOG_WRITE,
            KEY_PHONE_NUMBER,
            KEY_SMS_READ,
            KEY_SMS_SEND,
            KEY_MMS_PUSH,
            KEY_CALL,
            KEY_ANSWER_CALLS,
        )

    val SHIELDS: List<Shield> =
        listOf(
            Shield(
                "contacts",
                "Contacts",
                "Sees an empty address book",
                "Sees your real contacts",
                listOf(KEY_CONTACTS),
                true,
                listOf(Manifest.permission.READ_CONTACTS),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.CONTACTS),
                policyKey = OsSettingKey.CONTACTS,
            ),
            Shield(
                "calendar",
                "Calendar",
                "Sees an empty calendar",
                "Sees your real calendar",
                listOf(KEY_CALENDAR),
                true,
                listOf(Manifest.permission.READ_CALENDAR),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.feature("calendar")),
            ),
            Shield(
                "call_log",
                "Call log",
                "Sees an empty call log",
                "Sees your real call log",
                listOf(KEY_CALL_LOG),
                true,
                listOf(Manifest.permission.READ_CALL_LOG),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.feature("call_log")),
            ),
            Shield(
                "writes",
                "Saving contacts, calendar and calls",
                "Changes report success but are not saved",
                "Its changes are saved",
                listOf(KEY_CONTACTS_WRITE, KEY_CALENDAR_WRITE, KEY_CALL_LOG_WRITE),
                true,
                WRITE_TARGETS.map { it.permission },
                ShieldGroup.PERSONAL,
                followsRead = WRITE_TARGETS,
            ),
            Shield(
                "phone_number",
                "Phone number",
                "Gets no phone number",
                "Gets your phone number",
                listOf(KEY_PHONE_NUMBER),
                true,
                listOf(
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_SMS,
                ),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.feature("phone_number")),
            ),
            Shield(
                "sms_read",
                "Text messages",
                "Reads an empty inbox",
                "Reads your text messages",
                listOf(KEY_SMS_READ),
                true,
                listOf(Manifest.permission.READ_SMS),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.feature("sms_read")),
            ),
            Shield(
                "sms_send",
                "Sending texts",
                "You confirm every text it sends",
                "Sends texts without asking you",
                listOf(KEY_SMS_SEND),
                true,
                listOf(Manifest.permission.SEND_SMS),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.feature("sms_send")),
            ),
            Shield(
                "mms_push",
                "MMS and WAP push",
                "Doesn't receive them",
                "Receives them",
                listOf(KEY_MMS_PUSH),
                true,
                listOf(Manifest.permission.RECEIVE_MMS, Manifest.permission.RECEIVE_WAP_PUSH),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.feature("mms_push")),
            ),
            Shield(
                "call",
                "Placing calls",
                "You confirm every call it places",
                "Places calls without asking you",
                listOf(KEY_CALL),
                true,
                listOf(Manifest.permission.CALL_PHONE),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.feature("call")),
            ),
            Shield(
                "answer_calls",
                "Answering and ending calls",
                "Can't answer or end calls",
                "Can answer and end calls",
                listOf(KEY_ANSWER_CALLS),
                true,
                listOf(Manifest.permission.ANSWER_PHONE_CALLS),
                ShieldGroup.PERSONAL,
                masters = listOf(ShieldSwitch.feature("answer_calls")),
            ),
            Shield(
                "clipboard",
                "Clipboard",
                "Reads the clipboard only right after you tap in it, " +
                    "copied passwords only when you paste",
                "Reads the clipboard at any time, also in the background",
                listOf(KEY_CLIPBOARD),
                true,
                emptyList(),
                ShieldGroup.DEVICE,
                masters = listOf(ShieldSwitch.CLIPBOARD),
                policyKey = OsSettingKey.CLIPBOARD,
            ),
            Shield(
                "sensors",
                "Motion sensors",
                "Gets rounded, slowed sensor data",
                "Gets exact sensor data",
                listOf(KEY_SENSORS),
                true,
                emptyList(),
                ShieldGroup.DEVICE,
                masters = listOf(ShieldSwitch.SENSORS),
                policyKey = OsSettingKey.SENSORS,
            ),
            // Also on the Usage access page (IchthysUsageHistory): same key, same strings.
            Shield(
                "usage",
                "Hide usage history",
                "Sees an empty usage history",
                "Sees which apps you used and when",
                listOf(KEY_USAGE),
                true,
                listOf(Manifest.permission.PACKAGE_USAGE_STATS),
                ShieldGroup.DEVICE,
                labelRes = R.string.ichthys_shield_usage_title,
                protectedRes = R.string.ichthys_shield_usage_on,
                realRes = R.string.ichthys_shield_usage_off,
            ),
            Shield(
                "ble_advertise",
                "Bluetooth advertising",
                "Can't make the phone a beacon",
                "Can make the phone a beacon",
                listOf(KEY_BLE_ADVERTISE),
                true,
                listOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                ShieldGroup.RADIO,
            ),
            Shield(
                "nfc",
                "NFC",
                "Can't read tags or emulate cards",
                "Can read tags and emulate cards",
                listOf(KEY_NFC_BLOCKED),
                false,
                listOf(Manifest.permission.NFC),
                ShieldGroup.RADIO,
            ),
            Shield(
                "wifi_control",
                "Wi-Fi control",
                "Can't switch or change Wi-Fi",
                "Can switch and change Wi-Fi",
                listOf(KEY_WIFI_CONTROL),
                true,
                listOf(Manifest.permission.CHANGE_WIFI_STATE),
                ShieldGroup.RADIO,
            ),
            Shield(
                "uwb",
                "UWB ranging",
                // IchthysOS: UWB only; Bluetooth channel sounding and Wi-Fi RTT aren't covered.
                "Can't measure distance to nearby devices over UWB",
                "Can measure distance to nearby devices over UWB",
                listOf(KEY_UWB),
                true,
                listOf(Manifest.permission.UWB_RANGING, "android.permission.RANGING"),
                ShieldGroup.RADIO,
            ),
            // The two location lists and the scan list are the Location and Scans controls of the
            // app page (LocationMode, ScanMode); listed here so backups and "all else shielded"
            // see them too.
            Shield(
                "location_nofix",
                "Location",
                "Never gets a location, as if indoors",
                "Gets your location when allowed",
                listOf(KEY_LOCATION_NOFIX),
                false,
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                ShieldGroup.LOCATION,
                protectedRes = R.string.ichthys_shield_location_on,
                realRes = R.string.ichthys_shield_location_off,
            ),
            Shield(
                "location_real",
                "Fake location",
                "Sees the fake place, and no Wi-Fi, cell or Bluetooth scans",
                "Sees your real location",
                listOf(KEY_LOCATION_REAL),
                true,
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                ShieldGroup.LOCATION,
                masters = listOf(ShieldSwitch(KEY_SPOOF_ON, com.android.settings.ichthys.guard.model.SettingsTable.SECURE)),
            ),
            Shield(
                "scans",
                "Bluetooth and Wi-Fi scans",
                "Scans find nothing",
                "Scans find the devices around you",
                listOf(KEY_SCAN_EMPTY),
                false,
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                ShieldGroup.LOCATION,
            ),
        )

    fun byId(id: String): Shield? = SHIELDS.firstOrNull { it.id == id }

    /** The rows of one sheet. */
    fun group(group: ShieldGroup): List<Shield> = SHIELDS.filter { it.group == group }

    /** The ALLOW-polarity lists a preset clears ("everything else shielded"), by key. */
    val ALLOW_LIST_KEYS: List<String> =
        SHIELDS.filter { it.listedMeansReal }.flatMap { it.keys }.distinct()

    /** Every per-app list key, for the backup. */
    val ALL_LIST_KEYS: List<String> =
        SHIELDS.flatMap { it.keys }.distinct() + listOf(KEY_STORAGE_SCOPES, KEY_A11Y_SCOPES)

    /** Every master switch, for the backup (key → table). */
    val MASTER_SWITCHES: List<ShieldSwitch> =
        (SHIELD_SECTIONS.flatMap { it.second }.flatMap { it.switches } +
                SHIELDS.flatMap { it.masters } +
                WRITE_TARGETS.map { it.master })
            .distinctBy { it.key }

    /** The 11 master shields the home card counts ("N of 11 on"). */
    val MASTER_ROWS: List<ShieldGroupRow> = SHIELD_SECTIONS.flatMap { it.second }

    /** Every setting the app page shows, so it follows changes made elsewhere while open. */
    val OBSERVED_URIS: List<Uri> by lazy {
        (ALL_LIST_KEYS.map { Settings.Secure.getUriFor(it) } +
                MASTER_SWITCHES.map { it.uri } +
                SPOOF_KEYS.map { Settings.Secure.getUriFor(it) })
            .distinct()
    }

    // ---- the fake place and its spoofed values -------------------------------------------------

    /** The spoofed values, or null when only the position is faked (or nothing is set). */
    fun spoofProfile(context: Context): SpoofProfile? =
        SpoofProfile.fromJson(Settings.Secure.getString(context.contentResolver, KEY_SPOOF_PROFILE))

    /** Writes the spoofed values as one JSON string; null clears them (position only). */
    fun setSpoofProfile(context: Context, profile: SpoofProfile?) {
        Settings.Secure.putString(context.contentResolver, KEY_SPOOF_PROFILE, profile?.toJson())
    }

    /**
     * Sets the fake place in one go: coordinates ("lat,lon"), the name shown for it, the
     * spoofed values derived for it, and the switch on. The platform restarts the spoofed apps.
     */
    fun setFakePlace(context: Context, latLon: String, name: String, profile: SpoofProfile?) {
        val cr = context.contentResolver
        Settings.Secure.putString(cr, KEY_SPOOF_FAKE, latLon)
        Settings.Secure.putString(cr, KEY_SPOOF_FAKE_NAME, name)
        Settings.Secure.putString(cr, KEY_SPOOF_PROFILE, profile?.toJson())
        Settings.Secure.putString(cr, KEY_SPOOF_ON, "1")
    }

    // ---- contacts ":picked" ------------------------------------------------------------------

    /** True when the contacts list holds "pkg:picked" (only the contacts picked for it). */
    fun isContactsPicked(context: Context, pkg: String): Boolean =
        IchthysProtect.readPackages(context, KEY_CONTACTS).contains(pkg + PICKED_SUFFIX)

    /** Writes or removes the "pkg:picked" entry; a bare (real access) entry is removed either way. */
    fun setContactsPicked(context: Context, pkg: String, picked: Boolean) {
        IchthysProtect.setListed(context, KEY_CONTACTS, pkg, false)
        IchthysProtect.setListed(context, KEY_CONTACTS, pkg + PICKED_SUFFIX, picked)
    }

    // ---- storage scope -------------------------------------------------------------------------

    val STORAGE_PERMISSIONS =
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
                JSONObject(
                    Settings.Secure.getString(context.contentResolver, KEY_STORAGE_SCOPES) ?: "{}"
                )
            }
            .getOrElse { JSONObject() }

    /** The app's allowed folders, or null when it isn't scoped. */
    fun storageScope(context: Context, pkg: String): List<String>? {
        val arr = readScopes(context).optJSONArray(pkg) ?: return null
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    /** Stores the folders (relative to shared storage, e.g. "Download/"), or removes the scope. */
    fun setStorageScope(context: Context, pkg: String, folders: List<String>?) {
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
            KEY_STORAGE_SCOPES,
            if (scopes.length() == 0) null else scopes.toString(),
        )
    }

    /** "contacts", "contacts and calendar", "contacts, calendar and call log". */
    fun nouns(targets: List<WriteTarget>): String =
        when (targets.size) {
            0 -> ""
            1 -> targets[0].noun
            else -> targets.dropLast(1).joinToString { it.noun } + " and " + targets.last().noun
        }
}
