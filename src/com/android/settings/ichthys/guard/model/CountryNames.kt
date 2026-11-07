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

/**
 * IchthysOS: country names as IP-geolocation services report them (am.i.mullvad.net says "USA",
 * "UK", "Czech Republic") against ISO 3166 codes. Java's English display names differ for
 * those, so a plain name comparison reported a correct exit country as a mismatch.
 */
object CountryNames {
    private fun norm(s: String) =
        s.lowercase(Locale.ROOT).replace("&", "and").replace(Regex("[^a-z]"), "")

    private val ALIASES = mapOf(
        "usa" to "US", "unitedstatesofamerica" to "US", "uk" to "GB", "greatbritain" to "GB",
        "uae" to "AE", "czechrepublic" to "CZ", "southkorea" to "KR", "russia" to "RU",
        "hongkong" to "HK", "macau" to "MO", "turkey" to "TR", "turkiye" to "TR",
        "vietnam" to "VN", "macedonia" to "MK", "northmacedonia" to "MK", "ivorycoast" to "CI",
        "burma" to "MM", "palestine" to "PS", "vatican" to "VA", "taiwan" to "TW",
    )

    /** True when [name] (a country name, or a code) is the country [iso]. */
    fun matches(name: String, iso: String): Boolean {
        if (name.equals(iso, true)) return true
        val n = norm(name)
        if (n.isEmpty()) return false
        ALIASES[n]?.let { return it.equals(iso, true) }
        if (n == norm(Locale("", iso).getDisplayCountry(Locale.ENGLISH))) return true
        val code = Locale.getISOCountries()
            .firstOrNull { norm(Locale("", it).getDisplayCountry(Locale.ENGLISH)) == n }
        return code != null && code.equals(iso, true)
    }
}
