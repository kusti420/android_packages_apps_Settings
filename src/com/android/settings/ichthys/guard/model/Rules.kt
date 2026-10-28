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

package com.android.settings.ichthys.guard.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RuleAction {
    ALLOW,
    BLOCK,
}

/**
 * A custom rule: app × host pattern × action.
 *
 * Patterns:
 * - `example.com` matches example.com and every subdomain (label suffix, like the tracker DB);
 * - `=example.com` matches only that exact name;
 * - `*.example.com` matches subdomains only, not example.com itself;
 * - `ads*.example.com` / `*tracker*` are globs over the whole name (`*` never crosses nothing
 *   special; it is a plain wildcard);
 * - an IPv4/IPv6 literal matches connect events to that address.
 */
@Serializable
data class CustomRule(
    val id: String,
    /** Package name, or [ANY_APP] for every app. */
    val packageName: String = ANY_APP,
    val pattern: String,
    val action: RuleAction,
    val enabled: Boolean = true,
    val note: String = "",
    val createdMs: Long = 0,
) {
    val appliesToAll: Boolean
        get() = packageName == ANY_APP

    fun matches(pkg: String?, host: String): Boolean =
        enabled && (appliesToAll || pkg == packageName) && HostPattern.matches(pattern, host)

    companion object {
        const val ANY_APP = "*"
    }
}

object HostPattern {
    fun normalize(s: String): String = s.trim().trimEnd('.').lowercase()

    /** Returns an error message, or null if [pattern] is valid. */
    fun validate(pattern: String): String? {
        val p = normalize(pattern)
        if (p.isEmpty() || p == "=") return "Pattern is empty"
        if (p.any { it.isWhitespace() }) return "No spaces allowed"
        if (p.all { it == '*' || it == '.' }) return "Pattern would match everything"
        val body = p.removePrefix("=")
        if (!body.all { it.isLetterOrDigit() || it in ".-_*:" }) return "Invalid characters"
        return null
    }

    fun matches(pattern: String, host: String): Boolean {
        val p = normalize(pattern)
        val h = normalize(host)
        if (p.isEmpty() || h.isEmpty()) return false
        return when {
            p.startsWith("=") -> h == p.substring(1)
            p.startsWith("*.") && p.indexOf('*', 1) < 0 -> h.endsWith(p.substring(1))
            '*' in p -> globRegex(p).matches(h)
            else -> h == p || h.endsWith(".$p")
        }
    }

    /** Specificity for conflict resolution: longer literal part wins. */
    fun specificity(pattern: String): Int {
        val p = normalize(pattern)
        val literal = p.count { it != '*' && it != '=' }
        return literal * 4 + (if (p.startsWith("=")) 2 else 0) + (if ('*' in p) 0 else 1)
    }

    private val cache = HashMap<String, Regex>()

    private fun globRegex(p: String): Regex =
        synchronized(cache) {
            cache.getOrPut(p) { Regex(p.split('*').joinToString(".*") { Regex.escape(it) }) }
        }
}

enum class VerdictSource {
    NETWORK_POLICY,
    LOCKDOWN,
    CUSTOM_RULE,
    APP_ALLOWED_HOST,
    APP_BLOCKED_HOST,
    TRACKER,
    STRICT,
    DEFAULT,
    PAUSED,
}

/**
 * IchthysOS: whether the platform enforces per-host rules (tracker level, per-app host lists,
 * custom rules, blocklist sources). Phase 2, per-uid DNS filtering in IchthysGuardService, does
 * not exist yet: GuardBackend has no host method and nothing reads the downloaded lists. While
 * this is false the UI must not claim those rules block anything: verdicts report them as
 * [Verdict.notEnforced] instead of blocked, the controls that only create such rules are hidden,
 * and the cards that show saved ones say they aren't enforced. What does block today: the uid
 * firewall (network Block / Wi-Fi only, lockdown, screen-off, background) and the system-wide
 * hosts file.
 */
object HostRules {
    const val ENFORCED = false

    /** One line for every card that shows saved host or tracker rules. */
    const val NOT_ENFORCED_NOTE =
        "Not enforced yet: Guard saves host and tracker rules but doesn't apply them. Only the " +
            "system-wide blocklist (HaGeZi Light) blocks trackers, for every app."
}

/**
 * [blocked] is what happens to the connection. [notEnforced]: a saved host or tracker rule
 * ([source]) asks for a block that the platform doesn't apply yet (see [HostRules]).
 */
data class Verdict(
    val blocked: Boolean,
    val source: VerdictSource,
    val rule: CustomRule? = null,
    val notEnforced: Boolean = false,
)

/**
 * The single decision function for "would this app reach this host?". Mirrors what the platform
 * enforces (phase 1: uid network access; phase 2: per-host rules, not built yet, see [HostRules])
 * so the UI can explain a verdict and preview a rule before saving it.
 *
 * Order: pause > lockdown > network DENY > per-app host lists > custom rules (app-specific beats
 * global, then most specific pattern, BLOCK wins a tie) > STRICT > tracker DB > allow.
 */
