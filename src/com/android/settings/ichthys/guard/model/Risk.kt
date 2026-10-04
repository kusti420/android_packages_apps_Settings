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

/** What the risk score knows about an app's code and manifest. Plain values (testable). */
data class RiskFacts(
    /** Tracker SDKs from the install-time scan; null while unknown. */
    val trackers: List<String>?,
    val dangerousPermissions: Int,
    val startsAtBoot: Boolean,
    val usesInternet: Boolean,
)

/** A one-tap fix offered next to a risk factor. */
enum class RiskFix(val label: String) {
    STRICT_TRACKERS("Strict tracker blocking"),
    BACKGROUND_OFF("Background network off"),
    SCREEN_OFF("No network while screen off"),
    REVIEW_PERMISSIONS("Review permissions"),
    BLOCK_NETWORK("Block network"),
}

data class RiskFactor(val label: String, val points: Int, val fix: RiskFix?)

data class RiskScore(val score: Int, val factors: List<RiskFactor>) {
    val level: String
        get() =
            when {
                score >= HIGH -> "High"
                score >= MEDIUM -> "Medium"
                else -> "Low"
            }

    val isHigh: Boolean
        get() = score >= HIGH

    /** Distinct fixes, biggest factor first. */
    val fixes: List<RiskFix>
        get() = factors.sortedByDescending { it.points }.mapNotNull { it.fix }.distinct()

    companion object {
        const val HIGH = 60
        const val MEDIUM = 30
    }
}

/**
 * Per-app risk score, 0..100 (W8b decision 18): trackers embedded and contacted, dangerous
 * permissions, background data and how many domains it talks to. What Guard already enforces
 * lowers it: strict tracker blocking, background network off, a blocked network.
 */
object RiskScorer {
    fun score(
        facts: RiskFacts,
        policy: AppPolicy,
        events: List<LabeledEvent>,
        effective: NetworkAccess,
    ): RiskScore {
        val out = mutableListOf<RiskFactor>()
        val trackerFactor =
            when (policy.trackerBlock) {
                TrackerBlockLevel.OFF -> 1.0
                TrackerBlockLevel.KNOWN_TRACKERS -> 0.6
                TrackerBlockLevel.STRICT -> 0.25
            }
        val strictFix =
            RiskFix.STRICT_TRACKERS.takeIf { policy.trackerBlock != TrackerBlockLevel.STRICT }
        val n = facts.trackers?.size ?: 0
        if (n > 0) {
            out +=
                RiskFactor(
                    "$n tracker SDK${if (n == 1) "" else "s"} embedded",
                    (minOf(35, 8 * n) * trackerFactor).toInt(),
                    strictFix,
                )
        }
        if (facts.dangerousPermissions > 0) {
            out +=
                RiskFactor(
                    "${facts.dangerousPermissions} sensitive permissions",
                    minOf(25, 4 * facts.dangerousPermissions),
                    RiskFix.REVIEW_PERMISSIONS,
                )
        }
        if (facts.usesInternet && !policy.backgroundNetworkOff) {
            var pts = 10 + if (facts.startsAtBoot) 5 else 0
            if (policy.screenOffBlock) pts /= 2
            out +=
                RiskFactor(
                    if (facts.startsAtBoot) "Background data, starts at boot"
                    else "Background data",
                    pts,
                    RiskFix.BACKGROUND_OFF,
                )
        }
        val hosts = events.mapNotNull { it.event.host }.toSet()
        if (hosts.size >= 4) {
            out +=
                RiskFactor("${hosts.size} domains contacted", minOf(15, hosts.size / 4), strictFix)
        }
        val trackerHosts =
            events.filter { it.isTracker && !it.event.blocked }.mapNotNull { it.event.host }.toSet()
        if (trackerHosts.isNotEmpty()) {
            out +=
                RiskFactor(
                    "${trackerHosts.size} tracker domains reached",
                    minOf(10, 3 * trackerHosts.size),
                    strictFix,
                )
        }
        var total = out.sumOf { it.points }.coerceIn(0, 100)
        if (effective == NetworkAccess.DENY) total /= 4
        else if (total >= RiskScore.HIGH)
            out += RiskFactor("Network open", 0, RiskFix.BLOCK_NETWORK)
        return RiskScore(total, out.filter { it.points > 0 || it.fix == RiskFix.BLOCK_NETWORK })
    }
}
