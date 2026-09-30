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

package com.android.settings.ichthys.guard.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.TrackerBlockLevel

/**
 * Registered dynamically by [GuardNotifications.init] (implicit PACKAGE_ADDED is not delivered to
 * manifest receivers).
 */
class PackageAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        runAsync {
            GuardRepository.get(context).refreshApps()
            GuardNotifications.notifyNewApp(context, pkg)
        }
    }
}

/**
 * Explicit broadcast from IchthysGuardService after its install-time scan finished, so the
 * notification carries the real tracker count even when the Settings process wasn't running.
 */
class AppScannedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GuardNotifications.ACTION_APP_SCANNED) return
        val pkg = intent.getStringExtra(GuardNotifications.EXTRA_PACKAGE) ?: return
        runAsync {
            GuardRepository.get(context).refreshApps()
            GuardNotifications.notifyNewApp(context, pkg)
        }
    }
}

/** "Block network" / "Strict" buttons on the new-app notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(GuardNotifications.EXTRA_PACKAGE) ?: return
        runAsync {
            val repo = GuardRepository.get(context)
            val label = repo.appFor(pkg)?.label ?: pkg
            when (intent.action) {
                GuardNotifications.ACTION_BLOCK_NETWORK -> {
                    repo.updatePolicy(pkg) { it.copy(network = NetworkAccess.DENY) }
                    GuardNotifications.confirm(context, pkg, "$label: network blocked")
                }
                GuardNotifications.ACTION_STRICT -> {
                    repo.updatePolicy(pkg) { it.copy(trackerBlock = TrackerBlockLevel.STRICT) }
                    GuardNotifications.confirm(context, pkg, "$label: strict mode on")
                }
            }
        }
    }
}

class WeeklyReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync { GuardNotifications.postWeeklyReport(context) }
    }
}

/**
 * Re-arms the weekly alarm and re-pushes every app's network policy after boot. BOOT_COMPLETED
 * comes after the user unlocked, so this also finishes [GuardNotifications.init] when the Settings
 * process was already running in direct boot (FallbackHome) and skipped it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runAsync {
            GuardNotifications.init(context)
            GuardNotifications.scheduleWeeklyReport(context)
            val repo = GuardRepository.get(context)
            repo.refreshApps()
            repo.pushAllNetworkPolicies()
        }
    }
}
