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

package com.android.settings.ichthys.guard.data

import android.util.AtomicFile
import com.android.settings.ichthys.guard.model.StatsHistory
import java.io.File
import java.io.IOException

/** Daily counters (stats.json), so reports survive reboots and the bounded event window. */
class StatsStore(dir: File) {
    private val file = AtomicFile(File(dir, "stats.json"))

    fun load(): StatsHistory =
        try {
            StatsHistory.fromJson(String(file.readFully(), Charsets.UTF_8))
        } catch (ignored: IOException) {
            StatsHistory()
        } catch (e: IllegalArgumentException) {
            // A file this build can't read (an option renamed or removed since it was saved):
            // keep a copy before the defaults replace it on the next save.
            keepUnreadable()
            StatsHistory()
        }

    fun save(h: StatsHistory) {
        val out = file.startWrite()
        try {
            out.write(h.toJson().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private fun keepUnreadable() {
        val bad = File(file.baseFile.path + ".unreadable")
        try {
            if (!bad.exists()) file.baseFile.copyTo(bad, overwrite = false)
        } catch (ignored: IOException) {
            // No room for the copy: the defaults still beat a crash.
        }
    }
}
