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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.model.AppPreset
import com.android.settings.ichthys.guard.model.VerifyRun
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IchthysOS: the verify page. Starts a run (the tester app as a stand-in), shows the run in
 * progress and the last result per rule category with the Guard events each check produced,
 * the explicit manual step (the clipboard needs a real tap, in the probe's own screen), the
 * run history with the diff against the previous run, and a persistent warning when the probe
 * sits in /system.
 */
@Composable
fun VerifyScreen(vm: GuardViewModel, nav: GuardNav, forPackage: String?, modifier: Modifier = Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val history by repo.verify.collectAsStateWithLifecycle()
    val running by vm.verifier.current.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // The initial value is computed once (three binder calls), not on every recomposition.
    val initialProbe = remember { vm.verifier.probeStatus() }
    val probe by
        produceState(initialProbe, running) {
            value = withContext(Dispatchers.IO) { vm.verifier.probeStatus() }
        }
    // IchthysOS: the tester's Full access toggle (every permission, nothing shielded; two
    // confirmations), so no check is ever "not tested: needs a permission".
    val policies by repo.policies.collectAsStateWithLifecycle()
    val probeFull = policies.policyFor(PROBE).preset == AppPreset.FULL_ACCESS
    var confirmFullAccess by remember { mutableStateOf(false) }
    // A per-app page shows that app's runs only, never a general run under its header.
    val shown: VerifyRun? =
        running?.takeIf { !it.done }
            ?: history.runs.firstOrNull { it.done && it.forPackage == forPackage }
            ?: if (forPackage == null) history.runs.firstOrNull { it.done } else null
    val appLabel = forPackage?.let { repo.appFor(it)?.label ?: it }
    val now = System.currentTimeMillis()

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeader(
                if (appLabel != null) "Verify $appLabel" else "Verify my protection",
                nav.back,
                if (appLabel != null) "The tester app takes this app's setup and reports what it really gets"
                else "The tester app runs as a blocked app, a messenger and a maps app",
            )
        }
        if (!probe.installed) {
            item {
                Warn(
                    "IchthysProbe isn't installed",
                    "Build it with `m IchthysProbe IchthysProbeHelper` and install both with adb as user apps. Never put it into /system: a system app is exempt from the shields, so the results would be wrong.",
                )
            }
        } else if (probe.inSystem) {
            item {
                Warn(
                    "IchthysProbe is installed in /system",
                    "System apps are exempt from the data shields, so the shield results of every run are skewed. Uninstall it from /system and install the APK as a user app.",
                )
            }
        } else if (probe.quarantined) {
            item {
                Warn(
                    "IchthysProbe is still quarantined",
                    "Open it once from the launcher and release it (any preset), then come back and verify.",
                )
            }
        }
        if (probe.installed && !probe.inSystem) {
            item {
                GuardCard(title = "Tester app") {
                    ToggleRow(
                        "Full access for the tester",
                        probeFull,
                        if (probeFull)
                            "Every permission granted, nothing shielded: every check can run. A run as IchthysProbe itself must then show real data coming through."
                        else
                            "Grants IchthysProbe every permission it asks for and lifts every shield for it, after two confirmations. Without it, checks that need a permission stay untested.",
                        enabled = running?.done != false,
                    ) { on ->
                        if (on) confirmFullAccess = true
                        else
                            scope.launch(Dispatchers.IO) {
                                repo.resetToShielded(PROBE)
                                repo.revokeFullAccess(PROBE)
                            }
                    }
                    Text(
                        "Verify my protection still runs the tester as a blocked app, a messenger and a maps app: its permissions stay, only Guard's lists change for each phase, and its own setup comes back afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (probeFull && forPackage != PROBE) {
                        TextButton(onClick = { nav.openVerify(PROBE) }) {
                            Text("Verify with full access: real data must come through")
                        }
                    }
                }
            }
        }
        item {
            val busy = running?.done == false
            Button(
                onClick = {
                    message = null
                    scope.launch(Dispatchers.IO) {
                        val err = vm.verifier.start(forPackage)
                        if (err != null) withContext(Dispatchers.Main) { message = err }
                    }
                },
                enabled = !busy && probe.installed && !probe.quarantined,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(end = 10.dp).height(20.dp), strokeWidth = 2.dp, color = t.onAccent)
                    Text(running?.verdictLine ?: "Running…")
                } else Text(if (shown == null) "Run verification" else "Run again")
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.danger, modifier = Modifier.padding(top = 6.dp)) }
            Text(
                "Guard puts the tester on the stand-in's lists and policy, launches it, and puts its own setup back when the run ends. The tester's screen opens meanwhile; Guard comes back with the result.",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (shown != null) {
            item {
                GuardCard(title = if (shown.done) "Result · ${formatWhen(shown.finishedMs, now)}" else "Running") {
                    Text(
                        shown.verdictLine,
                        style = MaterialTheme.typography.titleLarge,
                        color = when {
                            !shown.done -> t.text
                            shown.passed -> t.accent
                            else -> t.danger
                        },
                    )
                    Text(
                        "Build ${shown.buildId} · probe ${shown.probeVersion}" +
                            (shown.forPackage?.let { " · as ${repo.appFor(it)?.label ?: it}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                        color = t.muted,
                    )
                    for (c in shown.byCategory()) {
                        KeyValue(
                            c.category.label,
                            if (c.ok) "pass · ${c.pass}" + (if (c.info > 0) " (+${c.info} info)" else "")
                            else "FAIL · ${c.fail} of ${c.pass + c.fail}",
                            danger = !c.ok,
                        )
                        for (f in c.failures.take(6))
                            Text("· $f", style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), color = t.danger, modifier = Modifier.padding(start = 12.dp))
                    }
                    if (shown.byCategory().isEmpty() && shown.done)
                        Text("No verdicts came back.", style = MaterialTheme.typography.bodySmall, color = t.danger)
                }
            }
            item {
                GuardCard(title = "Phases") {
                    for ((i, p) in shown.phases.withIndex()) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. ${p.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                when {
                                    !p.done -> Tag(if (p.startedMs == 0L) "WAITING" else "RUNNING", emphasized = true)
                                    p.error != null -> Tag("NO ANSWER", danger = true)
                                    p.failed -> Tag("FAIL", danger = true)
                                    else -> Tag("PASS", emphasized = true)
                                }
                            }
                            if (p.done) {
                                // Guard's own evidence for the phase: what its stream saw the probe do.
                                Text(
                                    "Guard saw ${quantity(p.attemptsSeen, "network attempt")} by the tester, ${p.attemptsBlocked} blocked" +
                                        (if (p.proofs.isNotEmpty()) " · shields: ${p.proofs.joinToString()}" else ""),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                                    color = t.muted,
                                )
                                p.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.danger) }
                            }
                        }
                    }
                }
            }
            if (shown.diff.isNotEmpty()) {
                item {
                    GuardCard(title = "Changed since the previous run") {
                        for (d in shown.diff.take(20)) Text(d, style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), color = t.muted)
                    }
                }
            }
        }
        item {
            GuardCard(title = "Needs your tap") {
                Text(
                    "The clipboard shield lets an app read the clipboard only right after a real tap. That can't be checked headless: open IchthysProbe and press \"Tap: paste test\". Green there means the first read works and a second read a second later doesn't.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                TextButton(
                    onClick = {
                        val ctx = repo.contextForLists()
                        ctx.packageManager.getLaunchIntentForPackage(com.android.settings.ichthys.guard.data.VerifyRunner.PROBE_PACKAGE)?.let {
                            ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    enabled = probe.installed,
                ) {
                    Text("Open IchthysProbe")
                }
            }
        }
        if (history.runs.any { it.done }) {
            item {
                GuardCard(title = "History") {
                    for (r in history.runs.filter { it.done }.take(VerifyRun_HISTORY_SHOWN)) {
                        KeyValue(
                            formatDateTime(r.finishedMs) + (r.forPackage?.let { " · ${repo.appFor(it)?.label ?: it}" } ?: ""),
                            (if (r.passed) "pass" else "FAIL") + " · ${r.buildId}",
                            danger = !r.passed,
                        )
                    }
                }
            }
        }
    }

    if (confirmFullAccess) {
        FullAccessConfirmation(
            appLabel = repo.appFor(PROBE)?.label ?: "IchthysProbe",
            packageName = PROBE,
            onConfirm = {
                confirmFullAccess = false
                scope.launch(Dispatchers.IO) {
                    repo.applyPreset(PROBE, AppPreset.FULL_ACCESS, grantPermissions = true)
                }
            },
            onDismiss = { confirmFullAccess = false },
        )
    }
}

private const val VerifyRun_HISTORY_SHOWN = 10
private const val PROBE = com.android.settings.ichthys.guard.data.VerifyRunner.PROBE_PACKAGE

@Composable
private fun Warn(title: String, text: String) {
    val t = Guard.tokens
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(t.radius),
        color = t.card,
        border = BorderStroke(t.hairlineWidth, t.danger),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = t.danger)
            Text(text, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
    }
}
