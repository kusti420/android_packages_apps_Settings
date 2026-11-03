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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.settings.ichthys.guard.data.FullAccessGranter
import com.android.settings.ichthys.guard.ui.theme.Guard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IchthysOS: the two confirmations before an app gets the Full access preset (every permission,
 * nothing shielded). The first says exactly what will be given, counted from the app's manifest;
 * the second asks again with the consequence in one sentence. Only the second's confirm button
 * calls [onConfirm]; everything else is [onDismiss]. The step survives rotation.
 */
@Composable
fun FullAccessConfirmation(
    appLabel: String,
    packageName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = Guard.tokens
    var step by rememberSaveable { mutableIntStateOf(1) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val preview by
        produceState<FullAccessGranter.Preview?>(null, packageName) {
            value = withContext(Dispatchers.IO) { FullAccessGranter.preview(ctx, packageName) }
        }
    if (step == 1) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = t.raised,
            titleContentColor = t.text,
            textContentColor = t.muted,
            title = { Text("Give $appLabel full access?") },
            text = {
                Column {
                    Text("Nothing is withheld, faked or shielded from this app any more:")
                    val p = preview
                    Bullet(
                        when {
                            p == null -> "every permission it asks for"
                            p.permissions.isEmpty() -> "every permission it asks for (all granted already)"
                            else -> "${quantity(p.permissions.size, "permission")} it asks for and doesn't have yet" +
                                (if (p.permissions.any { it.contains("BACKGROUND") }) ", background ones included" else "")
                        }
                    )
                    if (p != null && p.special.isNotEmpty()) Bullet("special access: ${p.special.joinToString()}")
                    if (p?.seeAllApps == true) Bullet("the list of every app you have")
                    if (p?.dndAccess == true) Bullet("Do Not Disturb access")
                    Bullet("your real contacts, calendar, call log, messages and phone number")
                    Bullet("your real location and the networks and devices around you")
                    Bullet("the clipboard at any time, exact sensors, your usage history")
                    Bullet("internet and your local network, also with the screen off")
                    Text(
                        "Guard keeps monitoring it. Meant for the tester app or an app you fully trust. " +
                            "Picking another preset later takes the permissions back.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { step = 2 }) { Text("Continue", color = t.accent) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = t.text) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = t.raised,
            titleContentColor = t.danger,
            textContentColor = t.muted,
            title = { Text("Are you sure?") },
            text = {
                Text(
                    "$appLabel will see your real data the moment it asks, and can reach the internet whenever it wants. " +
                        "This is the one preset that gives an app more than stock Android would by default."
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text("Give full access", color = t.danger) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = t.text) } },
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
}
