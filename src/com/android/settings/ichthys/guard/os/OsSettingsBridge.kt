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

import android.content.ContentResolver
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.android.settings.ichthys.guard.model.OsSettingKey
import com.android.settings.ichthys.guard.model.SettingsTable

/**
 * Guard's access to the per-app OS exception lists ([OsSettingKey]). The settings are shared with
 * other writers, so every edit touches one package and keeps every other entry.
 *
 * Writing Secure/Global settings needs android.permission.WRITE_SECURE_SETTINGS, which Settings
 * holds (platform-signed, privileged, in its privapp allowlist).
 */
class OsSettingsBridge(private val resolver: ContentResolver) {

    /**
     * Adds [pkg] to the list (real access) or removes it (back to the protected answer), keeping
     * every other entry. For contacts that includes the "pkg:picked" entries of other apps
     * written by the permission dialog; the app's own are replaced.
     *
     * @return false if the caller lacks WRITE_SECURE_SETTINGS.
     */
    fun setListed(key: OsSettingKey, pkg: String, listed: Boolean): Boolean =
        try {
            val rest = entries(key).filter { it != pkg && !it.startsWith("$pkg:") }
            val next = if (listed) rest + pkg else rest
            Settings.Secure.putString(resolver, key.key, next.joinToString(",").ifEmpty { null })
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS; ${key.key} not written", e)
            false
        }

    /** The listed packages: bare entries (for contacts, "pkg:picked" is not real access). */
    fun listedPackages(key: OsSettingKey): Set<String> =
        entries(key).filter { ':' !in it }.toSet()

    private fun entries(key: OsSettingKey): List<String> =
        Settings.Secure.getString(resolver, key.key)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    companion object {
        private const val TAG = "IchthysGuard"

        fun uriFor(key: OsSettingKey): Uri = Settings.Secure.getUriFor(key.key)
    }
}

/**
 * One OS master switch of a shield, for every app at once. Unset or non-zero is on, 0 is off:
 * the readers (ContactsProvider, CalendarProvider, TelephonyProvider, telephony, Telecom,
 * ClipboardService, SensorService) all default to on.
 */
data class ShieldSwitch(val key: String, val table: SettingsTable) {
    val uri: Uri
        get() =
            when (table) {
                SettingsTable.SECURE -> Settings.Secure.getUriFor(key)
                SettingsTable.GLOBAL -> Settings.Global.getUriFor(key)
            }

    fun isOn(resolver: ContentResolver): Boolean =
        when (table) {
            SettingsTable.SECURE -> Settings.Secure.getInt(resolver, key, 1)
            SettingsTable.GLOBAL -> Settings.Global.getInt(resolver, key, 1)
        } != 0

    /** @return false if the caller lacks WRITE_SECURE_SETTINGS. */
    fun setOn(resolver: ContentResolver, on: Boolean): Boolean =
        try {
            val value = if (on) 1 else 0
            when (table) {
                SettingsTable.SECURE -> Settings.Secure.putInt(resolver, key, value)
                SettingsTable.GLOBAL -> Settings.Global.putInt(resolver, key, value)
            }
            true
        } catch (e: SecurityException) {
            Log.w("IchthysGuard", "no WRITE_SECURE_SETTINGS; $key not written", e)
            false
        }

    companion object {
        /** Settings.Global: the contacts read shield (ContactsProvider). */
        val CONTACTS = ShieldSwitch("ichthys_contacts_lockdown", SettingsTable.GLOBAL)
        /** Settings.Global: the clipboard shield (ClipboardService). */
        val CLIPBOARD = ShieldSwitch("ichthys_clipboard_lockdown", SettingsTable.GLOBAL)
        /** Settings.Global: sensor anti-fingerprinting (SensorService). */
        val SENSORS = ShieldSwitch("ichthys_sensor_fingerprint_protection", SettingsTable.GLOBAL)

        /**
         * Settings.Secure ichthys_<feature>_shield, the per-user switch of an IchthysShield
         * feature: calendar, call_log, call_log_write, contacts_write, calendar_write, sms_read,
         * sms_send, mms_push, phone_number, call, answer_calls.
         */
        fun feature(name: String) = ShieldSwitch("ichthys_${name}_shield", SettingsTable.SECURE)
    }
}

/**
 * One row of Guard Home's shields card: a kind of data and the switches that protect it, turned
 * on and off together (a read shield with its write shield, the three SMS shields).
 */
data class ShieldGroupRow(
    val label: String,
    val protectedText: String,
    val switches: List<ShieldSwitch>,
)

/** The rows of the shields card, by section. Every OS master switch is in exactly one row. */
val SHIELD_SECTIONS: List<Pair<String, List<ShieldGroupRow>>> =
    listOf(
        "Contacts & calendar" to
            listOf(
                ShieldGroupRow(
                    "Contacts",
                    "Apps see an empty address book and their changes aren't saved",
                    listOf(ShieldSwitch.CONTACTS, ShieldSwitch.feature("contacts_write")),
                ),
                ShieldGroupRow(
                    "Calendar",
                    "Apps see an empty calendar and their changes aren't saved",
                    listOf(
                        ShieldSwitch.feature("calendar"),
                        ShieldSwitch.feature("calendar_write"),
                    ),
                ),
            ),
        "Calls & messages" to
            listOf(
                ShieldGroupRow(
                    "Call log",
                    "Apps see an empty call log and their changes aren't saved",
                    listOf(
                        ShieldSwitch.feature("call_log"),
                        ShieldSwitch.feature("call_log_write"),
                    ),
                ),
                ShieldGroupRow(
                    "Calls",
                    "You confirm calls apps place; they can't answer or end calls",
                    listOf(ShieldSwitch.feature("call"), ShieldSwitch.feature("answer_calls")),
                ),
                ShieldGroupRow(
                    "Text messages",
                    "Apps read an empty inbox, get no MMS, and you confirm texts they send",
                    listOf(
                        ShieldSwitch.feature("sms_read"),
                        ShieldSwitch.feature("sms_send"),
                        ShieldSwitch.feature("mms_push"),
                    ),
                ),
                ShieldGroupRow(
                    "Phone number",
                    "Apps get no phone number",
                    listOf(ShieldSwitch.feature("phone_number")),
                ),
            ),
        "Sensors & clipboard" to
            listOf(
                ShieldGroupRow(
                    "Motion sensors",
                    "Apps get rounded, slowed sensor data that can't fingerprint the phone",
                    listOf(ShieldSwitch.SENSORS),
                ),
                ShieldGroupRow(
                    "Clipboard",
                    "Apps read the clipboard only right after you tap in them, " +
                        "copied passwords only when you paste",
                    listOf(ShieldSwitch.CLIPBOARD),
                ),
            ),
    )
