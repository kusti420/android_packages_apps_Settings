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

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.settings.ichthys.guard.os.PickedContacts
import com.android.settings.ichthys.guard.ui.theme.Guard
import com.android.settings.ichthys.guard.ui.theme.GuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IchthysOS Guard: picks the contacts an app in "picked" mode may read. Opened from the app's
 * Personal data sheet, after the Messenger preset, and by the permission dialog's "Allow with
 * picked contacts" (PermissionController, through [PickedContacts.ACTION_PICK]; the manifest
 * guards that entry with GRANT_RUNTIME_PERMISSIONS, so only the permission dialog and Settings
 * itself can open it). Saving stores the set in ContactsProvider and puts the app in picked
 * mode; leaving without saving changes nothing.
 */
class ContactsPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // IchthysOS: no app's overlay over the picker (stock Settings pages hide them too).
        lifecycle.addObserver(
            com.android.settingslib.core.lifecycle.HideNonSystemOverlayMixin(this)
        )
        val pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        if (pkg.isNullOrEmpty()) {
            finish()
            return
        }
        val label =
            try {
                packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
            } catch (e: Exception) {
                pkg
            }
        setContent {
            GuardTheme {
                val t = Guard.tokens
                Scaffold(containerColor = t.ink, contentColor = t.text) { padding ->
                    PickerScreen(pkg, label, Modifier.padding(padding)) { finish() }
                }
            }
        }
    }
}

private class PickerState(val book: List<PickedContacts.Entry>, initial: Set<String>) {
    val selected = mutableStateListOf<String>().apply { addAll(initial) }
}

@Composable
private fun PickerScreen(pkg: String, label: String, modifier: Modifier, onDone: () -> Unit) {
    val context = LocalContext.current
    val t = Guard.tokens
    val scope = rememberCoroutineScope()
    val state by
        produceState<PickerState?>(null, pkg) {
            value =
                withContext(Dispatchers.IO) {
                    val book = PickedContacts.loadBook(context)
                    PickerState(book, PickedContacts.currentKeys(context, PickedContacts.get(context, pkg), book))
                }
        }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            PageHeader("Contacts for $label", onDone,
                "$label reads only the contacts you pick here; every other contact stays hidden from it.")
        }
        val s = state
        if (s == null) {
            Text("Loading contacts…", color = t.muted, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${s.selected.size} of ${s.book.size} picked",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.weight(1f),
                )
                if (s.book.any { it.starred })
                    TextButton(onClick = {
                        for (e in s.book) if (e.starred && e.lookup !in s.selected) s.selected += e.lookup
                    }) { Text("Pick starred", color = t.accent) }
                // Always laid out (disabled while nothing is picked): appearing on the first tick,
                // it pushed the list down under the next tap.
                TextButton(onClick = { s.selected.clear() }, enabled = s.selected.isNotEmpty()) {
                    Text("Clear", color = if (s.selected.isNotEmpty()) t.accent else t.muted)
                }
            }
        }
        val needle = query.trim()
        val shown =
            remember(s, needle) {
                val digits = needle.filter(Char::isDigit)
                if (needle.isEmpty()) s.book
                else s.book.filter {
                    it.name.contains(needle, ignoreCase = true) ||
                        (digits.isNotEmpty() && it.number?.filter(Char::isDigit)?.contains(digits) == true)
                }
            }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (s.book.isEmpty())
                item { Text("The address book is empty.", color = t.muted, modifier = Modifier.padding(vertical = 8.dp)) }
            items(shown, key = { it.lookup }) { e ->
                val checked = e.lookup in s.selected
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(value = checked, role = Role.Checkbox) { on ->
                            if (on) s.selected += e.lookup else s.selected -= e.lookup
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(e)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.number != null)
                            Text(e.number, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 1)
                    }
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) { Text("Cancel", color = t.accent) }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent),
                onClick = {
                    saving = true
                    val picks = s.selected.toList()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { PickedContacts.set(context, pkg, picks) }
                        if (ok) {
                            onDone()
                        } else {
                            saving = false
                            Toast.makeText(context, "Couldn't save the picked contacts", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) { Text("Save") }
        }
    }
}

@Composable
private fun Avatar(e: PickedContacts.Entry) {
    val context = LocalContext.current
    val t = Guard.tokens
    val photo by
        produceState<ImageBitmap?>(null, e.thumbnail) {
            val uri = e.thumbnail ?: return@produceState
            value =
                withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                    } catch (x: Exception) {
                        null
                    }
                }
        }
    val p = photo
    if (p != null) {
        Image(p, null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(40.dp).clip(CircleShape).background(t.accentTint), contentAlignment = Alignment.Center) {
            Text(e.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = t.accent)
        }
    }
}
