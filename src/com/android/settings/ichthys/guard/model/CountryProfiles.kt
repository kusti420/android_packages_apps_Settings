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

/** One mobile network: its display name and PLMN (MCC + MNC, both kept as strings). */
data class Carrier(val name: String, val mcc: String, val mnc: String) {
    val plmn: String
        get() = mcc + mnc
}

/**
 * What the spoofed values page derives for a country: the language apps see, the Samsung sales
 * code (null = leave the real one) and a few real carriers to choose from.
 */
data class CountryProfile(
    /** ISO 3166-1 alpha-2, upper case. */
    val iso: String,
    val name: String,
    /** BCP-47, the country's main language. */
    val locale: String,
    val sales: String?,
    val carriers: List<Carrier>,
) {
    val mcc: String
        get() = carriers.first().mcc
}

/**
 * IchthysOS: the table behind the Spoofed values page. One line per carrier so a wrong MCC/MNC is
 * a one-line fix. Countries not listed here get a best-effort profile from [MCC_BY_ISO] (the
 * MccTable's country → MCC list) with "<Country> Mobile" as the carrier.
 */
object CountryProfiles {
    private fun c(name: String, mcc: String, mnc: String) = Carrier(name, mcc, mnc)

    val ALL: List<CountryProfile> =
        listOf(
            CountryProfile("US", "United States", "en-US", "XAA", listOf(
                c("T-Mobile", "310", "260"), c("AT&T", "310", "410"), c("Verizon", "311", "480"))),
            CountryProfile("CA", "Canada", "en-CA", "XAC", listOf(
                c("Rogers", "302", "720"), c("Bell", "302", "610"), c("Telus", "302", "220"))),
            CountryProfile("MX", "Mexico", "es-MX", "TCE", listOf(
                c("Telcel", "334", "020"), c("AT&T", "334", "050"), c("Movistar", "334", "030"))),
            CountryProfile("GB", "United Kingdom", "en-GB", "BTU", listOf(
                c("EE", "234", "30"), c("O2", "234", "10"), c("Vodafone", "234", "15"), c("Three", "234", "20"))),
            CountryProfile("IE", "Ireland", "en-IE", "3IE", listOf(
                c("Vodafone", "272", "01"), c("Three", "272", "05"), c("Eir", "272", "02"))),
            CountryProfile("DE", "Germany", "de-DE", "DBT", listOf(
                c("Telekom", "262", "01"), c("Vodafone", "262", "02"), c("o2", "262", "03"))),
            CountryProfile("AT", "Austria", "de-AT", "ATO", listOf(
                c("A1", "232", "01"), c("Magenta", "232", "03"), c("Drei", "232", "05"))),
            CountryProfile("CH", "Switzerland", "de-CH", "AUT", listOf(
                c("Swisscom", "228", "01"), c("Sunrise", "228", "02"), c("Salt", "228", "03"))),
            CountryProfile("FR", "France", "fr-FR", "XEF", listOf(
                c("Orange", "208", "01"), c("SFR", "208", "10"), c("Bouygues Telecom", "208", "20"), c("Free Mobile", "208", "15"))),
            CountryProfile("BE", "Belgium", "nl-BE", "PRO", listOf(
                c("Proximus", "206", "01"), c("Orange", "206", "10"), c("Telenet", "206", "20"))),
            CountryProfile("NL", "Netherlands", "nl-NL", "PHN", listOf(
                c("KPN", "204", "08"), c("Vodafone", "204", "04"), c("Odido", "204", "16"))),
            CountryProfile("LU", "Luxembourg", "fr-LU", "LUX", listOf(
                c("POST", "270", "01"), c("Orange", "270", "99"), c("Tango", "270", "77"))),
            CountryProfile("ES", "Spain", "es-ES", "PHE", listOf(
                c("Movistar", "214", "07"), c("Vodafone", "214", "01"), c("Orange", "214", "03"), c("Yoigo", "214", "04"))),
            CountryProfile("PT", "Portugal", "pt-PT", "TPH", listOf(
                c("MEO", "268", "06"), c("Vodafone", "268", "01"), c("NOS", "268", "03"))),
            CountryProfile("IT", "Italy", "it-IT", "ITV", listOf(
                c("TIM", "222", "01"), c("Vodafone", "222", "10"), c("WindTre", "222", "88"), c("Iliad", "222", "50"))),
            CountryProfile("MT", "Malta", "en-MT", null, listOf(
                c("GO", "278", "21"), c("Epic", "278", "01"), c("Melita", "278", "77"))),
            CountryProfile("GR", "Greece", "el-GR", "EUR", listOf(
                c("Cosmote", "202", "01"), c("Vodafone", "202", "05"), c("Nova", "202", "10"))),
            CountryProfile("CY", "Cyprus", "el-CY", null, listOf(
                c("Cyta", "280", "01"), c("Epic", "280", "10"), c("Primetel", "280", "20"))),
            CountryProfile("DK", "Denmark", "da-DK", "NEE", listOf(
                c("TDC", "238", "01"), c("Telenor", "238", "02"), c("3", "238", "06"), c("Telia", "238", "20"))),
            CountryProfile("SE", "Sweden", "sv-SE", "VDS", listOf(
                c("Telia", "240", "01"), c("Tele2", "240", "07"), c("Telenor", "240", "08"), c("Tre", "240", "02"))),
            CountryProfile("NO", "Norway", "nb-NO", "NEE", listOf(
                c("Telenor", "242", "01"), c("Telia", "242", "02"), c("Ice", "242", "14"))),
            CountryProfile("FI", "Finland", "fi-FI", "NEE", listOf(
                c("Telia", "244", "91"), c("Elisa", "244", "05"), c("DNA", "244", "12"))),
            CountryProfile("IS", "Iceland", "is-IS", null, listOf(
                c("Síminn", "274", "01"), c("Vodafone", "274", "02"), c("Nova", "274", "11"))),
            CountryProfile("EE", "Estonia", "et-EE", "SEB", listOf(
                c("Telia", "248", "01"), c("Elisa", "248", "02"), c("Tele2", "248", "03"))),
            CountryProfile("LV", "Latvia", "lv-LV", "SEB", listOf(
                c("LMT", "247", "01"), c("Tele2", "247", "02"), c("Bite", "247", "05"))),
            CountryProfile("LT", "Lithuania", "lt-LT", "SEB", listOf(
                c("Telia", "246", "01"), c("Bite", "246", "02"), c("Tele2", "246", "03"))),
            CountryProfile("PL", "Poland", "pl-PL", "XEO", listOf(
                c("Orange", "260", "03"), c("Play", "260", "06"), c("Plus", "260", "01"), c("T-Mobile", "260", "02"))),
            CountryProfile("CZ", "Czechia", "cs-CZ", "XEZ", listOf(
                c("T-Mobile", "230", "01"), c("O2", "230", "02"), c("Vodafone", "230", "03"))),
            CountryProfile("SK", "Slovakia", "sk-SK", null, listOf(
                c("Orange", "231", "01"), c("Slovak Telekom", "231", "02"), c("O2", "231", "06"))),
            CountryProfile("HU", "Hungary", "hu-HU", "XEH", listOf(
                c("Magyar Telekom", "216", "30"), c("Yettel", "216", "01"), c("Vodafone", "216", "70"))),
            CountryProfile("RO", "Romania", "ro-RO", "ROM", listOf(
                c("Orange", "226", "10"), c("Vodafone", "226", "01"), c("Digi", "226", "05"), c("Telekom", "226", "03"))),
            CountryProfile("BG", "Bulgaria", "bg-BG", null, listOf(
                c("Vivacom", "284", "01"), c("A1", "284", "05"), c("Yettel", "284", "03"))),
            CountryProfile("SI", "Slovenia", "sl-SI", null, listOf(
                c("Telekom Slovenije", "293", "41"), c("A1", "293", "40"), c("Telemach", "293", "70"))),
            CountryProfile("HR", "Croatia", "hr-HR", null, listOf(
                c("Hrvatski Telekom", "219", "01"), c("A1", "219", "10"), c("Telemach", "219", "02"))),
            CountryProfile("RS", "Serbia", "sr-RS", null, listOf(
                c("mts", "220", "03"), c("Yettel", "220", "01"), c("A1", "220", "05"))),
            CountryProfile("UA", "Ukraine", "uk-UA", "SEK", listOf(
                c("Kyivstar", "255", "03"), c("Vodafone", "255", "01"), c("lifecell", "255", "06"))),
            CountryProfile("MD", "Moldova", "ro-MD", null, listOf(
                c("Orange", "259", "01"), c("Moldcell", "259", "02"))),
            CountryProfile("BY", "Belarus", "ru-BY", null, listOf(
                c("A1", "257", "01"), c("MTS", "257", "02"), c("life:)", "257", "04"))),
            CountryProfile("RU", "Russia", "ru-RU", "SER", listOf(
                c("MTS", "250", "01"), c("MegaFon", "250", "02"), c("Beeline", "250", "99"), c("Tele2", "250", "20"))),
            CountryProfile("TR", "Türkiye", "tr-TR", "TUR", listOf(
                c("Turkcell", "286", "01"), c("Vodafone", "286", "02"), c("Türk Telekom", "286", "03"))),
            CountryProfile("GE", "Georgia", "ka-GE", null, listOf(
                c("Magti", "282", "02"), c("Silknet", "282", "01"), c("Beeline", "282", "04"))),
            CountryProfile("KZ", "Kazakhstan", "ru-KZ", null, listOf(
                c("Beeline", "401", "01"), c("Kcell", "401", "02"), c("Tele2", "401", "77"))),
            CountryProfile("IL", "Israel", "he-IL", "ILO", listOf(
                c("Partner", "425", "01"), c("Cellcom", "425", "02"), c("Pelephone", "425", "03"))),
            CountryProfile("JO", "Jordan", "ar-JO", null, listOf(
                c("Zain", "416", "01"), c("Orange", "416", "77"), c("Umniah", "416", "03"))),
            CountryProfile("SA", "Saudi Arabia", "ar-SA", "KSA", listOf(
                c("STC", "420", "01"), c("Mobily", "420", "03"), c("Zain", "420", "04"))),
            CountryProfile("AE", "United Arab Emirates", "ar-AE", "XSG", listOf(
                c("Etisalat", "424", "02"), c("du", "424", "03"))),
            CountryProfile("QA", "Qatar", "ar-QA", null, listOf(
                c("Ooredoo", "427", "01"), c("Vodafone", "427", "02"))),
            CountryProfile("KW", "Kuwait", "ar-KW", null, listOf(
                c("Zain", "419", "02"), c("Ooredoo", "419", "03"), c("stc", "419", "04"))),
            CountryProfile("BH", "Bahrain", "ar-BH", null, listOf(
                c("Batelco", "426", "01"), c("Zain", "426", "02"), c("stc", "426", "04"))),
            CountryProfile("OM", "Oman", "ar-OM", null, listOf(
                c("Omantel", "422", "02"), c("Ooredoo", "422", "03"))),
            CountryProfile("EG", "Egypt", "ar-EG", "EGY", listOf(
                c("Vodafone", "602", "02"), c("Orange", "602", "01"), c("Etisalat", "602", "03"), c("WE", "602", "04"))),
            CountryProfile("MA", "Morocco", "ar-MA", null, listOf(
                c("Maroc Telecom", "604", "01"), c("Orange", "604", "00"), c("inwi", "604", "02"))),
            CountryProfile("DZ", "Algeria", "ar-DZ", null, listOf(
                c("Djezzy", "603", "02"), c("Mobilis", "603", "01"), c("Ooredoo", "603", "03"))),
            CountryProfile("TN", "Tunisia", "ar-TN", null, listOf(
                c("Tunisie Telecom", "605", "02"), c("Ooredoo", "605", "03"), c("Orange", "605", "01"))),
            CountryProfile("NG", "Nigeria", "en-NG", null, listOf(
                c("MTN", "621", "30"), c("Airtel", "621", "20"), c("Glo", "621", "50"), c("9mobile", "621", "60"))),
            CountryProfile("GH", "Ghana", "en-GH", null, listOf(
                c("MTN", "620", "01"), c("Telecel", "620", "02"), c("AirtelTigo", "620", "03"))),
            CountryProfile("KE", "Kenya", "en-KE", null, listOf(
                c("Safaricom", "639", "02"), c("Airtel", "639", "03"), c("Telkom", "639", "07"))),
            CountryProfile("TZ", "Tanzania", "sw-TZ", null, listOf(
                c("Vodacom", "640", "04"), c("Airtel", "640", "05"), c("Tigo", "640", "02"))),
            CountryProfile("UG", "Uganda", "en-UG", null, listOf(
                c("MTN", "641", "10"), c("Airtel", "641", "01"))),
            CountryProfile("ET", "Ethiopia", "am-ET", null, listOf(
                c("Ethio Telecom", "636", "01"))),
            CountryProfile("ZA", "South Africa", "en-ZA", "XFA", listOf(
                c("MTN", "655", "10"), c("Vodacom", "655", "01"), c("Cell C", "655", "07"), c("Telkom", "655", "02"))),
            CountryProfile("IN", "India", "en-IN", "INS", listOf(
                c("Airtel", "404", "10"), c("Jio", "405", "840"), c("Vi", "404", "20"))),
            CountryProfile("PK", "Pakistan", "en-PK", null, listOf(
                c("Jazz", "410", "01"), c("Telenor", "410", "06"), c("Zong", "410", "04"), c("Ufone", "410", "03"))),
            CountryProfile("BD", "Bangladesh", "bn-BD", null, listOf(
                c("Grameenphone", "470", "01"), c("Robi", "470", "02"), c("Banglalink", "470", "03"))),
            CountryProfile("LK", "Sri Lanka", "si-LK", null, listOf(
                c("Dialog", "413", "02"), c("Mobitel", "413", "01"))),
            CountryProfile("CN", "China", "zh-CN", "CHC", listOf(
                c("China Mobile", "460", "00"), c("China Unicom", "460", "01"), c("China Telecom", "460", "11"))),
            CountryProfile("HK", "Hong Kong", "zh-HK", "TGY", listOf(
                c("CSL", "454", "00"), c("3", "454", "03"), c("SmarTone", "454", "06"), c("China Mobile HK", "454", "12"))),
            CountryProfile("TW", "Taiwan", "zh-TW", "BRI", listOf(
                c("Chunghwa Telecom", "466", "92"), c("Taiwan Mobile", "466", "97"), c("Far EasTone", "466", "01"))),
            CountryProfile("JP", "Japan", "ja-JP", "SJP", listOf(
                c("NTT Docomo", "440", "10"), c("KDDI", "440", "50"), c("SoftBank", "440", "20"), c("Rakuten Mobile", "440", "11"))),
            CountryProfile("KR", "South Korea", "ko-KR", "SKC", listOf(
                c("SK Telecom", "450", "05"), c("KT", "450", "08"), c("LG U+", "450", "06"))),
            CountryProfile("SG", "Singapore", "en-SG", "XSP", listOf(
                c("Singtel", "525", "01"), c("StarHub", "525", "05"), c("M1", "525", "03"))),
            CountryProfile("MY", "Malaysia", "ms-MY", "XME", listOf(
                c("Maxis", "502", "12"), c("Celcom", "502", "19"), c("Digi", "502", "16"), c("U Mobile", "502", "18"))),
            CountryProfile("TH", "Thailand", "th-TH", "THL", listOf(
                c("AIS", "520", "03"), c("TrueMove H", "520", "04"), c("dtac", "520", "05"))),
            CountryProfile("VN", "Vietnam", "vi-VN", "XXV", listOf(
                c("Viettel", "452", "04"), c("Mobifone", "452", "01"), c("Vinaphone", "452", "02"))),
            CountryProfile("PH", "Philippines", "en-PH", "XTC", listOf(
                c("Globe", "515", "02"), c("Smart", "515", "03"))),
            CountryProfile("ID", "Indonesia", "id-ID", "XID", listOf(
                c("Telkomsel", "510", "10"), c("Indosat", "510", "01"), c("XL", "510", "11"))),
            CountryProfile("AU", "Australia", "en-AU", "XSA", listOf(
                c("Telstra", "505", "01"), c("Optus", "505", "02"), c("Vodafone", "505", "03"))),
            CountryProfile("NZ", "New Zealand", "en-NZ", "NZC", listOf(
                c("Spark", "530", "05"), c("One NZ", "530", "01"), c("2degrees", "530", "24"))),
            CountryProfile("BR", "Brazil", "pt-BR", "ZTO", listOf(
                c("Vivo", "724", "06"), c("Claro", "724", "05"), c("TIM", "724", "04"))),
            CountryProfile("AR", "Argentina", "es-AR", "ARO", listOf(
                c("Movistar", "722", "07"), c("Claro", "722", "310"), c("Personal", "722", "341"))),
            CountryProfile("CL", "Chile", "es-CL", "CHO", listOf(
                c("Entel", "730", "01"), c("Movistar", "730", "02"), c("WOM", "730", "09"), c("Claro", "730", "03"))),
            CountryProfile("CO", "Colombia", "es-CO", "COO", listOf(
                c("Claro", "732", "101"), c("Movistar", "732", "123"), c("Tigo", "732", "103"))),
            CountryProfile("PE", "Peru", "es-PE", "PEO", listOf(
                c("Movistar", "716", "06"), c("Claro", "716", "10"), c("Entel", "716", "17"))),
            CountryProfile("EC", "Ecuador", "es-EC", null, listOf(
                c("Claro", "740", "01"), c("Movistar", "740", "00"), c("CNT", "740", "02"))),
            CountryProfile("VE", "Venezuela", "es-VE", null, listOf(
                c("Movilnet", "734", "03"), c("Movistar", "734", "04"), c("Digitel", "734", "02"))),
            CountryProfile("BO", "Bolivia", "es-BO", null, listOf(
                c("Entel", "736", "02"), c("Tigo", "736", "03"), c("Viva", "736", "01"))),
            CountryProfile("PY", "Paraguay", "es-PY", null, listOf(
                c("Tigo", "744", "04"), c("Personal", "744", "05"), c("Claro", "744", "02"))),
            CountryProfile("UY", "Uruguay", "es-UY", null, listOf(
                c("Antel", "748", "01"), c("Movistar", "748", "07"), c("Claro", "748", "10"))),
            CountryProfile("PA", "Panama", "es-PA", null, listOf(
                c("Más Móvil", "714", "01"), c("Tigo", "714", "02"), c("Digicel", "714", "03"))),
            CountryProfile("GT", "Guatemala", "es-GT", null, listOf(
                c("Claro", "704", "01"), c("Tigo", "704", "02"))),
            CountryProfile("DO", "Dominican Republic", "es-DO", null, listOf(
                c("Claro", "370", "02"), c("Altice", "370", "01"), c("Viva", "370", "04"))),
        )

