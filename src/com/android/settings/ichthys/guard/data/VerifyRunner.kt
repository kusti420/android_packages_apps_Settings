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
import android.content.ContentUris
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
import com.android.settings.ichthys.guard.model.LocalNetwork
import com.android.settings.ichthys.guard.model.LocationMode
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.PolicySet
import com.android.settings.ichthys.guard.model.ScanMode
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
        // A run interrupted by a process death: a fresh process can never continue it (the
        // phase's alarm doesn't survive a reboot and the probe's answer may never come), so the
        // probe gets its own setup back now, token or not, and the recorded run is closed as
        // interrupted. Off the main thread: the first get() comes from the ViewModel. Under
        // [lock], and only while no new run has started in the meantime.
        if (prefs.contains(KEY_SNAPSHOT)) {
            Thread(
                    { synchronized(lock) { if (_current.value == null) abandonInterruptedRun() } },
                    "IchthysGuardVerify",
                )
                .start()
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
    fun start(forPackage: String?, fromProbe: Boolean = false): String? {
        val status = probeStatus()
        if (!status.installed)
            return "IchthysProbe isn't installed. Build it with `m IchthysProbe` and install it " +
                "as a user app (adb install), never into /system."
        if (status.quarantined)
            return "IchthysProbe is still quarantined. Open it once and release it, then verify."
        if (!repo.backend.isLive) return "The Guard service isn't reachable; nothing to verify."
        synchronized(lock) {
            if (_current.value?.done == false) return "A verification is already running."
            // IchthysOS: when the owner gave the tester Full access (every permission, nothing
            // shielded), a fourth phase checks the other direction: real data must come through.
            val probeFull = repo.policies.value.policyFor(PROBE_PACKAGE).preset == AppPreset.FULL_ACCESS
            val phases =
                if (forPackage == null)
                    listOfNotNull(
                        VerifyPhase(AppPreset.UNTRUSTED, "Blocked app: firewall, shields, no location"),
                        VerifyPhase(AppPreset.MESSENGER, "Messenger: fake place, picked contacts"),
                        VerifyPhase(AppPreset.MAPS, "Maps: online, real location"),
                        if (probeFull) VerifyPhase(AppPreset.FULL_ACCESS, "Full access: real data must come through")
                        else null,
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
                    fromProbe = fromProbe,
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
        // IchthysOS: LAN = Block is enforced by revoking ACCESS_LOCAL_NETWORK, which kills the
        // probe's process. Had the service's apply landed after the launch, the running probe
        // would die (a 5-minute "No answer"): wait until the service holds the LAN state the
        // stand-in asks for.
        awaitLanState()
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
            // Share Guard's identity with the probe: its launch check (GuardRun.trustedLaunch)
            // accepts a Guard run only from the system uid, which it can only see this way.
            val options = android.app.ActivityOptions.makeBasic().setShareIdentityEnabled(true)
            context.startActivity(intent, options.toBundle())
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
        // Wi-Fi only: blocked on a metered network right now, allowed otherwise (the probe only
        // knows "blocked" and "allowed"; the metered chain is what it would hit).
        val metered =
            runCatching {
                    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
                }
                .getOrDefault(false)
        intent.putExtra(
            EXTRA_EXPECT_NET,
            when (rule.access) {
                NetworkAccess.DENY -> "blocked"
                NetworkAccess.UNMETERED_ONLY -> if (metered) "blocked" else "allowed"
                NetworkAccess.ALLOW -> "allowed"
            },
        )
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
        // IchthysOS: a spoofed app (on the fake place) gets empty Wi-Fi, cell and Bluetooth
        // scans from the platform (IchthysLocationSpoof) whatever its scan control says.
        intent.putExtra(
            EXTRA_EXPECT_SCANS,
            if (
                repo.scanMode(pkg) == ScanMode.EMPTY ||
                    location == LocationMode.NONE ||
                    repo.isSpoofed(pkg)
            )
                "empty"
            else "real",
        )
        val app = repo.appFor(pkg)
        val lanDenied = app?.let { repo.backend.isLanDenied(it.uid) } ?: true
        intent.putExtra(EXTRA_EXPECT_LAN, if (lanDenied) "blocked" else "allowed")
        // IchthysOS: only shields that really apply right now: the app's list state and every
        // master switch of the shield (a shield switched off protects nobody, and the probe
        // would otherwise report "real data visible although the shield protects this app").
        val protecting =
            ShieldTable.SHIELDS.filter { it.isEnforced(context, pkg) }.map { it.id }
        intent.putExtra(EXTRA_EXPECT_SHIELDS, protecting.joinToString(","))
        // No expect_screen_off: no probe reads it, and the probe can't test the screen-off rule
        // while it is on screen (Guard's own events, not the probe, are the evidence for it).
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

    /**
     * Row counts as Settings sees them, so 0 rows is proof. These queries return the real rows
     * only because Settings runs as uid 1000 (the system), which the providers' shields exempt;
     * the same URIs are what the probe queries (its truth names, PersonalProbes).
     */
    private fun putTruths(intent: Intent) {
        fun count(uri: Uri, projection: Array<String>? = arrayOf("_id")): Int =
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use {
                    it.count
                } ?: -1
            } catch (e: RuntimeException) {
                -1
            }
        intent.putExtra("truth_contacts", count(ContactsContract.Contacts.CONTENT_URI))
        intent.putExtra("truth_raw_contacts", count(ContactsContract.RawContacts.CONTENT_URI))
        intent.putExtra("truth_phones", count(ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        intent.putExtra("truth_groups", count(ContactsContract.Groups.CONTENT_URI))
        intent.putExtra("truth_profile", count(ContactsContract.Profile.CONTENT_URI))
        // The probe asks ContactsContract.Settings.getDefaultAccount(), not for rows: 1 when a
        // default account for new contacts is set, 0 when none, -1 when the call fails.
        intent.putExtra(
            "truth_default_account",
            try {
                if (ContactsContract.Settings.getDefaultAccount(context.contentResolver) != null) 1
                else 0
            } catch (e: RuntimeException) {
                -1
            },
        )
        // The SIM providers build their own cursors; a projection is ignored (null, as the probe).
        intent.putExtra("truth_sim_phonebook", count(Uri.parse("content://icc/adn"), null))
        intent.putExtra("truth_calendars", count(CalendarContract.Calendars.CONTENT_URI))
        intent.putExtra("truth_events", count(CalendarContract.Events.CONTENT_URI))
        val now = System.currentTimeMillis()
        val instances = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(instances, now)
        ContentUris.appendId(instances, now + 30L * 86_400_000L)
        intent.putExtra("truth_instances_30d", count(instances.build()))
        intent.putExtra("truth_calllog", count(CallLog.Calls.CONTENT_URI))
        intent.putExtra("truth_sms", count(Telephony.Sms.CONTENT_URI))
        intent.putExtra("truth_mms", count(Telephony.Mms.CONTENT_URI))
        intent.putExtra(
            "truth_conversations",
            count(Uri.parse("content://mms-sms/conversations?simple=true")),
        )
        intent.putExtra("truth_sms_icc", count(Uri.parse("content://sms/icc"), null))
    }

    /** The probe's answer (any thread). Ignored unless [token] is the one of the open phase. */
    fun onResult(token: String?, extras: Bundle?) {
        if (token == null || extras == null) return
        // IchthysOS: a reply with an "error" extra (the probe was busy with another run) is a
        // failed phase, not an empty pass.
        finishPhase(token, extras, extras.getString(EXTRA_ERROR)?.takeIf { it.isNotBlank() })
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
            // IchthysOS: Settings died mid-run (the probe's answer or the alarm built a fresh
            // runner): the run is lost, but the probe must get its own setup back.
            if (_current.value == null) {
                restorePendingSnapshot()
                return
            }
            val run = _current.value ?: return
            val index = prefs.getInt(KEY_PHASE, -1)
            if (index !in run.phases.indices) return
            val phase = run.phases[index]
            // IchthysOS: the service hands its events over in 500 ms batches, so the attempts
            // and shield proofs of the probe's last requests reach Guard's stream only after the
            // reply: give them a moment before the evidence is sampled (a reply only; the timeout
            // and launch-failure paths have nothing to wait for). Off the main thread (runAsync).
            if (extras != null) SystemClock.sleep(EVIDENCE_SETTLE_MS)
            val now = System.currentTimeMillis()
            // IchthysOS: a locked or sleeping phone changes many answers (clipboard reads are
            // refused while locked, scans and sensors throttle); the probe reports the state it
            // saw and the phase is inconclusive: its categories are kept, but it is never a pass.
            val inconclusive =
                extras != null &&
                    (extras.getBoolean(EXTRA_KEYGUARD_LOCKED, false) ||
                        !extras.getBoolean(EXTRA_INTERACTIVE, true))
            val phaseError = error ?: if (inconclusive) ERROR_INCONCLUSIVE else null
            val attempts = probeAttempts(phase.startedMs, now)
            val done =
                phase.copy(
                    finishedMs = now,
                    categories = if (extras != null) categorise(extras) else emptyList(),
                    attemptsSeen = attempts.first,
                    attemptsBlocked = attempts.second,
                    proofs = probeProofs(phase.startedMs, now),
                    error = phaseError,
                    inconclusive = inconclusive,
                )
            var updated =
                run.copy(
                    phases = run.phases.mapIndexed { i, p -> if (i == index) done else p },
                    probeInSystem = extras?.getBoolean(EXTRA_IN_SYSTEM, run.probeInSystem) ?: run.probeInSystem,
                    diff = extras?.getStringArrayList(EXTRA_DIFF)?.toList() ?: run.diff,
                )
            if (index + 1 < run.phases.size && phaseError == null) {
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
            // Back to where the run was asked for: Guard's Verify page, or the tester's own
            // screen when its "Check everything" button started the run.
            try {
                context.startActivity(
                    if (run.fromProbe)
                        Intent()
                            .setClassName(PROBE_PACKAGE, PROBE_ACTIVITY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    else
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
        // IchthysOS: a probe that errored out ("probe error: …", Expect.judge) is a fail
        // (fail-closed: the promise wasn't checked) but counted apart, so the failures list can
        // show it as an error rather than a broken promise.
        fun add(cat: VerifyCategory, verdict: String, failure: String?, probeError: Boolean) {
            val c = out[cat] ?: CategoryResult(cat)
            out[cat] =
                when (verdict) {
                    "pass" -> c.copy(pass = c.pass + 1)
                    "fail" ->
                        c.copy(
                            fail = c.fail + 1,
                            errors = c.errors + (if (probeError) 1 else 0),
                            failures = c.failures + listOfNotNull(failure),
                        )
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
            val probeError = verdict == "fail" && why.startsWith(PROBE_ERROR_PREFIX)
            add(cat, verdict, if (verdict == "fail") "$id: $why" else null, probeError)
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
            id == "guard.net_matches" -> VerifyCategory.FIREWALL
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
            prefs.edit().remove(KEY_SNAPSHOT).apply()
        } catch (e: RuntimeException) {
            // IchthysOS: kept, so the next start tries again instead of losing the setup.
            Log.w(TAG, "probe setup not restored; kept for the next start", e)
        }
    }

    /**
     * IchthysOS: closes the run a dead Settings process left open. The probe gets its own setup
     * back, the phase keys and the alarm go, and the recorded run ends with its open phase as
     * "Interrupted (Settings restarted)" (a fresh process can't resume it). Holds [lock].
     */
    private fun abandonInterruptedRun() {
        val runId = prefs.getString(KEY_RUN, null)
        val index = prefs.getInt(KEY_PHASE, -1)
        prefs.getString(KEY_TOKEN, null)?.let { cancelTimeout(it) }
        prefs.edit().remove(KEY_TOKEN).remove(KEY_RUN).remove(KEY_PHASE).apply()
        restorePendingSnapshot()
        val run = repo.verify.value.runs.firstOrNull { it.id == runId } ?: return
        if (run.done) return
        val now = System.currentTimeMillis()
        repo.recordVerifyRun(
            run.copy(
                finishedMs = now,
                phases =
                    run.phases.mapIndexed { i, p ->
                        when {
                            p.done -> p
                            i == index || p.startedMs != 0L ->
                                p.copy(finishedMs = now, error = ERROR_INTERRUPTED)
                            else -> p.copy(finishedMs = now, error = "Not run (Settings restarted)")
                        }
                    },
            )
        )
    }

    /**
     * Waits (100 ms steps, up to [LAN_SETTLE_MS]) until the service's LAN state for the probe
     * is the one its policy asks for: blocked for Block and Default (the service treats an app
     * new since setup as blocked), allowed for Allow. Holds [lock].
     */
    private fun awaitLanState() {
        val uid = repo.appFor(PROBE_PACKAGE)?.uid ?: return
        val wantDenied =
            repo.policies.value.policyFor(PROBE_PACKAGE).localNetwork != LocalNetwork.ALLOW
        val deadline = SystemClock.elapsedRealtime() + LAN_SETTLE_MS
        while (repo.backend.isLanDenied(uid) != wantDenied) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "the probe's LAN state didn't settle within $LAN_SETTLE_MS ms")
                return
            }
            SystemClock.sleep(100)
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
        /** How long a phase waits for the service's LAN state to match the stand-in. */
        private const val LAN_SETTLE_MS = 2_000L
        /** The service's event batch (500 ms) plus margin, before a phase's evidence is sampled. */
        private const val EVIDENCE_SETTLE_MS = 2_500L
        /** The probe's Expect.judge prefix for a probe that errored out. */
        private const val PROBE_ERROR_PREFIX = "probe error"
        private const val ERROR_INTERRUPTED = "Interrupted (Settings restarted)"
        private const val ERROR_INCONCLUSIVE =
            "Phone was locked or asleep during the run; results not conclusive"

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
        /** IchthysOS: set by the probe when the phase could not run (it was busy); a phase error. */
        const val EXTRA_ERROR = "error"
        /** IchthysOS: the keyguard and screen state the probe saw (Report.device); locked or asleep = inconclusive. */
        const val EXTRA_KEYGUARD_LOCKED = "keyguard_locked"
        const val EXTRA_INTERACTIVE = "interactive"

        @Volatile private var instance: VerifyRunner? = null

        @JvmStatic
        fun get(context: Context): VerifyRunner =
            instance
                ?: synchronized(this) {
                    instance ?: VerifyRunner(context.applicationContext).also { instance = it }
                }
    }
}
