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

package com.android.settings.security.screenlock

import android.content.Context
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN
import com.android.settings.core.PreferenceControllerMixin
import com.android.settingslib.core.AbstractPreferenceController
import lineageos.providers.LineageSettings

/** Shuffles the bouncer's PIN pad digits (read by SystemUI's PinBouncerViewModel). */
class PinScramblePreferenceController(
    context: Context,
    private val userId: Int,
    private val lockPatternUtils: LockPatternUtils
) : AbstractPreferenceController(context), PreferenceControllerMixin,
    Preference.OnPreferenceChangeListener {

    companion object {
        private const val PREF_KEY = "lockscreen_scramble_pin_layout"
    }

    override fun isAvailable(): Boolean =
        lockPatternUtils.getCredentialTypeForUser(userId) == CREDENTIAL_TYPE_PIN

    override fun getPreferenceKey(): String = PREF_KEY

    override fun onPreferenceChange(preference: Preference, value: Any): Boolean =
        LineageSettings.System.putIntForUser(
            mContext.contentResolver,
            LineageSettings.System.LOCKSCREEN_PIN_SCRAMBLE_LAYOUT,
            if (value as Boolean) 1 else 0,
            userId
        )

    override fun updateState(preference: Preference) {
        (preference as TwoStatePreference).isChecked =
            LineageSettings.System.getIntForUser(
                mContext.contentResolver,
                LineageSettings.System.LOCKSCREEN_PIN_SCRAMBLE_LAYOUT,
                0,
                userId
            ) != 0
    }
}