    private val byIso: Map<String, CountryProfile> = ALL.associateBy { it.iso }

    /** The listed profile of [iso], or null when the country isn't in the table. */
    fun listed(iso: String): CountryProfile? = byIso[iso.uppercase(Locale.ROOT)]

    /**
     * The profile of [iso]: listed, or a best-effort one for any other country: its name from
     * the locale data, the MCC from [MCC_BY_ISO] with MNC "01", "<Country> Mobile" as the
     * carrier, the locale of the first language the system knows for the country, no sales code.
     */
    fun get(iso: String): CountryProfile {
        val code = iso.uppercase(Locale.ROOT)
        listed(code)?.let { return it }
        val name = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifEmpty { code }
        val mcc = MCC_BY_ISO[code] ?: "001"
        val language =
            Locale.getAvailableLocales()
                .firstOrNull { it.country == code && it.language.isNotEmpty() && it.variant.isEmpty() }
                ?.toLanguageTag()
                ?: "en-$code"
        return CountryProfile(code, name, language, null, listOf(Carrier("$name Mobile", mcc, "01")))
    }

    /** Every ISO country, listed ones first, then the rest by name: the country picker. */
    val PICKER: List<Pair<String, String>> by lazy {
        val listed = ALL.map { it.iso to it.name }
        val rest =
            Locale.getISOCountries()
                .filter { it !in byIso }
                .map { it to Locale("", it).getDisplayCountry(Locale.ENGLISH).ifEmpty { it } }
                .sortedBy { it.second }
        listed.sortedBy { it.second } + rest
    }

