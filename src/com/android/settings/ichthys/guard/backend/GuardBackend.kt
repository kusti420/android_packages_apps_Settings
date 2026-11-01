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

package com.android.settings.ichthys.guard.backend

import com.android.settings.ichthys.guard.model.BlockReason
import com.android.settings.ichthys.guard.model.GuardEvent
import com.android.settings.ichthys.guard.model.JournalKind
import com.android.settings.ichthys.guard.model.NetworkAccess
import kotlinx.coroutines.flow.Flow

/** IchthysOS: a uid's firewall rule with the reason Settings chose it (stamped on blocked events). */
data class UidRule(val access: NetworkAccess, val reason: BlockReason = BlockReason.POLICY)

/** IchthysOS: blocked network attempts of one uid since boot, and when the last one was. */
data class BlockedCounter(val count: Int, val lastMs: Long)

/**
 * Everything Guard needs from the platform service `ichthys_guard`
 * (com.android.internal.ichthys.guard.IIchthysGuard in frameworks/base). [ServiceBackend] is the
 * only implementation in Settings; the interface keeps the repository testable off-device (the
 * ichthys-guard Gradle project runs the model and repository tests against a fake).
 */
interface GuardBackend {
    /** True when this talks to the real platform service. */
    val isLive: Boolean

    fun setNetworkPolicy(uid: Int, rule: UidRule)

    /**
     * Replaces the whole table: listed uids get their access, all others go back to ALLOW. The
     * platform skips system-critical uids.
     */
    fun replaceNetworkPolicies(policies: Map<Int, UidRule>)

    /** uid → access; uids missing from the map are ALLOW. */
    fun getNetworkPolicies(): Map<Int, NetworkAccess>

    /** Seq of the newest buffered event (0 if none). */
    fun getLatestSeq(): Long

    /** Events with seq > [sinceSeq], oldest first, at most [max]. */
    fun getEvents(sinceSeq: Long, max: Int): List<GuardEvent>

    /** Live batches of new events. Cold; collecting registers a listener. */
    fun liveEvents(): Flow<List<GuardEvent>>

    /** Tracker names found by the install-time scan of [packageName]'s code; null if unknown. */
    fun getScan(packageName: String): List<String>?

    /** Stops enforcement (monitoring continues) for [minutes] (1..1440); 0 resumes at once. */
    fun pauseEnforcement(minutes: Int)

    /** Wall-clock end of the current pause, or 0 when enforcing. */
    fun getPausedUntil(): Long

    /**
     * Replaces the behaviour table (uid → [BehaviourFlags] bits); unlisted uids get none. Screen-off
     * and foreground-only are enforced on the firewall chain, LAN through the stock permission.
     */
    fun replaceBehaviours(flags: Map<Int, Int>)

    /** True when [uid]'s local network is blocked (explicitly, or as an app new since setup). */
    fun isLanDenied(uid: Int): Boolean

    /** False for a system-critical [uid] the service won't block (telephony, network stack). */
    fun isRestrictable(uid: Int): Boolean

    /** Which [com.android.settings.ichthys.guard.model.AlertType] bits the service reports. */
    fun setAlertMask(mask: Int)

    // ---- IchthysOS: evidence ----------------------------------------------------------------

    /** Appends a journal entry to the service's ring (it comes back through [liveEvents]). */
    fun addJournal(uid: Int, kind: JournalKind, detail: String?)

    /** Blocked attempts per uid since boot. */
    fun getBlockedCounters(): Map<Int, BlockedCounter>

    /** IIchthysGuard.HEALTH_* bits, or -1 when the service is unreachable. */
    fun getHealth(): Int

    /** Uids on the deny chain right now with the reason. */
    fun getDenyNow(): Map<Int, BlockReason>
}

/** IIchthysGuard.BEHAVIOUR_* values (kept here so the model needs no framework classes). */
object BehaviourFlags {
    const val SCREEN_OFF_DENY = 1
    const val FOREGROUND_ONLY = 2
    const val LAN_DENY = 4
    const val LAN_ALLOW = 8
}

/** IIchthysGuard.HEALTH_* values. */
object Health {
    const val MONITOR = 1
    const val FIREWALL = 2
    const val METERED = 4
    const val TRACKER_DB = 8
    const val POLICY_LOADED = 16
    const val ALL = 31
    /** [GuardBackend.getHealth] while the service is unreachable. */
    const val UNREACHABLE = -1

    /** The problems [bits] names, in the words of the home card; empty when all is well. */
    fun problems(bits: Int): List<String> =
        if (bits == UNREACHABLE) listOf("The Guard service isn't reachable")
        else
            buildList {
                if (bits and FIREWALL == 0) add("The firewall rules didn't reach the network stack")
                if (bits and MONITOR == 0) add("The traffic monitor isn't registered: no live activity")
                if (bits and METERED == 0) add("The Wi-Fi-only rules didn't reach the network policy")
                if (bits and POLICY_LOADED == 0) add("The saved rules couldn't be read at boot")
                if (bits and TRACKER_DB == 0) add("The tracker database isn't loaded yet")
            }
}

internal fun NetworkAccess.toWire(): Int =
    when (this) {
        NetworkAccess.ALLOW -> 0
        NetworkAccess.UNMETERED_ONLY -> 1
        NetworkAccess.DENY -> 2
    }

/** IchthysOS: access in the low byte, the reason above it (IIchthysGuard.ACCESS_REASON_SHIFT). */
internal fun UidRule.toWire(): Int = access.toWire() or (reason.code shl 8)

internal fun networkAccessFromWire(v: Int): NetworkAccess =
    when (v and 0xff) {
        1 -> NetworkAccess.UNMETERED_ONLY
        2 -> NetworkAccess.DENY
        else -> NetworkAccess.ALLOW
    }
