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
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settings.R
import com.android.settings.ichthys.guard.notify.GuardNotifications
import com.android.settings.ichthys.guard.ui.theme.Guard
import com.android.settings.ichthys.guard.ui.theme.GuardTheme

enum class GuardTab(val label: String, val glyph: String) {
    HOME("Home", "◉"),
    APPS("Apps", "▦"),
    ACTIVITY("Activity", "≋"),
    TRAFFIC("Traffic", "⇅"),
    RULES("Rules", "≡"),
}

/**
 * Settings' Guard page: the per-app firewall and activity monitor (formerly the standalone
 * org.ichthys.guard app). Opened from the "Guard" row on the Settings homepage, from Guard's
 * notifications and from a long-press on the Lockdown / Pause tiles.
 *
 * Intents: an explicit launch from Settings itself, optionally with
 * [GuardNotifications.EXTRA_OPEN_PACKAGE] or [Intent.EXTRA_PACKAGE_NAME] to open one app's page.
 */
class GuardActivity : ComponentActivity() {
    private val openPackage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // Settings' Application starts this lazily; make sure channels and the weekly alarm exist.
        GuardNotifications.init(this)
        setContent {
            GuardTheme {
                val t = Guard.tokens
                val vm: GuardViewModel = viewModel()
                var tab by rememberSaveable { mutableStateOf(GuardTab.HOME) }
                var detail by rememberSaveable { mutableStateOf<String?>(null) }
                openPackage.value?.let {
                    detail = it
                    openPackage.value = null
                }
                BackHandler(enabled = detail != null) { detail = null }
                BackHandler(enabled = detail == null && tab != GuardTab.HOME) {
                    tab = GuardTab.HOME
                }
                val open: (String) -> Unit = { detail = it }
                Scaffold(
                    containerColor = t.ink,
                    contentColor = t.text,
                    topBar = {
                        Row(
                            Modifier.fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 4.dp, end = 16.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription =
                                        stringResource(R.string.ichthys_guard_navigate_up),
                                    tint = t.text,
                                )
                            }
                            Text(
                                stringResource(R.string.ichthys_guard_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = t.text,
                            )
                        }
                    },
                    bottomBar = {
                        Column {
                            HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
                            NavigationBar(containerColor = t.ink, tonalElevation = 0.dp) {
                                // The selected tab is the bar's one accented element.
                                val colors =
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = t.accent,
                                        selectedTextColor = t.accent,
                                        indicatorColor = Color.Transparent,
                                        unselectedIconColor = t.muted,
                                        unselectedTextColor = t.muted,
                                    )
                                for (item in GuardTab.entries) {
                                    NavigationBarItem(
                                        selected = tab == item && detail == null,
                                        onClick = {
                                            tab = item
                                            detail = null
                                        },
                                        icon = { Text(item.glyph) },
                                        label = { Text(item.label) },
                                        colors = colors,
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    val d = detail
                    if (d != null) {
                        AppDetailScreen(vm, d, onBack = { detail = null }, modifier = m)
                    } else {
                        when (tab) {
                            GuardTab.HOME ->
                                HomeScreen(
                                    vm,
                                    open,
                                    onOpenActivity = { tab = GuardTab.ACTIVITY },
                                    modifier = m,
                                )
                            GuardTab.APPS -> AppsScreen(vm, open, m)
                            GuardTab.ACTIVITY -> ActivityScreen(vm, open, m)
                            GuardTab.TRAFFIC -> TrafficScreen(vm, open, m)
                            GuardTab.RULES -> RulesScreen(vm, m)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val pkg =
            intent?.getStringExtra(GuardNotifications.EXTRA_OPEN_PACKAGE)
                ?: intent?.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        pkg?.let { openPackage.value = it }
    }
}
