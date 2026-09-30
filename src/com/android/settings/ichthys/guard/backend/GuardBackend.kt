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

import com.android.settings.ichthys.guard.model.GuardEvent
import com.android.settings.ichthys.guard.model.NetworkAccess
import kotlinx.coroutines.flow.Flow

/**
 * Everything Guard needs from the platform service `ichthys_guard`
 * (com.android.internal.ichthys.guard.IIchthysGuard in frameworks/base). [ServiceBackend] is the
 * only implementation in Settings; the interface keeps the repository testable off-device (the
 * ichthys-guard Gradle project runs the model and repository tests against a fake).
 */
interface GuardBackend {
    /** True when this talks to the real platform service. */
    val isLive: Boolean

    fun setNetworkPolicy(uid: Int, access: NetworkAccess)

    /**
     * Replaces the whole table: listed uids get their access, all others go back to ALLOW. The
     * platform skips system-critical uids.
     */
    fun replaceNetworkPolicies(policies: Map<Int, NetworkAccess>)

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
}

internal fun NetworkAccess.toWire(): Int =
    when (this) {
        NetworkAccess.ALLOW -> 0
        NetworkAccess.UNMETERED_ONLY -> 1
        NetworkAccess.DENY -> 2
    }

internal fun networkAccessFromWire(v: Int): NetworkAccess =
    when (v) {
        1 -> NetworkAccess.UNMETERED_ONLY
        2 -> NetworkAccess.DENY
        else -> NetworkAccess.ALLOW
    }
