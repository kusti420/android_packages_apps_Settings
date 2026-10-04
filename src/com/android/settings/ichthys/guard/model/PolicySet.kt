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

package com.android.settings.ichthys.guard.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** All per-app policies of one user. Apps without an entry are Shielded (defaults). */
@Serializable
data class PolicySet(val policies: Map<String, AppPolicy> = emptyMap()) {

    fun policyFor(packageName: String): AppPolicy = policies[packageName] ?: AppPolicy(packageName)

    fun with(policy: AppPolicy): PolicySet =
        if (policy.isDefault) {
            PolicySet(policies - policy.packageName)
        } else {
            PolicySet(policies + (policy.packageName to policy))
        }

    fun update(packageName: String, change: (AppPolicy) -> AppPolicy): PolicySet =
        with(change(policyFor(packageName)))

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun fromJson(text: String): PolicySet = json.decodeFromString(serializer(), text)
    }
}

/** Where a setting lives in android.provider.Settings. */
enum class SettingsTable {
    SECURE,
    GLOBAL,
}

/**
 * The per-user OS package lists Guard mirrors in [AppPolicy]: comma-separated, no spaces, parsed
 * by the platform with String.split(","). A listed package is exempt from that shield (it gets
 * real access). The setting is the source of truth: other writers (the permission dialog, the
 * config service) edit the same keys, so Guard only ever adds or removes one package
 * (OsSettingsBridge.setListed) and mirrors the list back into its policies
 * (GuardRepository.syncOsListsFromSettings). It never rewrites a whole key.
 */
enum class OsSettingKey(
    val key: String,
    /** Whether [AppPolicy] says the app is listed. */
    val selector: (AppPolicy) -> Boolean,
    /** The policy with the app listed or not. */
    val assign: (AppPolicy, Boolean) -> AppPolicy,
) {
    /** Read by ContactsProvider. A bare entry is real contacts; "pkg:picked" is not. */
    CONTACTS(
        "ichthys_contacts_allowed_pkgs",
        { it.contactsAllowed },
        { p, on -> p.copy(contactsAllowed = on) },
    ),
    /** Read by ClipboardService. */
    CLIPBOARD(
        "ichthys_clipboard_allowed_pkgs",
        { it.clipboardAllowed },
        { p, on -> p.copy(clipboardAllowed = on) },
    ),
    /** Read by SensorService, which pushes it to the native sensor service. */
    SENSORS(
        "ichthys_sensor_exempt_pkgs",
        { it.sensorsExempt },
        { p, on -> p.copy(sensorsExempt = on) },
    ),
}
