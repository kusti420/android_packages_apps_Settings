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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settings.R
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.notify.GuardNotifications
import com.android.settings.ichthys.guard.os.ShieldGroup
import com.android.settings.ichthys.guard.ui.theme.Guard
import com.android.settings.ichthys.guard.ui.theme.GuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** IchthysOS: the three destinations of the bottom bar. The gear page sits in the top bar. */
enum class GuardTab(val label: String, val glyph: String) {
    PROTECTION("Protection", "◉"),
    APPS("Apps", "▦"),
    ACTIVITY("Activity", "≋"),
}

/**
 * Guard's one activity. Three destinations (Protection, Apps, Activity) plus pages pushed on top
 * of them: an app's page, a destination's page, the gear and its sub-pages, the verify page.
 * IchthysOS: the page stack is a string in saved state ("a:<package>", "h:<host>", "s:<page>",
 * ...), so Back walks it and a rotation keeps it.
 */
class GuardActivity : ComponentActivity() {
    private val openPackage = mutableStateOf<String?>(null)
    private val openPage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // IchthysOS: Guard's firewall table is device-wide and belongs to the system user; another
        // user's or profile's Guard would replace the owner's rules (GuardRepository.isGuardUser).
        if (!GuardRepository.isGuardUser()) {
            Toast.makeText(this, R.string.ichthys_guard_owner_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        handleIntent(intent)
        // Settings' Application starts this lazily; make sure channels and the weekly alarm exist.
        GuardNotifications.init(this)
        setContent {
            GuardTheme {
                val t = Guard.tokens
                val vm: GuardViewModel = viewModel()
                var tab by rememberSaveable { mutableStateOf(GuardTab.PROTECTION) }
                var stack by rememberSaveable { mutableStateOf("") }
                val pages = if (stack.isEmpty()) emptyList() else stack.split('\n')
                val push: (String) -> Unit = { page ->
                    if (pages.lastOrNull() != page) stack = (pages + page).joinToString("\n")
                }
                val pop: () -> Unit = { stack = pages.dropLast(1).joinToString("\n") }
                openPackage.value?.let {
                    stack = "a:$it"
                    openPackage.value = null
                }
                openPage.value?.let {
                    when (it) {
                        OPEN_PAGE_VERIFY -> {
                            tab = GuardTab.PROTECTION
                            stack = PAGE_VERIFY
                        }
                        OPEN_PAGE_GEAR -> stack = PAGE_GEAR
                        // IchthysOS: quiet notifications (a gear sub-page).
                        OPEN_PAGE_QUIET_NOTIFICATIONS -> stack = PAGE_QUIET_NOTIFICATIONS
                    }
                    openPage.value = null
                }
                BackHandler(enabled = pages.isNotEmpty()) { pop() }
                BackHandler(enabled = pages.isEmpty() && tab != GuardTab.PROTECTION) {
                    tab = GuardTab.PROTECTION
                }
                val nav =
                    GuardNav(
                        openApp = { push("a:$it") },
                        openHost = { push("h:$it") },
                        openAbout = { push("about:$it") },
                        openData = { pkg, group -> push("data:$pkg:${group.name}") },
                        openPage = { push("s:$it") },
                        openVerify = { pkg -> push(if (pkg == null) PAGE_VERIFY else "s:verify:$pkg") },
                        openTab = { tab = it; stack = "" },
                        back = pop,
                    )
                Scaffold(
                    containerColor = t.ink,
                    contentColor = t.text,
                    topBar = {
                        Row(
                            Modifier.fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
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
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { push(PAGE_GEAR) }) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "Guard settings",
                                    tint = if (pages.lastOrNull()?.startsWith("s:") == true) t.accent else t.muted,
                                )
                            }
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
                                        selected = tab == item && pages.isEmpty(),
                                        onClick = {
                                            tab = item
                                            stack = ""
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
                    val top = pages.lastOrNull()
                    when {
                        top == null ->
                            when (tab) {
                                GuardTab.PROTECTION -> ProtectionScreen(vm, nav, m)
                                GuardTab.APPS -> AppsScreen(vm, nav, m)
                                GuardTab.ACTIVITY -> ActivityScreen(vm, nav, m)
                            }
                        top == PAGE_VERIFY -> VerifyScreen(vm, nav, forPackage = null, modifier = m)
                        top.startsWith("s:verify:") ->
                            VerifyScreen(vm, nav, forPackage = top.removePrefix("s:verify:"), modifier = m)
                        top == PAGE_GEAR -> GearScreen(vm, nav, m)
                        // IchthysOS: quiet notifications (Fold ongoing / Silent section / Always
                        // quiet). The gear page opens it with nav.openPage("quiet").
                        top == PAGE_QUIET_NOTIFICATIONS ->
                            QuietNotificationsScreen(vm, onBack = pop, modifier = m)
                        top.startsWith("s:") -> GearSubPage(vm, nav, top.removePrefix("s:"), m)
                        top.startsWith("h:") ->
                            DestinationScreen(
                                vm,
                                top.removePrefix("h:"),
                                onOpenApp = nav.openApp,
                                onBack = pop,
                                modifier = m,
                            )
                        top.startsWith("about:") -> AboutAppScreen(vm, nav, top.removePrefix("about:"), m)
                        top.startsWith("data:") -> {
                            val parts = top.removePrefix("data:").split(':')
                            DataSheetScreen(
                                vm,
                                nav,
                                parts[0],
                                runCatching { ShieldGroup.valueOf(parts.getOrElse(1) { "PERSONAL" }) }
                                    .getOrDefault(ShieldGroup.PERSONAL),
                                m,
                            )
                        }
                        top.startsWith("a:") -> AppPageScreen(vm, nav, top.removePrefix("a:"), m)
                        else -> ProtectionScreen(vm, nav, m)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!GuardRepository.isGuardUser()) return
        // The SystemUI Pause tile talks to the service directly; pick up its deadline. On a cold
        // start this is the first get(), which builds the repository (it reads Guard's stores
        // from disk), so not on the main thread. IchthysOS: the health bits, the deny chain and
        // the blocked counters are re-read too, so the home cards show the state now.
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = GuardRepository.get(app)
            repo.refreshPause()
            repo.refreshEvidence(force = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    companion object {
        private const val PAGE_GEAR = "s:gear"
        private const val PAGE_VERIFY = "s:verify"
        /** IchthysOS: QuietNotificationsScreen; nav.openPage("quiet") lands here. */
        private const val PAGE_QUIET_NOTIFICATIONS = "s:quiet"

        /** IchthysOS: which page to open on top of the tabs. */
        const val EXTRA_OPEN_PAGE = "com.android.settings.ichthys.guard.extra.OPEN_PAGE"
        const val OPEN_PAGE_VERIFY = "verify"
        const val OPEN_PAGE_GEAR = "gear"
        /** IchthysOS: [EXTRA_OPEN_PAGE] value for the quiet-notification controls. */
        const val OPEN_PAGE_QUIET_NOTIFICATIONS = "quiet_notifications"

        /** IchthysOS: opens Guard at the quiet-notification controls (gear > Notifications). */
        @JvmStatic
        fun quietNotificationsIntent(context: Context): Intent =
            Intent(context, GuardActivity::class.java)
                .putExtra(EXTRA_OPEN_PAGE, OPEN_PAGE_QUIET_NOTIFICATIONS)

        /** Opens Guard at [packageName]'s page (the stock App info page's Guard row). */
        @JvmStatic
        fun appIntent(context: Context, packageName: String): Intent =
            Intent(context, GuardActivity::class.java)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)

        /**
         * Opens Shared values (home city, trips, units). IchthysOS: the screen moved out of Guard
         * to Settings › IchthysOS (SharedValuesActivity); the Weather page's Location row and
         * older callers still land there through this.
         */
        @JvmStatic
        fun sharedValuesIntent(context: Context): Intent = SharedValuesActivity.intent(context)
    }

    private fun handleIntent(intent: Intent?) {
        val pkg =
            intent?.getStringExtra(GuardNotifications.EXTRA_OPEN_PACKAGE)
                ?: intent?.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        pkg?.let { openPackage.value = it }
        intent?.getStringExtra(EXTRA_OPEN_PAGE)?.let { openPage.value = it }
    }
}

/** IchthysOS: how the screens move around; one object so each screen takes one parameter. */
data class GuardNav(
    val openApp: (String) -> Unit,
    val openHost: (String) -> Unit,
    val openAbout: (String) -> Unit,
    val openData: (String, ShieldGroup) -> Unit,
    /** A gear sub-page by its id (see GearSubPage). */
    val openPage: (String) -> Unit,
    /** The verify page; a package name runs "Verify this app". */
    val openVerify: (String?) -> Unit,
    val openTab: (GuardTab) -> Unit,
    val back: () -> Unit,
)
