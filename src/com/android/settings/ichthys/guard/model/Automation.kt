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

import java.util.Calendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** User tags on apps. Profiles block by tag, so one tag covers every app of that kind. */
@Serializable
enum class AppTag(val label: String) {
    SOCIAL("Social"),
    VIDEO("Video"),
    GAMES("Games"),
    NEWS("News"),
    SHOPPING("Shopping"),
    MESSAGING("Messaging"),
}

/**
 * A named set of policy overrides with a schedule (W8b decision 18). While active, every app it
 * matches (by tag or by package) gets [network] (the stricter of it and its own policy) and,
 * optionally, background-network-off and screen-off blocking.
 *
 * The window is [startMinute]..[endMinute] in minutes after midnight; an end at or before the start
 * runs past midnight (22:00–07:00). [days] are Calendar.DAY_OF_WEEK values of the day the window
 * starts on; empty means every day. Equal start and end means all day: the profile is on from
 * midnight to midnight on its [days], so its boundaries are the day changes, not the start time.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<Int> = emptySet(),
    val blockTags: Set<AppTag> = emptySet(),
    val blockPackages: Set<String> = emptySet(),
    val network: NetworkAccess = NetworkAccess.DENY,
    val backgroundNetworkOff: Boolean = false,
    val screenOffBlock: Boolean = false,
) {
    fun matches(pkg: String, tags: Set<AppTag>): Boolean =
        pkg in blockPackages || tags.any { it in blockTags }

    /** Start equals end: on for the whole of each of its [days]. */
    val isAllDay: Boolean
        get() = startMinute == endMinute

    private fun runsOn(dayOfWeek: Int) = days.isEmpty() || dayOfWeek in days

    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = cal.get(Calendar.DAY_OF_WEEK)
        val yesterday = if (today == Calendar.SUNDAY) Calendar.SATURDAY else today - 1
        return when {
            isAllDay -> runsOn(today)
            startMinute < endMinute -> runsOn(today) && minute in startMinute until endMinute
            else ->
                (runsOn(today) && minute >= startMinute) ||
                    (runsOn(yesterday) && minute < endMinute)
        }
    }

    /**
     * The next start or end after [nowMs] (wall clock ms); the schedule alarm wakes there. An
     * all-day profile only changes state when the day does, so it wakes at midnight.
     */
    fun nextBoundary(nowMs: Long): Long {
        var best = Long.MAX_VALUE
        val marks = if (isAllDay) listOf(0) else listOf(startMinute, endMinute)
        for (d in 0..1) {
            for (m in marks) {
                val t =
                    Calendar.getInstance()
                        .apply {
                            timeInMillis = nowMs
                            add(Calendar.DAY_OF_YEAR, d)
                            set(Calendar.HOUR_OF_DAY, m / 60)
                            set(Calendar.MINUTE, m % 60)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        .timeInMillis
                if (t > nowMs && t < best) best = t
            }
        }
        return best
    }

    val windowText: String
        get() =
            if (isAllDay) "all day"
            else
                "%02d:%02d–%02d:%02d".format(
                    startMinute / 60,
                    startMinute % 60,
                    endMinute / 60,
                    endMinute % 60,
                )

    companion object {
        const val BEDTIME_ID = "bedtime"
        const val FOCUS_ID = "focus"

        /** Premade, both off by default. */
        val PREMADE =
            listOf(
                Profile(
                    BEDTIME_ID,
                    "Bedtime",
                    enabled = false,
                    startMinute = 22 * 60,
                    endMinute = 7 * 60,
                    blockTags = setOf(AppTag.SOCIAL, AppTag.VIDEO),
                ),
                Profile(
                    FOCUS_ID,
                    "Focus",
                    enabled = false,
                    startMinute = 9 * 60,
                    endMinute = 17 * 60,
                    days =
                        setOf(
                            Calendar.MONDAY,
                            Calendar.TUESDAY,
                            Calendar.WEDNESDAY,
                            Calendar.THURSDAY,
                            Calendar.FRIDAY,
                        ),
                    blockTags = setOf(AppTag.SOCIAL, AppTag.VIDEO, AppTag.GAMES),
                ),
            )
    }
}

/** Behaviour alert types; [bit] is the IIchthysGuard.ALERT_* value. */
enum class AlertType(val bit: Int, val label: String, val summary: String) {
    BACKGROUND_SENSOR(
        1,
        "Camera, mic or location in the background",
        "An app uses them while it isn't on screen",
    ),
    BATTERY_EXEMPTION(
        2,
        "Battery-optimisation exemption",
        "An app asks for or gets unrestricted battery use",
    ),
    BOOT(4, "Starts at boot", "A new app registers to start when the phone boots"),
    WAKEUPS(8, "Frequent wake-ups", "An app wakes the phone very often with the screen off"),
    LONG_WAKELOCK(16, "Long wakelocks", "An app keeps the phone awake for 30 minutes or more");

    companion object {
        const val ALL = 31

        fun fromBit(bit: Int): AlertType? = entries.firstOrNull { it.bit == bit }
    }
}

/** Common messengers: exempt from "no network while screen off" unless the user changes it. */
val MESSENGER_PRESET: Set<String> =
    setOf(
        "org.thoughtcrime.securesms",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "im.vector.app",
        "chat.schildi.android",
        "ch.threema.app",
        "ch.threema.app.libre",
        "com.wire",
        "eu.siacs.conversations",
        "im.molly.app",
        "org.briarproject.briar.android",
        "com.facebook.orca",
        "com.discord",
        "com.viber.voip",
        "jp.naver.line.android",
        "com.Slack",
        "com.microsoft.teams",
        "com.google.android.apps.messaging",
        "com.android.messaging",
        "com.simplemobiletools.smsmessenger",
        "org.fossify.messages",
        "com.android.dialer",
        "com.google.android.dialer",
    )

/** Guard's automation settings: profiles, the global screen-off rule, alerts, auto-tighten. */
@Serializable
data class AutomationSettings(
    val profiles: List<Profile> = Profile.PREMADE,
    /** "No network while screen off" for every user app except [screenOffExempt]. */
    val screenOffAll: Boolean = false,
    val screenOffExempt: Set<String> = MESSENGER_PRESET,
    /** IIchthysGuard.ALERT_* bits that are on. */
    val alertMask: Int = AlertType.ALL,
    /**
     * Unused apps lose their network after [autoTightenDays]. Off unless the user turns it on: it
     * suspends apps, so it must never surprise anyone.
     */
    val autoTighten: Boolean = false,
    val autoTightenDays: Int = 28,
) {
    fun activeProfiles(nowMs: Long = System.currentTimeMillis()): List<Profile> =
        profiles.filter { it.isActive(nowMs) }

    fun alertOn(type: AlertType) = (alertMask and type.bit) != 0

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun fromJson(text: String): AutomationSettings = json.decodeFromString(serializer(), text)
    }
}
