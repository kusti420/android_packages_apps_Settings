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

import android.app.settings.SettingsEnums
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.ichthys.protect.IchthysProtect.DefaultReason
import java.util.concurrent.Executors

/**
 * Accessibility → (service) → App access: which apps' windows and events this accessibility
 * service may see (Guard decision 36). Opened with [IchthysProtect.a11yScopeIntent].
 */
class A11yScopeSettings : SettingsPreferenceFragment() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: ComponentName? = null

    private data class AppRow(
        val packageName: String,
        val label: CharSequence,
        val icon: Drawable?,
        val reason: DefaultReason,
        var visible: Boolean = true,
    )

    override fun getMetricsCategory(): Int = SettingsEnums.PAGE_UNKNOWN

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        val context = prefContext
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        service = (activity?.intent?.getStringExtra(IchthysProtect.EXTRA_SERVICE)
            ?: arguments?.getString(IchthysProtect.EXTRA_SERVICE))
            ?.let { ComponentName.unflattenFromString(it) }
        if (service == null) {
            finish()
            return
        }
        preferenceScreen.addPreference(
            Preference(context).apply {
                key = KEY_INTRO
                isSelectable = false
                isPersistent = false
            }
        )
        preferenceScreen.addPreference(
            Preference(context).apply {
                key = KEY_RESET
                title = getString(R.string.ichthys_a11y_scope_reset)
                isPersistent = false
                setOnPreferenceClickListener {
                    service?.let { IchthysProtect.resetScope(requireContext(), it) }
                    refresh()
                    true
                }
            }
        )
        preferenceScreen.addPreference(
            PreferenceCategory(context).apply {
                key = KEY_APPS
                title = getString(R.string.ichthys_a11y_scope_apps)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val component = service ?: return
        val context = requireContext().applicationContext
        executor.execute {
            val pm = context.packageManager
            val rows = mutableMapOf<String, AppRow>()
            // The lock screen and Settings are not launcher apps; list them first.
            for (pkg in listOf(IchthysProtect.SYSTEMUI_PACKAGE, "com.android.settings")) {
                val label = if (pkg == IchthysProtect.SYSTEMUI_PACKAGE) {
                    context.getString(R.string.ichthys_a11y_scope_lock_screen)
                } else {
                    appLabel(pm, pkg) ?: continue
                }
                rows[pkg] = AppRow(pkg, label, appIcon(pm, pkg),
                    IchthysProtect.defaultReason(context, pkg))
            }
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (ri in pm.queryIntentActivities(launcher, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg in rows || pkg == component.packageName) continue
                rows[pkg] = AppRow(pkg, ri.loadLabel(pm), ri.loadIcon(pm),
                    IchthysProtect.defaultReason(context, pkg))
            }
            val thirdParty = IchthysProtect.isThirdPartyService(context, component)
            val scope = IchthysProtect.readScopes(context)[component.flattenToString()]
            val serviceLabel = appLabel(pm, component.packageName) ?: component.packageName
            val pinned = rows.values.filter {
                it.packageName == IchthysProtect.SYSTEMUI_PACKAGE ||
                    it.packageName == "com.android.settings"
            }
            val others = rows.values.filter { it !in pinned }.sortedBy { it.label.toString() }
            for (row in rows.values) {
                row.visible = IchthysProtect.canSee(
                    context, component, row.packageName, scope, thirdParty)
            }
            mainHandler.post {
                if (isAdded) bind(serviceLabel, thirdParty, scope, pinned + others)
            }
        }
    }

    private fun bind(
        serviceLabel: CharSequence,
        thirdParty: Boolean,
        scope: IchthysProtect.Scope?,
        rows: List<AppRow>,
    ) {
        val component = service ?: return
        findPreference<Preference>(KEY_INTRO)?.summary = getString(
            if (thirdParty) R.string.ichthys_a11y_scope_intro_third_party
            else R.string.ichthys_a11y_scope_intro_system,
            serviceLabel,
        )
        findPreference<Preference>(KEY_RESET)?.isEnabled = scope != null
        val group = findPreference<PreferenceCategory>(KEY_APPS) ?: return
        group.removeAll()
        for (row in rows) {
            group.addPreference(
                SwitchPreferenceCompat(prefContext).apply {
                    key = "app:${row.packageName}"
                    title = row.label
                    icon = row.icon
                    isPersistent = false
                    summary = reasonSummary(thirdParty, row.reason)
                    isChecked = row.visible
                    setOnPreferenceChangeListener { _, value ->
                        IchthysProtect.setCanSee(
                            requireContext(), component, row.packageName, value as Boolean)
                        findPreference<Preference>(KEY_RESET)?.isEnabled = true
                        true
                    }
                }
            )
        }
    }

    private fun reasonSummary(thirdParty: Boolean, reason: DefaultReason): CharSequence? {
        if (!thirdParty) return null
        return when (reason) {
            DefaultReason.NONE -> null
            DefaultReason.SETTINGS -> getString(R.string.ichthys_a11y_scope_reason_settings)
            DefaultReason.LOCK_SCREEN -> getString(R.string.ichthys_a11y_scope_reason_lock_screen)
            DefaultReason.PASSWORDS -> getString(R.string.ichthys_a11y_scope_reason_passwords)
            DefaultReason.BANKING -> getString(R.string.ichthys_a11y_scope_reason_banking)
        }
    }

    private fun appLabel(pm: PackageManager, pkg: String): CharSequence? =
        try {
            pm.getApplicationInfo(pkg, 0).loadLabel(pm)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun appIcon(pm: PackageManager, pkg: String): Drawable? =
        try {
            pm.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    companion object {
        private const val KEY_INTRO = "ichthys_a11y_scope_intro"
        private const val KEY_RESET = "ichthys_a11y_scope_reset"
        private const val KEY_APPS = "ichthys_a11y_scope_apps"
    }
}
