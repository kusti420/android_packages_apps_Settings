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

/**
 * IchthysOS: a verification run. Guard prepares the tester app (IchthysProbe, org.ichthys.probe,
 * user-installed only) as a stand-in for a preset, launches it, and the probe reports back what
 * an ordinary app really got. Each phase is one launch with one stand-in; "Verify my protection"
 * runs three phases (Untrusted, Messenger, Maps), "Verify this app" one with the app's preset.
 */

/** Guard's rule categories the probe's results are grouped into. */
@Serializable
enum class VerifyCategory(val label: String) {
    FIREWALL("Firewall"),
    LOCATION("Location"),
    SHIELDS("Shields"),
    LAN("Local network & hosts"),
    SERVICE("Service health"),
}

@Serializable
data class CategoryResult(
    val category: VerifyCategory,
    val pass: Int = 0,
    val fail: Int = 0,
    val info: Int = 0,
    val skip: Int = 0,
    /** "probe.id: why", one per failed expectation. */
    val failures: List<String> = emptyList(),
) {
    val ok: Boolean
        get() = fail == 0
}

/** One probe launch of a run. */
@Serializable
data class VerifyPhase(
    val standIn: AppPreset,
    val label: String,
    val startedMs: Long = 0,
    val finishedMs: Long = 0,
    val categories: List<CategoryResult> = emptyList(),
    /** Guard's own evidence: the probe's network attempts it saw, and how many it blocked. */
    val attemptsSeen: Int = 0,
    val attemptsBlocked: Int = 0,
    /** Shield proofs the platform recorded for the probe during this phase. */
    val proofs: List<String> = emptyList(),
    /** Null while waiting; "timeout" when the probe never answered. */
    val error: String? = null,
) {
    val done: Boolean
        get() = finishedMs != 0L

    val failed: Boolean
        get() = error != null || categories.any { !it.ok }
}

@Serializable
data class VerifyRun(
    val id: String,
    val startedMs: Long,
    val finishedMs: Long = 0,
    /** The app this run stands in for, or null for "Verify my protection". */
    val forPackage: String? = null,
    val phases: List<VerifyPhase>,
    val buildId: String = "",
    val probeVersion: String = "",
    /** The probe was found in /system, which skews shield results. */
    val probeInSystem: Boolean = false,
    /** Probes whose verdict changed since the previous run, "id: old → new". */
    val diff: List<String> = emptyList(),
) {
    val done: Boolean
        get() = finishedMs != 0L

    val passed: Boolean
        get() = done && phases.all { it.done && !it.failed }

    val pass: Int
        get() = phases.sumOf { p -> p.categories.sumOf { it.pass } }

    val fail: Int
        get() = phases.sumOf { p -> p.categories.sumOf { it.fail } }

    /** The phase waiting for the probe's answer, or null. */
    val current: VerifyPhase?
        get() = phases.firstOrNull { !it.done }

    val verdictLine: String
        get() =
            when {
                !done -> "Running · phase ${phases.count { it.done } + 1} of ${phases.size}"
                passed -> "Passed · ${pass} checks"
                else -> "Failed · ${fail} of ${pass + fail} checks"
            }

    /** Per category over all phases. */
    fun byCategory(): List<CategoryResult> =
        VerifyCategory.entries.mapNotNull { c ->
            val parts = phases.flatMap { p -> p.categories.filter { it.category == c } }
            if (parts.isEmpty()) null
            else
                CategoryResult(
                    c,
                    parts.sumOf { it.pass },
                    parts.sumOf { it.fail },
                    parts.sumOf { it.info },
                    parts.sumOf { it.skip },
                    parts.flatMap { it.failures },
                )
        }
}

/** The run history Guard keeps (newest first, bounded). */
@Serializable
data class VerifyHistory(val runs: List<VerifyRun> = emptyList()) {
    fun with(run: VerifyRun): VerifyHistory =
        VerifyHistory((listOf(run) + runs.filter { it.id != run.id }).take(MAX))

    val last: VerifyRun?
        get() = runs.firstOrNull { it.done }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX = 20
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = false
        }

        fun fromJson(text: String): VerifyHistory = json.decodeFromString(serializer(), text)
    }
}

/** IchthysOS: a journal entry Guard keeps across reboots (the service's ring is lost at boot). */
@Serializable
data class JournalEntry(
    val timeMs: Long,
    val uid: Int,
    /** JournalKind.code or, for a shield proof, ShieldProof.code + [SHIELD_BASE]. */
    val code: Int,
    val detail: String? = null,
    val packageName: String? = null,
    val appLabel: String? = null,
) {
    val isShield: Boolean
        get() = code >= SHIELD_BASE

    val journal: JournalKind?
        get() = if (isShield) null else JournalKind.fromCode(code)

    val shield: ShieldProof?
        get() = if (isShield) ShieldProof.fromCode(code - SHIELD_BASE) else null

    val title: String
        get() = journal?.label ?: shield?.let { "Shield · ${it.label}" } ?: "Event $code"

    companion object {
        const val SHIELD_BASE = 1000

        fun of(e: LabeledEvent): JournalEntry? {
            val ev = e.event
            val code =
                when (ev.type) {
                    EventType.JOURNAL -> ev.code
                    EventType.SHIELD -> ev.code + SHIELD_BASE
                    else -> return null
                }
            return JournalEntry(ev.timeMs, ev.uid, code, ev.detail, e.packageName, e.appLabel)
        }
    }
}

@Serializable
data class JournalLog(val entries: List<JournalEntry> = emptyList()) {
    /** Appends [new] entries not already present (same time, uid and code), newest last. */
    fun plus(new: List<JournalEntry>): JournalLog {
        if (new.isEmpty()) return this
        val seen = entries.mapTo(HashSet()) { Triple(it.timeMs, it.uid, it.code) }
        val fresh = new.filter { seen.add(Triple(it.timeMs, it.uid, it.code)) }
        if (fresh.isEmpty()) return this
        return JournalLog((entries + fresh).sortedBy { it.timeMs }.takeLast(MAX))
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX = 1_000
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = false
        }

        fun fromJson(text: String): JournalLog = json.decodeFromString(serializer(), text)
    }
}
