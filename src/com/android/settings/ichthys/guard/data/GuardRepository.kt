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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.android.settings.R
import com.android.settings.ichthys.guard.backend.GuardBackend
import com.android.settings.ichthys.guard.backend.ServiceBackend
import com.android.settings.ichthys.guard.model.AppPolicy
import com.android.settings.ichthys.guard.model.CustomRule
import com.android.settings.ichthys.guard.model.GuardEvent
import com.android.settings.ichthys.guard.model.HostPattern
import com.android.settings.ichthys.guard.model.LabeledEvent
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.PolicySet
import com.android.settings.ichthys.guard.model.RuleAction
import com.android.settings.ichthys.guard.model.RuleBook
import com.android.settings.ichthys.guard.model.StatsHistory
import com.android.settings.ichthys.guard.os.AppInfo
import com.android.settings.ichthys.guard.os.AppInspector
import com.android.settings.ichthys.guard.os.OsSettingsBridge
import com.android.settings.ichthys.guard.tracker.TrackerMatcher
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    private var uidToApp: Map<Int, AppInfo> = emptyMap()
    private var lastSeq = 0L

    fun start() {
        scope.launch {
            refreshApps()
            // The platform owns the pause deadline; adopt it (it survives app restarts there).
            if (backend.isLive)
                backend.getPausedUntil().let {
                    _pausedUntil.value = it
                    prefs.edit().putLong(KEY_PAUSED_UNTIL, it).apply()
                }
            // Platform seq restarts at 1 after a reboot: don't let the old high-water mark hide it.
            if (backend.getLatestSeq() < _stats.value.lastSeq) _stats.update { it.resetSeq() }
            backfill()
            backend.liveEvents().collect { ingest(it) }
        }
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

    /** Pages through the service's ring buffer (oldest first) and keeps the newest window. */
    private fun backfill() {
        lastSeq = maxOf(lastSeq, backend.getLatestSeq() - MAX_EVENTS)
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
        val labeled = fresh.map(::label)
        _events.update { (it + labeled).takeLast(MAX_EVENTS) }
        _stats.update { it.record(labeled) }
        val now = System.currentTimeMillis()
        if (now - statsSavedMs > 30_000) flushStats(now)
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

    fun updatePolicy(pkg: String, change: (AppPolicy) -> AppPolicy) {
        val next = _policies.value.update(pkg, change)
        _policies.value = next
        policyStore.save(next)
        _osWritable.value = bridge.apply(next)
        appFor(pkg)?.let { backend.setNetworkPolicy(it.uid, effectiveAccess(pkg)) }
    }

    fun effectiveAccess(pkg: String): NetworkAccess {
        val lock = _rules.value.lockdown
        if (lock.enabled && pkg !in lock.allowlist) return NetworkAccess.DENY
        return _policies.value.policyFor(pkg).network
    }

    /** Pushes every app's effective access to the platform (boot, lockdown changes, import). */
    fun pushAllNetworkPolicies() {
        if (_apps.value.isEmpty()) refreshApps()
        val table = HashMap<Int, NetworkAccess>()
        for (app in _apps.value) {
            val want = effectiveAccess(app.packageName)
            if (want == NetworkAccess.ALLOW) continue
            // Several packages can share a uid; the strictest wins.
            val prev = table[app.uid]
            if (prev == null || want.ordinal > prev.ordinal) table[app.uid] = want
        }
        // One atomic replace; the platform skips system-critical uids.
        if (table != backend.getNetworkPolicies()) backend.replaceNetworkPolicies(table)
    }

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
        _rules.value = book
        ruleStore.save(book)
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
        val allow = if (lock.allowlist.isEmpty()) defaultLockdownAllowlist() else lock.allowlist
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
        scope.launch { pushAllNetworkPolicies() }
    }

    fun setLockdownAllowed(pkg: String, allowed: Boolean) {
        val lock = _rules.value.lockdown
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

    fun exportJson(): String = _rules.value.copy(policies = _policies.value).toJson()

    /** @return number of rules imported. Throws IllegalArgumentException on bad input. */
    fun importJson(text: String): Int {
        val imported = RuleBook.fromJson(text)
        val merged = _rules.value.copy(policies = _policies.value).merge(imported)
        saveRules(merged)
        _policies.value = merged.policies
        policyStore.save(merged.policies)
        _osWritable.value = bridge.apply(merged.policies)
        scope.launch { pushAllNetworkPolicies() }
        return imported.rules.size
    }

    /**
     * Downloads enabled blocklist sources into filesDir/lists and records count + sha256. Phase 2
     * hands these to the platform DNS hook; for now they feed the UI and the matcher.
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
        const val MAX_EVENTS = 5_000
        private const val PAGE = 1_000
        private const val MAX_BACKFILL_PAGES = 60
        private const val KEY_PAUSED_UNTIL = "paused_until"
        private const val DATA_DIR = "ichthys_guard"
        private const val PREFS_NAME = "ichthys_guard"
        private val DEFAULT_LOCKDOWN_HINTS =
            listOf("dialer", "messaging", "contacts", "signal", "org.ichthys")

        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

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
