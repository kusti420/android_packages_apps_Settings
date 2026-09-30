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

package com.android.settings.ichthys.guard.model

import kotlin.math.roundToInt

data class CompanyCount(
    val name: String,
    val category: String?,
    val hits: Int,
    val blocked: Int,
    val apps: Int,
)

data class AppActivity(
    val packageName: String?,
    val label: String,
    val events: Int,
    val trackerHits: Int,
    val blocked: Int,
    val lastSeenMs: Long,
)

/** Summary over a window of events: the Home screen and the weekly report. */
data class Summary(
    val queries: Int,
    val connects: Int,
    val trackersSeen: Int,
    val trackersBlocked: Int,
    val blockedTotal: Int,
    val appsContactingTrackers: Int,
    val activeApps: Int,
    val topCompanies: List<CompanyCount>,
    val topApps: List<AppActivity>,
    val score: Int,
) {
    val grade: String
        get() =
            when {
                score >= 90 -> "A"
                score >= 75 -> "B"
                score >= 60 -> "C"
                score >= 40 -> "D"
                else -> "E"
            }
}

object Insights {
    fun summarize(events: List<LabeledEvent>, sinceMs: Long = 0, topN: Int = 5): Summary {
        val window = events.filter { it.event.timeMs >= sinceMs }
        val trackerEvents = window.filter { it.isTracker }
        val companies =
            trackerEvents
                .groupBy { it.tracker!! }
                .map { (name, evs) ->
                    CompanyCount(
                        name,
                        evs.first().trackerCategory,
                        evs.size,
                        evs.count { it.event.blocked },
                        evs.mapNotNull { it.packageName }.distinct().size,
                    )
                }
                .sortedWith(compareByDescending<CompanyCount> { it.hits }.thenBy { it.name })
        val apps = byApp(window)
        return Summary(
            queries = window.count { it.event.type == EventType.DNS },
            connects = window.count { it.event.type == EventType.CONNECT },
            trackersSeen = trackerEvents.size,
            trackersBlocked = trackerEvents.count { it.event.blocked },
            blockedTotal = window.count { it.event.blocked },
            appsContactingTrackers = trackerEvents.mapNotNull { it.packageName }.distinct().size,
            activeApps = apps.size,
            topCompanies = companies.take(topN),
            topApps = apps.sortedByDescending { it.trackerHits }.take(topN),
            score = score(trackerEvents.size, trackerEvents.count { it.event.blocked }, window.size),
        )
    }

    fun byApp(events: List<LabeledEvent>): List<AppActivity> =
        events
            .groupBy { it.packageName to it.appLabel }
            .map { (k, evs) ->
                AppActivity(
                    k.first,
                    k.second,
                    evs.size,
                    evs.count { it.isTracker },
                    evs.count { it.event.blocked },
                    evs.maxOf { it.event.timeMs },
                )
            }
            .sortedByDescending { it.events }

    /**
     * 0..100. Starts at 100; each tracker contact that got through costs more than a blocked one,
     * scaled by how much of the traffic is tracking. No traffic = 100.
     */
    fun score(trackerHits: Int, trackersBlocked: Int, total: Int): Int {
        if (total <= 0 || trackerHits <= 0) return 100
        val leaked = (trackerHits - trackersBlocked).coerceAtLeast(0)
        val share = trackerHits.toDouble() / total
        val leakedShare = leaked.toDouble() / trackerHits
        val penalty =
            15 * share + 70 * leakedShare * share.coerceAtLeast(0.2) + minOf(15.0, leaked * 0.5)
        return (100 - penalty).roundToInt().coerceIn(0, 100)
    }

    data class Destination(
        val host: String,
        val hits: Int,
        val apps: Int,
        val tracker: String?,
        val blocked: Int,
    )

    fun topDestinations(events: List<LabeledEvent>, n: Int = 20): List<Destination> =
        events
            .groupBy { it.event.destination }
            .map { (host, evs) ->
                Destination(
                    host,
                    evs.size,
                    evs.mapNotNull { it.packageName }.distinct().size,
                    evs.firstNotNullOfOrNull { it.tracker },
                    evs.count { it.event.blocked },
                )
            }
            .sortedByDescending { it.hits }
            .take(n)

