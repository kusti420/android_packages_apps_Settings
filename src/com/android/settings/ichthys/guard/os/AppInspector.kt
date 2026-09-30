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

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.ConnectivityManager
import android.util.Log

/** An installed app Guard controls. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val firstInstallMs: Long,
    val usesInternet: Boolean,
)

data class LocalScan(
    val dangerousPermissions: List<String>,
    val exportedComponents: Int,
    val totalComponents: Int,
)

data class OpUsage(val op: String, val label: String, val lastAccessMs: Long, val durationMs: Long)

data class UidTraffic(
    val uid: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val wifiBytes: Long,
    val mobileBytes: Long,
) {
    val total: Long
        get() = rxBytes + txBytes
}

/** Read-only facts about installed apps from PackageManager, AppOps and NetworkStats. */
class AppInspector(private val context: Context) {
    private val pm = context.packageManager

    fun installedApps(): List<AppInfo> {
        val launchable =
            pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    PackageManager.ResolveInfoFlags.of(0),
                )
                .map { it.activityInfo.packageName }
                .toSet()
        return pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
            .mapNotNull { pi -> pi.toAppInfo(launchable) }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun app(packageName: String): AppInfo? =
        try {
            pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
                .toAppInfo(null)
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }

    private fun PackageInfo.toAppInfo(launchable: Set<String>?): AppInfo? {
        val ai = applicationInfo ?: return null
        val system =
            ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) !=
                0
        // User apps always; system apps only if launchable (the rest is OS plumbing Guard must not
        // touch).
        if (launchable != null && system && packageName !in launchable) return null
        return AppInfo(
            packageName,
            ai.loadLabel(pm).toString(),
            ai.uid,
            system,
            firstInstallTime,
            requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true,
        )
    }

    fun localScan(packageName: String): LocalScan? =
        try {
            val flags =
                PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.MATCH_DISABLED_COMPONENTS
            val pi =
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            val dangerous =
                pi.requestedPermissions
                    .orEmpty()
                    .filter { perm ->
                        try {
                            pm.getPermissionInfo(perm, 0).protection ==
                                PermissionInfo.PROTECTION_DANGEROUS
                        } catch (ignored: PackageManager.NameNotFoundException) {
                            false
                        }
                    }
                    .map { it.substringAfterLast('.') }
            val comps =
                pi.activities.orEmpty().map { it.exported && it.name != null } +
                    pi.services.orEmpty().map { it.exported } +
                    pi.receivers.orEmpty().map { it.exported } +
                    pi.providers.orEmpty().map { it.exported }
            LocalScan(dangerous.sorted(), comps.count { it }, comps.size)
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }

    /**
     * Last camera/mic/location access for one app. Uses the @SystemApi
     * AppOpsManager.getOpsForPackage through reflection (needs GET_APP_OPS_STATS, which the
     * platform-signed build has). Returns null when not accessible so the UI can show a hint.
     */
    fun permissionUsage(app: AppInfo): List<OpUsage>? =
        try {
            val aom = context.getSystemService(AppOpsManager::class.java)
            val ops =
                arrayOf(
                    AppOpsManager.OPSTR_CAMERA,
                    AppOpsManager.OPSTR_RECORD_AUDIO,
                    AppOpsManager.OPSTR_FINE_LOCATION,
                    AppOpsManager.OPSTR_COARSE_LOCATION,
                    "android:read_clipboard",
                    "android:read_contacts",
                )
            val m =
                AppOpsManager::class
                    .java
                    .getMethod(
                        "getOpsForPackage",
                        Int::class.javaPrimitiveType,
                        String::class.java,
                        Array<String>::class.java,
                    )
            val result = m.invoke(aom, app.uid, app.packageName, ops) as List<*>?
            val out = mutableListOf<OpUsage>()
            for (pkgOps in result.orEmpty()) {
                val entries = pkgOps!!.javaClass.getMethod("getOps").invoke(pkgOps) as List<*>
                for (entry in entries) {
                    val cls = entry!!.javaClass
                    val opStr = cls.getMethod("getOpStr").invoke(entry) as String
                    val last =
                        cls.getMethod("getLastAccessTime", Int::class.javaPrimitiveType)
                            .invoke(entry, OP_FLAGS_ALL) as Long
                    val dur =
                        runCatching {
                                cls.getMethod("getLastDuration", Int::class.javaPrimitiveType)
                                    .invoke(entry, OP_FLAGS_ALL) as Long
                            }
                            .getOrDefault(-1L)
                    if (last > 0) out += OpUsage(opStr, opLabel(opStr), last, dur)
                }
            }
            out.sortedByDescending { it.lastAccessMs }
        } catch (e: ReflectiveOperationException) {
            Log.i(TAG, "app ops history not accessible", e)
            null
        } catch (e: SecurityException) {
            Log.i(TAG, "app ops history denied", e)
            null
        }

    /**
     * Per-uid bytes since [sinceMs] over Wi-Fi and mobile. Needs READ_NETWORK_USAGE_HISTORY
     * (privileged) or usage access; returns null if neither is granted.
     */
    // querySummary still takes the legacy ConnectivityManager.TYPE_* network types.
    @Suppress("DEPRECATION")
    fun trafficByUid(
        sinceMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<Int, UidTraffic>? =
        try {
            val nsm = context.getSystemService(NetworkStatsManager::class.java)
            val acc = HashMap<Int, LongArray>() // rx, tx, wifi, mobile
            for (type in
                intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
                val stats = nsm.querySummary(type, null, sinceMs, nowMs)
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    val a = acc.getOrPut(b.uid) { LongArray(4) }
                    a[0] += b.rxBytes
                    a[1] += b.txBytes
                    a[if (type == ConnectivityManager.TYPE_WIFI) 2 else 3] += b.rxBytes + b.txBytes
                }
                stats.close()
            }
            acc.mapValues { (uid, a) -> UidTraffic(uid, a[0], a[1], a[2], a[3]) }
        } catch (e: SecurityException) {
            Log.i(TAG, "no network usage access", e)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "network stats failed", e)
            null
        }

    private companion object {
        const val TAG = "IchthysGuard"
        const val OP_FLAGS_ALL = 0x1F

        fun opLabel(op: String) =
            when (op) {
                AppOpsManager.OPSTR_CAMERA -> "Camera"
                AppOpsManager.OPSTR_RECORD_AUDIO -> "Microphone"
                AppOpsManager.OPSTR_FINE_LOCATION -> "Precise location"
                AppOpsManager.OPSTR_COARSE_LOCATION -> "Approximate location"
                "android:read_clipboard" -> "Clipboard"
                "android:read_contacts" -> "Contacts"
                else -> op.removePrefix("android:")
            }
    }
}
