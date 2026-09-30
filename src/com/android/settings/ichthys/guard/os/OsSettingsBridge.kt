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
import android.provider.Settings
import android.util.Log
import com.android.settings.ichthys.guard.model.OsSettingKey
import com.android.settings.ichthys.guard.model.PolicySet
import com.android.settings.ichthys.guard.model.SettingsTable

/**
 * Pushes the policy set's OS exceptions into the settings the Build 2 platform hooks read.
 *
 * Writing Secure/Global settings needs android.permission.WRITE_SECURE_SETTINGS, which Settings
 * holds (platform-signed, privileged, in its privapp allowlist).
 */
class OsSettingsBridge(private val resolver: ContentResolver) {

    /** @return false if the caller lacks WRITE_SECURE_SETTINGS. */
    fun apply(set: PolicySet): Boolean {
        val values = set.osSettingValues()
        return try {
            for (key in OsSettingKey.entries) {
                if (!key.writtenByGuard) continue
                val value = values.getValue(key).ifEmpty { null }
                when (key.table) {
                    SettingsTable.SECURE -> Settings.Secure.putString(resolver, key.key, value)
                    SettingsTable.GLOBAL -> Settings.Global.putString(resolver, key.key, value)
                }
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS; OS exceptions not applied", e)
            false
        }
    }

    fun read(key: OsSettingKey): String? =
        when (key.table) {
            SettingsTable.SECURE -> Settings.Secure.getString(resolver, key.key)
            SettingsTable.GLOBAL -> Settings.Global.getString(resolver, key.key)
        }

    private companion object {
        const val TAG = "IchthysGuard"
    }
}
