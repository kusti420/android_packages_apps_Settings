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

/** Settings → Storage → Gallery clean-up. */
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
        // Filtered by MediaProvider at query time: no markers to place, nothing to rescan.
        findPreference<TwoStatePreference>(GalleryCleanup.KEY_HIDE_TINY)?.apply {
            isChecked = GalleryCleanup.isTinyHidden(context)
            setOnPreferenceChangeListener { _, value ->
                GalleryCleanup.setTinyHidden(context, value as Boolean)
                true
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
            postFolders(context)
        }
    }

    /**
     * IchthysOS: the folder list is read on [executor]. hiddenFolders() waits for the lock that
     * GalleryCleanup.apply() holds while it walks shared storage (also from the clean-up job), so
     * reading it on the main thread could freeze the page for as long as a scan takes.
     */
    private fun refreshFolders() {
        val context = requireContext().applicationContext
        executor.execute { postFolders(context) }
    }

    /** Executor thread: reads the folders, then shows them on the main thread. */
    private fun postFolders(context: android.content.Context) {
        val hidden = GalleryCleanup.hiddenFolders(context).map { it.relativePath to true }
        val shown = GalleryCleanup.alwaysShow(context).map { it to false }
        val rows = (hidden + shown).sortedBy { it.first }
        mainHandler.post { if (isAdded) bindFolders(rows) }
    }

    /** One switch per folder: on = hidden; off = always show (kept in the list to re-hide). */
    private fun bindFolders(rows: List<Pair<String, Boolean>>) {
        val group = findPreference<PreferenceCategory>(KEY_FOLDERS) ?: return
        val context = requireContext().applicationContext
        group.removeAll()
        for ((path, isHidden) in rows) {
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
