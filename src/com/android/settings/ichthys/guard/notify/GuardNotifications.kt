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

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.android.settings.R
import com.android.settings.ichthys.guard.os.ShieldSwitch
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.AlertType
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.PeriodReport
import com.android.settings.ichthys.guard.model.StatsHistory
import com.android.settings.ichthys.guard.ui.FirstLaunchActivity
import com.android.settings.ichthys.guard.ui.GuardActivity
import java.util.Calendar

/**
 * Guard's notifications, posted by Settings: "new app installed" (with one-tap Block network, and
 * Strict once host rules are enforced), the confirmations after those actions, and the weekly
 * privacy report. Every notification opens [GuardActivity].
 *
 * [init] runs when the Settings process starts (SettingsApplication) and again from the boot
 * receiver. It is idempotent and does nothing until the user is unlocked, because Guard's state
 * lives in credential-protected storage.
 */
object GuardNotifications {
    private const val TAG = "IchthysGuard"
    /** New-app notices and action confirmations. */
    const val CHANNEL_ALERTS = "ichthys_guard_alerts"
    /** The weekly report. */
    const val CHANNEL_REPORTS = "ichthys_guard_reports"
    const val EXTRA_OPEN_PACKAGE = "com.android.settings.ichthys.guard.extra.OPEN_PACKAGE"
    const val EXTRA_PACKAGE = "package"
    const val ACTION_BLOCK_NETWORK = "com.android.settings.ichthys.guard.action.BLOCK_NETWORK"
    const val ACTION_STRICT = "com.android.settings.ichthys.guard.action.STRICT"
    const val ACTION_WEEKLY_REPORT = "com.android.settings.ichthys.guard.action.WEEKLY_REPORT"
    /**
     * Explicit broadcast from IchthysGuardService after its install-time scan (extra
     * [EXTRA_PACKAGE]). The action name is the platform contract and stays as it was for the
     * standalone app.
     */
    const val ACTION_APP_SCANNED = "org.ichthys.guard.action.APP_SCANNED"
    /**
     * Explicit broadcast from IchthysGuardService with one behaviour alert (extras [EXTRA_PACKAGE],
     * [EXTRA_ALERT_TYPE], [EXTRA_DETAIL]). Sent to AppScannedReceiver's component, so it reuses
     * that receiver's manifest entry and its NETWORK_STACK sender permission.
     */
    const val ACTION_ALERT = "org.ichthys.guard.action.ALERT"
    const val EXTRA_ALERT_TYPE = "alert_type"
    /**
     * IchthysOS: explicit broadcast from IchthysGuardService (to AppScannedReceiver, like
     * [ACTION_ALERT]) when an app asleep for being unused came to the top; the service has lifted
     * its deny already. Extra [EXTRA_PACKAGES]: the packages of the uid.
     */
    const val ACTION_WOKE = "org.ichthys.guard.action.WOKE"
    const val EXTRA_PACKAGES = "packages"
    const val EXTRA_DETAIL = "detail"
    /** Profile schedule alarm (explicit, to NotificationActionReceiver). */
    const val ACTION_PROFILE_TICK = "com.android.settings.ichthys.guard.action.PROFILE_TICK"
    /** Daily maintenance alarm: auto-tighten unused apps. */
    const val ACTION_MAINTENANCE = "com.android.settings.ichthys.guard.action.MAINTENANCE"
    private const val REPORT_ID = 0x16a0
    private const val TIGHTEN_ID = 0x16a1
    private const val ALERT_ID_BASE = 0x2000000
    /** IchthysOS: "<app>: review not finished", one per package. */
    private const val REVIEW_ID_BASE = 0x3000000
    private const val PREFS = "ichthys_guard_notify"
    private const val KEY_CHANGED_SEQ = "changed_seq"
    /** Settings.Global.BOOT_COUNT [KEY_CHANGED_SEQ] belongs to (the sequence restarts at boot). */
    private const val KEY_CHANGED_BOOT = "changed_boot"
    /** Local hour of the daily maintenance alarm: a time the phone is normally idle. */
    private const val MAINTENANCE_HOUR = 4

