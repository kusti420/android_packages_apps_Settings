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

package com.android.settings.ichthys.guard.data

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Process
import android.os.UserHandle
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * IchthysOS: the "every permission" half of the Full access preset (AppPreset.FULL_ACCESS).
 *
 * Guard's presets only ever write its own lists and policy; the runtime permissions are
 * Android's. Full access is the one preset that crosses over: after the page's two confirmations
 * it grants the app every runtime permission it asks for (background location and Health
 * included), "see all apps" (QUERY_ALL_PACKAGES, user-controlled on IchthysOS) and the special
 * accesses behind an app op (draw over apps, usage access, modify settings, install apps, all
 * files, exact alarms) plus Do Not Disturb access, so a tester app can try every check at once
 * instead of being granted each one by hand.
 *
 * Everything granted here is written down (per package, in [PREFS]) so that leaving the preset
 * takes back exactly what Guard gave and nothing the user granted themselves. Settings holds
 * GRANT_RUNTIME_PERMISSIONS / REVOKE_RUNTIME_PERMISSIONS (signature) and MANAGE_APP_OPS_MODES.
 * PermissionPolicyService syncs the app ops that belong to a runtime permission by itself.
 *
 * Binder work: call off the main thread.
 */
object FullAccessGranter {
    private const val TAG = "IchthysGuard"
    private const val PREFS = "ichthys_guard_full_access"

    /** Special accesses: the permission the app requests and the app op(s) that gate it. */
    private class SpecialAccess(val permission: String, val label: String, vararg val ops: Int)

    private val SPECIAL =
        listOf(
            SpecialAccess(
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                "draw over other apps",
                AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
            ),
            SpecialAccess(
                Manifest.permission.PACKAGE_USAGE_STATS,
                "usage access",
                AppOpsManager.OP_GET_USAGE_STATS,
                AppOpsManager.OP_LOADER_USAGE_STATS,
            ),
            SpecialAccess(
                Manifest.permission.WRITE_SETTINGS,
                "modify system settings",
                AppOpsManager.OP_WRITE_SETTINGS,
            ),
            SpecialAccess(
                Manifest.permission.REQUEST_INSTALL_PACKAGES,
                "install apps",
                AppOpsManager.OP_REQUEST_INSTALL_PACKAGES,
            ),
            SpecialAccess(
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                "all files",
                AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE,
            ),
            SpecialAccess(
                Manifest.permission.SCHEDULE_EXACT_ALARM,
                "exact alarms",
                AppOpsManager.OP_SCHEDULE_EXACT_ALARM,
            ),
        )

    /** What a grant would do, for the confirmation dialog. Nothing is changed. */
    data class Preview(
        /** Runtime permissions not granted yet, by name. */
        val permissions: List<String>,
        /** Special accesses not allowed yet, by label. */
        val special: List<String>,
        val seeAllApps: Boolean,
        val dndAccess: Boolean,
    ) {
        val count: Int
            get() = permissions.size + special.size + (if (seeAllApps) 1 else 0) + (if (dndAccess) 1 else 0)
    }

    /** What Guard granted to a package and still holds the record of. */
    data class Record(val permissions: List<String>, val special: List<String>, val dndAccess: Boolean, val atMs: Long) {
        val count: Int
            get() = permissions.size + special.size + (if (dndAccess) 1 else 0)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The record of what Guard granted [pkg], or null when Full access never granted anything. */
    fun recorded(context: Context, pkg: String): Record? {
        val text = prefs(context).getString(pkg, null) ?: return null
        return try {
            val o = JSONObject(text)
            Record(
                strings(o.optJSONArray("perms")),
                strings(o.optJSONArray("special")),
                o.optBoolean("dnd", false),
                o.optLong("at", 0),
            )
        } catch (e: JSONException) {
            null
        }
    }

    /** Everything a grant of [pkg] would add right now. */
    fun preview(context: Context, pkg: String): Preview {
        val pm = context.packageManager
        val requested = requested(pm, pkg)
        val perms =
            requested.filter { isRuntime(pm, it) && pm.checkPermission(it, pkg) != PackageManager.PERMISSION_GRANTED }
        val uid = uidOf(pm, pkg)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val special =
            if (uid < 0 || appOps == null) emptyList()
            else SPECIAL.filter { it.permission in requested && !allowed(appOps, it, uid, pkg) }.map { it.label }
        val seeAll =
            Manifest.permission.QUERY_ALL_PACKAGES in requested &&
                pm.checkPermission(Manifest.permission.QUERY_ALL_PACKAGES, pkg) != PackageManager.PERMISSION_GRANTED
        val nm = context.getSystemService(NotificationManager::class.java)
        val dnd =
            Manifest.permission.ACCESS_NOTIFICATION_POLICY in requested &&
                nm != null && !runCatching { nm.isNotificationPolicyAccessGrantedForPackage(pkg) }.getOrDefault(true)
        return Preview(sortForGrant(perms), special, seeAll, dnd)
    }

