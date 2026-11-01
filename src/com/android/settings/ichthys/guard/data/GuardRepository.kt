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

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.IPackageDataObserver
import android.content.pm.PackageManager
import android.content.pm.SuspendDialogInfo
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.settings.R
import com.android.settings.ichthys.guard.backend.BehaviourFlags
import com.android.settings.ichthys.guard.backend.BlockedCounter
import com.android.settings.ichthys.guard.backend.GuardBackend
import com.android.settings.ichthys.guard.backend.Health
import com.android.settings.ichthys.guard.backend.ServiceBackend
import com.android.settings.ichthys.guard.backend.UidRule
import com.android.settings.ichthys.guard.model.AppControls
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.AppPreset
import com.android.settings.ichthys.guard.model.AutomationSettings
import com.android.settings.ichthys.guard.model.BlockReason
import com.android.settings.ichthys.guard.model.CustomRule
import com.android.settings.ichthys.guard.model.GuardEvent
import com.android.settings.ichthys.guard.model.HostPattern
import com.android.settings.ichthys.guard.model.JournalEntry
import com.android.settings.ichthys.guard.model.JournalKind
import com.android.settings.ichthys.guard.model.JournalLog
import com.android.settings.ichthys.guard.model.LocationMode
import com.android.settings.ichthys.guard.model.PresetControl
import com.android.settings.ichthys.guard.model.ScanMode
import com.android.settings.ichthys.guard.model.ScreenOffMode
import com.android.settings.ichthys.guard.model.VerifyHistory
import com.android.settings.ichthys.guard.model.VerifyRun
import com.android.settings.ichthys.guard.os.ShieldTable
import com.android.settings.ichthys.protect.IchthysProtect
import com.android.settings.ichthys.guard.model.LabeledEvent
import com.android.settings.ichthys.guard.model.LocalNetwork
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.OsSettingKey
import com.android.settings.ichthys.guard.model.PolicySet
import com.android.settings.ichthys.guard.model.RiskFacts
import com.android.settings.ichthys.guard.model.RiskScore
import com.android.settings.ichthys.guard.model.RiskScorer
import com.android.settings.ichthys.guard.model.RuleAction
import com.android.settings.ichthys.guard.model.RuleBook
import com.android.settings.ichthys.guard.model.StatsHistory
import com.android.settings.ichthys.guard.os.AppInfo
import com.android.settings.ichthys.guard.os.AppInspector
import com.android.settings.ichthys.guard.notify.GuardNotifications
import com.android.settings.ichthys.guard.notify.NotificationActionReceiver
import com.android.settings.ichthys.guard.os.OsSettingsBridge
import com.android.settings.ichthys.guard.os.ShieldSwitch
import com.android.settings.ichthys.guard.tracker.TrackerMatcher
import com.android.settings.ichthys.guard.notify.LockdownControlReceiver
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * The single source of truth for the UI, notifications and workers. Owns persisted state
 * (PolicyStore, RuleStore), pushes enforcement to the [GuardBackend] and OS settings, and keeps a
 * bounded in-memory window of labelled events.
 *
 * Settings stores its own data in device-protected storage (defaultToDeviceProtectedStorage);
 * Guard's policies, rules and traffic history are private, so [context] is Settings'
 * credential-protected storage context and everything lives under files/ichthys_guard. Guard's
 * components are therefore not direct-boot aware (see the Settings manifest).
 */
