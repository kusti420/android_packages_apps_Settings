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

import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settings.ichthys.guard.backend.Health
import com.android.settings.ichthys.guard.data.GuardRepository
import com.android.settings.ichthys.guard.model.AlertType
import com.android.settings.ichthys.guard.model.AppTag
import com.android.settings.ichthys.guard.model.CountryNames
import com.android.settings.ichthys.guard.model.CountryProfiles
import com.android.settings.ichthys.guard.model.HostRules
import com.android.settings.ichthys.guard.model.JournalKind
import com.android.settings.ichthys.guard.model.NetworkAccess
import com.android.settings.ichthys.guard.model.Profile
import com.android.settings.ichthys.guard.model.RuleAction
import com.android.settings.ichthys.guard.model.SpoofProfile
import com.android.settings.ichthys.guard.os.ShieldTable
import com.android.settings.ichthys.guard.ui.theme.Guard
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * IchthysOS: the gear page: everything global, one row each, every row a page of its own. Every
 * control of the old Rules and Automation pages has a home here (nothing dropped).
 */
@Composable
fun GearScreen(vm: GuardViewModel, nav: GuardNav, modifier: Modifier = Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val rules by repo.rules.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    val masters = remember(tick) { repo.masterShieldsOn() }
    val fakeOn = remember(tick) { repo.fakePlaceOn() }
    val spoofSummary = remember(tick) { repo.spoofProfile()?.summary(repo.fakePlaceName()) }
    val realCount = remember(tick) { repo.realLocationApps().size }
    val quarantined = remember(apps) { repo.quarantinedApps().size }
    val asleep = remember(policies) { policies.policies.values.count { it.dormant } }
    val hostRules = remember(policies, rules) { rules.rules.size + policies.policies.values.count { it.allowedHosts.isNotEmpty() || it.blockedHosts.isNotEmpty() } }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Guard settings", nav.back) }
        item {
            GuardCard {
                NavRow("Master shields", "What every app gets of your data unless its page says otherwise", { nav.openPage("shields") }, trailing = "${masters.first} of ${masters.second} on")
                NavRow("Shared values", "Home city, trips and units: weather, sunset and Night Light use them", { nav.openPage("shared") })
                NavRow(
                    "Spoofed values",
                    if (fakeOn) "${spoofSummary ?: "Position only"} · ${quantity(realCount, "app")} see the real location"
                    else "Off · every app gets the real location",
                    { nav.openPage("spoofed") },
                )
                NavRow("Screen-off rule", if (auto.screenOffAll) "On · ${quantity(auto.screenOffExempt.size, "app")} stay online" else "Off", { nav.openPage("screenoff") })
                NavRow("Schedules", auto.activeProfiles().takeIf { it.isNotEmpty() }?.let { "Active now: " + it.joinToString { p -> p.name } } ?: "${quantity(auto.profiles.count { it.enabled }, "schedule")} on", { nav.openPage("schedules") })
                NavRow("Lockdown keeps online", "Set on each app's page", { nav.openPage("lockdown") }, trailing = quantity(rules.lockdown.allowlist.size, "app"))
                NavRow("New apps", if (quarantined > 0) "${quantity(quarantined, "app")} waiting for a preset" else "Quarantined until you pick a preset · local network blocked", { nav.openPage("newapps") })
                NavRow("Notifications", "Ongoing and silent notifications folded into chips or one row", { nav.openPage("quiet") })
                NavRow("Alerts", "Camera, mic and location in the background, wake-ups, boot starts · kept in the journal", { nav.openPage("alerts") }, trailing = "${AlertType.entries.count { auto.alertOn(it) }} of ${AlertType.entries.size}")
                NavRow("Put unused apps to sleep", if (auto.autoTighten) "On · after ${auto.autoTightenDays} days" + (if (asleep > 0) " · ${quantity(asleep, "app")} asleep" else "") else "Off", { nav.openPage("sleep") })
                NavRow("Service health", if (status.problems.isEmpty()) "All parts working" else status.problems.first(), { nav.openPage("health") }, danger = status.problems.isNotEmpty())
                NavRow("Backup", "Export or import presets, every shield list, the fake place and its spoofed values, schedules and storage scopes", { nav.openPage("backup") })
                NavRow("Per-app tracker host rules — coming", if (hostRules > 0) "${quantity(hostRules, "saved rule")}, kept, not enforced yet" else "Saved rules are kept until the engine lands", { nav.openPage("trackers") })
            }
        }
    }
}

