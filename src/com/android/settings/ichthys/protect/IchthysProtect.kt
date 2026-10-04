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

package com.android.settings.ichthys.protect

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Settings side of the Guard "powerful access" protections that live in system_server:
 * - accessibility scoping (decision 36): Secure [KEY_A11Y_SCOPES], enforced by
 *   AccessibilityManagerService (IchthysA11yScopes);
 * - usage history (decision 34): Secure [KEY_USAGE_FULL_PKGS], enforced by UsageStatsService;
 * - "No location" (decision 10): Secure [KEY_LOCATION_NOFIX_PKGS], enforced by
 *   LocationProviderManager (IchthysLocationNoFix).
 *
 * Package lists are comma-separated with no spaces, like the other Guard OS hooks. Guard links
 * here through [noLocationIntent], [a11yScopeIntent] and [locationPermissionIntent].
 */
object IchthysProtect {
    private const val TAG = "IchthysProtect"

    const val KEY_A11Y_SCOPES = "ichthys_a11y_scopes"
    const val KEY_USAGE_FULL_PKGS = "ichthys_usage_full_pkgs"
    const val KEY_LOCATION_NOFIX_PKGS = "ichthys_location_nofix_pkgs"

    /** Extra for [A11yScopeActivity]: the flattened accessibility service component. */
    const val EXTRA_SERVICE = "org.ichthys.settings.extra.A11Y_SERVICE"

    const val ACTION_A11Y_SCOPE = "org.ichthys.settings.action.ACCESSIBILITY_SCOPE"
    const val ACTION_NO_LOCATION = "org.ichthys.settings.action.NO_LOCATION"

    const val SYSTEMUI_PACKAGE = "com.android.systemui"

    // ---- Comma-separated package lists ------------------------------------------------------

    // The lists are per user (Settings.Secure) and the framework reads the list of the app's
    // user, so pages that show another user's apps (a work profile) pass that user's id.

    @JvmStatic
    @JvmOverloads
    fun readPackages(
        context: Context,
        key: String,
        userId: Int = context.userId,
    ): MutableSet<String> =
        Settings.Secure.getStringForUser(context.contentResolver, key, userId)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toMutableSet()
            ?: mutableSetOf()

    @JvmStatic
    @JvmOverloads
    fun isListed(
        context: Context,
        key: String,
        packageName: String,
        userId: Int = context.userId,
    ): Boolean = packageName in readPackages(context, key, userId)

    @JvmStatic
    @JvmOverloads
    fun setListed(
        context: Context,
        key: String,
        packageName: String,
        listed: Boolean,
        userId: Int = context.userId,
    ) {
        val packages = readPackages(context, key, userId)
        val changed = if (listed) packages.add(packageName) else packages.remove(packageName)
        if (!changed) return
        try {
            Settings.Secure.putStringForUser(
                context.contentResolver,
                key,
                packages.sorted().joinToString(",").ifEmpty { null },
                userId,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS; $key not written", e)
        }
    }

    // ---- Intents -----------------------------------------------------------------------------

    /** The "No location" page (all apps with location access), optionally at [packageName]. */
    @JvmStatic
    @JvmOverloads
    fun noLocationIntent(context: Context, packageName: String? = null): Intent =
        Intent(ACTION_NO_LOCATION)
            .setPackage(context.packageName)
            .apply { packageName?.let { putExtra(LocationNoFixSettings.ARG_PACKAGE, it) } }

    /** The scope page of one accessibility service. */
    @JvmStatic
    fun a11yScopeIntent(context: Context, service: ComponentName): Intent =
        Intent(ACTION_A11Y_SCOPE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_SERVICE, service.flattenToString())

    /**
     * The stock per-app location permission page: "Use precise location" (force approximate)
     * and "Allow only while using the app".
     */
    @JvmStatic
    fun locationPermissionIntent(context: Context, packageName: String): Intent =
        Intent(Intent.ACTION_MANAGE_APP_PERMISSION).apply {
            `package` = context.packageManager.permissionControllerPackageName
            putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, Manifest.permission_group.LOCATION)
            putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            putExtra(Intent.EXTRA_USER, context.user)
        }

