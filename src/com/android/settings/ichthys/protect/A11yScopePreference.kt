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

package com.android.settings.ichthys.protect

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import com.android.settings.R
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.UI_ONLY_PREFERENCE

/**
 * Row on an accessibility service's detail screen (A11yServiceScreen) that opens its
 * "App access" scope page ([A11yScopeSettings]).
 */
class A11yScopePreference(private val serviceInfo: AccessibilityServiceInfo) :
    PreferenceMetadata {
    override val key: String
        get() = KEY

    override val purpose: Int
        get() = R.string.ichthys_a11y_scope_purpose

    override val title: Int
        get() = R.string.ichthys_a11y_scope_title

    override val summary: Int
        get() = R.string.ichthys_a11y_scope_summary

    override fun tags(context: Context) = arrayOf(UI_ONLY_PREFERENCE)

    override val indexable
        get() = false

    override fun intent(context: Context): Intent? =
        IchthysProtect.a11yScopeIntent(context, serviceInfo.componentName)

    companion object {
        const val KEY = "ichthys_a11y_scope"
    }
}
