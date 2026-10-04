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
import com.android.settings.ichthys.guard.model.AutomationSettings
import java.io.File
import java.io.IOException

/** Automation settings (profiles, screen-off rule, alerts, auto-tighten), persisted as JSON. */
class AutomationStore(dir: File) {
    private val file = AtomicFile(File(dir, "automation.json"))

    fun load(): AutomationSettings =
        try {
            AutomationSettings.fromJson(String(file.readFully(), Charsets.UTF_8))
        } catch (ignored: IOException) {
            AutomationSettings()
        } catch (ignored: IllegalArgumentException) {
            AutomationSettings()
        }

    fun save(set: AutomationSettings) {
        val out = file.startWrite()
        try {
            out.write(set.toJson().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }
}
