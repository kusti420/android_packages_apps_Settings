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

package com.android.settings.ichthys.guard.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.SuspendDialogInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.settings.R
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.AdviceLine
import com.android.settings.ichthys.guard.model.AdviceReason
import com.android.settings.ichthys.guard.model.AdviceTopic
import com.android.settings.ichthys.guard.model.AppFacts
import com.android.settings.ichthys.guard.model.FirstLaunchAdvisor
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.PermissionGroup
import com.android.settings.ichthys.guard.model.Recommendation
import com.android.settings.ichthys.guard.model.TrackerBlockLevel
import com.android.settings.ichthys.guard.model.AppPreset
import com.android.settings.ichthys.guard.model.JournalKind
import com.android.settings.ichthys.guard.ui.theme.Guard
import com.android.settings.ichthys.guard.ui.theme.GuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guard's first-launch review sheet (W8b decision 15). A newly installed user app is quarantined by
 * IchthysGuardService with Settings as the suspending package; launching it shows the system's
 * SuspendedAppActivity, whose Review button (BUTTON_ACTION_MORE_DETAILS) starts this activity
 * through [Intent.ACTION_SHOW_SUSPENDED_APP_DETAILS] with [Intent.EXTRA_PACKAGE_NAME].
 *
 * It shows the install-time tracker scan, the requested permissions and a recommended profile
 * ([FirstLaunchAdvisor]). Each button sets the policy, lifts Settings' suspension and starts the
 * app; back or a tap outside the sheet leaves it quarantined.
 *
 * Apps auto-tightened after weeks unused (GuardRepository.runMaintenance) are suspended the same
 * way, so reopening one lands here too; every choice gives it its network back.
 *
 * Exported only because the contract requires it: the activity is protected by the signature
 * permission SEND_SHOW_SUSPENDED_APP_DETAILS, which SuspendedAppActivity checks for before it
 * offers the button. Not direct-boot aware (Guard's state is credential-protected).
 */
class FirstLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        val user = intent?.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
        // SuspendedAppActivity starts us in the suspending user, which is the app's user; anything
        // else would make us lift a suspension we can't see.
        if (pkg.isNullOrEmpty() || (user != null && user != Process.myUserHandle())) {
            finish()
            return
        }
        setContent {
            GuardTheme {
                FirstLaunchSheet(
                    pkg,
                    onDismiss = { finish() },
                    onChoice = { sheet, choice, preset -> release(sheet, choice, preset) },
                )
            }
        }
    }

    /** Writes the policy for [choice], lifts the quarantine and starts the app. Main thread. */
    private suspend fun release(sheet: SheetData, choice: SheetChoice, preset: AppPreset?) {
        val pkg = sheet.facts.packageName
        val (released, launch) =
            withContext(Dispatchers.IO) {
                if (choice == SheetChoice.APPLY && preset != null && preset != AppPreset.CUSTOM) {
                    // IchthysOS: a preset picked on the sheet: the whole policy and every shield
                    // list in one go, the same as on the app's page.
                    sheet.repo.applyPreset(pkg, preset)
                } else if (choice == SheetChoice.APPLY) {
                    // The same path the app page uses: store, OS allowlists and the service. A
                    // new app has no policy yet, so the recommendation becomes it. IchthysOS: any
                    // app with choices already (paused by auto-tighten, or blocked from the
                    // new-app notification before it was opened) keeps what the user set up;
                    // only the recommendation is merged in and a pause lifted.
                    sheet.repo.updatePolicy(pkg) { old ->
                        if (old.isDefault) sheet.recommendation.policy
                        else sheet.recommendation.mergedInto(old)
                    }
                }
                // An app auto-tightened for being unused (also suspended by Settings) gets its
                // network back with any choice: the user just opened it.
                if (sheet.repo.policies.value.policyFor(pkg).dormant) {
                    sheet.repo.updatePolicy(pkg) { it.copy(dormant = false) }
                }
                if (!unsuspend(pkg)) return@withContext false to null
                sheet.repo.journal(
                    JournalKind.RELEASED,
                    when {
                        preset != null && preset != AppPreset.CUSTOM -> "Released with the ${preset.label} preset"
                        choice == SheetChoice.APPLY -> "Released with the recommendation"
                        choice == SheetChoice.CUSTOMIZE -> "Released to set up by hand"
                        else -> "Released as is"
                    },
                    pkg,
                )
                true to packageManager.getLaunchIntentForPackage(pkg)
            }
        if (!released) {
            Toast.makeText(
                    this,
                    getString(R.string.ichthys_guard_first_launch_failed, sheet.label),
                    Toast.LENGTH_SHORT,
                )
                .show()
            finish()
            return
        }
        launch?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (choice == SheetChoice.CUSTOMIZE) {
            // On top of the app: back from Guard's page lands in the app.
            startActivity(
                Intent(this, GuardActivity::class.java)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    /**
     * Lifts Settings' own suspension (the quarantine) of [pkg]. The SuspendDialogInfo overload
     * calls setPackagesSuspendedAsUser with this package as the suspender, so only Settings' entry
     * is removed; suspensions by other packages (e.g. a device admin) stay. PMS lets the SYSTEM uid
     * through enforceCanSetPackagesSuspendedAsUser.
     */
    private fun unsuspend(pkg: String): Boolean =
        try {
            val failed =
                packageManager.setPackagesSuspended(
                    arrayOf(pkg),
                    false,
                    null,
                    null,
                    null as SuspendDialogInfo?,
                )
            if (failed.isNullOrEmpty()) {
                true
            } else {
                Log.w(TAG, "could not lift the quarantine of $pkg")
                false
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not lift the quarantine of $pkg", e)
            false
        }

    private companion object {
        const val TAG = "IchthysGuard"
    }
}

private enum class SheetChoice {
    APPLY,
    CUSTOMIZE,
    AS_IS,
}

/** Everything the sheet shows, gathered off the main thread. */
private class SheetData(
    val repo: GuardRepository,
    val label: String,
    val icon: ImageBitmap?,
    val installer: String?,
    val facts: AppFacts,
    val groups: List<PermissionGroup>,
    val recommendation: Recommendation,
    /** Paused by auto-tighten (it has run before), not a new app. */
    val dormant: Boolean,
)

private fun loadSheet(ctx: Context, pkg: String): SheetData? {
    val pm = ctx.packageManager
    val pi =
        try {
            pm.getPackageInfo(
                pkg,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } catch (ignored: PackageManager.NameNotFoundException) {
            return null
        }
    val ai = pi.applicationInfo ?: return null
    val repo = GuardRepository.get(ctx)
    val requested = pi.requestedPermissions.orEmpty().toSet()
    val facts = AppFacts(pkg, repo.backend.getScan(pkg), requested, isBrowser(pm, pkg))
    val iconPx = (48 * ctx.resources.displayMetrics.density).toInt()
    return SheetData(
        repo,
        ai.loadLabel(pm).toString(),
        runCatching { pm.getApplicationIcon(ai).toImageBitmap(iconPx) }.getOrNull(),
        installerLabel(pm, pkg),
        facts,
        PermissionGroup.of(requested),
        FirstLaunchAdvisor.recommend(facts),
        repo.policies.value.policyFor(pkg).dormant,
    )
}

/**
 * A generic http(s) handler: a BROWSABLE VIEW filter that matches any host. Quarantined components
 * are hidden from resolution unless asked for explicitly.
 */
private fun isBrowser(pm: PackageManager, pkg: String): Boolean {
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_QUARANTINED_COMPONENTS)
    return listOf("http", "https").any { scheme ->
        val probe =
            Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(pkg)
        pm.queryIntentActivities(probe, flags).isNotEmpty()
    }
}

private fun installerLabel(pm: PackageManager, pkg: String): String? {
    val src =
        try {
            pm.getInstallSourceInfo(pkg)
        } catch (ignored: PackageManager.NameNotFoundException) {
            return null
        }
    val installer = src.installingPackageName ?: src.initiatingPackageName ?: return null
    return try {
        pm.getApplicationInfo(installer, 0).loadLabel(pm).toString()
    } catch (ignored: PackageManager.NameNotFoundException) {
        installer
    }
}

private fun Drawable.toImageBitmap(px: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, px, px)
    draw(Canvas(bmp))
    return bmp.asImageBitmap()
}

/** The bottom sheet over a scrim. A tap on the scrim dismisses (the app stays quarantined). */
@Composable
private fun FirstLaunchSheet(
    pkg: String,
    onDismiss: () -> Unit,
    onChoice: suspend (SheetData, SheetChoice, AppPreset?) -> Unit,
) {
    val t = Guard.tokens
    val ctx = LocalContext.current
    val sheet by
        produceState<SheetData?>(null, pkg) {
            value = withContext(Dispatchers.IO) { loadSheet(ctx, pkg) }
            if (value == null) onDismiss() // uninstalled meanwhile
        }
    var busy by remember { mutableStateOf(false) }
    // IchthysOS: the preset the main button applies; starts at the recommendation's.
    var preset by remember { mutableStateOf<AppPreset?>(null) }
    val scope = rememberCoroutineScope()
    val noRipple = remember { MutableInteractionSource() }

    // The window is translucent and dimmed by the theme (Theme.IchthysGuard.Sheet).
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clickable(interactionSource = noRipple, indication = null, enabled = !busy) {
                onDismiss()
            }
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = maxHeight * 0.92f)
                    // Swallow taps so only the scrim dismisses.
                    .clickable(interactionSource = noRipple, indication = null) {},
            shape = RoundedCornerShape(topStart = t.radius, topEnd = t.radius),
            color = t.ink,
            border = if (t.hairlineSurfaces) BorderStroke(t.hairlineWidth, t.hairline) else null,
        ) {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val s = sheet
                    Header(s, pkg)
                    if (s == null) {
                        Text(
                            stringResource(R.string.ichthys_guard_first_launch_loading),
                            color = t.muted,
                        )
                    } else {
                        TrackersCard(s.facts.trackers)
                        PermissionsCard(s.facts.usesInternet, s.groups)
                        val chosen = preset ?: s.recommendation.preset
                        PresetCard(chosen) { preset = it }
                        if (chosen == AppPreset.CUSTOM) RecommendationCard(s.recommendation)
                    }
                }
                Buttons(
                    enabled = sheet != null && !busy,
                    applyLabel =
                        (preset ?: sheet?.recommendation?.preset)
                            ?.takeIf { it != AppPreset.CUSTOM }
                            ?.let { "Open with the ${it.label} preset" },
                    onChoice = { choice ->
                        val s = sheet ?: return@Buttons
                        busy = true
                        scope.launch { onChoice(s, choice, preset ?: s.recommendation.preset) }
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(s: SheetData?, pkg: String) {
    val t = Guard.tokens
    Row(verticalAlignment = Alignment.CenterVertically) {
        s?.icon?.let {
            Image(it, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(
                s?.label ?: pkg,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pkg,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            s?.installer?.let {
                Text(
                    stringResource(R.string.ichthys_guard_first_launch_installer, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
    }
    // IchthysOS: an app paused by auto-tighten has run before; don't call it new.
    Text(
        stringResource(
            if (s?.dormant == true) R.string.ichthys_guard_first_launch_intro_dormant
            else R.string.ichthys_guard_first_launch_intro
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = t.muted,
    )
}

@Composable
private fun TrackersCard(trackers: List<String>?) {
    val t = Guard.tokens
    GuardCard(title = stringResource(R.string.ichthys_guard_first_launch_section_trackers)) {
        when {
            trackers == null ->
                Text(
                    stringResource(R.string.ichthys_guard_first_launch_scan_pending),
                    color = t.muted,
                )
            trackers.isEmpty() ->
                Text(
                    stringResource(R.string.ichthys_guard_first_launch_no_trackers),
                    style = MaterialTheme.typography.titleMedium,
                )
            else -> {
                Text(
                    pluralStringResource(
                        R.plurals.ichthys_guard_first_launch_trackers_found,
                        trackers.size,
                        trackers.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    trackers.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
    }
}

@Composable
private fun PermissionsCard(internet: Boolean, groups: List<PermissionGroup>) {
    val t = Guard.tokens
    GuardCard(title = stringResource(R.string.ichthys_guard_first_launch_section_permissions)) {
        if (!internet && groups.isEmpty()) {
            Text(
                stringResource(R.string.ichthys_guard_first_launch_no_permissions),
                color = t.muted,
            )
        }
        if (internet) PermissionLine(stringResource(R.string.ichthys_guard_perm_internet))
        for (g in groups) PermissionLine(stringResource(g.labelRes()))
    }
}

@Composable
private fun PermissionLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun RecommendationCard(rec: Recommendation) {
    val t = Guard.tokens
    GuardCard(title = stringResource(R.string.ichthys_guard_first_launch_section_recommended)) {
        rec.lines.forEachIndexed { i, line ->
            Column(Modifier.padding(top = if (i == 0) 0.dp else 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(line.topic.labelRes()),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(line.valueRes(rec)),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono),
                    )
                }
                Text(
                    stringResource(line.reason.textRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
    }
}

/** IchthysOS: the preset picker of the sheet ("Quarantined · pick a preset to release"). */
@Composable
private fun PresetCard(chosen: AppPreset, onPick: (AppPreset) -> Unit) {
    val t = Guard.tokens
    GuardCard(title = "Preset") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (p in AppPreset.PICKER) Chip(p.label, chosen == p) { onPick(p) }
        }
        Text(chosen.summary, style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Buttons(enabled: Boolean, applyLabel: String?, onChoice: (SheetChoice) -> Unit) {
    val t = Guard.tokens
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The sheet's one accented element: the default action.
        Button(
            onClick = { onChoice(SheetChoice.APPLY) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors =
                ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent),
        ) {
            Text(applyLabel ?: stringResource(R.string.ichthys_guard_first_launch_apply))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onChoice(SheetChoice.CUSTOMIZE) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.ichthys_guard_first_launch_customize))
            }
            OutlinedButton(
                onClick = { onChoice(SheetChoice.AS_IS) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.ichthys_guard_first_launch_as_is))
            }
        }
    }
}

private fun PermissionGroup.labelRes(): Int =
    when (this) {
        PermissionGroup.LOCATION -> R.string.ichthys_guard_perm_location
        PermissionGroup.CAMERA -> R.string.ichthys_guard_perm_camera
        PermissionGroup.MICROPHONE -> R.string.ichthys_guard_perm_microphone
        PermissionGroup.CONTACTS -> R.string.ichthys_guard_perm_contacts
        PermissionGroup.SMS_CALLS -> R.string.ichthys_guard_perm_sms_calls
        PermissionGroup.STORAGE_MEDIA -> R.string.ichthys_guard_perm_storage_media
        PermissionGroup.NEARBY_DEVICES -> R.string.ichthys_guard_perm_nearby_devices
        PermissionGroup.NOTIFICATIONS -> R.string.ichthys_guard_perm_notifications
    }

private fun AdviceTopic.labelRes(): Int =
    when (this) {
        AdviceTopic.NETWORK -> R.string.ichthys_guard_advice_topic_network
        AdviceTopic.TRACKERS -> R.string.ichthys_guard_advice_topic_trackers
        AdviceTopic.EXCEPTIONS -> R.string.ichthys_guard_advice_topic_exceptions
    }

private fun AdviceLine.valueRes(rec: Recommendation): Int =
    when (topic) {
        AdviceTopic.NETWORK ->
            if (reason == AdviceReason.NETWORK_NOT_REQUESTED)
                R.string.ichthys_guard_advice_value_network_unused
            else R.string.ichthys_guard_advice_value_network_open
        // IchthysOS: per-app tracker levels aren't enforced yet (HostRules).
        AdviceTopic.TRACKERS ->
            if (!HostRules.ENFORCED) R.string.ichthys_guard_advice_value_trackers_not_enforced
            else
                when (rec.policy.trackerBlock) {
                    TrackerBlockLevel.STRICT -> R.string.ichthys_guard_advice_value_trackers_strict
                    TrackerBlockLevel.KNOWN_TRACKERS ->
                        R.string.ichthys_guard_advice_value_trackers_known
                    TrackerBlockLevel.OFF -> R.string.ichthys_guard_advice_value_trackers_off
                }
        AdviceTopic.EXCEPTIONS -> R.string.ichthys_guard_advice_value_exceptions_closed
    }

private fun AdviceReason.textRes(): Int =
    when (this) {
        AdviceReason.NETWORK_SHIELDED -> R.string.ichthys_guard_advice_network_shielded
        AdviceReason.NETWORK_NOT_REQUESTED -> R.string.ichthys_guard_advice_network_not_requested
        AdviceReason.TRACKERS_STRICT -> R.string.ichthys_guard_advice_trackers_strict
        AdviceReason.TRACKERS_KNOWN -> R.string.ichthys_guard_advice_trackers_known
        AdviceReason.TRACKERS_BROWSER -> R.string.ichthys_guard_advice_trackers_browser
        AdviceReason.TRACKERS_NONE -> R.string.ichthys_guard_advice_trackers_none
        AdviceReason.TRACKERS_UNKNOWN -> R.string.ichthys_guard_advice_trackers_unknown
        AdviceReason.EXCEPTIONS_CLOSED -> R.string.ichthys_guard_advice_exceptions_closed
    }
