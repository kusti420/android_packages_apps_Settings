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

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.settings.ichthys.guard.ui.theme.Guard
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guard's shared values: settings several parts of the system read, changed in one place.
 * Opened from the Rules tab.
 *
 * Home city and trips: the system's own location-based features (weather, sunset / sunrise for
 * Night Light, the AOD schedule and Living Sky) use the active place (a trip under way, else
 * home) instead of GPS. IchthysPlacesService works it out and switches at a trip's scheduled
 * start and end; a trip can also be started and ended by hand. The keys are hidden from apps
 * (SettingsProvider.isIchthysAllowlist).
 *
 * Units: metric or imperial for the weather everywhere it shows.
 */
@Composable
fun SharedValuesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val t = Guard.tokens
    val tick = rememberSettingsTick(SHARED_VALUE_URIS)
    val home = remember(tick) { Settings.Secure.getString(resolver, KEY_HOME) }
    val homeName = remember(tick) { Settings.Secure.getString(resolver, KEY_HOME_NAME) }
    val active = remember(tick) { Settings.Secure.getString(resolver, KEY_ACTIVE) }
    val lastFix = remember(tick) { Settings.Secure.getString(resolver, KEY_LAST_FIX) }
    val trips = remember(tick) { readTrips(Settings.Secure.getString(resolver, KEY_TRIPS)) }
    val units = remember(tick) { Settings.Secure.getString(resolver, KEY_UNITS) ?: UNITS_METRIC }
    val fake = remember(tick) { Settings.Secure.getString(resolver, KEY_FAKE) }
    val fakeName = remember(tick) { Settings.Secure.getString(resolver, KEY_FAKE_NAME) }
    val spoofOn = remember(tick) { Settings.Secure.getString(resolver, KEY_SPOOF_ON) != "0" }
    val realPkgs =
        remember(tick) {
            Settings.Secure.getString(resolver, KEY_REAL_PKGS)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        }
    val now = remember(tick) { System.currentTimeMillis() }

    // What the place search is for: null (closed), "home", "trip" or "fake".
    var searchFor by remember { mutableStateOf<String?>(null) }

    fun saveTrips(next: List<Trip>) = putSecure(context, KEY_TRIPS, writeTrips(next))

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Shared values", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Settings that several parts of the system use, changed in one place.",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        item {
            GuardCard(title = "Home") {
                Text(
                    "Weather and sunset / sunrise (Night Light, always-on display, Living Sky) " +
                        "are worked out for this city, not from GPS. Apps never see it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                if (searchFor == "home") {
                    PlaceSearch(
                        onPick = { place ->
                            putSecure(context, KEY_HOME, place.latLon)
                            putSecure(context, KEY_HOME_NAME, place.name)
                            searchFor = null
                        },
                        onCancel = { searchFor = null },
                    )
                } else if (home == null) {
                    Text(
                        "No home city set yet." +
                            (if (lastFix != null) " Until then the last GPS fix " +
                                "(${formatLatLon(lastFix)}) is used."
                            else " Until then the weather's own sunrise and sunset stand in."),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { searchFor = "home" }) { Text("Set home city") }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                homeName ?: formatLatLon(home),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                formatLatLon(home),
                                style = MaterialTheme.typography.bodySmall,
                                color = t.muted,
                            )
                        }
                        TextButton(onClick = { searchFor = "home" }) { Text("Change") }
                    }
                }
            }
        }
        item {
            GuardCard(title = "Travel") {
                val current = trips.filter { it.isUnderWay(now) }.maxByOrNull { it.start }
                Text(
                    when {
                        current != null ->
                            "Travelling: ${current.name}" +
                                (if (current.end > 0) " until ${formatWhen(context, current.end)}"
                                else " (until you end it)")
                        active != null -> "At home. A trip under way replaces the home city."
                        else -> "A trip under way replaces the home city."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Start a trip now or schedule it: it takes over from the home city at its " +
                        "start and hands back at its end. Trips without an end run until you " +
                        "end them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                val upcoming = trips.filter { !it.isUnderWay(now) && !it.isOver(now) }
                    .sortedBy { if (it.start > 0) it.start else Long.MAX_VALUE }
                val underWay = trips.filter { it.isUnderWay(now) }.sortedByDescending { it.start }
                val past = trips.filter { it.isOver(now) }.sortedByDescending { it.end }.take(5)
                for ((label, list) in
                    listOf("Under way" to underWay, "Planned" to upcoming, "Past" to past)) {
                    if (list.isEmpty()) continue
                    SectionLabel(label, Modifier.padding(top = 10.dp))
                    list.forEachIndexed { i, trip ->
                        if (i > 0) HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
                        TripRow(
                            trip,
                            now,
                            onChange = { next ->
                                saveTrips(trips.map { if (it.id == trip.id) next else it })
                            },
                            onDelete = { saveTrips(trips.filter { it.id != trip.id }) },
                        )
                    }
                }
                if (searchFor == "trip") {
                    PlaceSearch(
                        onPick = { place ->
                            saveTrips(
                                trips +
                                    Trip(
                                        UUID.randomUUID().toString(),
                                        place.name,
                                        place.latLon,
                                        start = 0,
                                        end = 0,
                                    )
                            )
                            searchFor = null
                        },
                        onCancel = { searchFor = null },
                    )
                } else {
                    TextButton(onClick = { searchFor = "trip" }) { Text("Add trip") }
                }
            }
        }
        item {
            GuardCard(title = "Fake location for apps") {
                Text(
                    "Installed apps get fixes at this place instead of your real position, and " +
                        "their Wi-Fi, cell and Bluetooth scans come back empty (each of them " +
                        "would place the phone). System apps, emergency calls and the apps you " +
                        "allow keep the real location. Play services / microG counts as an app: " +
                        "allow it only if your maps app needs it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                if (searchFor == "fake") {
                    PlaceSearch(
                        onPick = { place ->
                            putSecure(context, KEY_FAKE, place.latLon)
                            putSecure(context, KEY_FAKE_NAME, place.name)
                            putSecure(context, KEY_SPOOF_ON, "1")
                            searchFor = null
                        },
                        onCancel = { searchFor = null },
                    )
                } else if (fake == null) {
                    Text(
                        "Off: no fake place set. Apps get your real location when allowed.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { searchFor = "fake" }) { Text("Choose a fake place") }
                } else {
                    ToggleRow(
                        "Apps see ${fakeName ?: formatLatLon(fake)}",
                        spoofOn,
                        formatLatLon(fake),
                    ) { on ->
                        putSecure(context, KEY_SPOOF_ON, if (on) "1" else "0")
                    }
                    TextButton(onClick = { searchFor = "fake" }) { Text("Change place") }
                }
                SectionLabel(
                    "Real location · ${realPkgs.size}",
                    Modifier.padding(top = 8.dp),
                )
                val pm = context.packageManager
                for (pkg in realPkgs) {
                    val label =
                        remember(pkg) {
                            try {
                                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                            } catch (e: Exception) {
                                pkg
                            }
                        }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(pkg, size = 32.dp)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        TextButton(
                            onClick = {
                                putSecure(
                                    context,
                                    KEY_REAL_PKGS,
                                    (realPkgs - pkg).joinToString(","),
                                )
                            }
                        ) {
                            Text("Remove")
                        }
                    }
                }
                Text(
                    "Give an app the real location on its page: Apps, the app, Fake location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
        item {
            GuardCard(title = "Units") {
                Text(
                    "Temperature and wind speed of the weather, everywhere it shows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                Segmented(
                    listOf(UNITS_METRIC, UNITS_IMPERIAL),
                    selected = units,
                    label = { if (it == UNITS_IMPERIAL) "Imperial (°F)" else "Metric (°C)" },
                    onSelect = {
                        putSecure(context, KEY_UNITS, it)
                        refreshWeather(context)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** One trip: its place, its schedule, and what can be done with it now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripRow(trip: Trip, now: Long, onChange: (Trip) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val t = Guard.tokens
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(trip.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                trip.isOver(now) ->
                    (if (trip.start > 0) formatWhen(context, trip.start) + " – " else "") +
                        formatWhen(context, trip.end)
                trip.isUnderWay(now) ->
                    "Since ${formatWhen(context, trip.start)}" +
                        (if (trip.end > 0) " · ends ${formatWhen(context, trip.end)}"
                        else " · no end set")
                trip.start > 0 ->
                    "Starts ${formatWhen(context, trip.start)}" +
                        (if (trip.end > 0) " · ends ${formatWhen(context, trip.end)}" else "")
                else -> "Not scheduled"
            },
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        FlowRow {
            when {
                trip.isUnderWay(now) -> {
                    TextButton(onClick = { onChange(trip.copy(end = System.currentTimeMillis())) }) {
                        Text("End now")
                    }
                    TextButton(
                        onClick = {
                            pickDateTime(context, trip.end.takeIf { it > now }) { end ->
                                if (end > System.currentTimeMillis()) onChange(trip.copy(end = end))
                            }
                        }
                    ) {
                        Text(if (trip.end > 0) "Change end" else "Set end")
                    }
                }
                trip.isOver(now) -> {
                    TextButton(
                        onClick = {
                            onChange(trip.copy(start = System.currentTimeMillis(), end = 0))
                        }
                    ) {
                        Text("Go again now")
                    }
                }
                else -> {
                    TextButton(
                        onClick = {
                            val start = System.currentTimeMillis()
                            onChange(
                                trip.copy(start = start, end = trip.end.takeIf { it > start } ?: 0)
                            )
                        }
                    ) {
                        Text("Start now")
                    }
                    TextButton(
                        onClick = {
                            pickDateTime(context, trip.start.takeIf { it > 0 }) { start ->
                                onChange(
                                    trip.copy(
                                        start = start,
                                        end = trip.end.takeIf { it > start } ?: 0,
                                    )
                                )
                            }
                        }
                    ) {
                        Text(if (trip.start > 0) "Change start" else "Schedule")
                    }
                    if (trip.start > 0) {
                        TextButton(
                            onClick = {
                                pickDateTime(context, trip.end.takeIf { it > 0 }) { end ->
                                    if (end > trip.start) onChange(trip.copy(end = end))
                                }
                            }
                        ) {
                            Text(if (trip.end > 0) "Change end" else "Set end")
                        }
                    }
                }
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** A city search (Open-Meteo, the weather's provider); [onPick] gets the chosen place. */
@Composable
private fun PlaceSearch(onPick: (Place) -> Unit, onCancel: () -> Unit) {
    val t = Guard.tokens
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun search() {
        val q = query.trim()
        if (q.length < 2) return
        searching = true
        message = null
        scope.launch {
            val found = withContext(Dispatchers.IO) { searchPlaces(q) }
            searching = false
            results = found.orEmpty()
            message =
                when {
                    found == null -> "Search failed. Check the connection and try again."
                    found.isEmpty() -> "No place called \"$q\" found."
                    else -> null
                }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("City or town") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Row {
        TextButton(onClick = { search() }, enabled = !searching) {
            Text(if (searching) "Searching…" else "Search")
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
    Text(
        "The search goes to Open-Meteo, the weather's provider.",
        style = MaterialTheme.typography.bodySmall,
        color = t.muted,
    )
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.muted) }
    results.forEachIndexed { i, place ->
        if (i > 0) HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
        Column(Modifier.fillMaxWidth().clickable { onPick(place) }.padding(vertical = 10.dp)) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge)
            Text(place.detail, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
    }
}

/** A search result: [name] as it is shown later, [detail] to tell same-named places apart. */
private data class Place(val name: String, val detail: String, val latLon: String)

/**
 * A trip as IchthysPlacesService reads it: under way from [start] until [end] (epoch ms, 0 =
 * not set; no end = until ended by hand).
 */
private data class Trip(
    val id: String,
    val name: String,
    val latLon: String,
    val start: Long,
    val end: Long,
) {
    fun isUnderWay(now: Long) = start in 1..now && (end == 0L || now < end)

    fun isOver(now: Long) = end in 1..now
}

private fun readTrips(json: String?): List<Trip> =
    try {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Trip(
                o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                o.optString("name"),
                String.format(Locale.ROOT, "%.4f,%.4f", o.getDouble("lat"), o.getDouble("lon")),
                o.optLong("start"),
                o.optLong("end"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

private fun writeTrips(trips: List<Trip>): String {
    val array = JSONArray()
    for (trip in trips) {
        val (lat, lon) = trip.latLon.split(',').map { it.toDouble() }
        array.put(
            JSONObject()
                .put("id", trip.id)
                .put("name", trip.name)
                .put("lat", lat)
                .put("lon", lon)
                .put("start", trip.start)
                .put("end", trip.end)
        )
    }
    return array.toString()
}

/** Open-Meteo's geocoding (the weather's own provider); null when the request failed. */
private fun searchPlaces(query: String): List<Place>? =
    try {
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val url =
            URL(
                "https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
                    "&language=" + lang + "&name=" + URLEncoder.encode(query, "UTF-8")
            )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) null
            else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val array = json.optJSONArray("results")
                (0 until (array?.length() ?: 0)).map { i ->
                    val o = array!!.getJSONObject(i)
                    Place(
                        name = o.getString("name"),
                        detail =
                            listOf(o.optString("admin1"), o.optString("country"))
                                .filter { it.isNotEmpty() }
                                .joinToString(", "),
                        latLon =
                            String.format(
                                Locale.ROOT,
                                "%.4f,%.4f",
                                o.getDouble("latitude"),
                                o.getDouble("longitude"),
                            ),
                    )
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

/** A date, then a time; [onPicked] gets epoch ms. [initial] null = now. */
private fun pickDateTime(context: Context, initial: Long?, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { initial?.let { timeInMillis = it } }
    DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            cal.set(year, month, day, hour, minute, 0)
                            cal.set(Calendar.MILLISECOND, 0)
                            onPicked(cal.timeInMillis)
                        },
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        DateFormat.is24HourFormat(context),
                    )
                    .show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        )
        .show()
}

private fun formatWhen(context: Context, ms: Long): String =
    DateFormat.getMediumDateFormat(context).format(Date(ms)) + " " +
        DateFormat.getTimeFormat(context).format(Date(ms))

private fun formatLatLon(value: String): String {
    val parts = value.split(',')
    val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return value
    val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return value
    return String.format(
        Locale.getDefault(),
        "%.2f° %s, %.2f° %s",
        kotlin.math.abs(lat),
        if (lat >= 0) "N" else "S",
        kotlin.math.abs(lon),
        if (lon >= 0) "E" else "W",
    )
}

private fun putSecure(context: Context, key: String, value: String) {
    Settings.Secure.putString(context.contentResolver, key, value)
}

/** Asks OmniJaws for fresh weather now (its receiver's FORCE_UPDATE). */
private fun refreshWeather(context: Context) {
    context.sendBroadcast(
        Intent("org.omnirom.omnijaws.FORCE_UPDATE").setPackage("org.omnirom.omnijaws")
    )
}

private const val KEY_HOME = "ichthys_location_home"
private const val KEY_HOME_NAME = "ichthys_location_home_name"
private const val KEY_TRIPS = "ichthys_location_trips"
private const val KEY_ACTIVE = "ichthys_location_active"
private const val KEY_LAST_FIX = "ichthys_location_last_fix"
private const val KEY_UNITS = "ichthys_units"
private const val KEY_FAKE = "ichthys_location_fake"
private const val KEY_FAKE_NAME = "ichthys_location_fake_name"
private const val KEY_SPOOF_ON = "ichthys_location_spoof"
private const val KEY_REAL_PKGS = "ichthys_location_real_pkgs"
private const val UNITS_METRIC = "metric"
private const val UNITS_IMPERIAL = "imperial"

private val SHARED_VALUE_URIS =
    listOf(
            KEY_HOME,
            KEY_HOME_NAME,
            KEY_TRIPS,
            KEY_ACTIVE,
            KEY_LAST_FIX,
            KEY_UNITS,
            KEY_FAKE,
            KEY_FAKE_NAME,
            KEY_SPOOF_ON,
            KEY_REAL_PKGS,
        )
        .map { Settings.Secure.getUriFor(it) }
