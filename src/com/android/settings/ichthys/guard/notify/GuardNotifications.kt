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
import android.util.Log
import com.android.settings.R
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.PeriodReport
import com.android.settings.ichthys.guard.model.StatsHistory
import com.android.settings.ichthys.guard.ui.GuardActivity
import java.util.Calendar

/**
 * Guard's notifications, posted by Settings: "new app installed" (with one-tap Block network /
 * Strict), the confirmations after those actions, and the weekly privacy report. Every notification
 * opens [GuardActivity].
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
    private const val REPORT_ID = 0x16a0
    private const val PREFS = "ichthys_guard_notify"
    private const val KEY_CHANGED_SEQ = "changed_seq"

    @Volatile private var initialized = false

    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        try {
            createChannels(app)
            scheduleWeeklyReport(app)
            val filter =
                IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
            app.registerReceiver(PackageAddedReceiver(), filter, Context.RECEIVER_EXPORTED)
        } catch (e: RuntimeException) {
            // Never take Settings down over Guard.
            Log.w(TAG, "init failed", e)
        }
        Thread({ catchUp(app) }, "IchthysGuardCatchUp").start()
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
     */
    private fun catchUp(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pm = ctx.packageManager
            val last = prefs.getInt(KEY_CHANGED_SEQ, -1)
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
                prefs.edit().putInt(KEY_CHANGED_SEQ, changed.sequenceNumber).apply()
            } else if (last < 0) {
                prefs.edit().putInt(KEY_CHANGED_SEQ, 0).apply()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "package catch-up failed", e)
        }
    }

    private fun canNotify(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun openAppIntent(ctx: Context, pkg: String?): PendingIntent {
        val i =
            Intent(ctx, GuardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (pkg != null) i.putExtra(EXTRA_OPEN_PACKAGE, pkg)
        return PendingIntent.getActivity(
            ctx,
            pkg?.hashCode() ?: 0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(ctx: Context, action: String, pkg: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            (action + pkg).hashCode(),
            Intent(ctx, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_PACKAGE, pkg),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationId(pkg: String) = 1000 + (pkg.hashCode() and 0xFFFFFF)

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
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    .loadLabel(pm)
                    .toString()
            } catch (ignored: PackageManager.NameNotFoundException) {
                return
            }
        val scan =
            try {
                repo.backend.getScan(pkg)
            } catch (ignored: RuntimeException) {
                null
            }
        val trackers = scan.orEmpty()
        val scanText =
            if ((repo.backend.isLive && scan != null) || trackers.isNotEmpty()) {
                "${trackers.size} tracker${if (trackers.size == 1) "" else "s"}"
            } else {
                "scan pending"
            }
        val title = "$label installed · $scanText · Shielded"
        val body =
            if (trackers.isEmpty()) {
                "Monitored, known trackers blocked, contacts and clipboard locked."
            } else {
                "Embeds: ${trackers.joinToString()}. Known trackers are blocked."
            }
        val n =
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
                .addAction(
                    Notification.Action.Builder(
                            null,
                            "Strict",
                            actionIntent(ctx, ACTION_STRICT, pkg),
                        )
                        .build()
                )
                .build()
        ctx.getSystemService(NotificationManager::class.java).notify(notificationId(pkg), n)
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
        ctx.getSystemService(NotificationManager::class.java).notify(notificationId(pkg), n)
    }

    fun postWeeklyReport(ctx: Context) {
        if (!canNotify(ctx)) return
        val repo = GuardRepository.get(ctx)
        // Persisted per-day counters, so the report covers the full week even across reboots.
        val today = StatsHistory.epochDay(System.currentTimeMillis())
        val history = repo.stats.value
        val week = PeriodReport(history.range(today - 6, today))
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
        ctx.getSystemService(NotificationManager::class.java).notify(REPORT_ID, n)
    }
}

/** Runs [block] on a background thread while keeping the broadcast alive. */
internal fun BroadcastReceiver.runAsync(block: () -> Unit) {
    val pending = goAsync()
    Thread {
            try {
                block()
            } catch (e: RuntimeException) {
                Log.w("IchthysGuard", "receiver work failed", e)
            } finally {
                pending.finish()
            }
        }
        .start()
}
