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
import com.android.settings.ichthys.guard.model.PolicySet
import com.android.settings.ichthys.guard.model.RuleBook
import java.io.File
import java.io.IOException

/** Custom rules, blocklist sources and lockdown state (rules.json next to policies.json). */
class RuleStore(dir: File) {
    private val file = AtomicFile(File(dir, "rules.json"))

    fun load(): RuleBook =
        try {
            RuleBook.fromJson(String(file.readFully(), Charsets.UTF_8))
        } catch (ignored: IOException) {
            // IchthysOS: no file is a fresh start; a file that exists but can't be read is not.
            if (file.baseFile.exists()) unreadable = true
            RuleBook()
        } catch (e: IllegalArgumentException) {
            // A file this build can't read (an option renamed or removed since it was saved):
            // keep a copy before the defaults replace it on the next save.
            keepUnreadable()
            unreadable = true
            RuleBook()
        }

    fun save(book: RuleBook) {
        val out = file.startWrite()
        try {
            // Policies live in PolicyStore; don't duplicate them here.
            out.write(book.copy(policies = PolicySet()).toJson().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    /**
     * IchthysOS: the last load found a file it couldn't read and fell back to the defaults
     * (everything allowed). Guard then must not push those defaults over the service's table.
     */
    @Volatile
    var unreadable: Boolean = false
        private set

    /** IchthysOS: a copy of an unreadable file is waiting for the owner's decision. */
    fun hasUnreadableCopy(): Boolean = File(file.baseFile.path + ".unreadable").exists()

    /**
     * IchthysOS: the owner chose the defaults (or imported a backup): the copy is kept aside
     * under another name and the store counts as readable again.
     */
    fun acknowledgeUnreadable() {
        val bad = File(file.baseFile.path + ".unreadable")
        if (bad.exists()) bad.renameTo(File(file.baseFile.path + ".unreadable.kept"))
        unreadable = false
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
