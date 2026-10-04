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

/** What an app may do on the network. Enforced by IchthysGuardService (platform, phase 1). */
@Serializable
enum class NetworkAccess {
    /** Default ("Shielded"): open, but every connection is monitored and tracker rules apply. */
    ALLOW,
    /** Wi-Fi / unmetered only. */
    UNMETERED_ONLY,
    DENY,
}

/** How aggressively tracker/ad hosts are blocked for this app, on top of the system hosts list. */
@Serializable
enum class TrackerBlockLevel {
    /** Only the system-wide hosts blocker (HaGeZi) applies. */
    OFF,
    /** Also block domains in the Guard tracker DB for this app. Default. */
    KNOWN_TRACKERS,
    /** Only domains the user allowed (or learned as needed) are reachable. */
    STRICT,
}

/**
 * Local network access. DEFAULT: blocked for user apps installed after setup, stock for the rest
 * (IchthysGuardService decides; it uses the stock ACCESS_LOCAL_NETWORK permission).
 */
@Serializable
enum class LocalNetwork {
    DEFAULT,
    ALLOW,
    BLOCK,
}

/**
 * Per-app policy. The defaults are the "Shielded" state every app starts in: network open, always
 * monitored, known trackers blocked, and every OS privacy exception closed.
 */
@Serializable
data class AppPolicy(
    val packageName: String,
    val network: NetworkAccess = NetworkAccess.ALLOW,
    val trackerBlock: TrackerBlockLevel = TrackerBlockLevel.KNOWN_TRACKERS,
    /** Exception to the OS contacts lockdown (Settings.Secure ichthys_contacts_allowed_pkgs). */
    val contactsAllowed: Boolean = false,
    /** Exception to the OS clipboard lockdown (Settings.Secure ichthys_clipboard_allowed_pkgs). */
    val clipboardAllowed: Boolean = false,
    /** Exempt from sensor anti-fingerprinting (Settings.Secure ichthys_sensor_exempt_pkgs). */
    val sensorsExempt: Boolean = false,
    /** Hosts the user explicitly allowed for this app (STRICT mode / overriding a tracker hit). */
    val allowedHosts: Set<String> = emptySet(),
    /** Hosts the user explicitly blocked for this app. */
    val blockedHosts: Set<String> = emptySet(),
    /** No network while the screen is off or locked (W8b decision 13). */
    val screenOffBlock: Boolean = false,
    /** Network only while the app is on screen (W8b decision 13). */
    val backgroundNetworkOff: Boolean = false,
    /** Local network (LAN) access; DEFAULT is the platform's choice (see [LocalNetwork]). */
    val localNetwork: LocalNetwork = LocalNetwork.DEFAULT,
    /** The user's tags; profiles match on them (Bedtime blocks SOCIAL and VIDEO). */
    val tags: Set<AppTag> = emptySet(),
    /** Auto-tightened after weeks unused: no network until the user reviews it again. */
    val dormant: Boolean = false,
    /** Never auto-tighten this app. */
    val keepActive: Boolean = false,
) {
    /** True when nothing differs from the Shielded defaults (lets the store drop the entry). */
    val isDefault: Boolean
        get() = this == AppPolicy(packageName)
}