/** One gear sub-page by id. */
@Composable
fun GearSubPage(vm: GuardViewModel, nav: GuardNav, id: String, modifier: Modifier = Modifier) {
    when (id) {
        "shields" -> MasterShieldsPage(vm, nav, modifier)
        // IchthysOS: "fakeplace" is the page's old id; older callers still land here.
        "fakeplace", "spoofed" -> SpoofedValuesPage(vm, nav, modifier)
        "screenoff" -> ScreenOffPage(vm, nav, modifier)
        "schedules" -> SchedulesPage(vm, nav, modifier)
        "lockdown" -> LockdownKeepsPage(vm, nav, modifier)
        "newapps" -> NewAppsPage(vm, nav, modifier)
        "alerts" -> AlertsPage(vm, nav, modifier)
        "sleep" -> SleepPage(vm, nav, modifier)
        "health" -> HealthPage(vm, nav, modifier)
        "backup" -> BackupPage(vm, nav, modifier)
        "trackers" -> TrackerRulesPage(vm, nav, modifier)
        else -> GearScreen(vm, nav, modifier)
    }
}

// ---- Master shields ----------------------------------------------------------------------------

@Composable
private fun MasterShieldsPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val resolver = LocalContext.current.contentResolver
    val t = Guard.tokens
    var version by remember { mutableIntStateOf(0) }
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Master shields", nav.back, "What apps get of your data unless you give one real access on its own page. System apps and your default phone and SMS apps are exempt.") }
        item {
            GuardCard {
                for ((section, rows) in com.android.settings.ichthys.guard.os.SHIELD_SECTIONS) {
                    SectionLabel(section, Modifier.padding(top = 12.dp))
                    for (row in rows) {
                        val states = remember(version, tick, row) { row.switches.map { it.isOn(resolver) } }
                        val allOn = states.all { it }
                        ToggleRow(
                            row.label,
                            allOn,
                            when {
                                allOn -> row.protectedText
                                states.any { it } -> "Partly on: switch on to protect all of it"
                                else -> "Off: every app gets real access"
                            },
                        ) { on ->
                            repo.setShields(row.switches, on)
                            version++
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Evidence of these shields arrives in Activity › Journal as apps query contacts, calendar, call log or texts; a verification run proves each one with the tester app.",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}

// ---- Spoofed values (ex Fake place) -----------------------------------------------------------

/**
 * IchthysOS: the fake place and everything that goes with it. The place (Open-Meteo search)
 * gives a country and a time zone; the country gives a carrier, a language and a Samsung sales
 * code from [CountryProfiles]. Each derived row can be changed; every change rewrites the one
 * profile JSON (ShieldTable.KEY_SPOOF_PROFILE) and the platform restarts the spoofed apps.
 */
@Composable
private fun SpoofedValuesPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val context = LocalContext.current
    val t = Guard.tokens
    val scope = rememberCoroutineScope()
    val tick = rememberSettingsTick(ShieldTable.OBSERVED_URIS)
    var version by remember { mutableIntStateOf(0) }
    // IchthysOS: the page edits a draft and writes it to Settings once, when the page is left
    // (or the app goes to the background), so the spoofed apps restart once, not on every
    // change (owner's decision 2026-11-03). The draft starts from what is set now.
    var draftOn by remember { mutableStateOf(repo.fakePlaceOn()) }
    var draftFake by remember { mutableStateOf(android.provider.Settings.Secure.getString(context.contentResolver, ShieldTable.KEY_SPOOF_FAKE)) }
    var draftName by remember { mutableStateOf(repo.fakePlaceName()) }
    var draftProfile by remember { mutableStateOf(repo.spoofProfile()) }
    var dirty by remember { mutableStateOf(false) }
    val on = draftOn
    val fake = draftFake
    val name = draftName
    val profile = draftProfile
    val real = remember(tick, version) { repo.realLocationApps() }
    val apps by repo.apps.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val lastFake = remember(journal) { journal.entries.lastOrNull { it.shield == com.android.settings.ichthys.guard.model.ShieldProof.LOCATION_FAKE } }
    var searching by remember { mutableStateOf(false) }
    // Which editor is open: "country", "tz", "carrier", "custom", "locale", "sales" or null.
    var editing by remember { mutableStateOf<String?>(null) }
    var ipResult by remember { mutableStateOf<String?>(null) }
    var ipMismatch by remember { mutableStateOf(false) }
    var ipChecking by remember { mutableStateOf(false) }

    fun save(next: SpoofProfile?) {
        draftProfile = next
        dirty = true
        ipResult = null
        version++
    }

    /** Writes the draft in one burst (the platform coalesces it into one restart). */
    fun flush() {
        if (!dirty) return
        dirty = false
        val place = draftFake
        if (place != null) {
            ShieldTable.setFakePlace(context, place, draftName ?: "", draftProfile)
        } else {
            ShieldTable.setSpoofProfile(context, draftProfile)
        }
        com.android.settings.ichthys.guard.os.ShieldSwitch(ShieldTable.KEY_SPOOF_ON, com.android.settings.ichthys.guard.model.SettingsTable.SECURE)
            .setOn(context.contentResolver, draftOn)
    }
    val flushNow = androidx.compose.runtime.rememberUpdatedState { flush() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) flushNow.value()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            flushNow.value()
        }
    }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeader(
                "Spoofed values",
                nav.back,
                "What apps get instead of your real place: the position, and the country, carrier, time zone and language that go with it. Apps set to Real on their page are the exception.",
            )
        }
        item {
            GuardCard {
                ToggleRow(
                    "Fake place for every app",
                    on,
                    if (on) "Apps get ${name ?: "the fake place"} instead of your position, and no Wi-Fi, cell or Bluetooth scans." +
                        (if (profile != null) " They also see ${profile.countryName} as their country." else "")
                    else "Off: every app gets your real position, country and carrier.",
                ) { v ->
                    draftOn = v
                    dirty = true
                    version++
                }
                lastFake?.let {
                    Text("Last fake fix delivered ${formatWhen(it.timeMs)} to ${it.appLabel ?: "an app"}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), color = t.muted)
                }
            }
        }
        item {
            GuardCard(title = "Place") {
                if (searching) {
                    PlaceSearch(
                        onPick = { place ->
                            // The place and the values derived from its country, in one go. A
                            // result without a country code keeps the values already set.
                            val next =
                                if (place.countryCode.length == 2)
                                    SpoofProfile.defaults(
                                        place.countryCode,
                                        place.timezone,
                                        vpn = profile?.vpn ?: false,
                                        followLanguage = profile == null || profile.locale != null,
                                    )
                                else profile
                            draftFake = place.latLon
                            draftName = place.name
                            draftProfile = next
                            draftOn = true
                            dirty = true
                            searching = false
                            ipResult = null
                            version++
                        },
                        onCancel = { searching = false },
                    )
                } else if (fake == null) {
                    Text("No fake place set yet. Apps get your real position when allowed.", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { searching = true }) { Text("Choose a place") }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name ?: formatLatLon(fake), style = MaterialTheme.typography.bodyLarge)
                            Text(formatLatLon(fake), style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), color = t.muted)
                        }
                        TextButton(onClick = { searching = true }) { Text("Change place") }
                    }
                }
                Text(
                    "Picking a place fills in the rows below from its country. Your home city and trips are in Settings › IchthysOS › Shared values.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
        item {
            GuardCard(title = "What apps see") {
                if (profile == null) {
                    Text(
                        "Only the position is faked so far: apps still see your real country, carrier and time zone. Pick a country to fill in the rest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                    TextButton(onClick = { editing = "country" }) { Text("Choose the country") }
                } else {
                    NavRow("Country", "The SIM and network country apps are told", { editing = "country" }, trailing = "${profile.countryName} · ${profile.country}")
                    NavRow("Time zone", "The clock zone apps see", { editing = "tz" }, trailing = profile.tz)
                    NavRow("Carrier", "Network name and code apps see", { editing = "carrier" }, trailing = "${profile.operator} · ${profile.mcc} ${profile.mnc}")
                    ToggleRow(
                        "App language follows the place",
                        profile.locale != null,
                        if (profile.locale != null) "Spoofed apps run in ${languageName(profile.locale)} (${profile.locale})"
                        else "Off: apps keep your own language. Apps can tell a tourist from a local by it.",
                    ) { follow ->
                        save(profile.copy(locale = if (follow) CountryProfiles.get(profile.country).locale else null))
                    }
                    if (profile.locale != null)
                        NavRow("Language", "Change the language code", { editing = "locale" }, trailing = profile.locale)
                    NavRow("Samsung sales code", if (profile.sales == null) "Not changed: apps read the phone's real region code" else "The firmware region code some apps read", { editing = "sales" }, trailing = profile.sales ?: "real")
                    ToggleRow(
                        "Spoofed apps need a VPN",
                        profile.vpn,
                        if (profile.vpn) "Without a VPN they are offline, so your internet address never gives the real place away."
                        else "Off: they go online as usual, and your internet address still tells them where you really are.",
                    ) { v -> save(profile.copy(vpn = v)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !ipChecking,
                            onClick = {
                                ipChecking = true
                                ipResult = null
                                scope.launch {
                                    val check = try { withContext(Dispatchers.IO) { checkMyIp() } } finally { ipChecking = false }
                                    if (check == null) {
                                        ipResult = "Could not check. No connection, or the check is blocked."
                                        ipMismatch = false
                                    } else {
                                        val match = check.matches(profile.country)
                                        ipMismatch = !match
                                        ipResult =
                                            "${check.country}, ${check.city} (${check.ip})" +
                                                (if (check.mullvad) " · via Mullvad" else "") +
                                                (if (match) " · MATCH" else " · MISMATCH: apps on your connection see ${check.country}, not ${profile.countryName}")
                                    }
                                }
                            },
                        ) {
                            Text(if (ipChecking) "Checking…" else "Check my IP")
                        }
                        Text("Where this phone's internet address is right now", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    }
                    ipResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), color = if (ipMismatch) t.danger else t.muted)
                    }
                    TextButton(onClick = { save(null) }) { Text("Fake the position only") }
                }
                Text(
                    if (dirty) "Changes apply when you leave this page; the spoofed apps then restart once with the new values."
                    else "Changes apply when you leave this page, and the spoofed apps restart once with the new values.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dirty) t.accent else t.muted,
                )
            }
        }
        item {
            GuardCard(title = "What each value does") {
                for ((what, does) in SPOOF_VALUE_NOTES) {
                    Text(what, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    Text(does, style = MaterialTheme.typography.bodySmall, color = t.muted)
                }
            }
        }
        item {
            GuardCard(title = "See the real location · ${real.size}") {
                if (real.isEmpty()) Text("No app. Set Location: Real on an app's page.", color = t.muted)
                for (pkg in real.sorted()) {
                    val label = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                    AppLine(pkg, label, nav.openApp) {
                        TextButton(onClick = { repo.setLocationMode(pkg, com.android.settings.ichthys.guard.model.LocationMode.FAKE); version++ }) { Text("Remove") }
                    }
                }
                Text("These apps get your real position and real country, carrier and time zone too.", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
    }

    // ---- the editors ----
    when (editing) {
        "country" ->
            PickerDialog(
                title = "Country",
                options = CountryProfiles.PICKER.map { (iso, label) -> iso to "$label ($iso)" },
                selected = profile?.country,
                onPick = { iso ->
                    // A new country re-derives everything else; the VPN rule and the language
                    // choice are kept.
                    if (iso != profile?.country)
                        save(SpoofProfile.defaults(iso, null, vpn = profile?.vpn ?: false, followLanguage = profile == null || profile.locale != null))
                    editing = null
                },
                onDismiss = { editing = null },
            )
        "tz" ->
            profile?.let { p ->
                val zones = remember(p.country) { SpoofProfile.timeZonesOf(p.country).ifEmpty { listOf(p.tz) } }
                PickerDialog(
                    title = "Time zone",
                    options = zones.map { it to it },
                    selected = p.tz,
                    onPick = { save(p.copy(tz = it)); editing = null },
                    onDismiss = { editing = null },
                )
            }
        "carrier" ->
            profile?.let { p ->
                val carriers = CountryProfiles.get(p.country).carriers
                PickerDialog(
                    title = "Carrier",
                    options = carriers.map { it.plmn to "${it.name} · ${it.mcc} ${it.mnc}" } + ("custom" to "Custom…"),
                    selected = carriers.firstOrNull { it.plmn == p.plmn && it.name == p.operator }?.plmn,
                    onPick = { plmn ->
                        if (plmn == "custom") editing = "custom"
                        else {
                            carriers.firstOrNull { it.plmn == plmn }?.let { save(p.copy(operator = it.name, mcc = it.mcc, mnc = it.mnc)) }
                            editing = null
                        }
                    },
                    onDismiss = { editing = null },
                )
            }
        "custom" ->
            profile?.let { p ->
                CustomCarrierDialog(
                    p,
                    onSave = { name, mcc, mnc -> save(p.copy(operator = name, mcc = mcc, mnc = mnc)); editing = null },
                    onDismiss = { editing = null },
                )
            }
        "locale" ->
            profile?.let { p ->
                TextDialog(
                    title = "App language",
                    hint = "Language code, such as en-US or fr-FR",
                    initial = p.locale ?: "",
                    valid = { Locale.forLanguageTag(it).language.isNotEmpty() },
                    onSave = { save(p.copy(locale = it.trim())); editing = null },
                    onDismiss = { editing = null },
                )
            }
        "sales" ->
            profile?.let { p ->
                TextDialog(
                    title = "Samsung sales code",
                    hint = "Three letters, such as XAA; empty keeps the phone's real one",
                    initial = p.sales ?: "",
                    valid = { it.isEmpty() || (it.length == 3 && it.all { c -> c.isLetterOrDigit() }) },
                    onSave = { save(p.copy(sales = it.trim().uppercase(Locale.ROOT).ifEmpty { null })); editing = null },
                    onDismiss = { editing = null },
                )
            }
    }
}

/** What each spoofed value is used for, in plain words (the page's explanation card). */
private val SPOOF_VALUE_NOTES =
    listOf(
        "Place" to "The position apps get from GPS and network location. Wi-Fi, cell and Bluetooth scans come back empty so they can't place you another way.",
        "Country" to "What apps are told when they ask the phone which country the SIM and the network are in, and which country the phone has detected. Also the emergency numbers they are shown.",
        "Carrier" to "The network name apps see, and its code (MCC and MNC, the numbers that identify a mobile network). Apps use it to tell which carrier and country you are on.",
        "Time zone" to "The clock zone apps see, so the time of day and the zone name agree with the place. Apps also stop getting the phone's own time zone changes.",
        "App language" to "The language spoofed apps run in. A phone in Estonian with a place in Texas stands out; following the place avoids that.",
        "Samsung sales code" to "The region code of the phone's firmware (ro.boot.sales_code) some apps read to tell where the phone was sold.",
        "VPN" to "Your internet address places you too. With this on, spoofed apps are offline until a VPN is up, and they can't see the Wi-Fi's own DNS and addresses either. Check my IP shows what they would see.",
    )

/** The English name of a language tag ("en-US" → "English (United States)"). */
private fun languageName(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifEmpty { tag }

/** What https://am.i.mullvad.net/json says about this phone's connection. */
private data class IpCheck(
    val ip: String,
    val country: String,
    val city: String,
    val mullvad: Boolean,
    /** The ISO code from the Mullvad exit server's name ("us-lax-wg-401" -> "US"), or null. */
    val serverCode: String? = null,
) {
    /** True when the reported country is [iso]: by the exit server's code, the code, or the name. */
    fun matches(iso: String): Boolean {
        if (serverCode != null) return serverCode.equals(iso, true)
        return CountryNames.matches(country, iso)
    }
}

/** GETs the Mullvad connection check (10 s timeouts); null when it failed or the phone is offline. */
private fun checkMyIp(): IpCheck? =
    try {
        val conn = URL("https://am.i.mullvad.net/json").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) null
            else {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                IpCheck(
                    ip = o.optString("ip"),
                    country = o.optString("country"),
                    city = o.optString("city"),
                    mullvad = o.optBoolean("mullvad_exit_ip", false),
                    serverCode = o.optString("mullvad_exit_ip_hostname").substringBefore('-')
                        .takeIf { o.optBoolean("mullvad_exit_ip", false) && it.length == 2 }
                        ?.uppercase(Locale.ROOT),
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

/** A list to pick one value from, with a filter box; [options] are (value, label). */
@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = Guard.tokens
    var filter by remember { mutableStateOf("") }
    val shown = remember(filter, options) {
        val q = filter.trim()
        if (q.isEmpty()) options else options.filter { it.second.contains(q, ignoreCase = true) || it.first.contains(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (options.size > 8)
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("Filter") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown.size) { i ->
                        val (value, label) = shown[i]
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (value == selected) t.accent else t.text,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 10.dp),
                        )
                    }
                }
                if (shown.isEmpty()) Text("Nothing matches.", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One text value with a validity rule; Save stays off until [valid]. */
@Composable
private fun TextDialog(
    title: String,
    hint: String,
    initial: String,
    valid: (String) -> Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = Guard.tokens
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(hint, style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(enabled = valid(value.trim()), onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A carrier of your own: name, MCC (3 digits) and MNC (2 or 3 digits). */
@Composable
private fun CustomCarrierDialog(p: SpoofProfile, onSave: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    val t = Guard.tokens
    var name by remember { mutableStateOf(p.operator) }
    var mcc by remember { mutableStateOf(p.mcc) }
    var mnc by remember { mutableStateOf(p.mnc) }
    val ok = name.isNotBlank() && SpoofProfile.validPlmn(mcc.trim(), mnc.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom carrier") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Network name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = mcc, onValueChange = { mcc = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("MCC") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = mnc, onValueChange = { mnc = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("MNC") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Text("MCC is the country's 3-digit network code, MNC the carrier's 2 or 3 digits (T-Mobile US is 310 260).", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(name.trim(), mcc.trim(), mnc.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Screen-off rule --------------------------------------------------------------------------

@Composable
private fun ScreenOffPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Screen-off rule", nav.back) }
        item {
            GuardCard {
                ToggleRow(
                    "No network while the screen is off",
                    auto.screenOffAll,
                    "Every user app is offline while the screen is off or locked, except the apps below. An app's page can set it to Offline or Stay online on its own.",
                ) { on -> repo.updateAutomation { it.copy(screenOffAll = on) } }
            }
        }
        item {
            GuardCard(title = "Stay online · ${auto.screenOffExempt.count { p -> apps.any { it.packageName == p } }}") {
                val exempt = apps.filter { it.packageName in auto.screenOffExempt }
                if (exempt.isEmpty()) Text("No app.", color = t.muted)
                for (a in exempt) {
                    AppLine(a.packageName, a.label, nav.openApp) {
                        TextButton(onClick = { repo.setScreenOffMode(a.packageName, com.android.settings.ichthys.guard.model.ScreenOffMode.FOLLOW) }) { Text("Remove") }
                    }
                }
                Text("Common messengers stay online by default. Add more from an app's page (Screen off: Stay online).", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
    }
}

// ---- Schedules (ex Profiles) ------------------------------------------------------------------

@Composable
private fun SchedulesPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val active = auto.activeProfiles().map { it.id }.toSet()
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Schedules", nav.back, "While a schedule is on, apps with its tags lose their network, or keep it only while on screen. Tag apps on their About page. Starts and ends are written to the journal.") }
        item {
            GuardCard {
                auto.profiles.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
                    ProfileEditor(
                        p,
                        active = p.id in active,
                        taggedApps = apps.count { a -> p.matches(a.packageName, policies.policyFor(a.packageName).tags) },
                        onChange = { next -> repo.updateAutomation { s -> s.copy(profiles = s.profiles.map { if (it.id == p.id) next else it }) } },
                        onDelete =
                            if (Profile.PREMADE.any { it.id == p.id }) null
                            else { { repo.updateAutomation { s -> s.copy(profiles = s.profiles.filter { it.id != p.id }) } } },
                    )
                }
                TextButton(
                    onClick = {
                        repo.updateAutomation { s ->
                            s.copy(
                                profiles =
                                    s.profiles +
                                        Profile(
                                            GuardRepository.newId(),
                                            "Custom ${s.profiles.size - 1}",
                                            startMinute = 12 * 60,
                                            endMinute = 13 * 60,
                                            blockTags = setOf(AppTag.SOCIAL),
                                        )
                            )
                        }
                    }
                ) {
                    Text("Add schedule")
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(p: Profile, active: Boolean, taggedApps: Int, onChange: (Profile) -> Unit, onDelete: (() -> Unit)?) {
    val t = Guard.tokens
    val ctx = LocalContext.current
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                    if (active) Tag("ACTIVE", emphasized = true)
                }
                Text("${p.windowText} · ${daysText(p.days)} · ${quantity(taggedApps, "app")}", style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono), color = t.muted)
            }
            Switch(checked = p.enabled, onCheckedChange = { onChange(p.copy(enabled = it)) })
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
            for (tag in AppTag.entries) {
                Chip(tag.label, tag in p.blockTags) {
                    onChange(p.copy(blockTags = if (tag in p.blockTags) p.blockTags - tag else p.blockTags + tag))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { TimePickerDialog(ctx, { _, h, m -> onChange(p.copy(startMinute = h * 60 + m)) }, p.startMinute / 60, p.startMinute % 60, true).show() }) {
                Text("Starts %02d:%02d".format(p.startMinute / 60, p.startMinute % 60))
            }
            TextButton(onClick = { TimePickerDialog(ctx, { _, h, m -> onChange(p.copy(endMinute = h * 60 + m)) }, p.endMinute / 60, p.endMinute % 60, true).show() }) {
                Text("Ends %02d:%02d".format(p.endMinute / 60, p.endMinute % 60))
            }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        }
        // What the schedule does to matching apps: no network at all, or only while on screen.
        Segmented(
            listOf(true, false),
            p.network == NetworkAccess.DENY,
            label = { block -> if (block) "No network" else "Only on screen" },
            onSelect = { block ->
                onChange(
                    if (block) p.copy(network = NetworkAccess.DENY, backgroundNetworkOff = false)
                    else p.copy(network = NetworkAccess.ALLOW, backgroundNetworkOff = true)
                )
            },
        )
    }
}

private fun daysText(days: Set<Int>): String {
    if (days.isEmpty() || days.size == 7) return "every day"
    val names = DateFormatSymbols.getInstance().shortWeekdays
    val order = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
    return order.filter { it in days }.joinToString(" ") { names[it] }
}

// ---- Lockdown keeps online ---------------------------------------------------------------------

@Composable
private fun LockdownKeepsPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val rules by repo.rules.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Lockdown keeps online", nav.back, "These apps keep their network when Lockdown is on. Set on each app's page (Network › Keep online during lockdown); the Messenger preset sets it.") }
        item {
            GuardCard(title = "Apps · ${rules.lockdown.allowlist.size}") {
                val kept = apps.filter { it.packageName in rules.lockdown.allowlist }
                if (kept.isEmpty()) Text("No app keeps its network in lockdown.", color = t.muted)
                for (a in kept) AppLine(a.packageName, a.label, nav.openApp) { Text("›", color = t.muted) }
                TextButton(onClick = { pick = !pick }) { Text(if (pick) "Hide the full list" else "Choose from all apps") }
                if (pick) {
                    apps.sortedWith(compareBy({ it.packageName !in rules.lockdown.allowlist }, { it.label.lowercase() })).forEach { a ->
                        Row(Modifier.fillMaxWidth().clickable { repo.setLockdownAllowed(a.packageName, a.packageName !in rules.lockdown.allowlist) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = a.packageName in rules.lockdown.allowlist, onCheckedChange = { repo.setLockdownAllowed(a.packageName, it) })
                            Text(a.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---- New apps ---------------------------------------------------------------------------------

@Composable
private fun NewAppsPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val apps by repo.apps.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val waiting = remember(apps, journal) { repo.quarantinedApps() }
    val scope = rememberCoroutineScope()
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("New apps", nav.back) }
        item {
            GuardCard {
                Text("An app you install is quarantined until you open it and pick a preset: it can't run jobs, alarms or services meanwhile. Its local network is blocked until you allow it. Apps from Ichthys Apps and the ROM's own are left alone.", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
        item {
            GuardCard(title = "Waiting · ${waiting.size}") {
                if (waiting.isEmpty()) Text("No app is waiting.", color = t.muted)
                for (a in waiting) {
                    // IchthysOS: no "Release as is": a new app opens only with the
                    // recommendation, a preset or Customize (its page or its first launch).
                    AppLine(a.packageName, a.label, nav.openApp) {}
                }
                Text("Open an app to pick its preset; that releases it too.", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
    }
}

// ---- Alerts -----------------------------------------------------------------------------------

@Composable
private fun AlertsPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val recent = remember(journal) { journal.entries.asReversed().filter { it.journal == JournalKind.ALERT }.take(8) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Alerts", nav.back, "Alerts only: Guard notifies you and never blocks on these. Every alert is kept in Activity › Journal.") }
        item {
            GuardCard {
                for (type in AlertType.entries) {
                    ToggleRow(type.label, auto.alertOn(type), type.summary) { on ->
                        repo.updateAutomation { it.copy(alertMask = if (on) it.alertMask or type.bit else it.alertMask and type.bit.inv()) }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                GuardCard(title = "Recent") {
                    val now = System.currentTimeMillis()
                    for (j in recent) LatestJournalRow(j, now) { j.packageName?.let(nav.openApp) }
                }
            }
        }
    }
}

// ---- Sleep (ex auto-tighten) ------------------------------------------------------------------

@Composable
private fun SleepPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val auto by repo.automation.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Put unused apps to sleep", nav.back) }
        item {
            GuardCard {
                ToggleRow(
                    "Sleep after ${auto.autoTightenDays} days unused",
                    auto.autoTighten,
                    "An app you haven't used for ${auto.autoTightenDays} days loses its network; opening it gives it back. Off unless you turn it on.",
                ) { on -> repo.updateAutomation { it.copy(autoTighten = on) } }
                val dormant = apps.filter { policies.policyFor(it.packageName).dormant }
                if (dormant.isNotEmpty()) SectionLabel("Asleep · ${dormant.size}", Modifier.padding(top = 8.dp))
                for (a in dormant) {
                    AppLine(a.packageName, a.label, nav.openApp) {
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { repo.restoreDormant(a.packageName) } }) { Text("Wake") }
                    }
                }
                if (auto.autoTighten) {
                    // IchthysOS: the check reads usage stats for every app and can take a while.
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            message = null
                            scope.launch {
                                val paused = try { withContext(Dispatchers.IO) { repo.runMaintenance() } } finally { checking = false }
                                message = if (paused.isEmpty()) "No unused apps to put to sleep." else "Put to sleep: " + paused.joinToString()
                            }
                        },
                    ) {
                        Text(if (checking) "Checking…" else "Check now")
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.muted) }
            }
        }
    }
}

// ---- Service health ---------------------------------------------------------------------------

@Composable
private fun HealthPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val status by vm.status.collectAsStateWithLifecycle()
    val journal by repo.journal.collectAsStateWithLifecycle()
    val denyNow by repo.denyNow.collectAsStateWithLifecycle()
    val events by repo.events.collectAsStateWithLifecycle()
    val failures = remember(journal) { journal.entries.asReversed().filter { it.journal == JournalKind.FIREWALL_FAILED }.take(5) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Service health", nav.back, "What the ichthys_guard platform service reports about itself.") }
        item {
            GuardCard {
                val h = status.health
                fun bit(flag: Int) = status.live && (h and flag) != 0
                KeyValue("Service reachable", if (status.live) "yes" else "NO", danger = !status.live)
                KeyValue("Firewall chain applied", if (bit(Health.FIREWALL)) "yes" else "NO", danger = !bit(Health.FIREWALL))
                KeyValue("Wi-Fi-only rules applied", if (bit(Health.METERED)) "yes" else "NO", danger = !bit(Health.METERED))
                KeyValue("Traffic monitor registered", if (bit(Health.MONITOR)) "yes" else "NO", danger = !bit(Health.MONITOR))
                KeyValue("Saved rules loaded", if (bit(Health.POLICY_LOADED)) "yes" else "NO", danger = !bit(Health.POLICY_LOADED))
                KeyValue("Tracker database", if (bit(Health.TRACKER_DB)) "loaded" else "not loaded")
                KeyValue("Shield settings writable", if (status.osWritable) "yes" else "NO", danger = !status.osWritable)
                KeyValue("Apps offline now", "${denyNow.size}")
                KeyValue("Events in memory", "${events.size}")
                if (!status.live)
                    Text("Without the service no live traffic is shown and network rules, lockdown and pause aren't enforced. Rules are saved and sent at the next restart or change. The data shields don't depend on it.", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (failures.isNotEmpty()) {
            item {
                GuardCard(title = "Firewall failures") {
                    val now = System.currentTimeMillis()
                    for (j in failures) LatestJournalRow(j, now) {}
                }
            }
        }
    }
}

// ---- Backup -----------------------------------------------------------------------------------

@Composable
private fun BackupPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val context = LocalContext.current
    val t = Guard.tokens
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null)
                scope.launch {
                    message =
                        try {
                            val json = withContext(Dispatchers.IO) { repo.exportJson() }
                            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
                            "Exported the whole setup"
                        } catch (e: Exception) {
                            "Export failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null)
                scope.launch {
                    message =
                        try {
                            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                            val n = withContext(Dispatchers.Default) { repo.importJson(text) }
                            "Backup imported: every app's preset and setup, the shield lists, the fake place and its spoofed values, schedules" + (if (n > 0) " and ${quantity(n, "host rule")}" else "")
                        } catch (e: IllegalArgumentException) {
                            e.message ?: "Not a Guard backup"
                        } catch (e: Exception) {
                            "Import failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                }
        }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Backup", nav.back) }
        item {
            GuardCard {
                Text(
                    "Export saves every app's preset and controls, lockdown and its list, schedules, the screen-off rule, alerts, sleep, EVERY shield list (contacts, calendar, call log, texts, clipboard, sensors, usage, radios, location, scans, NFC), the storage scopes, the master shields, the fake place and its spoofed values (country, carrier, time zone, language, VPN rule) as one JSON file. Import replaces the setup with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("guard-backup.json") }) { Text("Export") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Import") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

// ---- Tracker host rules (coming) ---------------------------------------------------------------

@Composable
private fun TrackerRulesPage(vm: GuardViewModel, nav: GuardNav, modifier: Modifier) {
    val repo = vm.repo
    val t = Guard.tokens
    val book by repo.rules.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val apps by repo.apps.collectAsStateWithLifecycle()
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }
    val hostLists = policies.policies.values.filter { it.allowedHosts.isNotEmpty() || it.blockedHosts.isNotEmpty() }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Per-app tracker host rules", nav.back, "Coming") }
        item {
            GuardCard {
                Text(HostRules.NOT_ENFORCED_NOTE, style = MaterialTheme.typography.bodySmall, color = t.muted)
                Text(
                    "Everything saved here (custom rules, per-app host lists, blocklist sources, tracker levels) is kept and will apply once the engine lands. Nothing is offered as a control until then.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (book.rules.isNotEmpty()) {
            item {
                GuardCard(title = "Saved custom rules · ${book.rules.size}") {
                    book.rules.sortedWith(compareBy({ it.packageName != com.android.settings.ichthys.guard.model.CustomRule.ANY_APP }, { it.pattern })).forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.pattern, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Tag(r.action.name, emphasized = r.action == RuleAction.ALLOW, danger = r.action == RuleAction.BLOCK)
                                    Text(if (r.appliesToAll) "All apps" else labels[r.packageName] ?: r.packageName, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            TextButton(onClick = { repo.removeRule(r.id) }) { Text("Delete") }
                        }
                        HorizontalDivider(color = t.hairline)
                    }
                }
            }
        }
        if (hostLists.isNotEmpty()) {
            item {
                GuardCard(title = "Saved per-app host lists") {
                    hostLists.sortedBy { labels[it.packageName] ?: it.packageName }.forEach { p ->
                        Text(labels[p.packageName] ?: p.packageName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).clickable { nav.openApp(p.packageName) })
                        (p.blockedHosts.map { it to RuleAction.BLOCK } + p.allowedHosts.map { it to RuleAction.ALLOW }).sortedBy { it.first }.forEach { (host, action) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Tag(action.name, emphasized = action == RuleAction.ALLOW, danger = action == RuleAction.BLOCK)
                                Text(host, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = {
                                    repo.updatePolicy(p.packageName) {
                                        if (action == RuleAction.BLOCK) it.copy(blockedHosts = it.blockedHosts - host) else it.copy(allowedHosts = it.allowedHosts - host)
                                    }
                                }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
        item {
            GuardCard(title = "Blocklist sources") {
                for (s in book.sources) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            Text(if (s.lastUpdatedMs > 0) "updated ${formatAgo(s.lastUpdatedMs)} · ${s.entryCount} entries" else "never downloaded", style = MaterialTheme.typography.bodySmall, color = t.muted)
                        }
                        Switch(checked = s.enabled, onCheckedChange = { repo.setSourceEnabled(s.id, it) })
                    }
                }
                Text("The system hosts file (HaGeZi Light) always stays on. These sources will add to it per app once enforced.", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---- shared -----------------------------------------------------------------------------------

@Composable
internal fun AppLine(pkg: String, label: String, onOpenApp: (String) -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpenApp(pkg) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg, Modifier.padding(end = 10.dp), size = 28.dp)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}
