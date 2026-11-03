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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import com.android.settings.ichthys.guard.backend.Health
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.AppPreset
import com.android.settings.ichthys.guard.model.CategoryResult
import com.android.settings.ichthys.guard.model.JournalKind
import com.android.settings.ichthys.guard.model.LocationMode
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.PolicySet
import com.android.settings.ichthys.guard.model.ScanMode
import com.android.settings.ichthys.guard.model.ScreenOffMode
import com.android.settings.ichthys.guard.model.SpoofChecks
import com.android.settings.ichthys.guard.model.VerifyCategory
import com.android.settings.ichthys.guard.model.VerifyPhase
import com.android.settings.ichthys.guard.model.VerifyRun
import com.android.settings.ichthys.guard.notify.NotificationActionReceiver
import com.android.settings.ichthys.guard.os.ShieldTable
import com.android.settings.ichthys.guard.ui.GuardActivity
import com.android.settings.ichthys.protect.IchthysProtect
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What Guard knows about the tester app before a run. */
data class ProbeStatus(
    val installed: Boolean,
    /** Found under /system (or as an updated system app): shield results would be skewed. */
    val inSystem: Boolean,
    val version: String,
    val quarantined: Boolean,
)

/**
 * IchthysOS: runs a verification. Guard puts the tester app (IchthysProbe, [PROBE_PACKAGE]) on the
 * lists and policy of a stand-in preset, launches it headless with what it should find
 * (`expect_*` extras) and the ground truth it can't see (`truth_*` row counts, taken as Settings,
 * which every shield exempts), and the probe broadcasts its verdicts back to
 * [com.android.settings.ichthys.guard.notify.VerifyResultReceiver]. One launch per phase;
 * "Verify my protection" runs Untrusted (the block, every shield), Messenger (the fake place,
 * picked contacts) and Maps (online, real location). The probe's previous lists and policy are
 * saved first and put back when the run ends, however it ends.
 */
class VerifyRunner private constructor(private val context: Context) {
    private val repo: GuardRepository
        get() = GuardRepository.get(context)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _current = MutableStateFlow<VerifyRun?>(null)
    /** The run in progress, or null. */
    val current: StateFlow<VerifyRun?> = _current.asStateFlow()

    private val lock = Any()

    init {
        // A run interrupted by a process death: give the probe its own setup back. Off the main
        // thread: the first get() comes from the ViewModel.
        if (prefs.contains(KEY_SNAPSHOT) && !prefs.contains(KEY_TOKEN)) {
            Thread({ synchronized(lock) { restorePendingSnapshot() } }, "IchthysGuardVerify").start()
        }
    }

    fun probeStatus(): ProbeStatus {
        val pm = context.packageManager
        val ai =
            try {
                pm.getApplicationInfo(PROBE_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
            } catch (e: PackageManager.NameNotFoundException) {
                return ProbeStatus(false, false, "", false)
            }
        val inSystem =
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) !=
                0 || ai.sourceDir?.startsWith("/system") == true
        val version =
            runCatching { pm.getPackageInfo(PROBE_PACKAGE, 0).versionName ?: "" }.getOrDefault("")
        val quarantined = runCatching { pm.isPackageQuarantined(PROBE_PACKAGE) }.getOrDefault(false)
        return ProbeStatus(true, inSystem, version, quarantined)
    }

