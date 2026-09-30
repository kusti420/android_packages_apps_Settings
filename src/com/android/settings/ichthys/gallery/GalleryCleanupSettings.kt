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

package com.android.settings.ichthys.gallery

import android.app.settings.SettingsEnums
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.ichthys.gallery.GalleryCleanup.Category
import java.util.concurrent.Executors

/** Settings → Storage → Gallery clean-up (also opened from Guard; same state). */
class GalleryCleanupSettings : SettingsPreferenceFragment() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun getMetricsCategory(): Int = SettingsEnums.PAGE_UNKNOWN

    override fun getPreferenceScreenResId(): Int = R.xml.ichthys_gallery_cleanup

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        val context = requireContext().applicationContext
        for (category in Category.values()) {
            findPreference<TwoStatePreference>(category.key)?.apply {
                isChecked = GalleryCleanup.isEnabled(context, category)
                setOnPreferenceChangeListener { _, value ->
                    GalleryCleanup.setEnabled(context, category, value as Boolean)
                    applyInBackground()
                    true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFolders()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun applyInBackground() {
        val context = requireContext().applicationContext
        executor.execute {
            GalleryCleanup.apply(context)
            GalleryCleanup.schedule(context)
            mainHandler.post { if (isAdded) refreshFolders() }
        }
    }

    /** One switch per folder: on = hidden; off = always show (kept in the list to re-hide). */
    private fun refreshFolders() {
        val group = findPreference<PreferenceCategory>(KEY_FOLDERS) ?: return
        val context = requireContext().applicationContext
        group.removeAll()
        val hidden = GalleryCleanup.hiddenFolders(context).map { it.relativePath to true }
        val shown = GalleryCleanup.alwaysShow(context).map { it to false }
        for ((path, isHidden) in (hidden + shown).sortedBy { it.first }) {
            group.addPreference(
                SwitchPreferenceCompat(prefContext).apply {
                    key = "folder:$path"
                    title = path.trimEnd('/')
                    isChecked = isHidden
                    isPersistent = false
                    setOnPreferenceChangeListener { _, value ->
                        GalleryCleanup.setAlwaysShow(context, path, !(value as Boolean))
                        applyInBackground()
                        true
                    }
                }
            )
        }
        group.isVisible = group.preferenceCount > 0
    }

    companion object {
        private const val KEY_FOLDERS = "ichthys_gallery_folders"
    }
}
