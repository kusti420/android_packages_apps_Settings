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
    /** IchthysOS: a state change of Guard (pause, lockdown, preset, quarantine, self-test). */
    JOURNAL,
    /** IchthysOS: a data shield decided for an app (location withheld, contacts emptied). */
    SHIELD,
}

/**
 * IchthysOS: why a network attempt was blocked. The platform stamps the firewall's reason
 * (GuardEvent.REASON_*) on every blocked event; [BLOCKLIST] is added here for a lookup the
 * system hosts file answered with a sinkhole.
 */
@Serializable
enum class BlockReason(val code: Int, val label: String) {
    NONE(0, ""),
    POLICY(1, "policy"),
    LOCKDOWN(2, "lockdown"),
    SCREEN_OFF(3, "screen-off"),
    BACKGROUND(4, "background"),
    SCHEDULE(5, "schedule"),
    DORMANT(6, "asleep"),
    BLOCKLIST(7, "blocklist");

    /** What the stream row and the app page say about it. */
    val sentence: String
        get() =
            when (this) {
                NONE -> "allowed"
                POLICY -> "the app's own rule"
                LOCKDOWN -> "lockdown"
                SCREEN_OFF -> "screen-off rule"
                BACKGROUND -> "background rule"
                SCHEDULE -> "a schedule"
                DORMANT -> "put to sleep"
                BLOCKLIST -> "the system blocklist"
            }

    companion object {
        fun fromCode(code: Int): BlockReason = entries.firstOrNull { it.code == code } ?: POLICY
    }
}

/** IchthysOS: the journal's entry kinds (GuardEvent.JOURNAL_*). */
enum class JournalKind(val code: Int, val label: String) {
    PAUSE_START(1, "Paused"),
    PAUSE_END(2, "Resumed"),
    LOCKDOWN_ON(3, "Lockdown on"),
    LOCKDOWN_OFF(4, "Lockdown off"),
    RULES_APPLIED(5, "Rules applied"),
    BEHAVIOURS_APPLIED(6, "Behaviours applied"),
    QUARANTINE(7, "Quarantined"),
    RELEASED(8, "Released"),
    PRESET(9, "Preset"),
    SCHEDULE_START(10, "Schedule started"),
    SCHEDULE_END(11, "Schedule ended"),
    SLEEP(12, "Put to sleep"),
    ALERT(13, "Alert"),
    FIREWALL_FAILED(14, "Firewall failed"),
    SELF_TEST(15, "Verified"),
    LAN_APPLIED(16, "Local network"),
    RULES_RESTORED(17, "Rules restored"),
    SERVICE_START(18, "Service started"),
    WAKE(19, "Woken");

    /** Entries that mean something went wrong; shown in the danger colour. */
    val isWarning: Boolean
        get() = this == FIREWALL_FAILED || this == ALERT

    companion object {
        fun fromCode(code: Int): JournalKind? = entries.firstOrNull { it.code == code }
    }
}

/** IchthysOS: proof that a data shield decided for an app (GuardEvent.SHIELD_*). */
enum class ShieldProof(val code: Int, val label: String, val protective: Boolean) {
    LOCATION_REAL(1, "real fix delivered", false),
    LOCATION_FAKE(2, "fake place delivered", true),
    LOCATION_NONE(3, "no fix, as if indoors", true),
    SCANS_EMPTY(4, "scans emptied", true),
    CONTACTS(5, "contacts emptied", true),
    CALENDAR(6, "calendar emptied", true),
    CALL_LOG(7, "call log emptied", true),
    SMS(8, "texts emptied", true),
    CONTACTS_WRITE(9, "contact change dropped", true),
    CALENDAR_WRITE(10, "calendar change dropped", true),
    CALL_LOG_WRITE(11, "call log change dropped", true);

    val isLocation: Boolean
        get() = this == LOCATION_REAL || this == LOCATION_FAKE || this == LOCATION_NONE

    companion object {
        fun fromCode(code: Int): ShieldProof? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One entry of IchthysGuardService's ring: a DNS lookup or a TCP/UDP connect by a uid, a journal
 * entry, or a shield proof.
 */
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
    /** IchthysOS: REASON_*, JOURNAL_* or SHIELD_* by [type]. */
    val code: Int = 0,
    /** IchthysOS: journal text or shield detail. */
    val detail: String? = null,
) {
    /** What the row shows: the host if known, else the raw IP. */
    val destination: String
        get() = host ?: ip ?: "?"

    val isNetwork: Boolean
        get() = type == EventType.DNS || type == EventType.CONNECT

    /** Why a network event was blocked ([BlockReason.NONE] when it wasn't). */
    val reason: BlockReason
        get() = if (!isNetwork || !blocked) BlockReason.NONE else BlockReason.fromCode(code)

    val journal: JournalKind?
        get() = if (type == EventType.JOURNAL) JournalKind.fromCode(code) else null

    val shield: ShieldProof?
        get() = if (type == EventType.SHIELD) ShieldProof.fromCode(code) else null

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
                else -> false
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

/**
 * IchthysOS: a run of consecutive events that are the same attempt (same app, same destination
 * and port, same kind, same outcome), shown in the stream as one row with a count ("×7") and its
 * first and last time. Presentation only: [events] are the stream's own events, newest first,
 * nothing is dropped.
 */
data class EventRun(val events: List<LabeledEvent>) {
    val head: LabeledEvent
        get() = events.first()

    val count: Int
        get() = events.size

    /** Newest first, so the oldest event (the first attempt) is the last one. */
    val firstMs: Long
        get() = events.last().event.timeMs

    val lastMs: Long
        get() = events.first().event.timeMs

    /** Stable while the run grows at its newest end: the first attempt's sequence number. */
    val id: Long
        get() = events.last().event.seq

    companion object {
        /** Whether two events are the same attempt and may share a row. */
        fun sameAttempt(a: LabeledEvent, b: LabeledEvent): Boolean =
            a.event.uid == b.event.uid &&
                a.event.type == b.event.type &&
                a.event.destination == b.event.destination &&
                a.event.port == b.event.port &&
                a.event.blocked == b.event.blocked &&
                a.event.reason == b.event.reason &&
                GuardEvent.isSinkholed(a.event.type, a.event.ips, a.event.ip) ==
                    GuardEvent.isSinkholed(b.event.type, b.event.ips, b.event.ip)

        /** Collapses consecutive repeats of [events] (in their given order) into runs. */
        fun collapse(events: List<LabeledEvent>): List<EventRun> {
            val out = ArrayList<EventRun>()
            var run = ArrayList<LabeledEvent>()
            for (e in events) {
                if (run.isNotEmpty() && !sameAttempt(run.last(), e)) {
                    out.add(EventRun(run))
                    run = ArrayList()
                }
                run.add(e)
            }
            if (run.isNotEmpty()) out.add(EventRun(run))
            return out
        }
    }
}