    /**
     * Grants [pkg] everything in [preview] and records it. Returns how many grants were made.
     * Failures of single items are logged and skipped: a permission this build doesn't define,
     * a permission the app may not hold, a missing service.
     */
    fun grantAll(context: Context, pkg: String): Int {
        val pm = context.packageManager
        val user = UserHandle.of(context.userId)
        val p = preview(context, pkg)
        val granted = ArrayList<String>()
        val perms = ArrayList(p.permissions)
        if (p.seeAllApps) perms.add(Manifest.permission.QUERY_ALL_PACKAGES)
        for (perm in perms) {
            try {
                pm.grantRuntimePermission(pkg, perm, user)
                // A user decision, like the permissions page records it: the permission
                // controller doesn't treat it as a one-time or system grant.
                runCatching {
                    pm.updatePermissionFlags(
                        perm,
                        pkg,
                        PackageManager.FLAG_PERMISSION_USER_SET or PackageManager.FLAG_PERMISSION_USER_FIXED,
                        PackageManager.FLAG_PERMISSION_USER_SET,
                        user,
                    )
                }
                granted.add(perm)
            } catch (e: RuntimeException) {
                Log.w(TAG, "full access: $perm not granted to $pkg: $e")
            }
        }
        val uid = uidOf(pm, pkg)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val special = ArrayList<String>()
        if (uid >= 0 && appOps != null) {
            for (s in SPECIAL) {
                if (s.label !in p.special) continue
                try {
                    for (op in s.ops) appOps.setMode(op, uid, pkg, AppOpsManager.MODE_ALLOWED)
                    special.add(s.label)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "full access: ${s.label} not allowed for $pkg: $e")
                }
            }
        }
        var dnd = false
        if (p.dndAccess) {
            try {
                context.getSystemService(NotificationManager::class.java)
                    ?.setNotificationPolicyAccessGranted(pkg, true)
                dnd = true
            } catch (e: RuntimeException) {
                Log.w(TAG, "full access: DND access not granted to $pkg: $e")
            }
        }
        // Merge with an older record: a second grant (after the user revoked some by hand)
        // must not forget what the first one gave.
        val old = recorded(context, pkg)
        val record =
            Record(
                ((old?.permissions ?: emptyList()) + granted).distinct(),
                ((old?.special ?: emptyList()) + special).distinct(),
                (old?.dndAccess ?: false) || dnd,
                System.currentTimeMillis(),
            )
        if (record.count > 0) {
            prefs(context).edit().putString(pkg, toJson(record)).apply()
        }
        Log.i(TAG, "full access: granted ${granted.size} permissions, ${special.size} special accesses to $pkg")
        return granted.size + special.size + (if (dnd) 1 else 0)
    }

    /**
     * Takes back what [grantAll] gave [pkg] (only that: the user's own grants stay) and drops
     * the record. Returns how many were taken back. Safe to call without a record.
     */
    fun revokeAll(context: Context, pkg: String): Int {
        val record = recorded(context, pkg) ?: return 0
        val pm = context.packageManager
        val user = UserHandle.of(context.userId)
        var n = 0
        // Background permissions first, so a foreground one is never revoked under its
        // background partner.
        for (perm in sortForGrant(record.permissions).asReversed()) {
            try {
                if (pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED) {
                    pm.revokeRuntimePermission(pkg, perm, user)
                    n++
                }
                runCatching {
                    pm.updatePermissionFlags(perm, pkg, PackageManager.FLAG_PERMISSION_USER_SET, 0, user)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "full access: $perm not revoked from $pkg: $e")
            }
        }
        val uid = uidOf(pm, pkg)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        if (uid >= 0 && appOps != null) {
            for (s in SPECIAL) {
                if (s.label !in record.special) continue
                try {
                    // Back to the op's default: what a fresh install has (denied, or "ask").
                    for (op in s.ops) appOps.setMode(op, uid, pkg, AppOpsManager.opToDefaultMode(op))
                    n++
                } catch (e: RuntimeException) {
                    Log.w(TAG, "full access: ${s.label} not reset for $pkg: $e")
                }
            }
        }
        if (record.dndAccess) {
            try {
                context.getSystemService(NotificationManager::class.java)
                    ?.setNotificationPolicyAccessGranted(pkg, false)
                n++
            } catch (e: RuntimeException) {
                Log.w(TAG, "full access: DND access not removed from $pkg: $e")
            }
        }
        prefs(context).edit().remove(pkg).apply()
        Log.i(TAG, "full access: took back $n grants from $pkg")
        return n
    }

    /** Forgets the record of a package that is gone (nothing to revoke any more). */
    fun forget(context: Context, pkg: String) {
        prefs(context).edit().remove(pkg).apply()
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun requested(pm: PackageManager, pkg: String): List<String> =
        try {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                .requestedPermissions
                ?.toList() ?: emptyList()
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        }

    /** A runtime ("dangerous") permission this build defines. */
    private fun isRuntime(pm: PackageManager, perm: String): Boolean =
        try {
            pm.getPermissionInfo(perm, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Foreground permissions before their background partners, else manifest order. */
    private fun sortForGrant(perms: List<String>): List<String> =
        perms.sortedBy { if (it.contains("BACKGROUND")) 1 else 0 }

    private fun uidOf(pm: PackageManager, pkg: String): Int =
        try {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).uid
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }

    private fun allowed(appOps: AppOpsManager, s: SpecialAccess, uid: Int, pkg: String): Boolean =
        s.ops.all {
            runCatching { appOps.unsafeCheckOpNoThrow(AppOpsManager.opToPublicName(it), uid, pkg) }
                .getOrDefault(AppOpsManager.MODE_IGNORED) == AppOpsManager.MODE_ALLOWED
        }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }

    private fun toJson(r: Record): String =
        JSONObject()
            .put("perms", JSONArray(r.permissions))
            .put("special", JSONArray(r.special))
            .put("dnd", r.dndAccess)
            .put("at", r.atMs)
            .toString()

    /** True for user apps only: Full access is never offered to system apps. */
    fun isEligible(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        return try {
            val ai = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                UserHandle.getAppId(ai.uid) >= Process.FIRST_APPLICATION_UID
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