    /**
     * Starts a run. [forPackage] names the app the probe stands in for (its preset, or its custom
     * setup copied over); null runs the three-phase "Verify my protection". Returns an error
     * sentence, or null when the first phase was launched. Off the main thread.
     */
    fun start(forPackage: String?): String? {
        val status = probeStatus()
        if (!status.installed)
            return "IchthysProbe isn't installed. Build it with `m IchthysProbe` and install it " +
                "as a user app (adb install), never into /system."
        if (status.quarantined)
            return "IchthysProbe is still quarantined. Open it once and release it, then verify."
        if (!repo.backend.isLive) return "The Guard service isn't reachable; nothing to verify."
        synchronized(lock) {
            if (_current.value?.done == false) return "A verification is already running."
            val phases =
                if (forPackage == null)
                    listOf(
                        VerifyPhase(AppPreset.UNTRUSTED, "Blocked app: firewall, shields, no location"),
                        VerifyPhase(AppPreset.MESSENGER, "Messenger: fake place, picked contacts"),
                        VerifyPhase(AppPreset.MAPS, "Maps: online, real location"),
                    )
                else {
                    val preset = repo.policies.value.policyFor(forPackage).preset
                    listOf(
                        VerifyPhase(
                            preset,
                            if (preset == AppPreset.CUSTOM) "As ${repo.appFor(forPackage)?.label ?: forPackage} is set up"
                            else "${preset.label} preset",
                        )
                    )
                }
            val run =
                VerifyRun(
                    id = UUID.randomUUID().toString().substring(0, 8),
                    startedMs = System.currentTimeMillis(),
                    forPackage = forPackage,
                    phases = phases,
                    buildId = android.os.Build.ID,
                    probeVersion = status.version,
                    probeInSystem = status.inSystem,
                )
            snapshotProbe()
            _current.value = run
            repo.recordVerifyRun(run)
            launchPhase(run, 0)
        }
        return null
    }

