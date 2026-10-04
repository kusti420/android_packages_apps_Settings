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

import android.Manifest
import android.app.settings.SettingsEnums
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.PrimarySwitchPreference
import java.util.concurrent.Executors

/**
 * "No location" (Guard decision 10): per app, location requests never deliver a fix, as if the
 * phone were indoors without signal, while the app keeps its permission. Tapping an app opens
 * its stock location permission page (precise location, only while in use). Opened with
 * [IchthysProtect.noLocationIntent]; [ARG_PACKAGE] scrolls to one app.
 */
class LocationNoFixSettings : SettingsPreferenceFragment() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** The list is rebuilt on every resume; the jump to [ARG_PACKAGE] happens only once. */
    private var scrolledToApp = false
    /** The apps on screen, by package. */
    private var shown: Map<String, AppRow> = emptyMap()
    private val observer =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) = syncFromSetting()
        }

    private data class AppRow(
        val packageName: String,
        val label: CharSequence,
        val icon: Drawable?,
        val granted: Boolean,
    )

    override fun getMetricsCategory(): Int = SettingsEnums.PAGE_UNKNOWN

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        val context = prefContext
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen.addPreference(
            Preference(context).apply {
                key = KEY_INTRO
                summary = getString(R.string.ichthys_location_nofix_intro)
                isSelectable = false
                isPersistent = false
            }
        )
        preferenceScreen.addPreference(
            PreferenceCategory(context).apply {
                key = KEY_APPS
                title = getString(R.string.ichthys_location_nofix_apps)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scrolledToApp = savedInstanceState?.getBoolean(STATE_SCROLLED, false) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SCROLLED, scrolledToApp)
    }

    override fun onResume() {
        super.onResume()
        requireContext().contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(IchthysProtect.KEY_LOCATION_NOFIX_PKGS),
            false,
            observer,
        )
        refresh()
    }

    override fun onPause() {
        requireContext().contentResolver.unregisterContentObserver(observer)
        super.onPause()
    }

    /**
     * The setting changed, here or in Guard's Data access card (the same key): show what it holds
     * now, without rebuilding the list.
     */
    private fun syncFromSetting() {
        val context = context ?: return
        val group = findPreference<PreferenceCategory>(KEY_APPS) ?: return
        val noFix = IchthysProtect.readPackages(context, IchthysProtect.KEY_LOCATION_NOFIX_PKGS)
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i) as? PrimarySwitchPreference ?: continue
            val row = shown[pref.key.removePrefix(KEY_APP_PREFIX)] ?: continue
            val checked = row.packageName in noFix
            if (pref.isChecked != checked) pref.isChecked = checked
            pref.summary = summaryFor(row, checked)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val context = requireContext().applicationContext
        executor.execute {
            val pm = context.packageManager
            val rows = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { it.applicationInfo != null && wantsLocation(it) && isUserFacing(it) }
                .map { info ->
                    val app = info.applicationInfo!!
                    AppRow(
                        info.packageName,
                        app.loadLabel(pm),
                        app.loadIcon(pm),
                        isGranted(pm, info.packageName),
                    )
                }
                .sortedWith(compareBy({ !it.granted }, { it.label.toString() }))
            val noFix = IchthysProtect.readPackages(context, IchthysProtect.KEY_LOCATION_NOFIX_PKGS)
            mainHandler.post { if (isAdded) bind(rows, noFix) }
        }
    }

    private fun bind(rows: List<AppRow>, noFix: Set<String>) {
        val group = findPreference<PreferenceCategory>(KEY_APPS) ?: return
        group.removeAll()
        shown = rows.associateBy { it.packageName }
        for (row in rows) {
            group.addPreference(
                PrimarySwitchPreference(prefContext).apply {
                    key = KEY_APP_PREFIX + row.packageName
                    title = row.label
                    icon = row.icon
                    isPersistent = false
                    isChecked = row.packageName in noFix
                    summary = summaryFor(row, isChecked)
                    intent = IchthysProtect.locationPermissionIntent(
                        requireContext(), row.packageName)
                    setOnPreferenceChangeListener { pref, value ->
                        val checked = value as Boolean
                        IchthysProtect.setListed(
                            requireContext(), IchthysProtect.KEY_LOCATION_NOFIX_PKGS,
                            row.packageName, checked)
                        pref.summary = summaryFor(row, checked)
                        true
                    }
                }
            )
        }
        if (scrolledToApp) return
        scrolledToApp = true
        (activity?.intent?.getStringExtra(ARG_PACKAGE) ?: arguments?.getString(ARG_PACKAGE))
            ?.let { scrollToPreference(KEY_APP_PREFIX + it) }
    }

    private fun summaryFor(row: AppRow, noFix: Boolean): CharSequence = getString(
        when {
            noFix -> R.string.ichthys_shield_location_on
            row.granted -> R.string.ichthys_shield_location_off
            else -> R.string.ichthys_location_nofix_not_granted
        }
    )

    private fun wantsLocation(info: PackageInfo): Boolean =
        info.requestedPermissions?.any {
            it == Manifest.permission.ACCESS_FINE_LOCATION ||
                it == Manifest.permission.ACCESS_COARSE_LOCATION
        } == true

    /** Third-party apps and updated system apps. */
    private fun isUserFacing(info: PackageInfo): Boolean {
        val app = info.applicationInfo ?: return false
        return app.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
            app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    private fun isGranted(pm: PackageManager, pkg: String): Boolean =
        pm.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, pkg) ==
            PackageManager.PERMISSION_GRANTED ||
            pm.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, pkg) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Optional fragment argument / intent extra: the app to scroll to. */
        const val ARG_PACKAGE = "package"
        private const val KEY_INTRO = "ichthys_location_nofix_intro"
        private const val KEY_APPS = "ichthys_location_nofix_apps"
        private const val KEY_APP_PREFIX = "app:"
        private const val STATE_SCROLLED = "ichthys_location_nofix_scrolled"
    }
}
