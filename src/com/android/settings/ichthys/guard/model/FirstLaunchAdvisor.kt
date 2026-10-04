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

/**
 * What the first-launch review knows about a newly installed app. Plain values only, so the advisor
 * runs (and is tested) off-device.
 */
data class AppFacts(
    val packageName: String,
    /** Tracker names from the install-time scan; null while the scan is unknown or pending. */
    val trackers: List<String>?,
    /** Every permission the manifest requests (android.permission.* names). */
    val requestedPermissions: Set<String>,
    /** Has a BROWSABLE http(s) VIEW filter with no fixed host, i.e. it is a web browser. */
    val isBrowser: Boolean,
) {
    val usesInternet: Boolean
        get() = PERMISSION_INTERNET in requestedPermissions

    companion object {
        const val PERMISSION_INTERNET = "android.permission.INTERNET"
    }
}

/** Plain-language groups of runtime permissions, in the order the sheet lists them. */
enum class PermissionGroup(val permissions: Set<String>) {
    LOCATION(perms("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION")),
    CAMERA(perms("CAMERA")),
    MICROPHONE(perms("RECORD_AUDIO")),
    CONTACTS(perms("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS")),
    SMS_CALLS(
        perms(
            "SEND_SMS",
            "RECEIVE_SMS",
            "READ_SMS",
            "RECEIVE_MMS",
            "RECEIVE_WAP_PUSH",
            "READ_PHONE_STATE",
            "READ_PHONE_NUMBERS",
            "CALL_PHONE",
            "ANSWER_PHONE_CALLS",
            "READ_CALL_LOG",
            "WRITE_CALL_LOG",
            "PROCESS_OUTGOING_CALLS",
            "ADD_VOICEMAIL",
            "USE_SIP",
            "ACCEPT_HANDOVER",
        )
    ),
    STORAGE_MEDIA(
        perms(
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "MANAGE_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO",
            "READ_MEDIA_AUDIO",
            "READ_MEDIA_VISUAL_USER_SELECTED",
            "ACCESS_MEDIA_LOCATION",
        )
    ),
    NEARBY_DEVICES(
        perms(
            "BLUETOOTH_SCAN",
            "BLUETOOTH_CONNECT",
            "BLUETOOTH_ADVERTISE",
            "NEARBY_WIFI_DEVICES",
            "UWB_RANGING",
            "RANGING",
        )
    ),
    NOTIFICATIONS(perms("POST_NOTIFICATIONS"));

    companion object {
        /** The groups [requested] touches, in declaration order. */
        fun of(requested: Set<String>): List<PermissionGroup> =
            entries.filter { g -> g.permissions.any { it in requested } }
    }
}

private fun perms(vararg names: String): Set<String> =
    names.mapTo(LinkedHashSet()) { "android.permission.$it" }

/** The part of the policy a recommendation line is about. */
enum class AdviceTopic {
    NETWORK,
    TRACKERS,
    EXCEPTIONS,
}

/** Why a value was chosen. The UI turns each into one line of text. */
enum class AdviceReason {
    /** Requests INTERNET: network open, every connection monitored (Shielded). */
    NETWORK_SHIELDED,
    /** No INTERNET permission: nothing to restrict network-wise. */
    NETWORK_NOT_REQUESTED,
    /** ≥ [FirstLaunchAdvisor.STRICT_TRACKER_THRESHOLD] trackers: only allowed hosts reachable. */
    TRACKERS_STRICT,
    /** 1..threshold-1 trackers: block the known tracker hosts (the default). */
    TRACKERS_KNOWN,
    /** Trackers found, but it is a browser: strict mode would break the web, keep the default. */
    TRACKERS_BROWSER,
    /** Scan found nothing: known tracker hosts stay blocked anyway (the default). */
    TRACKERS_NONE,
    /** Scan unknown or still running: uncertain, so the default. */
    TRACKERS_UNKNOWN,
    /** Contacts, clipboard and sensors stay locked down until the user allows them. */
    EXCEPTIONS_CLOSED,
}

data class AdviceLine(val topic: AdviceTopic, val reason: AdviceReason)

/** The recommended policy plus one reason line per topic, in display order. */
data class Recommendation(val policy: AppPolicy, val lines: List<AdviceLine>) {
    /**
     * The recommendation applied on top of a policy the user already built (an app paused by
     * auto-tighten, back for review): network and tracker blocking become at least as strict as
     * recommended, [AppPolicy.dormant] is cleared, and everything else the user set stays (host
     * rules, tags, keepActive, the contacts and other exceptions, network behaviour).
     */
    fun mergedInto(existing: AppPolicy): AppPolicy =
        existing.copy(
            network = maxOf(existing.network, policy.network),
            trackerBlock = maxOf(existing.trackerBlock, policy.trackerBlock),
            dormant = false,
        )
}

/**
 * The first-launch recommendation (W8b decision 16), small and rule-based for now: only the
 * manifest (INTERNET, browser intent filter) and the install-time tracker scan feed it. Anything
 * uncertain stays at the stock default (Shielded), and every line carries its reason.
 *
 * Rules:
 * 1. Trackers found and the app is not a browser → trackerBlock STRICT when there are at least
 *    [STRICT_TRACKER_THRESHOLD], else KNOWN_TRACKERS.
 * 2. No INTERNET permission → nothing to restrict network-wise (network stays default).
 * 3. Otherwise the Shielded defaults.
 */
object FirstLaunchAdvisor {
    const val STRICT_TRACKER_THRESHOLD = 3

    fun recommend(facts: AppFacts): Recommendation {
        val base = AppPolicy(facts.packageName)
        val trackers = facts.trackers
        val (trackerBlock, trackerReason) =
            when {
                trackers == null -> base.trackerBlock to AdviceReason.TRACKERS_UNKNOWN
                trackers.isEmpty() -> base.trackerBlock to AdviceReason.TRACKERS_NONE
                facts.isBrowser -> base.trackerBlock to AdviceReason.TRACKERS_BROWSER
                trackers.size >= STRICT_TRACKER_THRESHOLD ->
                    TrackerBlockLevel.STRICT to AdviceReason.TRACKERS_STRICT
                else -> TrackerBlockLevel.KNOWN_TRACKERS to AdviceReason.TRACKERS_KNOWN
            }
        val networkReason =
            if (facts.usesInternet) AdviceReason.NETWORK_SHIELDED
            else AdviceReason.NETWORK_NOT_REQUESTED
        return Recommendation(
            base.copy(trackerBlock = trackerBlock),
            listOf(
                AdviceLine(AdviceTopic.NETWORK, networkReason),
                AdviceLine(AdviceTopic.TRACKERS, trackerReason),
                AdviceLine(AdviceTopic.EXCEPTIONS, AdviceReason.EXCEPTIONS_CLOSED),
            ),
        )
    }
}
