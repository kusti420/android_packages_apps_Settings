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

package com.android.settings.ichthys.guard.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One tracker/ad network: Exodus-style entry with its network signature (domains). */
@Serializable
data class Tracker(
    val name: String,
    val category: String,
    val domains: List<String>,
    /** Class-name prefixes for the install-time SDK scan (Exodus "code_signature"). */
    val codeSignatures: List<String> = emptyList(),
)

/**
 * Matches hostnames against tracker domains by label suffix: "doubleclick.net" matches
 * "doubleclick.net" and "ad.g.doubleclick.net", not "notdoubleclick.net".
 */
class TrackerMatcher(private val trackers: List<Tracker>) {

    private val byDomain: Map<String, Tracker> = buildMap {
        for (t in trackers) for (d in t.domains) put(normalize(d), t)
    }

    fun match(host: String): Tracker? {
        var candidate = normalize(host)
        while (candidate.isNotEmpty()) {
            byDomain[candidate]?.let {
                return it
            }
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
        }
        return null
    }

    /** Trackers whose SDK classes appear in an app (install-time scan over its dex class names). */
    fun scanClasses(classNames: Sequence<String>): Set<Tracker> {
        val found = mutableSetOf<Tracker>()
        for (cls in classNames) {
            for (t in trackers) {
                if (t !in found && t.codeSignatures.any { cls.startsWith(it) }) found += t
            }
        }
        return found
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): List<Tracker> =
            json.decodeFromString(ListSerializer(Tracker.serializer()), text)

        internal fun normalize(host: String): String = host.trim().trimEnd('.').lowercase()
    }
}
