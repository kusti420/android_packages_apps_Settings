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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settings.R

/**
 * "Hide usage history" on Settings → Special app access → Usage access → (app) (Guard decision
 * 34). The same switch as the row in Guard's Data access card, with the same strings and the same
 * polarity: on (the default) = the app sees an empty usage history, off = it sees the real one.
 * Only the presentation is inverted; the stored list keeps its meaning: an app sees the real
 * history when it is listed in Secure [IchthysProtect.KEY_USAGE_FULL_PKGS]. System apps are not
 * affected, so the switch is hidden for them. Called from UsageAccessDetails.
 *
 * The list is per user and UsageStatsService reads the calling app's user's, so the page passes
 * the user of the app it shows (it can be a work-profile app).
 */
object IchthysUsageHistory {
    private const val KEY = "ichthys_usage_full_history"

    /** Follows the setting while it is on screen, so it can't disagree with Guard's row. */
    private class UsageShieldPreference(
        context: Context,
        private val packageName: String,
        private val userId: Int,
    ) : SwitchPreferenceCompat(context) {
        private val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = sync()
            }

        fun sync() {
            val hidden =
                !IchthysProtect.isListed(
                    context, IchthysProtect.KEY_USAGE_FULL_PKGS, packageName, userId)
            isChecked = hidden
            summary = summaryFor(context, hidden)
        }

        override fun onAttached() {
            super.onAttached()
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(IchthysProtect.KEY_USAGE_FULL_PKGS),
                false,
                observer,
                userId,
            )
            sync()
        }

        override fun onDetached() {
            context.contentResolver.unregisterContentObserver(observer)
            super.onDetached()
        }
    }

    @JvmStatic
    fun addSwitch(
        screen: PreferenceScreen,
        packageName: String,
        userId: Int,
    ): SwitchPreferenceCompat {
        val context = screen.context
        val pref = UsageShieldPreference(context, packageName, userId).apply {
            key = KEY
            title = context.getString(R.string.ichthys_shield_usage_title)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val hidden = value as Boolean
                // Listed = the app sees the real history, so hiding it removes the entry.
                IchthysProtect.setListed(
                    context, IchthysProtect.KEY_USAGE_FULL_PKGS, packageName, !hidden, userId)
                summary = summaryFor(context, hidden)
                true
            }
        }
        screen.addPreference(pref)
        return pref
    }

    @JvmStatic
    fun refresh(pref: SwitchPreferenceCompat?, packageName: String, userId: Int,
            appInfo: ApplicationInfo?, hasAccess: Boolean) {
        if (pref == null) return
        val system = appInfo != null && appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        pref.isVisible = !system
        val hidden = !IchthysProtect.isListed(
            pref.context, IchthysProtect.KEY_USAGE_FULL_PKGS, packageName, userId)
        pref.isChecked = hidden
        pref.isEnabled = hasAccess
        pref.summary = summaryFor(pref.context, hidden)
    }

    private fun summaryFor(context: Context, hidden: Boolean): CharSequence =
        context.getString(
            if (hidden) R.string.ichthys_shield_usage_on else R.string.ichthys_shield_usage_off)
}