    /** Hosts in first-contact order for one app (the "first seen domains" list). */
    fun firstSeen(events: List<LabeledEvent>, packageName: String): List<Pair<String, Long>> =
        events
            .filter { it.packageName == packageName && it.event.host != null }
            .groupBy { it.event.host!! }
            .map { (h, evs) -> h to evs.minOf { it.event.timeMs } }
            .sortedByDescending { it.second }

    /**
     * "Bare minimum" learning: the hosts an app actually needed, i.e. non-tracker hosts it reached
     * (not blocked) at least [minHits] times. Seeds a STRICT allowlist.
     */
    fun learnAllowlist(
        events: List<LabeledEvent>,
        packageName: String,
        minHits: Int = 1,
    ): Set<String> =
        events
            .filter {
                it.packageName == packageName &&
                    !it.isTracker &&
                    !it.event.blocked &&
                    it.event.host != null
            }
            .groupingBy { HostPattern.normalize(it.event.host!!) }
            .eachCount()
            .filterValues { it >= minHits }
            .keys

    /** CSV of events (RFC 4180 quoting) for the Activity "Export" action. */
    fun toCsv(events: List<LabeledEvent>): String = buildString {
        append("seq,time_ms,uid,package,app,type,host,ip,port,ips,tracker,blocked,return_code\n")
        for (e in events) {
            val ev = e.event
            listOf(
                    ev.seq.toString(),
                    ev.timeMs.toString(),
                    ev.uid.toString(),
                    e.packageName.orEmpty(),
                    e.appLabel,
                    ev.type.name,
                    ev.host.orEmpty(),
                    ev.ip.orEmpty(),
                    ev.port.toString(),
                    ev.ips.joinToString(" "),
                    e.tracker.orEmpty(),
                    ev.blocked.toString(),
                    ev.returnCode.toString(),
                )
                .joinTo(this, ",") { csvField(it) }
            append('\n')
        }
    }

    private fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\""
        else s

    fun weeklyReportText(s: Summary): String = buildString {
        append(
            "Score ${s.score} (${s.grade}) · ${s.trackersBlocked} of ${s.trackersSeen} tracker contacts blocked"
        )
        if (s.topCompanies.isNotEmpty()) {
            append(" · most active: ").append(s.topCompanies.take(3).joinToString { it.name })
        }
        if (s.topApps.isNotEmpty() && s.topApps.first().trackerHits > 0) {
            append(" · noisiest app: ").append(s.topApps.first().label)
        }
    }
}

enum class Grouping {
    NONE,
    APP,
    DOMAIN,
}

/** Activity screen filter; pure so it can be unit-tested. */
data class ActivityFilter(
    val query: String = "",
    val packageName: String? = null,
    val trackersOnly: Boolean = false,
    val blockedOnly: Boolean = false,
    val types: Set<EventType> = EventType.entries.toSet(),
) {
    val isActive: Boolean
        get() =
            query.isNotBlank() ||
                packageName != null ||
                trackersOnly ||
                blockedOnly ||
                types.size != EventType.entries.size

    fun accepts(e: LabeledEvent): Boolean {
        if (packageName != null && e.packageName != packageName) return false
        if (trackersOnly && !e.isTracker) return false
        if (blockedOnly && !e.event.blocked) return false
        if (e.event.type !in types) return false
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            val hay =
                listOfNotNull(e.event.host, e.event.ip, e.appLabel, e.packageName, e.tracker) +
                    e.event.ips
            if (hay.none { it.lowercase().contains(q) }) return false
        }
        return true
    }

    fun apply(events: List<LabeledEvent>): List<LabeledEvent> = events.filter(::accepts)
}

/** Apps screen filters. */
enum class AppFilter {
    ALL,
    HAS_TRACKERS,
    BLOCKED,
    RECENTLY_ACTIVE,
    CUSTOMIZED,
}
