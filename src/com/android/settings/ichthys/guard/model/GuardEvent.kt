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

import kotlinx.serialization.Serializable

enum class EventType {
    DNS,
    CONNECT,
}

/** One monitor event from IchthysGuardService (a DNS lookup or a TCP/UDP connect by a uid). */
@Serializable
data class GuardEvent(
    val seq: Long,
    val timeMs: Long,
    val uid: Int,
    val type: EventType,
    /** Hostname (DNS query, or the name last resolved to [ip] by this uid for a connect). */
    val host: String? = null,
    /** DNS answers. */
    val ips: List<String> = emptyList(),
    /** Connect destination. */
    val ip: String? = null,
    val port: Int = 0,
    val blocked: Boolean = false,
    /** DNS: getaddrinfo()-style return code (0 = success). */
    val returnCode: Int = 0,
) {
    /** What the row shows: the host if known, else the raw IP. */
    val destination: String
        get() = host ?: ip ?: "?"

    companion object {
        /** The unspecified addresses the system hosts file (HaGeZi) answers blocked names with. */
        private val SINKHOLES = setOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0")

        /**
         * IchthysOS: true when the system-wide hosts file blocked this lookup or connect: a DNS
         * answer made only of sinkhole addresses, or a connect to one. The service's own
         * `blocked` flag only means the uid was on the firewall's deny chain, so without this
         * every tracker the hosts file stopped counted as reached.
         */
        fun isSinkholed(type: EventType, ips: List<String>, ip: String?): Boolean =
            when (type) {
                EventType.DNS -> ips.isNotEmpty() && ips.all { it in SINKHOLES }
                EventType.CONNECT -> ip != null && ip in SINKHOLES
            }
    }
}

/** An event joined with the app that made it and the tracker it hit, if any. */
data class LabeledEvent(
    val event: GuardEvent,
    val packageName: String?,
    val appLabel: String,
    val tracker: String?,
    val trackerCategory: String?,
) {
    val isTracker: Boolean
        get() = tracker != null
}