    @Volatile private var initialized = false

    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        // IchthysOS: no Guard for secondary users and profiles (see GuardRepository.isGuardUser).
        if (!GuardRepository.isGuardUser()) return
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        try {
            createChannels(app)
            channelsReady = true
            scheduleWeeklyReport(app)
            scheduleMaintenance(app)
            val filter =
                IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
            app.registerReceiver(PackageAddedReceiver(), filter, Context.RECEIVER_EXPORTED)
        } catch (e: RuntimeException) {
            // Never take Settings down over Guard.
            Log.w(TAG, "init failed", e)
        }
        Thread({ catchUp(app) }, "IchthysGuardCatchUp").start()
    }

    @Volatile private var channelsReady = false

    /**
     * IchthysOS: the channels exist before anything is posted. [init] creates them, but a
     * receiver can start this process after the unlock and before BOOT_COMPLETED, and a post
     * to a missing channel is dropped silently.
     */
    private fun ensureChannels(ctx: Context) {
        if (channelsReady) return
        try {
            createChannels(ctx.applicationContext)
            channelsReady = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "channels not created", e)
        }
    }

    private fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ALERTS,
                    ctx.getString(R.string.ichthys_guard_channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description = ctx.getString(R.string.ichthys_guard_channel_alerts_summary)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_REPORTS,
                    ctx.getString(R.string.ichthys_guard_channel_reports),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = ctx.getString(R.string.ichthys_guard_channel_reports_summary)
                }
        )
    }

    /** Next Sunday 19:00, then weekly. FLAG_UPDATE_CURRENT keeps it a single alarm. */
    fun scheduleWeeklyReport(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val cal =
            Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                set(Calendar.HOUR_OF_DAY, 19)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, 1)
            }
        am.setInexactRepeating(
            AlarmManager.RTC,
            cal.timeInMillis,
            AlarmManager.INTERVAL_DAY * 7,
            reportAlarmIntent(ctx),
        )
    }

    /**
     * Daily, inexact: auto-tighten runs in [NotificationActionReceiver]. Anchored to the next
     * [MAINTENANCE_HOUR]:00, like the weekly report, so setting it again (every Settings process
     * start and boot) replaces the alarm with the same one instead of pushing it back.
     */
    fun scheduleMaintenance(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi =
            PendingIntent.getBroadcast(
                ctx,
                2,
                Intent(ctx, NotificationActionReceiver::class.java).setAction(ACTION_MAINTENANCE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cal =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, MAINTENANCE_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
        am.setInexactRepeating(AlarmManager.RTC, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    private fun reportAlarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ctx, WeeklyReportReceiver::class.java).setAction(ACTION_WEEKLY_REPORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Notifies about apps installed while Guard wasn't running. First run only records the
     * sequence.
     *
     * IchthysOS: PackageManager's change sequence restarts at 0 on every boot, so the stored one is
     * only valid for the boot it was saved in (KEY_CHANGED_BOOT); from another boot, everything
     * changed since this boot started is new. [markAnnounced] moves it on after a live notice, so
     * the next process start doesn't announce the same app again.
     */
    private fun catchUp(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pm = ctx.packageManager
            val boot = bootCount(ctx)
            var last = prefs.getInt(KEY_CHANGED_SEQ, -1)
            if (last >= 0 && prefs.getInt(KEY_CHANGED_BOOT, -1) != boot) last = 0
            val changed = pm.getChangedPackages(if (last < 0) 0 else last)
            if (changed != null) {
                if (last >= 0) {
                    for (pkg in changed.packageNames) {
                        val pi =
                            try {
                                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                            } catch (ignored: PackageManager.NameNotFoundException) {
                                continue
                            }
                        // Only fresh installs, not updates.
                        if (pi.firstInstallTime == pi.lastUpdateTime) notifyNewApp(ctx, pkg)
                    }
                }
                prefs
                    .edit()
                    .putInt(KEY_CHANGED_SEQ, changed.sequenceNumber)
                    .putInt(KEY_CHANGED_BOOT, boot)
                    .apply()
            } else if (last <= 0) {
                prefs.edit().putInt(KEY_CHANGED_SEQ, 0).putInt(KEY_CHANGED_BOOT, boot).apply()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "package catch-up failed", e)
        }
    }

    /**
     * IchthysOS: after a live new-app notice ([PackageAddedReceiver]), moves the stored sequence
     * to now, so [catchUp] at the next process start doesn't announce the same install again.
     * Every install while the process lives gets its own live broadcast, so nothing is skipped.
     */
    fun markAnnounced(ctx: Context) {
        try {
            val changed = ctx.packageManager.getChangedPackages(0) ?: return
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CHANGED_SEQ, changed.sequenceNumber)
                .putInt(KEY_CHANGED_BOOT, bootCount(ctx))
                .apply()
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not record the package sequence", e)
        }
    }

    private fun bootCount(ctx: Context): Int =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun canNotify(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun openAppIntent(ctx: Context, pkg: String?): PendingIntent {
        val i =
            Intent(ctx, GuardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // IchthysOS: the package rides in the data URI, which PendingIntent matching compares:
        // two apps whose names share a hashCode used to get one PendingIntent, and
        // FLAG_UPDATE_CURRENT retargeted the other app's buttons.
        if (pkg != null) i.putExtra(EXTRA_OPEN_PACKAGE, pkg).setData(packageUri(pkg))
        return PendingIntent.getActivity(
            ctx,
            0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(ctx: Context, action: String, pkg: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ctx, NotificationActionReceiver::class.java)
                .setAction(action)
                .setData(packageUri(pkg))
                .putExtra(EXTRA_PACKAGE, pkg),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun packageUri(pkg: String): android.net.Uri = android.net.Uri.fromParts("package", pkg, null)

    /**
     * IchthysOS: per-app notifications are told apart by their tag (the package name), with one
     * fixed id per kind; the id used to be derived from pkg.hashCode(), so a colliding name
     * replaced another app's alert.
     */
    private const val NEW_APP_ID = 1000

    private fun notifyTagged(ctx: Context, tag: String, id: Int, n: Notification) =
        ctx.getSystemService(NotificationManager::class.java).notify(tag, id, n)

    /** IchthysOS: removes every notification Guard posted about [pkg]. Any thread. */
    fun cancelForPackage(ctx: Context, pkg: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.cancel(pkg, NEW_APP_ID)
            for (type in AlertType.entries) nm.cancel("$pkg|${type.name}", ALERT_ID_BASE)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not clear the notifications of $pkg", e)
        }
    }

    /**
     * "X installed · N trackers · Shielded" with Block network / Strict actions. Call off the main
     * thread.
     */
    fun notifyNewApp(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName || !canNotify(ctx)) return
        val repo = GuardRepository.get(ctx)
        val label =
            try {
                val pm = ctx.packageManager
                val info = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                // IchthysOS: preinstalled parts of the OS (APEX apps re-scanned after a restart,
                // RemoteProvisioner and friends) are not news: only apps the user installed.
                if ((info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return
                info.loadLabel(pm).toString()
            } catch (ignored: PackageManager.NameNotFoundException) {
                return
            }
        val info =
            try {
                repo.backend.getScanInfo(pkg)
            } catch (ignored: RuntimeException) {
                null
            }
        // IchthysOS: T1: an incomplete scan is "trackers unknown", never "0 trackers".
        val scan = info?.knownTrackers
        val trackers = scan.orEmpty()
        val scanText =
            (if (info != null && info.knownTrackers == null) {
                "trackers unknown"
            } else if ((repo.backend.isLive && scan != null) || trackers.isNotEmpty()) {
                (if (info?.complete == false) "at least " else "") +
                    "${trackers.size} tracker${if (trackers.size == 1) "" else "s"}"
            } else {
                "scan pending"
            }) + if (info?.loadsCodeAtRuntime == true) " · loads code at runtime" else ""
        // What the OS shields actually are right now (each can be switched off in Guard).
        val resolver = ctx.contentResolver
        val locked =
            listOfNotNull(
                "contacts".takeIf { ShieldSwitch.CONTACTS.isOn(resolver) },
                "clipboard".takeIf { ShieldSwitch.CLIPBOARD.isOn(resolver) },
            )
        val title =
            if (locked.isEmpty()) "$label installed · $scanText"
            else "$label installed · $scanText · Shielded"
        // IchthysOS: per-app tracker rules aren't enforced yet (HostRules), so the text doesn't
        // claim blocks and the "Strict" button is left out until they are.
        val trackerLine =
            if (HostRules.ENFORCED) "Known trackers are blocked."
            else "The system-wide blocklist stops common trackers."
        val body =
            if (trackers.isEmpty()) {
                when (locked.size) {
                    0 -> "Monitored. $trackerLine"
                    1 -> "Monitored, ${locked[0]} locked. $trackerLine"
                    else -> "Monitored, contacts and clipboard locked. $trackerLine"
                }
            } else {
                "Embeds: ${trackers.joinToString()}. $trackerLine"
            }
        val builder =
            Notification.Builder(ctx, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_ichthys_guard_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(openAppIntent(ctx, pkg))
                .setAutoCancel(true)
                .addAction(
                    Notification.Action.Builder(
                            null,
                            "Block network",
                            actionIntent(ctx, ACTION_BLOCK_NETWORK, pkg),
                        )
                        .build()
                )
        if (HostRules.ENFORCED) {
            builder.addAction(
                Notification.Action.Builder(null, "Strict", actionIntent(ctx, ACTION_STRICT, pkg))
                    .build()
            )
        }
        val n = builder.build()
        ensureChannels(ctx)
        notifyTagged(ctx, pkg, NEW_APP_ID, n)
    }

    /** Replaces the new-app notification with a confirmation after an action. */
    fun confirm(ctx: Context, pkg: String, text: String) {
        if (!canNotify(ctx)) return
        val n =
            Notification.Builder(ctx, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_ichthys_guard_notify)
                .setContentTitle(text)
                .setContentText("Tap to review in Guard")
                .setContentIntent(openAppIntent(ctx, pkg))
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .build()
        ensureChannels(ctx)
        notifyTagged(ctx, pkg, NEW_APP_ID, n)
    }

    /**
     * One behaviour alert from IchthysGuardService (W8b decisions 9, 35): informational only, it
     * opens the app's page. Dropped when that alert type is switched off in Guard.
     */
    fun notifyAlert(ctx: Context, pkg: String, type: AlertType, detail: String) {
        if (pkg == ctx.packageName || !canNotify(ctx)) return
        val repo = GuardRepository.get(ctx)
        if (!repo.automation.value.alertOn(type)) return
        val label =
            try {
                val pm = ctx.packageManager
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    .loadLabel(pm)
                    .toString()
            } catch (ignored: PackageManager.NameNotFoundException) {
                return
            }
        val n =
            Notification.Builder(ctx, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_ichthys_guard_notify)
                .setContentTitle(label)
                .setContentText(detail)
                .setSubText(type.label)
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent(ctx, pkg))
                .setAutoCancel(true)
                .build()
        ensureChannels(ctx)
        notifyTagged(ctx, "$pkg|${type.name}", ALERT_ID_BASE, n)
    }


    /**
     * IchthysOS: a quarantined app's first-launch review was closed without a choice. Silent (it
     * only repeats what the user just did); tapping it opens the review again. Cleared by
     * [cancelReviewUnfinished] when the app is released or removed. Call off the main thread.
     */
    fun notifyReviewUnfinished(ctx: Context, pkg: String, label: String) {
        if (pkg == ctx.packageName || !canNotify(ctx)) return
        // The same entry point as the suspended-app dialog's Review button. Settings' own uid
        // passes the activity's SEND_SHOW_SUSPENDED_APP_DETAILS permission.
        val review =
            PendingIntent.getActivity(
                ctx,
                0,
                FirstLaunchActivity.reviewIntent(ctx, pkg).setData(packageUri(pkg)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val text = ctx.getString(R.string.ichthys_guard_review_unfinished_text)
        val n =
            Notification.Builder(ctx, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_ichthys_guard_notify)
                .setContentTitle(ctx.getString(R.string.ichthys_guard_review_unfinished_title, label))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(review)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
        ensureChannels(ctx)
        notifyTagged(ctx, pkg, REVIEW_ID_BASE, n)
    }

    /** IchthysOS: removes [notifyReviewUnfinished]'s notification of [pkg], if any. Any thread. */
    fun cancelReviewUnfinished(ctx: Context, pkg: String) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(pkg, REVIEW_ID_BASE)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not clear the review notice of $pkg", e)
        }
    }

    /** After auto-tighten paused apps. */
    fun notifyTightened(ctx: Context, labels: List<String>) {
        if (labels.isEmpty() || !canNotify(ctx)) return
        val days = GuardRepository.get(ctx).automation.value.autoTightenDays
        val text =
            labels.joinToString() +
                " — unused for $days days, network cut. Opening one gives it its network back."
        val n =
            Notification.Builder(ctx, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_ichthys_guard_notify)
                .setContentTitle(
                    "Guard paused ${labels.size} unused app${if (labels.size == 1) "" else "s"}"
                )
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent(ctx, null))
                .setAutoCancel(true)
                .build()
        ensureChannels(ctx)
        ctx.getSystemService(NotificationManager::class.java).notify(TIGHTEN_ID, n)
    }

    fun postWeeklyReport(ctx: Context) {
        if (!canNotify(ctx)) return
        val repo = GuardRepository.get(ctx)
        // IchthysOS: a cold start (the alarm woke the process) counts the buffered events first.
        repo.awaitBackfill()
        // Persisted per-day counters, so the report covers the full week even across reboots.
        val today = StatsHistory.epochDay(System.currentTimeMillis())
        val history = repo.stats.value
        val week = PeriodReport(history.range(today - 6, today))
        // IchthysOS: a week without a single recorded event (service missing, or a phone left
        // off) would be reported as "100/100" with "0 of 0 tracker contacts blocked".
        if (week.total == 0) return
        val prev = PeriodReport(history.range(today - 13, today - 7))
        val delta = if (prev.total > 0) week.score - prev.score else null
        val text =
            week.text() + (delta?.let { " · ${if (it >= 0) "+" else ""}$it vs last week" } ?: "")
        val n =
            Notification.Builder(ctx, CHANNEL_REPORTS)
                .setSmallIcon(R.drawable.ic_ichthys_guard_notify)
                .setContentTitle("Your week in privacy · ${week.score}/100")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent(ctx, null))
                .setAutoCancel(true)
                .build()
        ensureChannels(ctx)
        ctx.getSystemService(NotificationManager::class.java).notify(REPORT_ID, n)
    }
}

/** Runs [block] on a background thread while keeping the broadcast alive. */
internal fun BroadcastReceiver.runAsync(block: () -> Unit) {
    val pending = goAsync()
    Thread {
            try {
                block()
            } catch (e: Exception) {
                // IchthysOS: Exception, not only RuntimeException: an IOException (full disk) from
                // the stores would otherwise kill the Settings process.
                Log.w("IchthysGuard", "receiver work failed", e)
            } finally {
                pending.finish()
            }
        }
        .start()
}
