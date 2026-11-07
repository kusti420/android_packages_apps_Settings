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

import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * IchthysOS: the spoofed values that go with the fake place, as the platform reads them from
 * Settings.Secure `ichthys_location_profile` (ShieldTable.KEY_SPOOF_PROFILE):
 *
 * ```
 * {"country":"US","tz":"America/New_York","mcc":"310","mnc":"260","operator":"T-Mobile",
 *  "locale":"en-US","sales":"XAA","vpn":true}
 * ```
 * [country] is ISO 3166-1 alpha-2 in upper case; [mcc] 3 digits and [mnc] 2 or 3 digits, both
 * strings; [locale] (BCP-47) and [sales] (Samsung sales code) are left out of the JSON when
 * empty, which means "leave the real one". [vpn] true = spoofed apps are offline unless a VPN is
 * up. A missing key with the fake place on = only the position is faked, as before.
 */
data class SpoofProfile(
    val country: String,
    val tz: String,
    val mcc: String,
    val mnc: String,
    val operator: String,
    val locale: String? = null,
    val sales: String? = null,
    val vpn: Boolean = false,
) {
    val plmn: String
        get() = mcc + mnc

    /** The country's English name, for the page and the IP check. */
    val countryName: String
        get() = CountryProfiles.listed(country)?.name ?: Locale("", country).getDisplayCountry(Locale.ENGLISH).ifEmpty { country }

    /** Configuration.mnc as the platform derives it (an MNC that parses to 0 → MNC_ZERO, 0xFFFF). */
    val configurationMnc: Int
        get() = mnc.toIntOrNull().let { if (it == 0) 0xFFFF else it ?: 0 }

    /** The one Settings.Secure string, keys in the schema's order. */
    fun toJson(): String =
        JSONObject()
            .put("country", country.uppercase(Locale.ROOT))
            .put("tz", tz)
            .put("mcc", mcc)
            .put("mnc", mnc)
            .put("operator", operator)
            .apply {
                if (!locale.isNullOrEmpty()) put("locale", locale)
                if (!sales.isNullOrEmpty()) put("sales", sales)
            }
            .put("vpn", vpn)
            .toString()

    /** "Los Angeles, US · T-Mobile · America/New_York · VPN required" (the home and gear rows). */
    fun summary(placeName: String?): String =
        listOfNotNull(
                placeName?.takeIf { it.isNotBlank() }?.let { "$it, ${country.uppercase(Locale.ROOT)}" }
                    ?: countryName,
                operator.takeIf { it.isNotBlank() },
                tz.takeIf { it.isNotBlank() },
                if (vpn) "VPN required" else null,
            )
            .joinToString(" · ")

    companion object {
        /** Parses the Settings value; null when unset, empty or not the schema. */
        fun fromJson(text: String?): SpoofProfile? {
            if (text.isNullOrBlank()) return null
            return try {
                val o = JSONObject(text)
                val country = o.optString("country").uppercase(Locale.ROOT)
                if (country.length != 2) return null
                SpoofProfile(
                    country = country,
                    tz = o.optString("tz"),
                    mcc = o.optString("mcc"),
                    mnc = o.optString("mnc"),
                    operator = o.optString("operator"),
                    locale = o.optString("locale").takeIf { it.isNotEmpty() },
                    sales = o.optString("sales").takeIf { it.isNotEmpty() },
                    vpn = o.optBoolean("vpn", false),
                )
            } catch (e: JSONException) {
                null
            }
        }

        /**
         * The defaults for a country: its first listed carrier, language and sales code from
         * [CountryProfiles]; [tz] from the place search when it gave one, else the country's
         * first time zone. [followLanguage] false leaves the app language alone.
         */
        fun defaults(
            country: String,
            tz: String?,
            vpn: Boolean = false,
            followLanguage: Boolean = true,
        ): SpoofProfile {
            val iso = country.uppercase(Locale.ROOT)
            val cp = CountryProfiles.get(iso)
            val carrier = cp.carriers.first()
            return SpoofProfile(
                country = iso,
                tz = tz?.takeIf { it.isNotBlank() } ?: timeZonesOf(iso).firstOrNull() ?: "UTC",
                mcc = carrier.mcc,
                mnc = carrier.mnc,
                operator = carrier.name,
                locale = if (followLanguage) cp.locale else null,
                sales = cp.sales,
                vpn = vpn,
            )
        }

        /**
         * The zones of a country: its default zone first, then the ones the time zone picker
         * offers (tz data's country table). IchthysOS: ICU's list is alphabetical and includes
         * aliases, so its first entry picked America/Adak for the US, Atlantic/Azores for
         * Portugal and Asia/Calcutta for India; it remains the fallback.
         */
        fun timeZonesOf(country: String): List<String> {
            val iso = country.uppercase(Locale.ROOT)
            try {
                val ctz = com.android.i18n.timezone.TimeZoneFinder.getInstance()
                    .lookupCountryTimeZones(iso.lowercase(Locale.ROOT))
                if (ctz != null) {
                    val now = java.time.Instant.now()
                    val ids = (listOfNotNull(ctz.defaultTimeZoneId) +
                        ctz.getEffectiveTimeZoneMappingsAt(now.toEpochMilli())
                            .filter { it.isShownInPickerAt(now) }
                            .map { it.timeZoneId }).distinct()
                    if (ids.isNotEmpty()) return ids
                }
            } catch (e: RuntimeException) {
                // fall back to ICU
            }
            return try {
                android.icu.util.TimeZone.getAvailableIDs(iso).toList().filter { '/' in it }.distinct()
            } catch (e: RuntimeException) {
                emptyList()
            }
        }

        /** True for 3-digit MCC and 2–3-digit MNC strings. */
        fun validPlmn(mcc: String, mnc: String): Boolean =
            mcc.length == 3 && mcc.all { it.isDigit() } && mnc.length in 2..3 && mnc.all { it.isDigit() }
    }
}