    /** Country → MCC (the first one where a country has several), after the MccTable. */
    val MCC_BY_ISO: Map<String, String> =
        mapOf(
            "GR" to "202", "NL" to "204", "BE" to "206", "FR" to "208", "MC" to "212", "AD" to "213",
            "ES" to "214", "HU" to "216", "BA" to "218", "HR" to "219", "RS" to "220", "XK" to "221",
            "IT" to "222", "VA" to "225", "RO" to "226", "CH" to "228", "CZ" to "230", "SK" to "231",
            "AT" to "232", "GB" to "234", "DK" to "238", "SE" to "240", "NO" to "242", "FI" to "244",
            "LT" to "246", "LV" to "247", "EE" to "248", "RU" to "250", "UA" to "255", "BY" to "257",
            "MD" to "259", "PL" to "260", "DE" to "262", "GI" to "266", "PT" to "268", "LU" to "270",
            "IE" to "272", "IS" to "274", "AL" to "276", "MT" to "278", "CY" to "280", "GE" to "282",
            "AM" to "283", "BG" to "284", "TR" to "286", "FO" to "288", "GL" to "290", "SM" to "292",
            "SI" to "293", "MK" to "294", "LI" to "295", "ME" to "297", "CA" to "302", "PM" to "308",
            "US" to "310", "PR" to "330", "VI" to "332", "MX" to "334", "JM" to "338", "GP" to "340",
            "MQ" to "340", "GF" to "340", "BB" to "342", "AG" to "344", "KY" to "346", "VG" to "348",
            "BM" to "350", "GD" to "352", "MS" to "354", "KN" to "356", "LC" to "358", "VC" to "360",
            "CW" to "362", "SX" to "362", "BQ" to "362", "AW" to "363", "BS" to "364", "AI" to "365",
            "DM" to "366", "CU" to "368", "DO" to "370", "HT" to "372", "TT" to "374", "TC" to "376",
            "AZ" to "400", "KZ" to "401", "BT" to "402", "IN" to "404", "PK" to "410", "AF" to "412",
            "LK" to "413", "MM" to "414", "LB" to "415", "JO" to "416", "SY" to "417", "IQ" to "418",
            "KW" to "419", "SA" to "420", "YE" to "421", "OM" to "422", "AE" to "424", "IL" to "425",
            "PS" to "425", "BH" to "426", "QA" to "427", "MN" to "428", "NP" to "429", "IR" to "432",
            "UZ" to "434", "TJ" to "436", "KG" to "437", "TM" to "438", "JP" to "440", "KR" to "450",
            "VN" to "452", "HK" to "454", "MO" to "455", "KH" to "456", "LA" to "457", "CN" to "460",
            "TW" to "466", "KP" to "467", "BD" to "470", "MV" to "472", "MY" to "502", "AU" to "505",
            "ID" to "510", "TL" to "514", "PH" to "515", "TH" to "520", "SG" to "525", "BN" to "528",
            "NZ" to "530", "NR" to "536", "PG" to "537", "TO" to "539", "SB" to "540", "VU" to "541",
            "FJ" to "542", "WF" to "543", "AS" to "544", "KI" to "545", "NC" to "546", "PF" to "547",
            "CK" to "548", "WS" to "549", "FM" to "550", "MH" to "551", "PW" to "552", "TV" to "553",
            "TK" to "554", "NU" to "555", "EG" to "602", "DZ" to "603", "MA" to "604", "TN" to "605",
            "LY" to "606", "GM" to "607", "SN" to "608", "MR" to "609", "ML" to "610", "GN" to "611",
            "CI" to "612", "BF" to "613", "NE" to "614", "TG" to "615", "BJ" to "616", "MU" to "617",
            "LR" to "618", "SL" to "619", "GH" to "620", "NG" to "621", "TD" to "622", "CF" to "623",
            "CM" to "624", "CV" to "625", "ST" to "626", "GQ" to "627", "GA" to "628", "CG" to "629",
            "CD" to "630", "AO" to "631", "GW" to "632", "SC" to "633", "SD" to "634", "RW" to "635",
            "ET" to "636", "SO" to "637", "DJ" to "638", "KE" to "639", "TZ" to "640", "UG" to "641",
            "BI" to "642", "MZ" to "643", "ZM" to "645", "MG" to "646", "RE" to "647", "YT" to "647",
            "ZW" to "648", "NA" to "649", "MW" to "650", "LS" to "651", "BW" to "652", "SZ" to "653",
            "KM" to "654", "ZA" to "655", "ER" to "657", "SH" to "658", "SS" to "659", "BZ" to "702",
            "GT" to "704", "SV" to "706", "HN" to "708", "NI" to "710", "CR" to "712", "PA" to "714",
            "PE" to "716", "AR" to "722", "BR" to "724", "CL" to "730", "CO" to "732", "VE" to "734",
            "BO" to "736", "GY" to "738", "EC" to "740", "PY" to "744", "SR" to "746", "UY" to "748",
            "FK" to "750",
        )

    /**
     * Emergency numbers a spoofed app is told about (the phone process answers the same table):
     * 112 and 911 everywhere, plus the country's own.
     */
    fun emergencyNumbers(iso: String): Set<String> {
        val extra =
            when (iso.uppercase(Locale.ROOT)) {
                "US", "CA", "MX" -> setOf("911")
                "GB", "IE" -> setOf("999")
                "AU" -> setOf("000")
                "NZ" -> setOf("111")
                "JP" -> setOf("110", "119")
                "CN" -> setOf("110", "119", "120")
                "IN" -> setOf("100", "101", "102", "108")
                "BR" -> setOf("190", "192", "193")
                "RU" -> setOf("101", "102", "103")
                "KR" -> setOf("119")
                else -> emptySet()
            }
        return setOf("112", "911") + extra
    }
}
