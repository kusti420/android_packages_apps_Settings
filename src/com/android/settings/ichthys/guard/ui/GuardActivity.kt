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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.withContext

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
 *
 * IchthysOS: open so [GuardReviewActivity] can run it in first-launch review mode.
 */
open class GuardActivity : ComponentActivity() {
    private val openPackage = mutableStateOf<String?>(null)
    private val openPage = mutableStateOf<String?>(null)

    /**
     * IchthysOS: the quarantined app being set up from the first-launch review's Customize
     * (only in [GuardReviewActivity]); null for Guard itself.
     */
    private var reviewPkg: String? = null
    /** IchthysOS: the review ended by Done or "Back to review": leaving isn't unfinished. */
    private var reviewHandled = false
    private var reviewMarked = false
    private var reviewNotified = false
    private val reviewBusy = mutableStateOf(false)

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
        reviewPkg =
            if (this is GuardReviewActivity) intent?.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
            else null
        if (this is GuardReviewActivity && reviewPkg.isNullOrEmpty()) {
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
                val review = reviewPkg
                var stack by rememberSaveable { mutableStateOf(review?.let { "a:$it" } ?: "") }
                val pages = if (stack.isEmpty()) emptyList() else stack.split('\n')
                val push: (String) -> Unit = { page ->
                    if (pages.lastOrNull() != page) stack = (pages + page).joinToString("\n")
                }
                // IchthysOS: in the review, Back from the app's page leaves the review (the app
                // stays quarantined, the review is marked unfinished in onStop).
                val pop: () -> Unit = {
                    if (review != null && pages.size <= 1) finish()
                    else stack = pages.dropLast(1).joinToString("\n")
                }
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
                        openTab = { if (review == null) { tab = it; stack = "" } },
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
                            // IchthysOS: the review stays on the app being set up.
                            if (review == null) IconButton(onClick = { push(PAGE_GEAR) }) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "Guard settings",
                                    tint = if (pages.lastOrNull()?.startsWith("s:") == true) t.accent else t.muted,
                                )
                            }
                        }
                    },
                    bottomBar = {
                        if (review != null) {
                            ReviewBar(
                                busy = reviewBusy.value,
                                onBackToReview = { backToReview(review) },
                                onDone = { finishReview(review) },
                            )
                        } else Column {
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
                        // IchthysOS (owner): Shared values live in Guard (home city, trips,
                        // fake place, units); Settings › IchthysOS and Weather link here too.
                        top == PAGE_SHARED_VALUES -> SharedValuesScreen(onBack = pop, modifier = m)
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
                        top.startsWith("a:") -> {
                            val pkg = top.removePrefix("a:")
                            AppPageScreen(vm, nav, pkg, m, firstLaunchReview = pkg == review)
                        }
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

    // IchthysOS: leaving the review without Done (Back, home, another app) is an unfinished
    // review, like leaving the sheet: finishing marks it and notifies; only stopping marks it,
    // in case the user comes back from recents; the notification follows if it is destroyed.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) noteReviewUnfinished(notify = isFinishing)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) noteReviewUnfinished(notify = true)
        super.onDestroy()
    }

    private fun noteReviewUnfinished(notify: Boolean) {
        val pkg = reviewPkg ?: return
        if (reviewHandled || reviewNotified || (!notify && reviewMarked)) return
        reviewMarked = true
        reviewNotified = notify
        val app = applicationContext
        Thread(
                { GuardRepository.get(app).markReviewUnfinished(pkg, notify) },
                "IchthysGuardReview",
            )
            .start()
    }

    /** IchthysOS: "Back to review": the sheet again, which owns the review from here. */
    private fun backToReview(pkg: String) {
        reviewHandled = true
        startActivity(FirstLaunchActivity.reviewIntent(this, pkg))
        finish()
    }

    /**
     * IchthysOS: "Done – open app": the policy is already what the user set on the page; lift the
     * quarantine (which also clears the unfinished mark and its notification) and start the app.
     */
    private fun finishReview(pkg: String) {
        if (reviewBusy.value) return
        reviewBusy.value = true
        reviewHandled = true
        val app = applicationContext
        lifecycleScope.launch {
            val (released, label) =
                withContext(Dispatchers.IO) {
                    val repo = GuardRepository.get(app)
                    val label = repo.appFor(pkg)?.label ?: pkg
                    val quarantined =
                        runCatching { app.packageManager.isPackageQuarantined(pkg) }
                            .getOrDefault(true)
                    if (quarantined) {
                        releaseQuarantine(repo, pkg, "Released to set up by hand") to label
                    } else {
                        // Released meanwhile (another review, the app page): just open it.
                        repo.clearUnfinishedReview(pkg)
                        app.packageManager.getLaunchIntentForPackage(pkg)?.let {
                            app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        true to label
                    }
                }
            if (!released) {
                reviewHandled = false
                reviewBusy.value = false
                Toast.makeText(
                        this@GuardActivity,
                        getString(R.string.ichthys_guard_first_launch_failed, label),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                return@launch
            }
            finish()
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
        private const val PAGE_SHARED_VALUES = "s:shared"

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

        /**
         * IchthysOS: the first-launch review's Customize: [packageName]'s page in review mode
         * ([GuardReviewActivity]), in a task of its own, with the app still quarantined.
         */
        @JvmStatic
        fun firstLaunchReviewIntent(context: Context, packageName: String): Intent =
            Intent(context, GuardReviewActivity::class.java)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

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
        // IchthysOS: the review stays on its app (the stack starts at its page).
        if (reviewPkg != null) return
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

/**
 * IchthysOS: Guard in first-launch review mode, started by the review sheet's Customize
 * ([GuardActivity.firstLaunchReviewIntent]). The quarantined app's page with a review bar instead
 * of the tabs: edits apply to the policy as usual, "Done – open app" lifts the quarantine and
 * starts the app, "Back to review" returns to the sheet, and leaving any other way keeps the app
 * quarantined with the review marked unfinished. A class of its own so it gets its own task
 * (manifest taskAffinity="") and nothing aimed at Guard (notifications, App info) lands in it.
 */
class GuardReviewActivity : GuardActivity()

/** IchthysOS: the review mode's bottom bar (replaces the tabs). */
@androidx.compose.runtime.Composable
private fun ReviewBar(busy: Boolean, onBackToReview: () -> Unit, onDone: () -> Unit) {
    val t = Guard.tokens
    Column(Modifier.navigationBarsPadding()) {
        HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.ichthys_guard_review_customize_hint),
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onBackToReview,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(stringResource(R.string.ichthys_guard_review_back))
                }
                // The bar's one accented element: the way out.
                Button(
                    onClick = onDone,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = t.accent,
                            contentColor = t.onAccent,
                        ),
                ) {
                    Text(stringResource(R.string.ichthys_guard_review_done))
                }
            }
        }
    }
}
