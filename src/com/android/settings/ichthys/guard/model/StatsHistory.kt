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

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-day counters that outlive the in-memory event window (the platform ring buffer is lost on
 * reboot). Feeds the weekly report and the 7-day trend on Home.
 */
@Serializable
data class DayStats(
    val epochDay: Long,
    val queries: Int = 0,
    val connects: Int = 0,
    val trackersSeen: Int = 0,
    val trackersBlocked: Int = 0,
    val blockedTotal: Int = 0,
    /** Tracker company → hits. */
    val companies: Map<String, Int> = emptyMap(),
    /** App label → tracker hits. */
    val appTrackerHits: Map<String, Int> = emptyMap(),
) {
    val total: Int
        get() = queries + connects

    val score: Int
        get() = Insights.score(trackersSeen, trackersBlocked, total)

    fun plus(e: LabeledEvent): DayStats =
        copy(
            queries = queries + if (e.event.type == EventType.DNS) 1 else 0,
            connects = connects + if (e.event.type == EventType.CONNECT) 1 else 0,
            trackersSeen = trackersSeen + if (e.isTracker) 1 else 0,
            trackersBlocked = trackersBlocked + if (e.isTracker && e.event.blocked) 1 else 0,
            blockedTotal = blockedTotal + if (e.event.blocked) 1 else 0,
            companies =
                if (e.tracker == null) companies
                else companies + (e.tracker to (companies[e.tracker] ?: 0) + 1),
            appTrackerHits =
                if (e.tracker == null) appTrackerHits
                else appTrackerHits + (e.appLabel to (appTrackerHits[e.appLabel] ?: 0) + 1),
        )
}

@Serializable
data class StatsHistory(
    val days: Map<Long, DayStats> = emptyMap(),
    /** Highest event seq already counted (so re-ingesting a backfill doesn't double count). */
    val lastSeq: Long = 0,
) {
    fun record(
        events: List<LabeledEvent>,
        zone: ZoneId = ZoneId.systemDefault(),
        keepDays: Int = 90,
    ): StatsHistory {
        val fresh = events.filter { it.event.seq > lastSeq }
        if (fresh.isEmpty()) return this
        val m = days.toMutableMap()
        for (e in fresh) {
            val d = epochDay(e.event.timeMs, zone)
            m[d] = (m[d] ?: DayStats(d)).plus(e)
        }
        val newest = m.keys.max()
        return StatsHistory(
            m.filterKeys { it > newest - keepDays },
            maxOf(lastSeq, fresh.maxOf { it.event.seq }),
        )
    }

    /** The platform's seq restarts at 1 on reboot; call this when that is detected. */
    fun resetSeq(): StatsHistory = copy(lastSeq = 0)

    /** Days [fromDay, toDay], missing days filled with zeros, oldest first. */
    fun range(fromDay: Long, toDay: Long): List<DayStats> =
        (fromDay..toDay).map { days[it] ?: DayStats(it) }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): StatsHistory = json.decodeFromString(serializer(), text)

        fun epochDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    }
}

/** Aggregate of several days (the weekly report). */
data class PeriodReport(val days: List<DayStats>) {
    val trackersSeen = days.sumOf { it.trackersSeen }
    val trackersBlocked = days.sumOf { it.trackersBlocked }
    val total = days.sumOf { it.total }
    val score = Insights.score(trackersSeen, trackersBlocked, total)
    val topCompanies: List<Pair<String, Int>> =
        days
            .flatMap { it.companies.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    val noisiestApp: Pair<String, Int>? =
        days
            .flatMap { it.appTrackerHits.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
            .maxByOrNull { it.value }
            ?.toPair()

    fun text(): String = buildString {
        append("Score $score · $trackersBlocked of $trackersSeen tracker contacts blocked")
        if (topCompanies.isNotEmpty())
            append(" · most active: ").append(topCompanies.take(3).joinToString { it.first })
        noisiestApp?.let { append(" · noisiest app: ${it.first} (${it.second})") }
    }
}