    // ---- Accessibility scopes ----------------------------------------------------------------
    // Mirrors frameworks/base/services/accessibility/.../IchthysA11yScopes.java; keep in sync.

    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.credentialmanager",
    )

    private val PASSWORD_MANAGERS = setOf(
        "com.x8bit.bitwarden",
        "com.bitwarden.authenticator",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.callpod.android_apps.keeper",
        "proton.android.pass",
        "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "com.kunzisoft.keepass.pro",
        "io.enpass.app",
        "com.beemdevelopment.aegis",
        "org.fedorahosted.freeotp",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "ee.sk.smartid",
    )

    private val PAYMENT_APPS = setOf(
        "com.revolut.revolut",
        "com.transferwise.android",
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "de.number26.android",
        "co.uk.getmondo",
        "com.klarna.android",
        "se.bankgirot.swish",
        "com.squareup.cash",
        "com.venmo",
        "com.coinbase.android",
        "com.binance.dev",
    )

    private val BANK_SEGMENT_WORDS = listOf("bank", "pank", "banking")

    /** Why an app is protected from third-party accessibility services by default. */
    enum class DefaultReason { NONE, SETTINGS, LOCK_SCREEN, PASSWORDS, BANKING }

    /** One service's choices: apps it may see despite the default, and extra hidden apps. */
    data class Scope(val allowed: MutableSet<String>, val denied: MutableSet<String>)

    @JvmStatic
    fun isThirdPartyService(context: Context, service: ComponentName): Boolean {
        val info = try {
            context.packageManager.getApplicationInfo(service.packageName, 0)
        } catch (e: Exception) {
            return true
        }
        return info.flags and ApplicationInfo.FLAG_SYSTEM == 0
    }

    @JvmStatic
    fun defaultReason(context: Context, packageName: String): DefaultReason = when {
        packageName == SYSTEMUI_PACKAGE -> DefaultReason.LOCK_SCREEN
        packageName in SETTINGS_PACKAGES -> DefaultReason.SETTINGS
        packageName in PASSWORD_MANAGERS ||
            packageName in credentialProviderPackages(context) -> DefaultReason.PASSWORDS
        packageName in PAYMENT_APPS || isBankName(packageName) -> DefaultReason.BANKING
        else -> DefaultReason.NONE
    }

    private fun isBankName(packageName: String): Boolean =
        packageName.lowercase(Locale.ROOT).split('.').any { segment ->
            BANK_SEGMENT_WORDS.any { segment == it || segment.startsWith(it) ||
                segment.endsWith(it) }
        }

    private fun credentialProviderPackages(context: Context): Set<String> {
        val resolver = context.contentResolver
        return listOf(
            Settings.Secure.AUTOFILL_SERVICE,
            Settings.Secure.CREDENTIAL_SERVICE,
            Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
        ).flatMap { key ->
            Settings.Secure.getString(resolver, key)
                ?.split(':')
                ?.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
                ?: emptyList()
        }.toSet()
    }

    @JvmStatic
    fun readScopes(context: Context): MutableMap<String, Scope> {
        val scopes = mutableMapOf<String, Scope>()
        val json = Settings.Secure.getString(context.contentResolver, KEY_A11Y_SCOPES)
        if (json.isNullOrEmpty()) return scopes
        try {
            val root = JSONObject(json)
            for (key in root.keys()) {
                val service = ComponentName.unflattenFromString(key) ?: continue
                val entry = root.optJSONObject(key) ?: continue
                scopes[service.flattenToString()] =
                    Scope(entry.optJSONArray("allow").toSet(), entry.optJSONArray("deny").toSet())
            }
        } catch (e: JSONException) {
            Log.w(TAG, "ignoring malformed $KEY_A11Y_SCOPES", e)
        }
        return scopes
    }

    private fun JSONArray?.toSet(): MutableSet<String> {
        val out = mutableSetOf<String>()
        if (this == null) return out
        for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /** Whether [service] may currently see [packageName], as the framework decides it. */
    @JvmStatic
    fun canSee(
        context: Context,
        service: ComponentName,
        packageName: String,
        scope: Scope?,
        thirdParty: Boolean,
    ): Boolean {
        if (scope != null) {
            if (packageName in scope.denied) return false
            if (packageName in scope.allowed) return true
        }
        return !thirdParty || defaultReason(context, packageName) == DefaultReason.NONE
    }

    /** Stores the user's choice for one app, dropping it when it matches the default. */
    @JvmStatic
    fun setCanSee(context: Context, service: ComponentName, packageName: String, visible: Boolean) {
        val scopes = readScopes(context)
        val key = service.flattenToString()
        val scope = scopes.getOrPut(key) { Scope(mutableSetOf(), mutableSetOf()) }
        scope.allowed.remove(packageName)
        scope.denied.remove(packageName)
        val defaultVisible = !isThirdPartyService(context, service) ||
            defaultReason(context, packageName) == DefaultReason.NONE
        if (visible != defaultVisible) {
            if (visible) scope.allowed += packageName else scope.denied += packageName
        }
        if (scope.allowed.isEmpty() && scope.denied.isEmpty()) scopes.remove(key)
        writeScopes(context, scopes)
    }

    /** Drops every choice for [service], back to the defaults. */
    @JvmStatic
    fun resetScope(context: Context, service: ComponentName) {
        val scopes = readScopes(context)
        if (scopes.remove(service.flattenToString()) != null) writeScopes(context, scopes)
    }

    private fun writeScopes(context: Context, scopes: Map<String, Scope>) {
        val root = JSONObject()
        for ((service, scope) in scopes.toSortedMap()) {
            root.put(
                service,
                JSONObject()
                    .put("allow", JSONArray(scope.allowed.sorted()))
                    .put("deny", JSONArray(scope.denied.sorted())),
            )
        }
        try {
            Settings.Secure.putString(
                context.contentResolver,
                KEY_A11Y_SCOPES,
                if (scopes.isEmpty()) null else root.toString(),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS; $KEY_A11Y_SCOPES not written", e)
        }
    }
}
