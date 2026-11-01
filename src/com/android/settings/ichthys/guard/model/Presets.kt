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

/**
 * IchthysOS: what an app gets of the phone's position. One three-way control in place of the
 * two independent toggles ("No location" and "sees the real location") that used to let an app
 * be on both lists; mapped onto the two Settings lists by GuardRepository.setLocationMode, so the
 * lists can't contradict each other.
 */
enum class LocationMode(val label: String) {
    /** On ichthys_location_real_pkgs: the real position even while the fake place is on. */
    REAL("Real"),
    /** On neither list: the fake place while it is on, the real position otherwise. */
    FAKE("Fake place"),
    /** On ichthys_location_nofix_pkgs: never a fix, as if indoors; scans find nothing. */
    NONE("None"),
}

/** IchthysOS: network while the screen is off or locked. One control for the two old toggles. */
enum class ScreenOffMode(val label: String) {
    /** The global screen-off rule decides (Gear › Screen-off rule). */
    FOLLOW("Follow rule"),
    /** Offline while the screen is off, whatever the rule says. */
    OFFLINE("Offline"),
    /** Online while the screen is off, even when the rule is on (a messenger). */
    STAY_ONLINE("Stay online"),
}

/** IchthysOS: what Wi-Fi and Bluetooth scans return to the app. */
enum class ScanMode(val label: String) {
    REAL("Real"),
    EMPTY("Empty"),
}

/**
 * IchthysOS: one preset's table. Every control of the app page has a value here; anything not
 * listed is "shielded": the app is on no allow list and gets the protected answer. Presets are a
 * Settings-side layer: they only write the existing policy and the existing ichthys_* lists.
 */
data class PresetSpec(
    val network: NetworkAccess,
    val screenOff: ScreenOffMode,
    val backgroundOff: Boolean,
    val lan: LocalNetwork,
    val location: LocationMode,
    val scans: ScanMode,
    /** Contacts: "pkg:picked" in the contacts list (only the contacts picked for it). */
    val contactsPicked: Boolean = false,
    /** Exact sensor data (ichthys_sensor_exempt_pkgs). */
    val sensorsReal: Boolean = true,
    val nfcBlocked: Boolean = false,
    /** Keeps its network when Lockdown is on. */
    val lockdownKeep: Boolean = false,
)

/** IchthysOS: the presets of the app page. [CUSTOM] has no table: the user's own choices. */
@Serializable
enum class AppPreset(val label: String, val summary: String) {
    MAPS("Maps", "Online, real location, real scans; everything else shielded"),
    MESSENGER(
        "Messenger",
        "Online even with the screen off and in lockdown; picked contacts; fake place",
    ),
    BROWSER("Browser", "Online while on screen; no location; everything shielded"),
    GAME("Game", "Wi-Fi only; no location, no scans, rounded sensors; everything shielded"),
    UNTRUSTED("Untrusted", "No network, no location, empty scans, no NFC; everything shielded"),
    CUSTOM("Custom", "Your own choices");

    val spec: PresetSpec?
        get() =
            when (this) {
                MAPS ->
                    PresetSpec(
                        network = NetworkAccess.ALLOW,
                        screenOff = ScreenOffMode.FOLLOW,
                        backgroundOff = false,
                        lan = LocalNetwork.BLOCK,
                        location = LocationMode.REAL,
                        scans = ScanMode.REAL,
                    )
                MESSENGER ->
                    PresetSpec(
                        network = NetworkAccess.ALLOW,
                        screenOff = ScreenOffMode.STAY_ONLINE,
                        backgroundOff = true,
                        lan = LocalNetwork.BLOCK,
                        location = LocationMode.FAKE,
                        scans = ScanMode.REAL,
                        contactsPicked = true,
                        lockdownKeep = true,
                    )
                BROWSER ->
                    PresetSpec(
                        network = NetworkAccess.ALLOW,
                        screenOff = ScreenOffMode.FOLLOW,
                        backgroundOff = true,
                        lan = LocalNetwork.BLOCK,
                        location = LocationMode.NONE,
                        scans = ScanMode.REAL,
                    )
                GAME ->
                    PresetSpec(
                        network = NetworkAccess.UNMETERED_ONLY,
                        screenOff = ScreenOffMode.FOLLOW,
                        backgroundOff = false,
                        lan = LocalNetwork.BLOCK,
                        location = LocationMode.NONE,
                        scans = ScanMode.EMPTY,
                        sensorsReal = false,
                    )
                UNTRUSTED ->
                    PresetSpec(
                        network = NetworkAccess.DENY,
                        screenOff = ScreenOffMode.FOLLOW,
                        backgroundOff = false,
                        lan = LocalNetwork.BLOCK,
                        location = LocationMode.NONE,
                        scans = ScanMode.EMPTY,
                        sensorsReal = false,
                        nfcBlocked = true,
                    )
                CUSTOM -> null
            }

    companion object {
        /** The presets offered on the app page, in order. */
        val PICKER: List<AppPreset> = listOf(MAPS, MESSENGER, BROWSER, GAME, UNTRUSTED, CUSTOM)
    }
}

/**
 * IchthysOS: the controls of the app page, for the "differs from preset" marks and the Apps
 * filter "Custom". Settings-side only.
 */
enum class PresetControl(val label: String) {
    NETWORK("Internet"),
    SCREEN_OFF("Screen off"),
    BACKGROUND("Background"),
    LAN("Local network"),
    LOCATION("Location"),
    SCANS("Scans"),
    CONTACTS("Contacts"),
    SENSORS("Sensors"),
    NFC("NFC"),
    LOCKDOWN_KEEP("Lockdown"),
    /** Any other allow list the app is on (calendar, call log, clipboard, ...). */
    OTHER_SHIELDS("Other shields"),
}

/**
 * IchthysOS: the effective state of an app's controls, as read back from the policy and the
 * Settings lists, so it can be compared with a [PresetSpec].
 */
data class AppControls(
    val network: NetworkAccess,
    val screenOff: ScreenOffMode,
    val backgroundOff: Boolean,
    val lan: LocalNetwork,
    val location: LocationMode,
    val scans: ScanMode,
    val contactsReal: Boolean,
    val contactsPicked: Boolean,
    val sensorsReal: Boolean,
    val nfcBlocked: Boolean,
    val lockdownKeep: Boolean,
    /** Allow lists the app is on that the preset would clear (their shield labels). */
    val otherAllowed: List<String>,
) {
    /** The controls that differ from [spec]; empty when the app is exactly on its preset. */
    fun differences(spec: PresetSpec): Set<PresetControl> =
        buildSet {
            if (network != spec.network) add(PresetControl.NETWORK)
            if (screenOff != spec.screenOff) add(PresetControl.SCREEN_OFF)
            if (backgroundOff != spec.backgroundOff) add(PresetControl.BACKGROUND)
            if (lan != spec.lan) add(PresetControl.LAN)
            if (location != spec.location) add(PresetControl.LOCATION)
            if (scans != spec.scans) add(PresetControl.SCANS)
            if (contactsReal || contactsPicked != spec.contactsPicked) add(PresetControl.CONTACTS)
            if (sensorsReal != spec.sensorsReal) add(PresetControl.SENSORS)
            if (nfcBlocked != spec.nfcBlocked) add(PresetControl.NFC)
            if (lockdownKeep != spec.lockdownKeep) add(PresetControl.LOCKDOWN_KEEP)
            if (otherAllowed.isNotEmpty()) add(PresetControl.OTHER_SHIELDS)
        }
}
