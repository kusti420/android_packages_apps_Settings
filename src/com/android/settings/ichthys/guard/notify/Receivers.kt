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
import com.android.settings.ichthys.guard.data.VerifyRunner
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
            // IchthysOS: the firewall tables were built before this app existed. Push them again
            // so lockdown, an active profile and the screen-off rule cover the new uid too (and a
            // reinstalled app gets its saved policy back under its new uid).
            repo.pushAllNetworkPolicies()
            repo.pushBehaviours()
            GuardNotifications.notifyNewApp(context, pkg)
            GuardNotifications.markAnnounced(context)
            repo.riskFactsFor(pkg)
        }
    }
}

/**
 * IchthysOS: an app was uninstalled: drops its unfinished first-launch review mark and
 * notification. PACKAGE_FULLY_REMOVED is exempt from the implicit-broadcast limits, so this
 * manifest receiver runs even when Settings isn't; the repository is only started when the app
 * had a mark.
 */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        if (!GuardRepository.isGuardUser()) return
        // Guard's state is credential-protected.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        runAsync {
            GuardNotifications.cancelReviewUnfinished(context, pkg)
            if (GuardRepository.hasUnfinishedReview(context, pkg)) {
                GuardRepository.get(context).clearUnfinishedReview(pkg)
            }
        }
    }
}

/**
 * Explicit broadcasts from IchthysGuardService (sender must hold NETWORK_STACK): after its
 * install-time scan finished, so the notification carries the real tracker count even when the
 * Settings process wasn't running; [GuardNotifications.ACTION_ALERT] behaviour alerts; and
 * IchthysOS: [GuardNotifications.ACTION_WOKE] when an asleep app was opened.
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
        // IchthysOS: an asleep app was opened; the service already gave it its network back.
        if (intent.action == GuardNotifications.ACTION_WOKE) {
            val pkgs = intent.getStringArrayExtra(GuardNotifications.EXTRA_PACKAGES) ?: return
            runAsync {
                val repo = GuardRepository.get(context)
                for (pkg in pkgs) repo.wakeOpened(pkg)
            }
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
            // IchthysOS: a verification phase got no answer from the probe.
            VerifyRunner.ACTION_TIMEOUT -> {
                val token = intent.getStringExtra(VerifyRunner.EXTRA_REPLY_TOKEN)
                runAsync { VerifyRunner.get(context).onTimeout(token) }
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

/**
 * IchthysOS: IchthysProbe's answer to a verification phase Guard launched (VerifyRunner). The
 * probe is an ordinary user app, so the receiver is exported without a permission; the reply is
 * accepted only when it carries the one-time token Guard handed the probe for this phase, so
 * nothing else can forge a verdict.
 */
class VerifyResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VerifyRunner.ACTION_RESULT || !GuardRepository.isGuardUser()) return
        val token = intent.getStringExtra(VerifyRunner.EXTRA_REPLY_TOKEN) ?: return
        val extras = intent.extras ?: return
        runAsync { VerifyRunner.get(context).onResult(token, extras) }
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
                } catch (e: Exception) {
                    // The tile shows "off" rather than Settings crashing on its broadcast.
                    android.util.Log.w("IchthysGuard", "lockdown broadcast failed", e)
                    pending.resultCode = 0
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
