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

package com.android.settings.ichthys.guard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.DayStats
import com.android.settings.ichthys.guard.model.Insights
import com.android.settings.ichthys.guard.model.LabeledEvent
import com.android.settings.ichthys.guard.model.StatsHistory
import com.android.settings.ichthys.guard.model.Summary
import java.util.Calendar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Shared app-wide state; screen ViewModels build on [repo]. */
class GuardViewModel(app: Application) : AndroidViewModel(app) {
    val repo: GuardRepository = GuardRepository.get(app)

    val today: StateFlow<Summary> =
        combine(repo.events, repo.stats) { evs, history ->
                val s = Insights.summarize(evs, sinceMs = startOfDay())
                // IchthysOS: the in-memory window holds only the newest MAX_EVENTS events; on a
                // busy day the persisted counters (every event) are the complete totals.
                val d = history.days[StatsHistory.epochDay(System.currentTimeMillis())]
                if (d == null || d.total <= s.queries + s.connects) s
                else
                    s.copy(
                        queries = d.queries,
                        connects = d.connects,
                        trackersSeen = d.trackersSeen,
                        trackersBlocked = d.trackersBlocked,
                        blockedTotal = d.blockedTotal,
                        score = d.score,
                    )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                Insights.summarize(emptyList()),
            )

    /** package → events (for Apps list badges and details). */
    val eventsByApp: StateFlow<Map<String, List<LabeledEvent>>> =
        repo.events
            .map { evs -> evs.filter { it.packageName != null }.groupBy { it.packageName!! } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Last 7 days of persisted counters, oldest first (Home trend). */
    val week: StateFlow<List<DayStats>> =
        repo.stats
            .map { h ->
                StatsHistory.epochDay(System.currentTimeMillis()).let { d -> h.range(d - 6, d) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val status: StateFlow<Status> =
        combine(repo.pausedUntil, repo.rules, repo.osWritable) { p, r, w ->
                Status(p, r.lockdown.enabled, w, repo.backend.isLive)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                Status(0, false, true, repo.backend.isLive),
            )

    data class Status(
        val pausedUntil: Long,
        val lockdown: Boolean,
        val osWritable: Boolean,
        val live: Boolean,
    )

    companion object {
        fun startOfDay(now: Long = System.currentTimeMillis()): Long =
            Calendar.getInstance()
                .apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                .timeInMillis
    }
}