class GuardRepository(
    private val context: Context,
    val backend: GuardBackend,
    val inspector: AppInspector,
    val matcher: TrackerMatcher,
    private val dataDir: File = File(context.filesDir, DATA_DIR).apply { mkdirs() },
    private val policyStore: PolicyStore = PolicyStore(dataDir),
    private val ruleStore: RuleStore = RuleStore(dataDir),
    private val bridge: OsSettingsBridge = OsSettingsBridge(context.contentResolver),
    private val statsStore: StatsStore = StatsStore(dataDir),
    private val automationStore: AutomationStore = AutomationStore(dataDir),
    private val journalStore: JournalStore = JournalStore(dataDir),
    private val verifyStore: VerifyStore = VerifyStore(dataDir),
) {
    // A failing job (a package that can't be read, a store that throws) is logged, not fatal:
    // uncaught, it would take the whole Settings process down.
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> Log.w(TAG, "guard job failed", e) })
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _policies = MutableStateFlow(policyStore.load())
    val policies: StateFlow<PolicySet> = _policies.asStateFlow()

    private val _rules = MutableStateFlow(ruleStore.load())
    val rules: StateFlow<RuleBook> = _rules.asStateFlow()

    private val _events = MutableStateFlow<List<LabeledEvent>>(emptyList())
    /** Newest last. Bounded to [MAX_EVENTS]. */
    val events: StateFlow<List<LabeledEvent>> = _events.asStateFlow()

    private val _pausedUntil = MutableStateFlow(prefs.getLong(KEY_PAUSED_UNTIL, 0))
    val pausedUntil: StateFlow<Long> = _pausedUntil.asStateFlow()

    private val _osWritable = MutableStateFlow(true)
    val osWritable: StateFlow<Boolean> = _osWritable.asStateFlow()

    private val _stats = MutableStateFlow(statsStore.load())
    /** Per-day counters that survive reboots (weekly report, 7-day trend). */
    val stats: StateFlow<StatsHistory> = _stats.asStateFlow()
    private var statsSavedMs = 0L

    private val _automation = MutableStateFlow(automationStore.load())
    /** Profiles, the screen-off rule, alert switches and auto-tighten. */
    val automation: StateFlow<AutomationSettings> = _automation.asStateFlow()

    private val _riskFacts = MutableStateFlow<Map<String, RiskFacts>>(emptyMap())
    /** package → what the risk score needs from its code and manifest (user apps). */
    val riskFacts: StateFlow<Map<String, RiskFacts>> = _riskFacts.asStateFlow()

    private val _journal = MutableStateFlow(journalStore.load())
    /**
     * IchthysOS: the journal: Guard's state changes and the platform's shield proofs, oldest
     * first, kept across reboots (the service's ring restarts at boot).
     */
    val journal: StateFlow<JournalLog> = _journal.asStateFlow()
    private var journalSavedMs = 0L

    private val _verify = MutableStateFlow(verifyStore.load())
    /** IchthysOS: verification runs (IchthysProbe launched by Guard), newest first. */
    val verify: StateFlow<VerifyHistory> = _verify.asStateFlow()

    private val _blocked = MutableStateFlow<Map<Int, BlockedCounter>>(emptyMap())
    /** IchthysOS: uid → blocked attempts since boot (service counters). */
    val blockedCounters: StateFlow<Map<Int, BlockedCounter>> = _blocked.asStateFlow()

    private val _denyNow = MutableStateFlow<Map<Int, BlockReason>>(emptyMap())
    /** IchthysOS: the uids offline right now, with the reason (the effective firewall state). */
    val denyNow: StateFlow<Map<Int, BlockReason>> = _denyNow.asStateFlow()

    private val _health = MutableStateFlow(Health.UNREACHABLE)
    /** IchthysOS: IIchthysGuard.HEALTH_* bits, [Health.UNREACHABLE] without the service. */
    val health: StateFlow<Int> = _health.asStateFlow()
    private var countersRefreshedMs = 0L

    private var uidToApp: Map<Int, AppInfo> = emptyMap()
    private var lastSeq = 0L
    /** IchthysOS: released once [start]'s first backfill has run (see [awaitBackfill]). */
    private val backfilled = CountDownLatch(1)

    /** Guards every read-modify-write of [_policies] (main thread, workers, the observer). */
    private val policyLock = Any()

    /**
     * The OS exception lists ([OsSettingKey]) are also edited elsewhere (the permission dialog's
     * contacts choice, the config service), so the settings are the source of truth: mirror them
     * into the policies Guard shows. Runs at start and whenever one of the keys changes.
     */
    fun syncOsListsFromSettings() {
        synchronized(policyLock) {
            var set = _policies.value
            for (key in OsSettingKey.entries) {
                val listed = bridge.listedPackages(key)
                val pkgs = listed + set.policies.values.filter(key.selector).map { it.packageName }
                for (pkg in pkgs) {
                    val isListed = pkg in listed
                    if (key.selector(set.policyFor(pkg)) != isListed) {
                        set = set.update(pkg) { key.assign(it, isListed) }
                    }
                }
            }
            if (set != _policies.value) {
                _policies.value = set
                savePolicies(set)
            }
        }
    }

    /**
     * "Exact sensor data" used to be saved in the policy only (Guard didn't write the setting
     * yet). Apply those saved choices once, so making the setting the source of truth doesn't
     * drop them.
     */
    private fun migrateSavedSensorExemptions() {
        if (prefs.getBoolean(KEY_SENSORS_MIGRATED, false)) return
        val saved = _policies.value.policies.values.filter { it.sensorsExempt }
        if (saved.all { bridge.setListed(OsSettingKey.SENSORS, it.packageName, true) }) {
            prefs.edit().putBoolean(KEY_SENSORS_MIGRATED, true).apply()
        }
    }

    private fun observeOsLists() {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scope.launch { syncOsListsFromSettings() }
                }
            }
        for (key in OsSettingKey.entries) {
            context.contentResolver.registerContentObserver(
                OsSettingsBridge.uriFor(key),
                false,
                observer,
            )
        }
    }

    /** Re-reads the platform's pause deadline (a pause may come from the SystemUI tile). */
    fun refreshPause() {
        if (!backend.isLive) return
        scope.launch {
            backend.getPausedUntil().let {
                _pausedUntil.value = it
                prefs.edit().putLong(KEY_PAUSED_UNTIL, it).apply()
            }
        }
    }

    fun start() {
        scope.launch {
            refreshApps()
            migrateSavedSensorExemptions()
            syncOsListsFromSettings()
            observeOsLists()
            // The platform owns the pause deadline; adopt it (it survives app restarts there).
            if (backend.isLive)
                backend.getPausedUntil().let {
                    _pausedUntil.value = it
                    prefs.edit().putLong(KEY_PAUSED_UNTIL, it).apply()
                }
            // Platform seq restarts at 1 after a reboot: don't let the old high-water mark hide it.
            if (backend.getLatestSeq() < _stats.value.lastSeq) _stats.update { it.resetSeq() }
            // Behaviours, alerts and schedules live here; the service only holds the result.
            backend.setAlertMask(_automation.value.alertMask)
            pushBehaviours()
            scheduleProfileTick()
            launch { loadRiskFacts() }
            try {
                backfill()
            } finally {
                backfilled.countDown()
            }
            refreshEvidence(force = true)
            backend.liveEvents().collect { ingest(it) }
        }
    }

    /**
     * IchthysOS: re-reads the service's health bits, the deny chain and the per-uid blocked
     * counters (three cheap binder calls). Called on start, when the UI comes back, and after a
     * batch with blocked events, at most every few seconds.
     */
    fun refreshEvidence(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - countersRefreshedMs < 3_000) return
        countersRefreshedMs = now
        _health.value = backend.getHealth()
        if (_health.value == Health.UNREACHABLE) return
        _blocked.value = backend.getBlockedCounters()
        _denyNow.value = backend.getDenyNow()
    }

    fun refreshApps() {
        val list = inspector.installedApps()
        uidToApp = list.associateBy { it.uid }
        _apps.value = list
        // Relabel what we already have now that names are known.
        _events.update { evs -> evs.map { label(it.event) } }
    }

    fun appFor(pkg: String): AppInfo? =
        _apps.value.firstOrNull { it.packageName == pkg } ?: inspector.app(pkg)

    /**
     * Waits (off the main thread) until the first backfill after process start has been counted,
     * so a reader of [stats] (the weekly report) doesn't see a week without today's events.
     */
    fun awaitBackfill(timeoutMs: Long = 15_000): Boolean =
        backfilled.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Pages through the service's ring buffer (oldest first) and keeps the newest window.
     * IchthysOS: paging starts at what [stats] already counted, not at the newest [MAX_EVENTS],
     * so the per-day counters see every buffered event; [ingest] still keeps only the newest
     * window in memory.
     */
    private fun backfill() {
        val newestWindow = backend.getLatestSeq() - MAX_EVENTS
        lastSeq = maxOf(lastSeq, minOf(_stats.value.lastSeq, newestWindow).coerceAtLeast(0))
        var pages = 0
        while (pages++ < MAX_BACKFILL_PAGES) {
            val page = backend.getEvents(lastSeq, PAGE)
            if (page.isEmpty()) break
            ingest(page)
            if (page.size < PAGE) break
        }
    }

    private fun ingest(batch: List<GuardEvent>) {
        val fresh = batch.filter { it.seq > lastSeq }
        if (fresh.isEmpty()) return
        lastSeq = fresh.maxOf { it.seq }
        // IchthysOS: journal entries and shield proofs share the ring but not the traffic
        // analytics; they go to the journal, which also survives reboots.
        val (network, evidence) = fresh.partition { it.isNetwork }
        if (evidence.isNotEmpty()) {
            val entries = evidence.mapNotNull { JournalEntry.of(label(it)) }
            _journal.update { it.plus(entries) }
            val now = System.currentTimeMillis()
            if (now - journalSavedMs > 10_000) flushJournal(now)
        }
        if (network.isEmpty()) return
        val labeled = network.map(::label)
        _events.update { (it + labeled).takeLast(MAX_EVENTS) }
        _stats.update { it.record(labeled) }
        val now = System.currentTimeMillis()
        if (now - statsSavedMs > 30_000) flushStats(now)
        if (labeled.any { it.event.blocked }) scope.launch { refreshEvidence() }
    }

    fun flushJournal(now: Long = System.currentTimeMillis()) {
        journalSavedMs = now
        try {
            journalStore.save(_journal.value)
        } catch (e: IOException) {
            Log.w(TAG, "journal not saved", e)
        }
    }

    /**
     * IchthysOS: records a state change Guard itself made. It goes to the service's ring (so the
     * stream shows it between the connections it explains) and, in case the service is away, to
     * the local journal right away; the echo from the service is deduplicated by time, uid and
     * code.
     */
    fun journal(kind: JournalKind, detail: String?, pkg: String? = null) {
        val app = pkg?.let { appFor(it) }
        val uid = app?.uid ?: 0
        val now = System.currentTimeMillis()
        _journal.update {
            it.plus(listOf(JournalEntry(now, uid, kind.code, detail, pkg, app?.label ?: pkg)))
        }
        flushJournal(now)
        if (backend.isLive) backend.addJournal(uid, kind, detail)
    }

    /** IchthysOS: records a finished or started verification run. */
    fun recordVerifyRun(run: VerifyRun) {
        _verify.update { it.with(run) }
        try {
            verifyStore.save(_verify.value)
        } catch (e: IOException) {
            Log.w(TAG, "verify history not saved", e)
        }
    }

    fun flushStats(now: Long = System.currentTimeMillis()) {
        statsSavedMs = now
        try {
            statsStore.save(_stats.value)
        } catch (e: java.io.IOException) {
            Log.w("IchthysGuard", "stats not saved", e)
        }
    }

    fun label(e: GuardEvent): LabeledEvent {
        val app = uidToApp[e.uid]
        val t = e.host?.let(matcher::match)
        return LabeledEvent(
            e,
            app?.packageName,
            app?.label ?: uidLabel(e.uid),
            t?.name,
            t?.category,
        )
    }

    private fun uidLabel(uid: Int): String =
        when (uid) {
            0 -> "root"
            1000 -> "Android system"
            1051 -> "DNS resolver"
            else -> context.packageManager.getNameForUid(uid) ?: "uid $uid"
        }

    fun isPaused(now: Long = System.currentTimeMillis()) = _pausedUntil.value > now

    // ---- policies -------------------------------------------------------------------------

    /**
     * IchthysOS: PolicyStore.save throws on a full disk. The change still applies (memory, OS
     * lists, the service); it is only lost at the next restart, so log instead of crashing
     * Settings from a click handler or a receiver.
     */
    private fun savePolicies(set: PolicySet) {
        try {
            policyStore.save(set)
        } catch (e: IOException) {
            Log.w(TAG, "policies not saved", e)
        }
    }

    fun updatePolicy(pkg: String, change: (AppPolicy) -> AppPolicy) {
        val before: AppPolicy
        val after: AppPolicy
        synchronized(policyLock) {
            before = _policies.value.policyFor(pkg)
            val next = _policies.value.update(pkg, change)
            after = next.policyFor(pkg)
            // One package in, one package out: the rest of each shared key is left alone.
            for (key in OsSettingKey.entries) {
                if (key.selector(before) != key.selector(after)) {
                    _osWritable.value = bridge.setListed(key, pkg, key.selector(after))
                }
            }
            _policies.value = next
            savePolicies(next)
        }
        appFor(pkg)?.let { backend.setNetworkPolicy(it.uid, effectiveUidRule(it.uid, pkg)) }
        if (
            before.screenOffBlock != after.screenOffBlock ||
                before.backgroundNetworkOff != after.backgroundNetworkOff ||
                before.localNetwork != after.localNetwork ||
                before.tags != after.tags
        ) {
            scope.launch { pushBehaviours() }
        }
    }

    /**
     * Turns one row of Home's shields card on or off for every app: the OS master switches in
     * [switches] together (see SHIELD_SECTIONS).
     */
    fun setShields(switches: List<ShieldSwitch>, on: Boolean) {
        _osWritable.value = switches.map { it.setOn(context.contentResolver, on) }.all { it }
    }

    /**
     * What the firewall enforces for [pkg]: lockdown, then auto-tighten (dormant), then the
     * stricter of the app's own policy and every active profile that matches it.
     */
    fun effectiveAccess(pkg: String): NetworkAccess = effectiveRule(pkg).access

    /**
     * IchthysOS: [effectiveAccess] with the reason that decided it, which the service stamps on
     * every blocked event (BLOCKED · lockdown, · schedule, · asleep, · policy).
     */
    fun effectiveRule(pkg: String): UidRule {
        val lock = _rules.value.lockdown
        if (lock.enabled && pkg !in lock.allowlist)
            return UidRule(NetworkAccess.DENY, BlockReason.LOCKDOWN)
        val policy = _policies.value.policyFor(pkg)
        if (policy.dormant) return UidRule(NetworkAccess.DENY, BlockReason.DORMANT)
        var rule = UidRule(policy.network, BlockReason.POLICY)
        for (p in _automation.value.activeProfiles()) {
            if (p.matches(pkg, policy.tags) && p.network.ordinal > rule.access.ordinal)
                rule = UidRule(p.network, BlockReason.SCHEDULE)
        }
        return rule
    }

    /**
     * IchthysOS: what the firewall should hold for [uid]: the strictest [effectiveRule] of every
     * package sharing it (as [pushAllNetworkPolicies] does), so changing one package doesn't
     * override a stricter sibling. [pkg] counts even before [apps] lists it.
     */
    private fun effectiveUidRule(uid: Int, pkg: String): UidRule {
        var rule = effectiveRule(pkg)
        for (app in _apps.value) {
            if (app.uid != uid || app.packageName == pkg) continue
            val other = effectiveRule(app.packageName)
            if (other.access.ordinal > rule.access.ordinal) rule = other
        }
        return rule
    }

    /**
     * IchthysOS: why [pkg] is offline right now, or null when it is online: the service's own
     * deny chain (which also knows the screen-off and background rules), with the saved rule as
     * the fallback while the service is away.
     */
    fun offlineReason(pkg: String): BlockReason? {
        val app = appFor(pkg) ?: return null
        _denyNow.value[app.uid]?.let {
            return it
        }
        if (backend.isLive && _denyNow.value.isNotEmpty()) return null
        val rule = effectiveRule(pkg)
        return if (rule.access == NetworkAccess.DENY && !isPaused()) rule.reason else null
    }

    /** The active profiles that override [pkg] right now. */
    fun profilesFor(pkg: String) =
        _policies.value.policyFor(pkg).let { p ->
            _automation.value.activeProfiles().filter { it.matches(pkg, p.tags) }
        }

    /** True when "no network while screen off" applies to [app] (own toggle, profile or rule). */
    fun screenOffBlocked(app: AppInfo): Boolean {
        val p = _policies.value.policyFor(app.packageName)
        val auto = _automation.value
        return p.screenOffBlock ||
            profilesFor(app.packageName).any { it.screenOffBlock } ||
            (auto.screenOffAll && !app.isSystem && app.packageName !in auto.screenOffExempt)
    }

    private fun behaviourFlags(app: AppInfo): Int {
        val p = _policies.value.policyFor(app.packageName)
        var f = 0
        if (screenOffBlocked(app)) f = f or BehaviourFlags.SCREEN_OFF_DENY
        if (p.backgroundNetworkOff || profilesFor(app.packageName).any { it.backgroundNetworkOff })
            f = f or BehaviourFlags.FOREGROUND_ONLY
        when (p.localNetwork) {
            LocalNetwork.ALLOW -> f = f or BehaviourFlags.LAN_ALLOW
            LocalNetwork.BLOCK -> f = f or BehaviourFlags.LAN_DENY
            LocalNetwork.DEFAULT -> {}
        }
        return f
    }

    /** Pushes every app's behaviour flags (screen-off, foreground-only, LAN) to the service. */
    fun pushBehaviours() {
        if (_apps.value.isEmpty()) refreshApps()
        val table = HashMap<Int, Int>()
        for (app in _apps.value) {
            val f = behaviourFlags(app)
            if (f != 0) table[app.uid] = (table[app.uid] ?: 0) or f
        }
        // IchthysOS: and the installed apps with a stored rule that left the list (see
        // pushAllNetworkPolicies).
        val listed = _apps.value.mapTo(HashSet()) { it.packageName }
        for (pkg in _policies.value.policies.keys) {
            if (pkg in listed) continue
            val app = inspector.app(pkg) ?: continue
            val f = behaviourFlags(app)
            if (f != 0) table[app.uid] = (table[app.uid] ?: 0) or f
        }
        backend.replaceBehaviours(table)
    }

    /** Pushes every app's effective access to the platform (boot, lockdown changes, import). */
    fun pushAllNetworkPolicies() {
        if (_apps.value.isEmpty()) refreshApps()
        val table = HashMap<Int, UidRule>()
        for (app in _apps.value) {
            val want = effectiveRule(app.packageName)
            if (want.access == NetworkAccess.ALLOW) continue
            // Several packages can share a uid; the strictest wins.
            val prev = table[app.uid]
            if (prev == null || want.access.ordinal > prev.access.ordinal) table[app.uid] = want
        }
        // IchthysOS: a stored rule also holds for an installed app that left the list (a system
        // app whose launcher icon was hidden, microG for one); the replace would drop it.
        val listed = _apps.value.mapTo(HashSet()) { it.packageName }
        for ((pkg, policy) in _policies.value.policies) {
            if (pkg in listed) continue
            val want =
                if (policy.dormant) UidRule(NetworkAccess.DENY, BlockReason.DORMANT)
                else UidRule(policy.network, BlockReason.POLICY)
            if (want.access == NetworkAccess.ALLOW) continue
            val app = inspector.app(pkg) ?: continue
            val prev = table[app.uid]
            if (prev == null || want.access.ordinal > prev.access.ordinal) table[app.uid] = want
        }
        // One atomic replace; the platform skips system-critical uids. IchthysOS: reasons aren't
        // read back, so the table is compared by access and pushed whenever the set differs.
        if (table.mapValues { it.value.access } != backend.getNetworkPolicies() ||
            table.values.any { it.reason != BlockReason.POLICY } != lastPushHadReasons
        ) {
            backend.replaceNetworkPolicies(table)
            lastPushHadReasons = table.values.any { it.reason != BlockReason.POLICY }
        }
        scope.launch { refreshEvidence(force = true) }
    }

    private var lastPushHadReasons = false

    fun blockHost(pkg: String?, host: String) {
        if (pkg == null) {
            addRule(
                CustomRule(
                    newId(),
                    CustomRule.ANY_APP,
                    HostPattern.normalize(host),
                    RuleAction.BLOCK,
                    createdMs = System.currentTimeMillis(),
                )
            )
        } else {
            updatePolicy(pkg) {
                it.copy(
                    blockedHosts = it.blockedHosts + host,
                    allowedHosts = it.allowedHosts - host,
                )
            }
        }
    }

    fun allowHost(pkg: String?, host: String) {
        if (pkg == null) {
            addRule(
                CustomRule(
                    newId(),
                    CustomRule.ANY_APP,
                    HostPattern.normalize(host),
                    RuleAction.ALLOW,
                    createdMs = System.currentTimeMillis(),
                )
            )
        } else {
            updatePolicy(pkg) {
                it.copy(
                    allowedHosts = it.allowedHosts + host,
                    blockedHosts = it.blockedHosts - host,
                )
            }
        }
    }

    // ---- rules, lockdown, pause -----------------------------------------------------------

    private fun saveRules(book: RuleBook) {
        val wasLocked = _rules.value.lockdown.enabled
        _rules.value = book
        try {
            ruleStore.save(book)
        } catch (e: IOException) {
            // IchthysOS: a full disk must not take Settings down; the change still applies now.
            Log.w(TAG, "rules not saved", e)
        }
        // SystemUI's Lockdown tile caches the state, so every path that flips it (the switch, the
        // tile, an imported backup) announces the change here.
        if (book.lockdown.enabled != wasLocked) {
            context.sendBroadcastAsUser(
                Intent(LockdownControlReceiver.ACTION_LOCKDOWN_CHANGED)
                    .setPackage("com.android.systemui")
                    .putExtra(LockdownControlReceiver.EXTRA_ENABLED, book.lockdown.enabled),
                context.user,
                "org.ichthys.permission.MANAGE_ICHTHYS_GUARD",
            )
        }
    }

    fun addRule(rule: CustomRule) = saveRules(_rules.value.upsert(rule))

    fun removeRule(id: String) = saveRules(_rules.value.remove(id))

    fun setSourceEnabled(id: String, enabled: Boolean) =
        saveRules(
            _rules.value.copy(
                sources =
                    _rules.value.sources.map { if (it.id == id) it.copy(enabled = enabled) else it }
            )
        )

    fun setLockdown(enabled: Boolean) {
        if (_apps.value.isEmpty()) refreshApps() // e.g. toggled from the QS tile on a cold start
        val lock = _rules.value.lockdown
        // IchthysOS: seed the default allowlist once. An allowlist the user emptied on purpose
        // stays empty.
        val seeded = prefs.getBoolean(KEY_LOCKDOWN_SEEDED, false)
        val allow =
            if (lock.allowlist.isEmpty() && !seeded) defaultLockdownAllowlist() else lock.allowlist
        if (!seeded) prefs.edit().putBoolean(KEY_LOCKDOWN_SEEDED, true).apply()
        saveRules(
            _rules.value.copy(
                lockdown =
                    lock.copy(
                        enabled = enabled,
                        allowlist = allow,
                        sinceMs = if (enabled) System.currentTimeMillis() else 0,
                    )
            )
        )
        if (lock.enabled != enabled) {
            journal(
                if (enabled) JournalKind.LOCKDOWN_ON else JournalKind.LOCKDOWN_OFF,
                if (enabled) "${allow.size} apps keep their network" else null,
            )
        }
        scope.launch { pushAllNetworkPolicies() }
    }

    /**
     * IchthysOS: the allowlist [setLockdown] (true) would use right now (the seeded defaults on
     * the very first lockdown, the user's list after that), so the confirmation names the apps
     * that really keep their network instead of assuming the defaults.
     */
    fun lockdownAllowlistPreview(): Set<String> {
        val lock = _rules.value.lockdown
        val seeded = prefs.getBoolean(KEY_LOCKDOWN_SEEDED, false)
        return if (lock.allowlist.isEmpty() && !seeded) defaultLockdownAllowlist() else lock.allowlist
    }

    fun setLockdownAllowed(pkg: String, allowed: Boolean) {
        val lock = _rules.value.lockdown
        // The user is choosing the list; never replace it with the defaults afterwards.
        prefs.edit().putBoolean(KEY_LOCKDOWN_SEEDED, true).apply()
        saveRules(
            _rules.value.copy(
                lockdown =
                    lock.copy(
                        allowlist = if (allowed) lock.allowlist + pkg else lock.allowlist - pkg
                    )
            )
        )
        if (lock.enabled) scope.launch { pushAllNetworkPolicies() }
    }

    /** Messaging and phone apps stay reachable in lockdown unless the user changes it. */
    private fun defaultLockdownAllowlist(): Set<String> =
        _apps.value
            .map { it.packageName }
            .filter { p -> DEFAULT_LOCKDOWN_HINTS.any { p.contains(it) } }
            .toSet()

    fun pause(minutes: Int) {
        backend.pauseEnforcement(minutes)
        val until = if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L
        _pausedUntil.value = until
        prefs.edit().putLong(KEY_PAUSED_UNTIL, until).apply()
    }

    // ---- automation: profiles, screen-off rule, alerts, auto-tighten -------------------------

    fun updateAutomation(change: (AutomationSettings) -> AutomationSettings) {
        val before = _automation.value
        val next = change(before)
        _automation.value = next
        try {
            automationStore.save(next)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "automation not saved", e)
        }
        if (before.alertMask != next.alertMask)
            scope.launch { backend.setAlertMask(next.alertMask) }
        if (
            before.profiles != next.profiles ||
                before.screenOffAll != next.screenOffAll ||
                before.screenOffExempt != next.screenOffExempt
        ) {
            scope.launch { onScheduleTick() }
        }
    }

    /** A profile window opened or closed (alarm), or profiles changed: re-push everything. */
    fun onScheduleTick() {
        // IchthysOS: the journal says which schedule started or ended.
        val active = _automation.value.activeProfiles().map { it.name }.toSet()
        val before = prefs.getStringSet(KEY_ACTIVE_SCHEDULES, null)
        if (before != null) {
            for (name in active - before) journal(JournalKind.SCHEDULE_START, name)
            for (name in before - active) journal(JournalKind.SCHEDULE_END, name)
        }
        prefs.edit().putStringSet(KEY_ACTIVE_SCHEDULES, active).apply()
        pushAllNetworkPolicies()
        pushBehaviours()
        scheduleProfileTick()
    }

    /** One exact alarm at the next start or end of any enabled profile. */
    fun scheduleProfileTick() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi =
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(GuardNotifications.ACTION_PROFILE_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val now = System.currentTimeMillis()
        val next =
            _automation.value.profiles.filter { it.enabled }.minOfOrNull { it.nextBoundary(now) }
        if (next == null || next == Long.MAX_VALUE) {
            am.cancel(pi)
            return
        }
        try {
            // Settings runs as system, which may always set exact alarms.
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 1_000, pi)
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, next, 60_000, pi)
        }
    }

    /** Fills [riskFacts] for the user apps (scan results are cached by the service). */
    fun loadRiskFacts() {
        if (_apps.value.isEmpty()) refreshApps()
        val out = HashMap<String, RiskFacts>()
        for (app in _apps.value) {
            if (app.isSystem) continue
            riskFactsFor(app.packageName)?.let { out[app.packageName] = it }
        }
        _riskFacts.value = out
    }

    fun riskFactsFor(pkg: String): RiskFacts? {
        _riskFacts.value[pkg]?.let {
            return it
        }
        val pm = context.packageManager
        val pi =
            try {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } catch (ignored: PackageManager.NameNotFoundException) {
                return null
            }
        val requested = pi.requestedPermissions.orEmpty().toSet()
        val local = inspector.localScan(pkg)
        val facts =
            RiskFacts(
                trackers = backend.getScan(pkg),
                dangerousPermissions = local?.dangerousPermissions?.size ?: 0,
                startsAtBoot = "android.permission.RECEIVE_BOOT_COMPLETED" in requested,
                usesInternet = "android.permission.INTERNET" in requested,
            )
        // Cache only once the install scan is known, so a pending scan is asked again later.
        if (facts.trackers != null) _riskFacts.update { it + (pkg to facts) }
        return facts
    }

    fun riskFor(pkg: String, events: List<LabeledEvent>): RiskScore? =
        _riskFacts.value[pkg]?.let {
            RiskScorer.score(it, _policies.value.policyFor(pkg), events, effectiveAccess(pkg))
        }

    /**
     * Auto-tighten (W8b decision 18): a user app unused for [AutomationSettings.autoTightenDays]
     * loses its network ([AppPolicy.dormant]) and is suspended with Settings as the suspender, so
     * opening it shows the suspended-app dialog whose Review button opens Guard's first-launch
     * sheet (FirstLaunchActivity), which lifts both. Daily, off the main thread.
     *
     * Off by default. "Unused" means nothing of the app ran for the user ([lastActiveMs]), and an
     * app that works without being opened is never paused ([backgroundRoles], [worksUnopened]).
     * Whenever that can't be established, the app (or the whole run) is skipped.
     */
    fun runMaintenance(): List<String> {
        val auto = _automation.value
        if (!auto.autoTighten) return emptyList()
        refreshApps()
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val cutoff = now - auto.autoTightenDays * DAY_MS
        val usage =
            try {
                usm.queryAndAggregateUsageStats(cutoff - auto.autoTightenDays * DAY_MS, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, "usage stats unavailable; auto-tighten skipped", e)
                return emptyList()
            }
        val pm = context.packageManager
        val lockdownAllow = _rules.value.lockdown.allowlist
        val inUse =
            try {
                backgroundRoles()
            } catch (e: RuntimeException) {
                Log.w(TAG, "exempt apps unknown; auto-tighten skipped", e)
                return emptyList()
            }
        val tightened = mutableListOf<String>()
        for (app in _apps.value) {
            val pkg = app.packageName
            if (app.isSystem || !app.usesInternet || app.firstInstallMs > cutoff) continue
            val p = _policies.value.policyFor(pkg)
            if (p.dormant || p.keepActive || p.network == NetworkAccess.DENY) continue
            if (pkg in lockdownAllow || pkg in auto.screenOffExempt || pkg == context.packageName)
                continue
            // Apps that work without being opened (keyboard, launcher, accessibility, admin,
            // notification listeners, always-on VPN, tap-to-pay, companion apps, the catalog's
            // apps) or can't be opened at all are never "unused".
            if (pkg in inUse || pm.getLaunchIntentForPackage(pkg) == null) continue
            if (lastActiveMs(usm, usage[pkg], pkg) >= cutoff) continue
            // Never opened yet: the first-launch quarantine already covers it.
            if (runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(true)) continue
            // Binder calls per app, so only for the few that got this far.
            if (worksUnopened(pkg)) continue
            updatePolicy(pkg) { it.copy(dormant = true) }
            suspendDormant(pkg, app.label, auto.autoTightenDays)
            journal(JournalKind.SLEEP, "Unused for ${auto.autoTightenDays} days", pkg)
            tightened += app.label
        }
        return tightened
    }

    /**
     * When anything of [pkg] last ran for the user: an activity on screen, a foreground service,
     * or any component started for it (a sync, a share target, a notification action), not only
     * the last time it was visible. UsageStatsManager's own figure also counts user interaction
     * but is cut to the start of its day, so that day counts in full. Unknown counts as now.
     */
    private fun lastActiveMs(usm: UsageStatsManager, u: UsageStats?, pkg: String): Long {
        val anyComponent =
            try {
                usm.getLastTimeAnyComponentUsed(pkg)
            } catch (e: RuntimeException) {
                Log.w(TAG, "last use of $pkg unknown; not paused", e)
                return Long.MAX_VALUE
            }
        return maxOf(
            u?.lastTimeUsed ?: 0L,
            u?.lastTimeVisible ?: 0L,
            u?.lastTimeForegroundServiceUsed ?: 0L,
            u?.lastTimeAnyComponentUsed ?: 0L,
            if (anyComponent > 0) anyComponent + DAY_MS else 0L,
        )
    }

    /**
     * True when [pkg] does its job without being opened, or is looked after by Ichthys Apps: it
     * may set exact alarms (an alarm clock, a calendar, a medication reminder), has a widget on a
     * home screen, or Ichthys Apps installed it or owns its updates. A paused app's alarms and
     * widgets stop, and Ichthys Apps' apps are the ROM's own recommendations. Anything that can't
     * be read counts as true.
     */
    private fun worksUnopened(pkg: String): Boolean =
        try {
            val pm = context.packageManager
            val userId = context.userId
            val source = pm.getInstallSourceInfo(pkg)
            pm.checkPermission(android.Manifest.permission.USE_EXACT_ALARM, pkg) ==
                PackageManager.PERMISSION_GRANTED ||
                context
                    .getSystemService(AlarmManager::class.java)
                    ?.hasScheduleExactAlarm(pkg, userId) != false ||
                AppWidgetManager.getInstance(context)?.isBoundWidgetPackage(pkg, userId) == true ||
                source.installingPackageName == ICHTHYS_APPS_PACKAGE ||
                source.updateOwnerPackageName == ICHTHYS_APPS_PACKAGE
        } catch (e: PackageManager.NameNotFoundException) {
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "can't tell whether $pkg works unopened; not paused", e)
            true
        }

    /**
     * Packages in the Ichthys Apps catalog: the apps the ROM recommends and sets up (default
     * camera, file manager, …), however they were installed. No catalog means none; an unreadable
     * one throws, so the caller skips the run.
     */
    private fun catalogPackages(): Set<String> {
        val file = File(APPS_CATALOG)
        if (!file.exists()) return emptySet()
        return try {
            val apps = JSONObject(file.readText()).optJSONArray("apps") ?: return emptySet()
            (0 until apps.length())
                .mapNotNull { i -> apps.optJSONObject(i)?.optString("package") }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (e: IOException) {
            throw IllegalStateException("$APPS_CATALOG unreadable", e)
        } catch (e: JSONException) {
            throw IllegalStateException("$APPS_CATALOG unreadable", e)
        }
    }

    /**
     * Packages doing their job in the background without the user opening them. Throws when one
     * of the sources can't be read (the caller then pauses nothing).
     */
    private fun backgroundRoles(): Set<String> {
        val out = HashSet<String>()
        val cr = context.contentResolver
        for (key in
            listOf(
                Settings.Secure.ENABLED_INPUT_METHODS,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "enabled_notification_listeners",
            )) {
            Settings.Secure.getString(cr, key)
                ?.split(':', ';')
                ?.mapNotNull { it.substringBefore('/').takeIf { p -> p.isNotBlank() } }
                ?.let { out += it }
        }
        context.packageManager
            .resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
            ?.activityInfo
            ?.packageName
            ?.let { out += it }
        context
            .getSystemService(android.app.admin.DevicePolicyManager::class.java)
            ?.activeAdmins
            ?.forEach { out += it.packageName }
        // The always-on VPN and the tap-to-pay app (the setting, and the Wallet role that
        // replaced it) run for the user all the time without being opened.
        Settings.Secure.getString(cr, Settings.Secure.ALWAYS_ON_VPN_APP)
            ?.takeIf { it.isNotBlank() }
            ?.let { out += it }
        Settings.Secure.getString(cr, Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT)
            ?.let(ComponentName::unflattenFromString)
            ?.let { out += it.packageName }
        context.getSystemService(RoleManager::class.java)?.let { roles ->
            if (roles.isRoleAvailable(RoleManager.ROLE_WALLET))
                out += roles.getRoleHolders(RoleManager.ROLE_WALLET)
        }
        // Companion apps keep a watch, earbuds or a car connected.
        context
            .getSystemService(CompanionDeviceManager::class.java)
            ?.allAssociations
            ?.forEach { out += it.packageName }
        out += catalogPackages()
        return out
    }

    private fun suspendDormant(pkg: String, label: String, days: Int) {
        val dialog =
            SuspendDialogInfo.Builder()
                .setTitle("$label is paused")
                .setMessage(
                    "You haven't used $label for $days days, so Guard cut its network. Review " +
                        "it to use it again."
                )
                .setNeutralButtonText("Review")
                .setNeutralButtonAction(SuspendDialogInfo.BUTTON_ACTION_MORE_DETAILS)
                .build()
        try {
            context.packageManager.setPackagesSuspended(arrayOf(pkg), true, null, null, dialog)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not pause $pkg", e)
        }
    }

    /** Undoes auto-tighten: network back to the app's own policy, suspension lifted. */
    fun restoreDormant(pkg: String) {
        if (_policies.value.policyFor(pkg).dormant) journal(JournalKind.WAKE, null, pkg)
        updatePolicy(pkg) { it.copy(dormant = false) }
        try {
            context.packageManager.setPackagesSuspended(
                arrayOf(pkg),
                false,
                null,
                null,
                null as SuspendDialogInfo?,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not resume $pkg", e)
        }
    }

    // ---- fresh identity -------------------------------------------------------------------

    /**
     * "Reset identity" (W8b decision 12): the stock clear-data path (which also stops the app),
     * then SettingsProvider's SSAID reset for the package, so the app starts as on a new phone.
     * [done] gets (data cleared, Android ID reset) on a binder thread, or on the caller's when the
     * request couldn't be made; callers move to the main thread themselves before touching UI.
     */
    fun resetIdentity(pkg: String, done: (Boolean, Boolean) -> Unit) {
        val am = context.getSystemService(ActivityManager::class.java)
        val started =
            try {
                am.clearApplicationUserData(
                    pkg,
                    object : IPackageDataObserver.Stub() {
                        override fun onRemoveCompleted(packageName: String?, succeeded: Boolean) {
                            done(succeeded, succeeded && resetSsaid(pkg))
                        }
                    },
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "clear data of $pkg failed", e)
                false
            }
        if (!started) done(false, false)
    }

    /**
     * Asks SettingsProvider to drop and re-key [pkg]'s SSAID (Settings.Secure.ANDROID_ID as the
     * app sees it). Method [CALL_RESET_SSAID], arg = package, "_user" = user id; the provider
     * answers {"result": true} when the next read returns a fresh value.
     */
    private fun resetSsaid(pkg: String): Boolean =
        try {
            context.contentResolver
                .call(
                    Settings.Secure.CONTENT_URI,
                    CALL_RESET_SSAID,
                    pkg,
                    Bundle().apply { putInt("_user", context.userId) },
                )
                ?.getBoolean("result", false) == true
        } catch (e: RuntimeException) {
            Log.w(TAG, "SSAID reset of $pkg failed", e)
            false
        }

    /**
     * IchthysOS: the whole setup as JSON: rules, lockdown, every app's policy (with its preset),
     * schedules and the other automation, every per-app shield list and the storage scopes as
     * raw Settings values, the master switches and the fake place.
     */
    fun exportJson(): String {
        val cr = context.contentResolver
        val lists = HashMap<String, String>()
        for (key in ShieldTable.ALL_LIST_KEYS) {
            Settings.Secure.getString(cr, key)?.let { lists[key] = it }
        }
        val masters = HashMap<String, Int>()
        for (sw in ShieldTable.MASTER_SWITCHES) masters[sw.key] = if (sw.isOn(cr)) 1 else 0
        val fake = HashMap<String, String>()
        for (key in
            listOf(
                ShieldTable.KEY_SPOOF_ON,
                ShieldTable.KEY_SPOOF_FAKE,
                ShieldTable.KEY_SPOOF_FAKE_NAME,
            )) {
            Settings.Secure.getString(cr, key)?.let { fake[key] = it }
        }
        return _rules.value
            .copy(
                policies = _policies.value,
                automation = _automation.value,
                shieldLists = lists,
                masters = masters,
                fakePlace = fake,
            )
            .toJson()
    }

    /** @return number of rules imported. Throws IllegalArgumentException on bad input. */
    fun importJson(text: String): Int {
        val imported = RuleBook.fromJson(text)
        val merged = _rules.value.copy(policies = _policies.value).merge(imported)
        // The stores keep only what they own; the rest is applied to Settings below.
        saveRules(merged.copy(automation = null, shieldLists = emptyMap(), masters = emptyMap(),
            fakePlace = emptyMap()))
        synchronized(policyLock) {
            // Imported policies overlay local ones: apply each exception that changed, one
            // package at a time. Nothing else in the shared keys changes.
            val local = _policies.value
            for (p in imported.policies.policies.values) {
                val before = local.policyFor(p.packageName)
                for (key in OsSettingKey.entries) {
                    if (key.selector(before) != key.selector(p)) {
                        _osWritable.value = bridge.setListed(key, p.packageName, key.selector(p))
                    }
                }
            }
            _policies.value = merged.policies
            savePolicies(merged.policies)
        }
        // IchthysOS: the rest of the setup. Lists replace the local value key by key (a backup
        // is a whole setup, not a merge); unknown keys are skipped.
        val cr = context.contentResolver
        try {
            for ((key, value) in imported.shieldLists) {
                if (key in ShieldTable.ALL_LIST_KEYS) Settings.Secure.putString(cr, key, value)
            }
            for ((key, on) in imported.masters) {
                ShieldTable.MASTER_SWITCHES.firstOrNull { it.key == key }?.setOn(cr, on != 0)
            }
            for ((key, value) in imported.fakePlace) {
                if (
                    key == ShieldTable.KEY_SPOOF_ON ||
                        key == ShieldTable.KEY_SPOOF_FAKE ||
                        key == ShieldTable.KEY_SPOOF_FAKE_NAME
                )
                    Settings.Secure.putString(cr, key, value)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS; shield lists not imported", e)
            _osWritable.value = false
        }
        imported.automation?.let { auto -> updateAutomation { auto } }
        syncOsListsFromSettings()
        scope.launch {
            pushAllNetworkPolicies()
            pushBehaviours()
        }
        journal(JournalKind.RULES_APPLIED, "Backup imported")
        return imported.rules.size
    }

    // ---- IchthysOS: the app page's controls and presets ---------------------------------------

    /** The screen-off control's value: the two old toggles read as one three-way. */
    fun screenOffMode(pkg: String): ScreenOffMode {
        val p = _policies.value.policyFor(pkg)
        return when {
            p.screenOffBlock -> ScreenOffMode.OFFLINE
            pkg in _automation.value.screenOffExempt -> ScreenOffMode.STAY_ONLINE
            else -> ScreenOffMode.FOLLOW
        }
    }

    fun setScreenOffMode(pkg: String, mode: ScreenOffMode) {
        updatePolicy(pkg) { it.copy(screenOffBlock = mode == ScreenOffMode.OFFLINE) }
        val exempt = mode == ScreenOffMode.STAY_ONLINE
        if ((pkg in _automation.value.screenOffExempt) != exempt) {
            updateAutomation {
                it.copy(
                    screenOffExempt =
                        if (exempt) it.screenOffExempt + pkg else it.screenOffExempt - pkg
                )
            }
        }
    }

    /**
     * The location control: the two Settings lists read as one three-way. An app on both lists
     * (possible with the old independent toggles) reads NONE, because that is what the platform
     * does (LocationProviderManager checks "No location" first).
     */
    fun locationMode(pkg: String): LocationMode =
        when {
            IchthysProtect.isListed(context, ShieldTable.KEY_LOCATION_NOFIX, pkg) ->
                LocationMode.NONE
            IchthysProtect.isListed(context, ShieldTable.KEY_LOCATION_REAL, pkg) -> LocationMode.REAL
            else -> LocationMode.FAKE
        }

    /** Writes both lists so they can't contradict each other. */
    fun setLocationMode(pkg: String, mode: LocationMode) {
        IchthysProtect.setListed(
            context,
            ShieldTable.KEY_LOCATION_NOFIX,
            pkg,
            mode == LocationMode.NONE,
        )
        IchthysProtect.setListed(
            context,
            ShieldTable.KEY_LOCATION_REAL,
            pkg,
            mode == LocationMode.REAL,
        )
    }

    fun scanMode(pkg: String): ScanMode =
        if (IchthysProtect.isListed(context, ShieldTable.KEY_SCAN_EMPTY, pkg)) ScanMode.EMPTY
        else ScanMode.REAL

    fun setScanMode(pkg: String, mode: ScanMode) =
        IchthysProtect.setListed(context, ShieldTable.KEY_SCAN_EMPTY, pkg, mode == ScanMode.EMPTY)

    /** True when the fake place is on for everyone not on the real list (Gear › Fake place). */
    fun fakePlaceOn(): Boolean =
        Settings.Secure.getString(context.contentResolver, ShieldTable.KEY_SPOOF_ON) != "0"

    /** The effective state of every control of [pkg], for the preset comparison. */
    fun appControls(pkg: String): AppControls {
        val p = _policies.value.policyFor(pkg)
        val otherAllowed =
            ShieldTable.SHIELDS
                .filter { s ->
                    s.listedMeansReal &&
                        s.id != "contacts" &&
                        s.id != "sensors" &&
                        s.id != "location_real" &&
                        !s.isProtected(context, pkg)
                }
                .map { it.label }
        return AppControls(
            network = p.network,
            screenOff = screenOffMode(pkg),
            backgroundOff = p.backgroundNetworkOff,
            lan = p.localNetwork,
            location = locationMode(pkg),
            scans = scanMode(pkg),
            contactsReal = IchthysProtect.isListed(context, ShieldTable.KEY_CONTACTS, pkg),
            contactsPicked = ShieldTable.isContactsPicked(context, pkg),
            sensorsReal = p.sensorsExempt,
            nfcBlocked = IchthysProtect.isListed(context, ShieldTable.KEY_NFC_BLOCKED, pkg),
            lockdownKeep = pkg in _rules.value.lockdown.allowlist,
            otherAllowed = otherAllowed,
        )
    }

    /** The controls of [pkg] that differ from its preset; empty for Custom or an exact match. */
    fun presetDifferences(pkg: String): Set<PresetControl> {
        val spec = _policies.value.policyFor(pkg).preset.spec ?: return emptySet()
        return appControls(pkg).differences(spec)
    }

    /**
     * IchthysOS: applies [preset] to [pkg] in one go: the whole [AppPolicy] and every Settings
     * list key the preset decides (network, screen-off, background, local network, location,
     * scans, contacts, sensors, NFC, lockdown allowlist; every other allow list cleared, so
     * "everything else shielded" holds). Then one push to the service and one journal entry.
     * [AppPreset.CUSTOM] only records the choice; nothing else changes.
     */
    fun applyPreset(pkg: String, preset: AppPreset) {
        val spec = preset.spec
        if (spec == null) {
            updatePolicy(pkg) { it.copy(preset = AppPreset.CUSTOM) }
            journal(JournalKind.PRESET, "Custom", pkg)
            return
        }
        synchronized(policyLock) {
            // Every allow list the preset doesn't grant: off. ":picked" entries too (set below).
            for (key in ShieldTable.ALLOW_LIST_KEYS) {
                if (key == ShieldTable.KEY_SENSORS || key == ShieldTable.KEY_CONTACTS) continue
                if (key == ShieldTable.KEY_LOCATION_REAL) continue
                IchthysProtect.setListed(context, key, pkg, false)
            }
            ShieldTable.setContactsPicked(context, pkg, spec.contactsPicked)
            setLocationMode(pkg, spec.location)
            setScanMode(pkg, spec.scans)
            IchthysProtect.setListed(context, ShieldTable.KEY_NFC_BLOCKED, pkg, spec.nfcBlocked)
        }
        // The policy (mirrors contacts, clipboard and sensors into their lists itself).
        val wasDormant = _policies.value.policyFor(pkg).dormant
        updatePolicy(pkg) {
            it.copy(
                network = spec.network,
                contactsAllowed = false,
                clipboardAllowed = false,
                sensorsExempt = spec.sensorsReal,
                screenOffBlock = spec.screenOff == ScreenOffMode.OFFLINE,
                backgroundNetworkOff = spec.backgroundOff,
                localNetwork = spec.lan,
                dormant = false,
                preset = preset,
            )
        }
        if (wasDormant) restoreDormant(pkg)
        val exempt = spec.screenOff == ScreenOffMode.STAY_ONLINE
        if ((pkg in _automation.value.screenOffExempt) != exempt) {
            updateAutomation {
                it.copy(
                    screenOffExempt =
                        if (exempt) it.screenOffExempt + pkg else it.screenOffExempt - pkg
                )
            }
        }
        if ((pkg in _rules.value.lockdown.allowlist) != spec.lockdownKeep)
            setLockdownAllowed(pkg, spec.lockdownKeep)
        syncOsListsFromSettings()
        journal(JournalKind.PRESET, preset.label, pkg)
    }

    /** Apps held in Guard's first-launch quarantine (suspended by Settings, not yet released). */
    fun quarantinedApps(): List<AppInfo> {
        val pm = context.packageManager
        return _apps.value.filter { a ->
            !a.isSystem &&
                runCatching { pm.isPackageQuarantined(a.packageName) }.getOrDefault(false) &&
                !_policies.value.policyFor(a.packageName).dormant
        }
    }

    /**
     * IchthysOS: the context the Settings lists are read with (the UI's helpers share it so the
     * list reads happen against the same user as the writes).
     */
    fun contextForLists(): Context = context

    /** Apps on the real-location list (the fake place doesn't apply to them). */
    fun realLocationApps(): Set<String> =
        IchthysProtect.readPackages(context, ShieldTable.KEY_LOCATION_REAL).filter { ':' !in it }.toSet()

    /** The master shields (home card): how many of the rows are fully on. */
    fun masterShieldsOn(): Pair<Int, Int> {
        val cr = context.contentResolver
        val rows = ShieldTable.MASTER_ROWS
        return rows.count { r -> r.switches.all { it.isOn(cr) } } to rows.size
    }

    /**
     * Downloads enabled blocklist sources into filesDir/lists and records count + sha256. Phase 2
     * hands these to the platform DNS hook. IchthysOS: nothing reads them yet (not even the
     * matcher, which uses the bundled tracker DB), so the Rules page hides the sources while
     * [com.android.settings.ichthys.guard.model.HostRules.ENFORCED] is false.
     */
    suspend fun updateSources(): List<String> =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            val dir = File(dataDir, "lists").apply { mkdirs() }
            var book = _rules.value
            for (s in book.sources.filter { it.enabled }) {
                val updated =
                    try {
                        val conn = URL(s.url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 30_000
                        val bytes = conn.inputStream.use { it.readBytes() }
                        conn.disconnect()
                        File(dir, "${s.id}.txt").writeBytes(bytes)
                        val text = String(bytes, Charsets.UTF_8)
                        val count =
                            if (s.id == "exodus")
                                Regex("\"network_signature\"").findAll(text).count()
                            else
                                text.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
                        val sha =
                            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                                "%02x".format(it)
                            }
                        Log.i("IchthysGuard", "source ${s.id}: $count entries sha256=$sha")
                        s.copy(
                            lastUpdatedMs = System.currentTimeMillis(),
                            entryCount = count,
                            lastError = null,
                        )
                    } catch (e: Exception) {
                        errors += "${s.name}: ${e.message}"
                        s.copy(lastError = e.message ?: e.javaClass.simpleName)
                    }
                book = book.copy(sources = book.sources.map { if (it.id == s.id) updated else it })
            }
            saveRules(book)
            errors
        }

    companion object {
        private const val TAG = "IchthysGuard"
        private const val DAY_MS = 24 * 60 * 60_000L
        /** Ichthys Apps: the installer and updater of the catalog's apps. */
        private const val ICHTHYS_APPS_PACKAGE = "org.ichthys.apps"
        /** Its catalog, generated by the ROM build (see packages/apps/IchthysApps). */
        private const val APPS_CATALOG = "/product/etc/ichthys/apps-catalog.json"
        /** SettingsProvider call method that resets one package's SSAID. */
        const val CALL_RESET_SSAID = "ichthys_reset_ssaid"
        const val MAX_EVENTS = 5_000
        private const val PAGE = 1_000
        private const val MAX_BACKFILL_PAGES = 60
        private const val KEY_PAUSED_UNTIL = "paused_until"
        private const val KEY_SENSORS_MIGRATED = "sensor_exemptions_migrated"
        private const val KEY_LOCKDOWN_SEEDED = "lockdown_allowlist_seeded"
        /** IchthysOS: the schedules active at the last tick, for the journal's start/end lines. */
        private const val KEY_ACTIVE_SCHEDULES = "active_schedules"
        /** The tester app Guard launches for a verification run; user-installed only. */
        const val PROBE_PACKAGE = "org.ichthys.probe"
        private const val DATA_DIR = "ichthys_guard"
        private const val PREFS_NAME = "ichthys_guard"
        private val DEFAULT_LOCKDOWN_HINTS =
            listOf("dialer", "messaging", "contacts", "signal", "org.ichthys")

        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

        /**
         * IchthysOS: Guard runs for the system user only. IchthysGuardService holds one
         * device-wide table, so another user's or profile's Settings (Private Space, a work
         * profile) must not push its own app list over the owner's rules. Other users get no
         * Guard page, receivers or alarms, and [ServiceBackend] drops their writes.
         */
        @JvmStatic fun isGuardUser(): Boolean = UserHandle.myUserId() == UserHandle.USER_SYSTEM

        @Volatile private var instance: GuardRepository? = null

        @JvmStatic
        fun get(context: Context): GuardRepository =
            instance
                ?: synchronized(this) {
                    instance
                        ?: create(context.applicationContext).also {
                            instance = it
                            it.start()
                        }
                }

        private fun create(app: Context): GuardRepository {
            // Credential-protected storage: Guard's state must not be readable before unlock.
            val ctx = app.createCredentialProtectedStorageContext()
            val inspector = AppInspector(app)
            val matcher =
                TrackerMatcher(
                    TrackerMatcher.parse(
                        app.resources
                            .openRawResource(R.raw.ichthys_guard_trackers)
                            .bufferedReader()
                            .use { it.readText() }
                    )
                )
            return GuardRepository(ctx, ServiceBackend(), inspector, matcher)
        }
    }
}
