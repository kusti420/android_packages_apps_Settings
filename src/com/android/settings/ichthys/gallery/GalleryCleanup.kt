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

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Gallery clean-up (W8b decision 31): keeps clutter out of gallery apps with the stock `.nomedia`
 * mechanism. Files are never moved or deleted; only `.nomedia` markers that carry [MARKER] are
 * ever created or removed, so markers placed by the user or by apps are left alone.
 *
 * Two cases can't use `.nomedia`, since it hides whole folders: tiny images ([KEY_HIDE_TINY]) and
 * cover art sitting next to music (part of [Category.ART_CACHES]). MediaProvider reads the same
 * Settings.Secure keys and filters those images out of gallery queries itself (IchthysMediaPolicy),
 * re-notifying gallery apps when a switch changes, so no rescan is needed for them.
 *
 * Settings lives in Settings.Secure (edited in Settings → Storage). The markers placed
 * so far are remembered in Settings' own data dir, because a hidden folder disappears from
 * MediaStore and can't be found again by querying it.
 */
object GalleryCleanup {
    private const val TAG = "GalleryCleanup"

    const val KEY_ALWAYS_SHOW = "ichthys_gallery_always_show"

    /**
     * Images of 128×128 px or smaller; filtered by MediaProvider, no markers involved. On unless
     * switched off (MediaProvider's IchthysMediaPolicy reads it with the same default).
     */
    const val KEY_HIDE_TINY = "ichthys_gallery_hide_tiny"

    /** [defaultOn] is the state of a category whose key was never written. */
    enum class Category(val key: String, val defaultOn: Boolean = false) {
        /** `Android/media/<pkg>`: app caches, previews and thumbnails (messengers excluded). */
        APP_MEDIA("ichthys_gallery_hide_app_media"),
        /** Messenger sticker and animated GIF folders; received photos stay visible. */
        STICKERS("ichthys_gallery_hide_stickers"),
        /**
         * Folders holding nothing but cover art (music players' art caches), no audio. Cover art
         * next to audio is hidden by MediaProvider under the same key, keeping the songs visible.
         */
        ART_CACHES("ichthys_gallery_hide_art_caches", defaultOn = true),
    }

    data class HiddenFolder(val relativePath: String, val category: Category)

    private const val MARKER = "ichthys-gallery-cleanup"
    private const val STATE_FILE = "gallery_cleanup_markers"
    private const val JOB_ID = 0x1c470031

