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

package com.android.settings.ichthys.guard.os

import android.app.role.RoleManager
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import com.android.settings.R

/**
 * IchthysOS: real personal data an app gets through a door Guard's shields don't cover: a role or
 * special access the user granted elsewhere. Each one hands the app real data whatever its
 * contacts, call log or SMS shield says, so the app page lists them as real-data grants with a
 * link to where they are taken back.
 *
 * - Notification access: every notification, message previews and one-time codes included
 *   (Guard's quiet notifications only change the display; listeners still get them).
 * - Call screening: the number of every incoming call.
 * - Companion device roles (watch, glasses, ...): a watch companion may manage ongoing calls
 *   (MANAGE_ONGOING_CALLS: number and name of every call) and usually reads notifications.
 * - Default assistant: the screen content and microphone when invoked.
 * - Autofill service: what is typed into forms, passwords included.
 */
object RealDataGrants {
    private const val TAG = "IchthysGuard"

    /** One grant: what it is, what it hands over, and the settings page that takes it back. */
    data class Grant(@StringRes val label: Int, @StringRes val detail: Int, val manage: Intent)

    /** The companion device roles that come with real-data permissions, with their names. */
    private val COMPANION_ROLES =
        listOf(
            "android.app.role.COMPANION_DEVICE_WATCH" to R.string.ichthys_guard_grant_companion_watch,
            "android.app.role.COMPANION_DEVICE_GLASSES" to R.string.ichthys_guard_grant_companion_glasses,
            "android.app.role.COMPANION_DEVICE_APP_STREAMING" to R.string.ichthys_guard_grant_companion_streaming,
            "android.app.role.COMPANION_DEVICE_NEARBY_DEVICE_STREAMING" to R.string.ichthys_guard_grant_companion_streaming,
            "android.app.role.COMPANION_DEVICE_COMPUTER" to R.string.ichthys_guard_grant_companion_computer,
            "android.app.role.COMPANION_DEVICE_VIRTUAL_DEVICE" to R.string.ichthys_guard_grant_companion_virtual,
            "android.app.role.COMPANION_DEVICE_MEDICAL" to R.string.ichthys_guard_grant_companion_medical,
        )

    /** Every real-data grant [pkg] holds right now. Binder calls: off the main thread. */
    fun forPackage(context: Context, pkg: String): List<Grant> {
        val out = ArrayList<Grant>()
        val cr = context.contentResolver
        val appDetails =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        val defaultApps = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

        if (componentsOf(Settings.Secure.getString(cr, "enabled_notification_listeners")).contains(pkg)) {
            out += Grant(
                R.string.ichthys_guard_grant_notifications,
                R.string.ichthys_guard_grant_notifications_detail,
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
        }
        val roles = context.getSystemService(RoleManager::class.java)
        fun holds(role: String): Boolean =
            try {
                roles != null && roles.isRoleAvailable(role) && pkg in roles.getRoleHolders(role)
            } catch (e: RuntimeException) {
                Log.w(TAG, "role $role unknown for $pkg", e)
                false
            }
        if (holds(RoleManager.ROLE_CALL_SCREENING)) {
            out += Grant(
                R.string.ichthys_guard_grant_call_screening,
                R.string.ichthys_guard_grant_call_screening_detail,
                defaultApps,
            )
        }
        var companion = false
        for ((role, label) in COMPANION_ROLES) {
            if (holds(role)) {
                companion = true
                out += Grant(label, R.string.ichthys_guard_grant_companion_detail, appDetails)
            }
        }
        // An association without a device profile still keeps the app bound and unrestricted.
        if (!companion) {
            val associated =
                try {
                    context.getSystemService(CompanionDeviceManager::class.java)
                        ?.allAssociations
                        ?.any { it.packageName == pkg } == true
                } catch (e: RuntimeException) {
                    false
                }
            if (associated) {
                out += Grant(
                    R.string.ichthys_guard_grant_companion_device,
                    R.string.ichthys_guard_grant_companion_device_detail,
                    appDetails,
                )
            }
        }
        if (holds(RoleManager.ROLE_ASSISTANT) ||
            ComponentName.unflattenFromString(Settings.Secure.getString(cr, "assistant") ?: "")
                ?.packageName == pkg
        ) {
            out += Grant(
                R.string.ichthys_guard_grant_assistant,
                R.string.ichthys_guard_grant_assistant_detail,
                defaultApps,
            )
        }
        if (ComponentName.unflattenFromString(Settings.Secure.getString(cr, Settings.Secure.AUTOFILL_SERVICE) ?: "")
                ?.packageName == pkg
        ) {
            out += Grant(
                R.string.ichthys_guard_grant_autofill,
                R.string.ichthys_guard_grant_autofill_detail,
                defaultApps,
            )
        }
        return out
    }

    /** Package names of a ':'-separated component list setting. */
    private fun componentsOf(value: String?): Set<String> =
        value
            ?.split(':')
            ?.mapNotNull { ComponentName.unflattenFromString(it.trim())?.packageName }
            ?.toSet()
            .orEmpty()
}
