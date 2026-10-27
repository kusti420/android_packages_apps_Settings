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
import android.os.UserManager
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.AlertType
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.TrackerBlockLevel

/**
 * Registered dynamically by [GuardNotifications.init] (implicit PACKAGE_ADDED is not delivered to
 * manifest receivers).
 */
class PackageAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED || !GuardRepository.isGuardUser()) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        runAsync {
            val repo = GuardRepository.get(context)
            repo.refreshApps()
            GuardNotifications.notifyNewApp(context, pkg)
            GuardNotifications.markAnnounced(context)
            repo.riskFactsFor(pkg)
        }
    }
}

/**
 * Explicit broadcasts from IchthysGuardService (sender must hold NETWORK_STACK): after its
 * install-time scan finished, so the notification carries the real tracker count even when the
 * Settings process wasn't running; and [GuardNotifications.ACTION_ALERT] behaviour alerts.
 */
class AppScannedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!GuardRepository.isGuardUser()) return
        if (intent.action == GuardNotifications.ACTION_ALERT) {
            val pkg = intent.getStringExtra(GuardNotifications.EXTRA_PACKAGE) ?: return
            val type =
                AlertType.fromBit(intent.getIntExtra(GuardNotifications.EXTRA_ALERT_TYPE, 0))
                    ?: return
            val detail = intent.getStringExtra(GuardNotifications.EXTRA_DETAIL) ?: type.summary
            runAsync { GuardNotifications.notifyAlert(context, pkg, type, detail) }
            return
        }
        if (intent.action != GuardNotifications.ACTION_APP_SCANNED) return
        val pkg = intent.getStringExtra(GuardNotifications.EXTRA_PACKAGE) ?: return
        runAsync {
            GuardRepository.get(context).refreshApps()
            GuardNotifications.notifyNewApp(context, pkg)
        }
    }
}

/**
 * "Block network" / "Strict" buttons on the new-app notification, plus Guard's own alarms: the
 * profile schedule ([GuardNotifications.ACTION_PROFILE_TICK]) and daily maintenance
 * ([GuardNotifications.ACTION_MAINTENANCE], auto-tighten). All explicit PendingIntents.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!GuardRepository.isGuardUser()) return
        when (intent.action) {
            GuardNotifications.ACTION_PROFILE_TICK -> {
                runAsync { GuardRepository.get(context).onScheduleTick() }
                return
            }
            GuardNotifications.ACTION_MAINTENANCE -> {
                runAsync {
                    val paused = GuardRepository.get(context).runMaintenance()
                    GuardNotifications.notifyTightened(context, paused)
                }
                return
            }
        }
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
                    // IchthysOS: only notifications from before HostRules still carry the button.
                    // STRICT with no allowed hosts would cut the app off once host rules are
                    // enforced, and does nothing until then, so it isn't saved.
                    if (!HostRules.ENFORCED) {
                        GuardNotifications.confirm(
                            context,
                            pkg,
                            "$label: per-app tracker blocking isn't available yet",
                        )
                        return@runAsync
                    }
                    repo.updatePolicy(pkg) { it.copy(trackerBlock = TrackerBlockLevel.STRICT) }
                    GuardNotifications.confirm(context, pkg, "$label: strict mode on")
                }
            }
        }
    }
}

class WeeklyReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!GuardRepository.isGuardUser()) return
        runAsync { GuardNotifications.postWeeklyReport(context) }
    }
}

/**
 * Re-arms the weekly alarm and re-pushes every app's network policy after boot, and re-evaluates
 * the profile schedule after a clock or time-zone change. BOOT_COMPLETED comes after the user
 * unlocked, so this also finishes [GuardNotifications.init] when the Settings process was already
 * running in direct boot (FallbackHome) and skipped it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // IchthysOS: every user's BOOT_COMPLETED lands here. Only the system user's Guard may
        // push; another user's app list would replace the owner's whole table.
        if (!GuardRepository.isGuardUser()) return
        if (
            intent.action == Intent.ACTION_TIME_CHANGED ||
                intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            // IchthysOS: the clock moved, so profile windows open or close at other times. Guard's
            // state is credential-protected; before the first unlock BOOT_COMPLETED re-arms all.
            if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
            runAsync { GuardRepository.get(context).onScheduleTick() }
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runAsync {
            GuardNotifications.init(context)
            GuardNotifications.scheduleWeeklyReport(context)
            GuardNotifications.scheduleMaintenance(context)
            val repo = GuardRepository.get(context)
            repo.refreshApps()
            repo.pushAllNetworkPolicies()
            repo.pushBehaviours()
            repo.scheduleProfileTick()
        }
    }
}

/**
 * The native SystemUI "Guard lockdown" tile reads and sets lockdown here (lockdown state lives in
 * [GuardRepository], not in IchthysGuardService). An ordered broadcast guarded by
 * MANAGE_ICHTHYS_GUARD; an "enabled" extra sets the state, and the result code reports it
 * (1 = on, 0 = off).
 */
class LockdownControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOCKDOWN) return
        // IchthysOS: Guard (and its lockdown) belongs to the system user only.
        if (!GuardRepository.isGuardUser()) {
            if (isOrderedBroadcast) resultCode = 0
            return
        }
        // The rules are in credential-protected storage. Before the first unlock after a boot
        // the tile already asks for its state; answer "off" instead of crashing on the storage.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
            if (isOrderedBroadcast) resultCode = 0
            return
        }
        val pending = goAsync()
        Thread {
                try {
                    val repo = GuardRepository.get(context)
                    if (intent.hasExtra(EXTRA_ENABLED)) {
                        repo.setLockdown(intent.getBooleanExtra(EXTRA_ENABLED, false))
                    }
                    pending.resultCode = if (repo.rules.value.lockdown.enabled) 1 else 0
                } finally {
                    pending.finish()
                }
            }
            .start()
    }

    companion object {
        const val ACTION_LOCKDOWN = "com.android.settings.ichthys.guard.action.LOCKDOWN"
        const val ACTION_LOCKDOWN_CHANGED =
            "com.android.settings.ichthys.guard.action.LOCKDOWN_CHANGED"
        const val EXTRA_ENABLED = "enabled"
    }
}
