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

    /**
     * Values of the OS allowlist settings, in the format the platform parses (see OsSettingKey).
     */
    fun osSettingValues(): Map<OsSettingKey, String> =
        OsSettingKey.entries.associateWith { key ->
            policies.values.filter(key.selector).map { it.packageName }.sorted().joinToString(",")
        }

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
 * The Build 2 OS hooks Guard drives: comma-separated package lists, no spaces, parsed with
 * String.split(","). Secure ones are per user. See docs/ARCHITECTURE.md "Integration contract".
 */
enum class OsSettingKey(
    val key: String,
    val table: SettingsTable,
    val selector: (AppPolicy) -> Boolean,
    /** Implemented in Guard phase 1; the others are written once their OS side has landed. */
    val writtenByGuard: Boolean,
) {
    CONTACTS("ichthys_contacts_allowed_pkgs", SettingsTable.SECURE, { it.contactsAllowed }, true),
    CLIPBOARD(
        "ichthys_clipboard_allowed_pkgs",
        SettingsTable.SECURE,
        { it.clipboardAllowed },
        true,
    ),
    SENSORS("ichthys_sensor_exempt_pkgs", SettingsTable.SECURE, { it.sensorsExempt }, false),
    UNRESTRICTED_BG(
        "ichthys_unrestricted_bg_pkgs",
        SettingsTable.GLOBAL,
        { it.unrestrictedBackground },
        false,
    ),
}