    private val MESSENGERS =
        setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "org.thoughtcrime.securesms",
            "im.molly.app",
            "com.discord",
            "com.facebook.orca",
            "com.viber.voip",
        )
    private val STICKER_DIR = Regex("(?i).*\\b(stickers|animated gifs)$")
    private val ART_NAME =
        Regex("(?i)^(albumart.*|cover|folder|front|artwork|album)\\.(jpe?g|png|webp)$")
    /** Top-level folders whose future content must never be hidden by an art-cache guess. */
    private val PROTECTED_ROOTS =
        setOf("dcim", "pictures", "download", "documents", "movies", "recordings", "screenshots")

    private val lock = Any()

    fun isEnabled(context: Context, category: Category): Boolean {
        val unset = if (category.defaultOn) 1 else 0
        return Settings.Secure.getInt(context.contentResolver, category.key, unset) != 0
    }

    fun setEnabled(context: Context, category: Category, enabled: Boolean) {
        Settings.Secure.putInt(context.contentResolver, category.key, if (enabled) 1 else 0)
    }

    fun anyEnabled(context: Context): Boolean = Category.values().any { isEnabled(context, it) }

    fun isTinyHidden(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_HIDE_TINY, 1) != 0

    /** MediaProvider watches the key and tells gallery apps to query again. */
    fun setTinyHidden(context: Context, hidden: Boolean) {
        Settings.Secure.putInt(context.contentResolver, KEY_HIDE_TINY, if (hidden) 1 else 0)
    }

    fun alwaysShow(context: Context): Set<String> =
        Settings.Secure.getString(context.contentResolver, KEY_ALWAYS_SHOW)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet() ?: emptySet()

    fun setAlwaysShow(context: Context, relativePath: String, show: Boolean) {
        val paths = alwaysShow(context).toMutableSet()
        if (show) paths.add(relativePath) else paths.remove(relativePath)
        Settings.Secure.putString(
            context.contentResolver,
            KEY_ALWAYS_SHOW,
            paths.sorted().joinToString("\n").ifEmpty { null },
        )
    }

    /** Folders currently hidden by this feature. */
    fun hiddenFolders(context: Context): List<HiddenFolder> =
        synchronized(lock) { loadState(context).sortedBy { it.relativePath } }

    /**
     * Brings the markers in line with the settings: places missing ones, removes ours where the
     * category is off or the folder is set to always show. Blocking; call off the main thread.
     */
    fun apply(context: Context) {
        synchronized(lock) {
            val root = Environment.getExternalStorageDirectory()
            val alwaysShow = alwaysShow(context)
            val enabled = Category.values().filter { isEnabled(context, it) }.toSet()
            val previous = loadState(context)

            val wanted = LinkedHashMap<String, Category>()
            // Folders hidden earlier drop out of MediaStore, so keep them while still wanted.
            for (folder in previous) {
                if (folder.category in enabled && File(root, folder.relativePath).isDirectory) {
                    wanted[folder.relativePath] = folder.category
                }
            }
            for (category in enabled) {
                for (path in findTargets(context, root, category)) {
                    wanted.putIfAbsent(path, category)
                }
            }
            alwaysShow.forEach { wanted.remove(it) }

            val changed = mutableListOf<File>()
            val kept = mutableListOf<HiddenFolder>()
            for ((path, category) in wanted) {
                val dir = File(root, path)
                val marker = File(dir, ".nomedia")
                when {
                    isOurs(marker) -> kept.add(HiddenFolder(path, category))
                    marker.exists() -> {} // the user's or the app's own marker: not ours to track
                    else -> {
                        try {
                            marker.writeText("$MARKER ${category.name}\n")
                            kept.add(HiddenFolder(path, category))
                            changed.add(dir)
                        } catch (e: IOException) {
                            Log.w(TAG, "Could not hide $path", e)
                        }
                    }
                }
            }
            for (folder in previous) {
                if (wanted.containsKey(folder.relativePath)) continue
                val dir = File(root, folder.relativePath)
                val marker = File(dir, ".nomedia")
                if (isOurs(marker) && marker.delete()) changed.add(dir)
            }

            saveState(context, kept)
            for (dir in changed) {
                try {
                    MediaStore.scanFile(context.contentResolver, dir)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Rescan of $dir failed", e)
                }
            }
        }
    }

    /** Runs [apply] again whenever images change, while any category is on. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!anyEnabled(context) && hiddenFolders(context).isEmpty()) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job =
            JobInfo.Builder(JOB_ID, ComponentName(context, GalleryCleanupJobService::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    )
                )
                .setTriggerContentUpdateDelay(10_000)
                .setTriggerContentMaxDelay(60_000)
                .build()
        scheduler.schedule(job)
    }

    private fun findTargets(context: Context, root: File, category: Category): List<String> =
        when (category) {
            Category.APP_MEDIA ->
                File(root, "Android/media")
                    .listFiles { f -> f.isDirectory && f.name !in MESSENGERS }
                    .orEmpty()
                    .map { "Android/media/${it.name}/" }
            Category.STICKERS ->
                MESSENGERS.flatMap { pkg ->
                    stickerDirs(File(root, "Android/media/$pkg"), depth = 4).map {
                        it.relativeTo(root).path + "/"
                    }
                }
            Category.ART_CACHES -> artCacheFolders(context)
        }

    private fun stickerDirs(dir: File, depth: Int): List<File> {
        if (depth < 0 || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }.orEmpty().flatMap { child ->
            if (STICKER_DIR.matches(child.name)) listOf(child) else stickerDirs(child, depth - 1)
        }
    }

    /** Folders whose indexed images are all cover-art names and which hold no audio. */
    private fun artCacheFolders(context: Context): List<String> {
        val images = HashMap<String, MutableList<String>>()
        val audioFolders = HashSet<String>()
        val projection =
            arrayOf(
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
            )
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO})"
        try {
            context.contentResolver
                .query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    projection,
                    selection,
                    null,
                    null,
                )
                ?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (c.getInt(2) == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO) {
                            audioFolders.add(path)
                        } else {
                            images.getOrPut(path) { mutableListOf() }.add(name)
                        }
                    }
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaStore query failed", e)
            return emptyList()
        }
        return images
            .filter { (path, names) ->
                path !in audioFolders &&
                    path.trimEnd('/').contains('/') &&
                    path.substringBefore('/').lowercase() !in PROTECTED_ROOTS &&
                    names.all { ART_NAME.matches(it) }
            }
            .keys
            .toList()
    }

    private fun isOurs(marker: File): Boolean =
        try {
            marker.isFile && marker.length() < 256 && marker.readText().startsWith(MARKER)
        } catch (e: IOException) {
            false
        }

    private fun loadState(context: Context): List<HiddenFolder> {
        val file = File(context.filesDir, STATE_FILE)
        if (!file.isFile) return emptyList()
        val lines =
            try {
                file.readLines()
            } catch (e: IOException) {
                return emptyList()
            }
        return lines.mapNotNull { line ->
            val (category, path) = line.split('\t', limit = 2).takeIf { it.size == 2 }
                ?: return@mapNotNull null
            val c = Category.values().firstOrNull { it.name == category } ?: return@mapNotNull null
            HiddenFolder(path, c)
        }
    }

    private fun saveState(context: Context, folders: List<HiddenFolder>) {
        // IchthysOS: runs on the job's raw thread; a full disk must not kill Settings and leave the
        // content-triggered job unarmed.
        try {
            File(context.filesDir, STATE_FILE)
                .writeText(folders.joinToString("") { "${it.category.name}\t${it.relativePath}\n" })
        } catch (e: IOException) {
            Log.w(TAG, "Could not save the hidden folder list", e)
        }
    }
}