    /** Puts the probe into the phase's stand-in state and starts it. Holds [lock]. */
    private fun launchPhase(run: VerifyRun, index: Int) {
        val phase = run.phases[index]
        val token = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_RUN, run.id).putInt(KEY_PHASE, index).apply()
        // The stand-in: the preset's table, or the verified app's own setup for Custom.
        if (phase.standIn.spec != null) {
            repo.applyPreset(PROBE_PACKAGE, phase.standIn)
        } else if (run.forPackage != null) {
            copySetup(run.forPackage, PROBE_PACKAGE)
        }
        // Let the pushes land before the probe measures.
        SystemClock.sleep(600)
        val started = System.currentTimeMillis()
        val updated =
            run.copy(
                phases =
                    run.phases.mapIndexed { i, p -> if (i == index) p.copy(startedMs = started) else p }
            )
        _current.value = updated
        repo.recordVerifyRun(updated)
        val intent =
            Intent()
                .setClassName(PROBE_PACKAGE, PROBE_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_AUTORUN, true)
                .putExtra(EXTRA_LABEL, "guard-${run.id}-${index + 1}")
                .putExtra(EXTRA_GUARD_RUN, true)
                .putExtra(EXTRA_REPLY_TOKEN, token)
                .putExtra(EXTRA_REPLY_COMPONENT, "${context.packageName}/$RECEIVER_CLASS")
                .putExtra(EXTRA_STAND_IN, phase.standIn.label)
        putExpectations(intent, PROBE_PACKAGE)
        putTruths(intent)
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not start the probe", e)
            finishPhase(token, null, "IchthysProbe couldn't be started: ${e.message}")
            return
        }
        armTimeout(token)
    }

    /** What the probe should find, from its effective state right now. */
    private fun putExpectations(intent: Intent, pkg: String) {
        val rule = repo.effectiveRule(pkg)
        intent.putExtra(EXTRA_EXPECT_NET, if (rule.access == NetworkAccess.DENY) "blocked" else "allowed")
        intent.putExtra("qap", "granted")
        val location = repo.locationMode(pkg)
        val fakeOn = repo.fakePlaceOn()
        intent.putExtra(
            EXTRA_EXPECT_LOCATION,
            when (location) {
                LocationMode.NONE -> "none"
                LocationMode.REAL -> "real"
                LocationMode.FAKE -> if (fakeOn) "fake" else "real"
            },
        )
        if (fakeOn) {
            Settings.Secure.getString(context.contentResolver, ShieldTable.KEY_SPOOF_FAKE)?.let {
                intent.putExtra(EXTRA_EXPECT_FAKE_LATLON, it)
            }
        }
        intent.putExtra(
            EXTRA_EXPECT_SCANS,
            if (repo.scanMode(pkg) == ScanMode.EMPTY || location == LocationMode.NONE) "empty"
            else "real",
        )
        val app = repo.appFor(pkg)
        val lanDenied = app?.let { repo.backend.isLanDenied(it.uid) } ?: true
        intent.putExtra(EXTRA_EXPECT_LAN, if (lanDenied) "blocked" else "allowed")
        val protecting =
            ShieldTable.SHIELDS.filter { it.isProtected(context, pkg) }.map { it.id }
        intent.putExtra(EXTRA_EXPECT_SHIELDS, protecting.joinToString(","))
        intent.putExtra(EXTRA_EXPECT_SCREEN_OFF, repo.screenOffMode(pkg).name.lowercase())
        // IchthysOS: the spoofed values (Gear › Spoofed values). The probe reports raw values and
        // Guard compares them itself (SpoofChecks); the extras let the probe show them too.
        val spoof = spoofExpectation(pkg)
        intent.putExtra(EXTRA_EXPECT_SPOOFED, if (spoof.spoofed) "yes" else "no")
        intent.putExtra(EXTRA_EXPECT_PROFILE, spoof.profile?.takeIf { spoof.spoofed }?.toJson() ?: "")
        intent.putExtra(EXTRA_EXPECT_IP, if (spoof.ipBlocked) "blocked" else "allowed")
    }

    /** What the probe should get of the spoofed values right now. */
    private fun spoofExpectation(pkg: String): SpoofChecks.Expectation =
        SpoofChecks.Expectation(
            spoofed = repo.isSpoofed(pkg),
            profile = repo.spoofProfile(),
            vpnUp = vpnUp(),
        )

    /** True when any network with the VPN transport is up (the "fake IP" rule needs one). */
    private fun vpnUp(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return try {
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Row counts as Settings sees them (every shield exempts the system), so 0 rows is proof. */
    private fun putTruths(intent: Intent) {
        fun count(uri: Uri): Int =
            try {
                context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use {
                    it.count
                } ?: -1
            } catch (e: RuntimeException) {
                -1
            }
        intent.putExtra("truth_contacts", count(ContactsContract.Contacts.CONTENT_URI))
        intent.putExtra("truth_raw_contacts", count(ContactsContract.RawContacts.CONTENT_URI))
        intent.putExtra("truth_phones", count(ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        intent.putExtra("truth_groups", count(ContactsContract.Groups.CONTENT_URI))
        intent.putExtra("truth_calendars", count(CalendarContract.Calendars.CONTENT_URI))
        intent.putExtra("truth_events", count(CalendarContract.Events.CONTENT_URI))
        intent.putExtra("truth_calllog", count(CallLog.Calls.CONTENT_URI))
        intent.putExtra("truth_sms", count(Telephony.Sms.CONTENT_URI))
        intent.putExtra("truth_mms", count(Telephony.Mms.CONTENT_URI))
    }

    /** The probe's answer (any thread). Ignored unless [token] is the one of the open phase. */
    fun onResult(token: String?, extras: Bundle?) {
        if (token == null || extras == null) return
        finishPhase(token, extras, null)
    }

    /** The phase's alarm went off without an answer. */
    fun onTimeout(token: String?) {
        if (token != null) finishPhase(token, null, "No answer from IchthysProbe within 5 minutes")
    }

    private fun finishPhase(token: String, extras: Bundle?, error: String?) {
        synchronized(lock) {
            if (prefs.getString(KEY_TOKEN, null) != token) return
            prefs.edit().remove(KEY_TOKEN).apply()
            cancelTimeout(token)
            val run = _current.value ?: return
            val index = prefs.getInt(KEY_PHASE, -1)
            if (index !in run.phases.indices) return
            val phase = run.phases[index]
            val now = System.currentTimeMillis()
            val done =
                phase.copy(
                    finishedMs = now,
                    categories = if (extras != null) categorise(extras) else emptyList(),
                    attemptsSeen = probeAttempts(phase.startedMs, now).first,
                    attemptsBlocked = probeAttempts(phase.startedMs, now).second,
                    proofs = probeProofs(phase.startedMs, now),
                    error = error,
                )
            var updated =
                run.copy(
                    phases = run.phases.mapIndexed { i, p -> if (i == index) done else p },
                    probeInSystem = extras?.getBoolean(EXTRA_IN_SYSTEM, run.probeInSystem) ?: run.probeInSystem,
                    diff = extras?.getStringArrayList(EXTRA_DIFF)?.toList() ?: run.diff,
                )
            if (index + 1 < run.phases.size && error == null) {
                _current.value = updated
                repo.recordVerifyRun(updated)
                launchPhase(updated, index + 1)
                return
            }
            updated = updated.copy(finishedMs = now)
            restorePendingSnapshot()
            _current.value = updated
            repo.recordVerifyRun(updated)
            repo.journal(JournalKind.SELF_TEST, updated.verdictLine, run.forPackage)
            // Back to Guard, on the Verify page, with the result.
            try {
                context.startActivity(
                    Intent(context, GuardActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(GuardActivity.EXTRA_OPEN_PAGE, GuardActivity.OPEN_PAGE_VERIFY)
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not return to Guard", e)
            }
        }
    }

    /**
     * Groups the probe's per-probe verdicts into Guard's rule categories and adds the service
     * health as its own category (from the service, not the probe).
     */
    private fun categorise(extras: Bundle): List<CategoryResult> {
        val out = LinkedHashMap<VerifyCategory, CategoryResult>()
        fun add(cat: VerifyCategory, verdict: String, failure: String?) {
            val c = out[cat] ?: CategoryResult(cat)
            out[cat] =
                when (verdict) {
                    "pass" -> c.copy(pass = c.pass + 1)
                    "fail" -> c.copy(fail = c.fail + 1, failures = c.failures + listOfNotNull(failure))
                    "skip" -> c.copy(skip = c.skip + 1)
                    else -> c.copy(info = c.info + 1)
                }
        }
        val probes =
            try {
                JSONArray(extras.getString(EXTRA_PROBES) ?: "[]")
            } catch (e: org.json.JSONException) {
                JSONArray()
            }
        // IchthysOS: the spoof.* probes report raw values; Guard judges them against the profile.
        val spoof by lazy { spoofExpectation(PROBE_PACKAGE) }
        for (i in 0 until probes.length()) {
            val p = probes.optJSONObject(i) ?: continue
            val id = p.optString("id")
            var verdict = p.optString("verdict", "info")
            var why = p.optString("why", "")
            if (SpoofChecks.isSpoofProbe(id)) {
                val detail = p.optJSONObject("detail")
                val values = LinkedHashMap<String, String>()
                if (detail != null) for (k in detail.keys()) values[k] = detail.optString(k)
                SpoofChecks.judge(id, values, spoof)?.let {
                    verdict = it.verdict
                    why = it.why
                }
            }
            val cat = categoryOf(id, p.optString("category", ""))
            add(cat, verdict, if (verdict == "fail") "$id: $why" else null)
        }
        // Service health, from Guard's own view.
        val health = repo.backend.getHealth()
        val problems = Health.problems(health)
        out[VerifyCategory.SERVICE] =
            if (problems.isEmpty()) CategoryResult(VerifyCategory.SERVICE, pass = 1)
            else CategoryResult(VerifyCategory.SERVICE, fail = problems.size, failures = problems)
        return out.values.toList()
    }

    private fun categoryOf(id: String, probeCategory: String): VerifyCategory =
        when {
            id.startsWith("net.lan") || id.startsWith("net.dns_trackers") -> VerifyCategory.LAN
            id.startsWith("net.") -> VerifyCategory.FIREWALL
            id.startsWith("spoof.") -> VerifyCategory.SPOOF
            id.startsWith("location.") || id == "bt.le_scan" || id == "bt.discovery" ||
                id == "wifi.scan_results" || id == "wifi.connection" || id == "phone.cell_info" ->
                VerifyCategory.LOCATION
            id.startsWith("guard.") -> VerifyCategory.SERVICE
            probeCategory == "Network" -> VerifyCategory.FIREWALL
            else -> VerifyCategory.SHIELDS
        }

    /** Guard's own stream: the probe's network attempts in the window and how many it blocked. */
    private fun probeAttempts(fromMs: Long, toMs: Long): Pair<Int, Int> {
        val evs =
            repo.events.value.filter {
                it.packageName == PROBE_PACKAGE && it.event.timeMs in fromMs..toMs
            }
        return evs.size to evs.count { it.event.blocked }
    }

    /** The shield proofs the platform recorded for the probe in the window. */
    private fun probeProofs(fromMs: Long, toMs: Long): List<String> =
        repo.journal.value.entries
            .filter { it.packageName == PROBE_PACKAGE && it.isShield && it.timeMs in fromMs..toMs }
            .mapNotNull { it.shield?.label }
            .distinct()

    // ---- the probe's own setup, saved and restored --------------------------------------------

    private fun snapshotProbe() {
        if (prefs.contains(KEY_SNAPSHOT)) return // an older run's; keep the original state
        val o = JSONObject()
        o.put("policy", PolicySet(mapOf(PROBE_PACKAGE to repo.policies.value.policyFor(PROBE_PACKAGE))).toJson())
        val lists = JSONObject()
        for (key in ShieldTable.ALL_LIST_KEYS) {
            if (key == ShieldTable.KEY_STORAGE_SCOPES || key == ShieldTable.KEY_A11Y_SCOPES) continue
            lists.put(key, IchthysProtect.isListed(context, key, PROBE_PACKAGE))
        }
        o.put("lists", lists)
        o.put("picked", ShieldTable.isContactsPicked(context, PROBE_PACKAGE))
        o.put("screenOffExempt", PROBE_PACKAGE in repo.automation.value.screenOffExempt)
        o.put("lockdownKeep", PROBE_PACKAGE in repo.rules.value.lockdown.allowlist)
        prefs.edit().putString(KEY_SNAPSHOT, o.toString()).apply()
    }

    private fun restorePendingSnapshot() {
        val text = prefs.getString(KEY_SNAPSHOT, null) ?: return
        try {
            val o = JSONObject(text)
            val policy =
                PolicySet.fromJson(o.getString("policy")).policyFor(PROBE_PACKAGE)
            val lists = o.getJSONObject("lists")
            for (key in lists.keys()) {
                IchthysProtect.setListed(context, key, PROBE_PACKAGE, lists.getBoolean(key))
            }
            ShieldTable.setContactsPicked(context, PROBE_PACKAGE, o.optBoolean("picked"))
            if (!o.optBoolean("picked") && lists.optBoolean(ShieldTable.KEY_CONTACTS))
                IchthysProtect.setListed(context, ShieldTable.KEY_CONTACTS, PROBE_PACKAGE, true)
            repo.updatePolicy(PROBE_PACKAGE) { policy.copy(packageName = PROBE_PACKAGE) }
            val exempt = o.optBoolean("screenOffExempt")
            if ((PROBE_PACKAGE in repo.automation.value.screenOffExempt) != exempt) {
                repo.updateAutomation {
                    it.copy(
                        screenOffExempt =
                            if (exempt) it.screenOffExempt + PROBE_PACKAGE
                            else it.screenOffExempt - PROBE_PACKAGE
                    )
                }
            }
            val keep = o.optBoolean("lockdownKeep")
            if ((PROBE_PACKAGE in repo.rules.value.lockdown.allowlist) != keep)
                repo.setLockdownAllowed(PROBE_PACKAGE, keep)
            repo.syncOsListsFromSettings()
        } catch (e: RuntimeException) {
            Log.w(TAG, "probe setup not restored", e)
        } finally {
            prefs.edit().remove(KEY_SNAPSHOT).apply()
        }
    }

    /** Copies [from]'s policy and list memberships onto [to] (the probe stands in for it). */
    private fun copySetup(from: String, to: String) {
        val src = repo.policies.value.policyFor(from)
        for (key in ShieldTable.ALL_LIST_KEYS) {
            if (key == ShieldTable.KEY_STORAGE_SCOPES || key == ShieldTable.KEY_A11Y_SCOPES) continue
            IchthysProtect.setListed(context, key, to, IchthysProtect.isListed(context, key, from))
        }
        ShieldTable.setContactsPicked(context, to, ShieldTable.isContactsPicked(context, from))
        if (IchthysProtect.isListed(context, ShieldTable.KEY_CONTACTS, from))
            IchthysProtect.setListed(context, ShieldTable.KEY_CONTACTS, to, true)
        repo.updatePolicy(to) {
            src.copy(packageName = to, dormant = false, tags = emptySet(), keepActive = true)
        }
        repo.setScreenOffMode(to, repo.screenOffMode(from))
        repo.setLockdownAllowed(to, from in repo.rules.value.lockdown.allowlist)
        repo.syncOsListsFromSettings()
    }

    // ---- timeout ---------------------------------------------------------------------------

    private fun timeoutIntent(token: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_TIMEOUT)
                .putExtra(EXTRA_REPLY_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun armTimeout(token: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + TIMEOUT_MS,
                timeoutIntent(token),
            )
        } catch (e: SecurityException) {
            am.setWindow(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + TIMEOUT_MS,
                60_000,
                timeoutIntent(token),
            )
        }
    }

    private fun cancelTimeout(token: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(timeoutIntent(token))
    }

    companion object {
        private const val TAG = "IchthysGuard"
        const val PROBE_PACKAGE = GuardRepository.PROBE_PACKAGE
        const val PROBE_ACTIVITY = "org.ichthys.probe.MainActivity"
        private const val RECEIVER_CLASS =
            "com.android.settings.ichthys.guard.notify.VerifyResultReceiver"
        private const val PREFS = "ichthys_guard_verify"
        private const val KEY_TOKEN = "token"
        private const val KEY_RUN = "run"
        private const val KEY_PHASE = "phase"
        private const val KEY_SNAPSHOT = "probe_snapshot"
        private const val TIMEOUT_MS = 5 * 60_000L

        /** Explicit broadcast from the probe with one phase's verdicts. */
        const val ACTION_RESULT = "org.ichthys.probe.action.RESULT"
        /** Guard's own alarm when a phase gets no answer. */
        const val ACTION_TIMEOUT = "com.android.settings.ichthys.guard.action.VERIFY_TIMEOUT"

        // ---- the intent protocol with IchthysProbe (see its GuardRun.java) ----
        const val EXTRA_AUTORUN = "autorun"
        const val EXTRA_LABEL = "label"
        const val EXTRA_GUARD_RUN = "guard_run"
        const val EXTRA_REPLY_TOKEN = "reply_token"
        const val EXTRA_REPLY_COMPONENT = "reply_component"
        const val EXTRA_STAND_IN = "stand_in"
        const val EXTRA_EXPECT_NET = "expect_net"
        const val EXTRA_EXPECT_LOCATION = "expect_location"
        const val EXTRA_EXPECT_FAKE_LATLON = "expect_fake_latlon"
        const val EXTRA_EXPECT_SCANS = "expect_scans"
        const val EXTRA_EXPECT_LAN = "expect_lan"
        const val EXTRA_EXPECT_SHIELDS = "expect_shields"
        const val EXTRA_EXPECT_SCREEN_OFF = "expect_screen_off"
        /** IchthysOS: "yes" when the probe gets the spoofed values (fake place on, not on the real list). */
        const val EXTRA_EXPECT_SPOOFED = "expect_spoofed"
        /** IchthysOS: the profile JSON the platform hands the probe ("" when none / not spoofed). */
        const val EXTRA_EXPECT_PROFILE = "expect_profile"
        /** IchthysOS: "blocked" when the profile says vpn=true and no VPN is up, else "allowed". */
        const val EXTRA_EXPECT_IP = "expect_ip"
        // ---- the probe's reply ----
        const val EXTRA_PROBES = "probes"
        const val EXTRA_IN_SYSTEM = "in_system"
        const val EXTRA_DIFF = "diff"
        const val EXTRA_REPORT = "report"

        @Volatile private var instance: VerifyRunner? = null

        @JvmStatic
        fun get(context: Context): VerifyRunner =
            instance
                ?: synchronized(this) {
                    instance ?: VerifyRunner(context.applicationContext).also { instance = it }
                }
    }
}
