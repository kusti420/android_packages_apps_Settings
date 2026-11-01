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
import com.android.settings.ichthys.guard.model.JournalLog
import com.android.settings.ichthys.guard.model.VerifyHistory
import java.io.File
import java.io.IOException

/**
 * IchthysOS: the journal (state changes and shield proofs) Guard keeps across reboots. The
 * service's ring is lost at boot; this keeps the newest [JournalLog.MAX] entries as JSON in
 * Guard's (credential-encrypted) data directory, like the other stores.
 */
class JournalStore(dir: File) {
    private val file = AtomicFile(File(dir, "journal.json"))

    fun load(): JournalLog =
        try {
            JournalLog.fromJson(String(file.readFully(), Charsets.UTF_8))
        } catch (ignored: IOException) {
            JournalLog()
        } catch (e: IllegalArgumentException) {
            keepUnreadable(file)
            JournalLog()
        }

    fun save(log: JournalLog) {
        val out = file.startWrite()
        try {
            out.write(log.toJson().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }
}

/** IchthysOS: the verification runs (IchthysProbe launched by Guard), newest first. */
class VerifyStore(dir: File) {
    private val file = AtomicFile(File(dir, "verify.json"))

    fun load(): VerifyHistory =
        try {
            VerifyHistory.fromJson(String(file.readFully(), Charsets.UTF_8))
        } catch (ignored: IOException) {
            VerifyHistory()
        } catch (e: IllegalArgumentException) {
            keepUnreadable(file)
            VerifyHistory()
        }

    fun save(h: VerifyHistory) {
        val out = file.startWrite()
        try {
            out.write(h.toJson().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }
}

/**
 * A file this build can't read (an option renamed or removed since it was saved): keep a copy
 * before the defaults replace it on the next save.
 */
private fun keepUnreadable(file: AtomicFile) {
    val bad = File(file.baseFile.path + ".unreadable")
    try {
        if (!bad.exists()) file.baseFile.copyTo(bad, overwrite = false)
    } catch (ignored: IOException) {
        // No room for the copy: the defaults still beat a crash.
    }
}