object RuleEngine {
    /** A block asked for by a host-level rule: real only once [HostRules.ENFORCED]. */
    private fun hostBlock(source: VerdictSource, rule: CustomRule? = null) =
        Verdict(HostRules.ENFORCED, source, rule, notEnforced = !HostRules.ENFORCED)

    fun decide(
        pkg: String?,
        host: String,
        policy: AppPolicy?,
        rules: List<CustomRule>,
        isTracker: Boolean,
        lockdown: LockdownState = LockdownState(),
        paused: Boolean = false,
    ): Verdict {
        if (paused) return Verdict(false, VerdictSource.PAUSED)
        if (lockdown.enabled && pkg != null && pkg !in lockdown.allowlist) {
            return Verdict(true, VerdictSource.LOCKDOWN)
        }
        if (policy?.network == NetworkAccess.DENY)
            return Verdict(true, VerdictSource.NETWORK_POLICY)
        val h = HostPattern.normalize(host)
        if (policy != null) {
            if (policy.allowedHosts.any { HostPattern.matches(it, h) })
                return Verdict(false, VerdictSource.APP_ALLOWED_HOST)
            if (policy.blockedHosts.any { HostPattern.matches(it, h) })
                return hostBlock(VerdictSource.APP_BLOCKED_HOST)
        }
        bestRule(pkg, h, rules)?.let {
            return if (it.action == RuleAction.BLOCK) hostBlock(VerdictSource.CUSTOM_RULE, it)
            else Verdict(false, VerdictSource.CUSTOM_RULE, it)
        }
        val level = policy?.trackerBlock ?: TrackerBlockLevel.KNOWN_TRACKERS
        if (level == TrackerBlockLevel.STRICT) return hostBlock(VerdictSource.STRICT)
        if (isTracker && level == TrackerBlockLevel.KNOWN_TRACKERS)
            return hostBlock(VerdictSource.TRACKER)
        return Verdict(false, VerdictSource.DEFAULT)
    }

    fun bestRule(pkg: String?, host: String, rules: List<CustomRule>): CustomRule? =
        rules
            .filter { it.matches(pkg, host) }
            .maxWithOrNull(
                compareBy<CustomRule> { if (it.appliesToAll) 0 else 1 }
                    .thenBy { HostPattern.specificity(it.pattern) }
                    .thenBy { if (it.action == RuleAction.BLOCK) 1 else 0 }
            )
}

/** Panic switch: deny network to every user app except [allowlist]. */
@Serializable
data class LockdownState(
    val enabled: Boolean = false,
    val allowlist: Set<String> = emptySet(),
    val sinceMs: Long = 0,
)

/** A blocklist feed the tracker DB is built from. */
@Serializable
data class BlocklistSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val lastUpdatedMs: Long = 0,
    val entryCount: Int = 0,
    val lastError: String? = null,
)

/** The user's rules document: what Rules import/export reads and writes. */
@Serializable
data class RuleBook(
    val version: Int = 1,
    val rules: List<CustomRule> = emptyList(),
    val sources: List<BlocklistSource> = DEFAULT_SOURCES,
    val lockdown: LockdownState = LockdownState(),
    /** Policies are included in exports so a whole setup moves between devices. */
    val policies: PolicySet = PolicySet(),
) {
    fun upsert(rule: CustomRule): RuleBook =
        copy(rules = rules.filterNot { it.id == rule.id } + rule)

    fun remove(id: String): RuleBook = copy(rules = rules.filterNot { it.id == id })

    fun toJson(): String = json.encodeToString(serializer(), this)

    /**
     * Merges an imported book: rules are deduplicated by (app, pattern) with the import winning;
     * sources keep local update state; imported policies overlay local ones.
     */
    fun merge(imported: RuleBook): RuleBook {
        val key = { r: CustomRule -> r.packageName to HostPattern.normalize(r.pattern) }
        val importedKeys = imported.rules.map(key).toSet()
        val validImported = imported.rules.filter { HostPattern.validate(it.pattern) == null }
        val sources =
            sources.map { s ->
                imported.sources.firstOrNull { it.id == s.id }?.let { s.copy(enabled = it.enabled) }
                    ?: s
            }
        var pol = policies
        for (p in imported.policies.policies.values) pol = pol.with(p)
        return copy(
            rules = rules.filterNot { key(it) in importedKeys } + validImported,
            sources = sources,
            policies = pol,
        )
    }

    companion object {
        val DEFAULT_SOURCES =
            listOf(
                BlocklistSource(
                    "hagezi-light",
                    "HaGeZi Light (system baseline)",
                    "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/light.txt",
                    true,
                ),
                BlocklistSource(
                    "hagezi-pro",
                    "HaGeZi Pro",
                    "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt",
                    false,
                ),
                BlocklistSource(
                    "hagezi-tif",
                    "HaGeZi Threat Intelligence",
                    "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.mini.txt",
                    false,
                ),
                BlocklistSource(
                    "exodus",
                    "Exodus Privacy trackers",
                    "https://reports.exodus-privacy.eu.org/api/trackers",
                    true,
                ),
            )

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Parses an export. Throws IllegalArgumentException with a readable message. */
        fun fromJson(text: String): RuleBook =
            try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a Guard rules file: ${e.message?.take(120)}", e)
            }
    }
}
