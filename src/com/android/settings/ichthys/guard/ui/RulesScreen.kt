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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.CustomRule
import com.android.settings.ichthys.guard.model.HostPattern
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.RuleAction
import com.android.settings.ichthys.guard.os.AppInfo
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Custom rules, per-app host lists, blocklist sources, lockdown allowlist, import/export. */
@Composable
fun RulesScreen(
    vm: GuardViewModel,
    modifier: Modifier = Modifier,
    onOpenAutomation: () -> Unit = {},
) {
    val repo = vm.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val book by repo.rules.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val events by repo.events.collectAsStateWithLifecycle()
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }

    var editing by remember { mutableStateOf<CustomRule?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var showAllowlist by remember { mutableStateOf(false) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null)
                scope.launch {
                    message =
                        try {
                            val json = repo.exportJson()
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(uri, "wt")!!.use {
                                    it.write(json.toByteArray(Charsets.UTF_8))
                                }
                            }
                            "Exported rules and app settings"
                        } catch (e: Exception) {
                            "Export failed: ${e.message}"
                        }
                }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null)
                scope.launch {
                    message =
                        try {
                            val text =
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)!!.use {
                                        it.readBytes().toString(Charsets.UTF_8)
                                    }
                                }
                            val n = withContext(Dispatchers.Default) { repo.importJson(text) }
                            "Imported $n rules"
                        } catch (e: IllegalArgumentException) {
                            e.message ?: "Not a Guard rules file"
                        } catch (e: Exception) {
                            "Import failed: ${e.message}"
                        }
                }
        }

    LazyColumn(
        modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        message?.let { m ->
            item {
                GuardCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { message = null }) { Text("Dismiss") }
                    }
                }
            }
        }

        item {
            val auto by repo.automation.collectAsStateWithLifecycle()
            val active = auto.activeProfiles()
            GuardCard(Modifier.clickable(onClick = onOpenAutomation), title = "Automation") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Profiles, alerts, screen-off and auto-tighten",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (active.isEmpty()) "${auto.profiles.count { it.enabled }} profiles on"
                            else "Active now: " + active.joinToString { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = Guard.tokens.muted,
                        )
                    }
                    Text("›", color = Guard.tokens.muted)
                }
            }
        }

        // IchthysOS: custom rules aren't enforced yet (HostRules). Saved ones stay listed so
        // they can be deleted; new ones aren't offered.
        if (HostRules.ENFORCED || book.rules.isNotEmpty()) {
            item {
                GuardCard(title = "Custom rules") {
                    if (!HostRules.ENFORCED) {
                        Text(
                            HostRules.NOT_ENFORCED_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = Guard.tokens.muted,
                        )
                    }
                    if (book.rules.isEmpty()) {
                        Text(
                            "No custom rules. Rules match an app (or all apps) and a host pattern, and win over the tracker list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Guard.tokens.muted,
                        )
                    }
                    book.rules
                        .sortedWith(
                            compareBy({ it.packageName != CustomRule.ANY_APP }, { it.pattern })
                        )
                        .forEach { r ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { editing = r }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.pattern,
                                        style =
                                            MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = Guard.tokens.mono
                                            ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Tag(
                                            r.action.name,
                                            emphasized = r.action == RuleAction.ALLOW,
                                            danger = r.action == RuleAction.BLOCK,
                                        )
                                        Text(
                                            if (r.appliesToAll) "All apps"
                                            else labels[r.packageName] ?: r.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Guard.tokens.muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (r.note.isNotBlank()) {
                                        Text(
                                            r.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Guard.tokens.muted,
                                        )
                                    }
                                }
                                Switch(
                                    checked = r.enabled,
                                    onCheckedChange = { on -> repo.addRule(r.copy(enabled = on)) },
                                )
                                TextButton(onClick = { repo.removeRule(r.id) }) { Text("Delete") }
                            }
                            HorizontalDivider(color = Guard.tokens.hairline)
                        }
                    if (HostRules.ENFORCED) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                editing =
                                    CustomRule(
                                        GuardRepository.newId(),
                                        CustomRule.ANY_APP,
                                        "",
                                        RuleAction.BLOCK,
                                        createdMs = System.currentTimeMillis(),
                                    )
                            }
                        ) {
                            Text("Add rule")
                        }
                    }
                }
            }
        }

        val hostLists =
            policies.policies.values.filter {
                it.allowedHosts.isNotEmpty() || it.blockedHosts.isNotEmpty()
            }
        if (hostLists.isNotEmpty()) {
            item {
                GuardCard(title = "Per-app host lists") {
                    if (!HostRules.ENFORCED) {
                        Text(
                            HostRules.NOT_ENFORCED_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = Guard.tokens.muted,
                        )
                    }
                    hostLists
                        .sortedBy { labels[it.packageName] ?: it.packageName }
                        .forEach { p ->
                            Text(
                                labels[p.packageName] ?: p.packageName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            (p.blockedHosts.map { it to RuleAction.BLOCK } +
                                    p.allowedHosts.map { it to RuleAction.ALLOW })
                                .sortedBy { it.first }
                                .forEach { (host, action) ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Tag(
                                            action.name,
                                            emphasized = action == RuleAction.ALLOW,
                                            danger = action == RuleAction.BLOCK,
                                        )
                                        Text(
                                            host,
                                            Modifier.weight(1f),
                                            style =
                                                MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = Guard.tokens.mono
                                                ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        TextButton(
                                            onClick = {
                                                repo.updatePolicy(p.packageName) {
                                                    if (action == RuleAction.BLOCK)
                                                        it.copy(
                                                            blockedHosts = it.blockedHosts - host
                                                        )
                                                    else
                                                        it.copy(
                                                            allowedHosts = it.allowedHosts - host
                                                        )
                                                }
                                            }
                                        ) {
                                            Text("Remove")
                                        }
                                    }
                                }
                        }
                }
            }
        }

        // IchthysOS: nothing reads the downloaded lists until host rules are enforced
        // (HostRules), so the sources and "Update now" stay hidden.
        if (HostRules.ENFORCED) {
            item {
                GuardCard(title = "Blocklist sources") {
                    book.sources.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyLarge)
                                val info =
                                    buildList {
                                            if (s.lastUpdatedMs > 0)
                                                add("updated ${formatAgo(s.lastUpdatedMs)}")
                                            else add("never updated")
                                            if (s.entryCount > 0) add("${s.entryCount} entries")
                                        }
                                        .joinToString(" · ")
                                Text(
                                    info,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Guard.tokens.muted,
                                )
                                s.lastError?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Guard.tokens.danger,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Switch(
                                checked = s.enabled,
                                onCheckedChange = { repo.setSourceEnabled(s.id, it) },
                            )
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !updating,
                            onClick = {
                                updating = true
                                scope.launch {
                                    val errors =
                                        try {
                                            repo.updateSources()
                                        } catch (e: Exception) {
                                            listOf(e.message ?: "failed")
                                        }
                                    updating = false
                                    message =
                                        if (errors.isEmpty()) "Blocklists updated"
                                        else "Some sources failed: ${errors.joinToString("; ")}"
                                }
                            },
                        ) {
                            Text("Update now")
                        }
                        if (updating) {
                            CircularProgressIndicator(
                                Modifier.padding(start = 12.dp).size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    Text(
                        "The system hosts file (HaGeZi Light) always stays on. Sources here add to it per app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Guard.tokens.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item {
            GuardCard(title = "Lockdown allowlist") {
                Text(
                    "Apps that keep network when Lockdown is on (${book.lockdown.allowlist.size} selected).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Guard.tokens.muted,
                )
                TextButton(onClick = { showAllowlist = !showAllowlist }) {
                    Text(if (showAllowlist) "Hide apps" else "Choose apps")
                }
                if (showAllowlist) {
                    apps
                        .sortedWith(
                            compareBy(
                                { it.packageName !in book.lockdown.allowlist },
                                { it.label.lowercase() },
                            )
                        )
                        .forEach { a ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    repo.setLockdownAllowed(
                                        a.packageName,
                                        a.packageName !in book.lockdown.allowlist,
                                    )
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = a.packageName in book.lockdown.allowlist,
                                    onCheckedChange = { repo.setLockdownAllowed(a.packageName, it) },
                                )
                                Text(
                                    a.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                }
            }
        }

        item {
            GuardCard(title = "Backup") {
                Text(
                    "Export saves custom rules, sources, lockdown and every app's settings as JSON. Import merges: imported rules replace ones with the same app and pattern.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Guard.tokens.muted,
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { exportLauncher.launch("guard-rules.json") }) {
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        }
                    ) {
                        Text("Import")
                    }
                }
            }
        }
    }

    editing?.let { rule ->
        RuleEditor(
            initial = rule,
            apps = apps,
            recentHosts = remember(events) { events.mapNotNull { it.event.host }.distinct() },
            onDismiss = { editing = null },
            onSave = {
                repo.addRule(it)
                editing = null
            },
        )
    }
}

@Composable
private fun RuleEditor(
    initial: CustomRule,
    apps: List<AppInfo>,
    recentHosts: List<String>,
    onDismiss: () -> Unit,
    onSave: (CustomRule) -> Unit,
) {
    var pkg by remember { mutableStateOf(initial.packageName) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var action by remember { mutableStateOf(initial.action) }
    var note by remember { mutableStateOf(initial.note) }
    var menuOpen by remember { mutableStateOf(false) }
    val error = if (pattern.isEmpty()) null else HostPattern.validate(pattern)
    val matches =
        remember(pattern, recentHosts) {
            if (pattern.isBlank() || HostPattern.validate(pattern) != null) emptyList()
            else recentHosts.filter { HostPattern.matches(pattern, it) }
        }
    val appLabel =
        if (pkg == CustomRule.ANY_APP) "All apps"
        else apps.firstOrNull { it.packageName == pkg }?.label ?: pkg

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.pattern.isEmpty()) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("App")
                Column {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.heightIn(max = 360.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("All apps") },
                            onClick = {
                                pkg = CustomRule.ANY_APP
                                menuOpen = false
                            },
                        )
                        apps.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label) },
                                onClick = {
                                    pkg = a.packageName
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it.trim() },
                    label = { Text("Host pattern") },
                    placeholder = { Text("example.com, =exact.com, *.cdn.com, ads*") },
                    isError = error != null,
                    supportingText = {
                        Text(
                            error
                                ?: if (pattern.isBlank())
                                    "Domain includes subdomains; = for exact; * wildcard"
                                else
                                    "Matches ${matches.size} host${if (matches.size == 1) "" else "s"} seen recently"
                        )
                    },
                    singleLine = true,
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = Guard.tokens.mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (matches.isNotEmpty()) {
                    matches.take(5).forEach {
                        Text(
                            it,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = Guard.tokens.mono
                                ),
                            color = Guard.tokens.muted,
                        )
                    }
                    if (matches.size > 5)
                        Text(
                            "… and ${matches.size - 5} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = Guard.tokens.muted,
                        )
                }
                Spacer(Modifier.height(12.dp))
                Segmented(
                    listOf(RuleAction.ALLOW, RuleAction.BLOCK),
                    action,
                    { if (it == RuleAction.ALLOW) "Allow" else "Block" },
                    { action = it },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pattern.isNotBlank() && error == null,
                onClick = {
                    onSave(
                        initial.copy(
                            packageName = pkg,
                            pattern = HostPattern.normalize(pattern),
                            action = action,
                            note = note.trim(),
                        )
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
