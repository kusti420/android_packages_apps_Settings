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
 * IchthysOS: Guard's own verdict on the "spoof.*" probes of IchthysProbe. The probe reports the
 * raw values an app got (its reply's "detail" object: country codes, PLMN, carrier name, time
 * zone, locale, Configuration mcc/mnc, emergency numbers, what the IP check saw); only Settings
 * knows the profile, so the comparison happens here. The keys are the probe's
 * (IchthysProbe README, "Spoofed values"); a missing key is simply not compared.
 */
object SpoofChecks {
    /** What the tester app should have got during a phase. */
    data class Expectation(
        /** The fake place is on and the probe isn't on the real-location list. */
        val spoofed: Boolean,
        /** The profile the platform hands to spoofed apps; null = position only. */
        val profile: SpoofProfile?,
        /** A VPN was up while the probe measured. */
        val vpnUp: Boolean,
    ) {
        /** The IP check of a spoofed app must come back offline when vpn=true and none is up. */
        val ipBlocked: Boolean
            get() = spoofed && profile?.vpn == true && !vpnUp
    }

    /** verdict ("pass", "fail", "info") and why. */
    data class Verdict(val verdict: String, val why: String)

    fun isSpoofProbe(id: String): Boolean = id.startsWith("spoof.")

    /**
     * Judges one probe from its raw values: null = no opinion (not a spoof probe, or no raw
     * values came back), so the probe's own verdict stands.
     */
    fun judge(id: String, values: Map<String, String>, e: Expectation): Verdict? {
        if (!isSpoofProbe(id)) return null
        if (id == "spoof.broadcasts") return Verdict("info", "informational")
        if (!e.spoofed) return Verdict("info", "not spoofed: the app gets the real values")
        val p = e.profile ?: return Verdict("info", "position only: no spoofed values set")
        if (values.isEmpty()) return null
        val c = Checker(values, p)
        var note: String? = null
        when (id) {
            "spoof.telephony_country" -> {
                c.country("sim_country", "network_country")
                c.plmn("sim_operator", "network_operator")
                c.operator("sim_operator_name", "network_operator_name")
                c.equal("roaming", "false")
            }
            "spoof.service_state" -> {
                c.plmn("operator_numeric")
                c.operator("operator_alpha_long", "operator_alpha_short")
                c.equal("roaming", "false")
                c.plmnList("nri_plmns")
                c.equal("cell_mccmnc", "none", "cell identity")
            }
            "spoof.configuration" -> {
                c.intEqual("config_mcc", p.mcc, "Configuration.mcc")
                if (p.mnc == "00") c.equal("config_mnc_zero", "true", "Configuration.mnc = MNC_ZERO")
                else c.intEqual("config_mnc", p.mnc, "Configuration.mnc")
                p.locale?.let { c.locale("default_locales", it); c.locale("config_locales", it) }
                c.country("prop_gsm.sim.operator.iso-country", "prop_gsm.operator.iso-country")
                c.plmn("prop_gsm.sim.operator.numeric", "prop_gsm.operator.numeric")
                c.operator("prop_gsm.sim.operator.alpha", "prop_gsm.operator.alpha")
                c.equal("prop_gsm.operator.isroaming", "false", "roaming")
                c.intEqual("prop_debug.tracing.mcc", p.mcc, "debug.tracing.mcc")
                c.intEqual("prop_debug.tracing.mnc", p.mnc, "debug.tracing.mnc")
                p.locale?.let { c.equal("prop_persist.sys.locale", it, "persist.sys.locale") }
                p.sales?.let { c.equal("prop_ro.boot.sales_code", it, "sales code") }
                // getprop_*: a child process reads the real values (known side channel): informational.
            }
            "spoof.timezone" -> {
                for (k in listOf("java_tz", "icu_tz", "zone_id", "prop_persist.sys.timezone", "env_TZ"))
                    c.equal(k, p.tz, "time zone ($k)")
            }
            "spoof.emergency" -> {
                val want = CountryProfiles.emergencyNumbers(p.country)
                for ((k, v) in values) {
                    if (!k.startsWith("emergency_")) continue
                    val number = k.removePrefix("emergency_")
                    if (number.isEmpty() || !number.all { it.isDigit() }) continue
                    c.equal(k, (number in want).toString(), "$number as emergency for ${p.country}")
                }
            }
            "spoof.country_detector" -> {
                val v = values["country_iso"]
                if (v != null) {
                    c.compared++
                    if (!(v.equals(p.country, true) || v == "null" || v == "blocked" || v == "unavailable"))
                        c.failures += "country detector $v, want ${p.country.lowercase(Locale.ROOT)}"
                }
            }
            "spoof.network_visibility" -> {
                c.equal("non_vpn_dns_servers", "0", "DNS servers of the real network")
                c.equal("dhcp_dns1_set", "false", "DHCP DNS visible")
                val ipCheck = values["ip_check"]
                if (e.ipBlocked) {
                    c.equal("ip_check", "offline", "IP check without a VPN")
                    c.equal("active_network", "false", "an active network without a VPN")
                } else if (ipCheck == "ok") {
                    c.compared++
                    val got = values["ip_country"].orEmpty()
                    if (got.isNotEmpty() && !got.equals(p.countryName, true) && !got.equals(p.country, true))
                        note = "IP country \"$got\", profile ${p.countryName}: the VPN exits elsewhere"
                }
            }
            else -> return null
        }
        return when {
            c.failures.isNotEmpty() -> Verdict("fail", c.failures.joinToString("; "))
            note != null -> Verdict("info", note)
            c.compared > 0 -> Verdict("pass", "matches the spoofed values")
            else -> null
        }
    }

    /** Compares the keys that are present; counts what it compared and collects what broke. */
    private class Checker(private val values: Map<String, String>, private val p: SpoofProfile) {
        var compared = 0
        val failures = ArrayList<String>()

        private fun get(key: String): String? = values[key]?.trim()

        fun equal(key: String, want: String, what: String = key) {
            val v = get(key) ?: return
            compared++
            if (!v.equals(want, true)) failures += "$what: $v, want $want"
        }

        fun intEqual(key: String, want: String, what: String) {
            val v = get(key) ?: return
            compared++
            if (v.toIntOrNull() != want.toIntOrNull()) failures += "$what: $v, want $want"
        }

        /** Multi-SIM values come comma-joined ("us,"): every non-empty part must match. */
        private fun allParts(v: String, ok: (String) -> Boolean): Boolean {
            val items = v.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "empty" }
            return items.isNotEmpty() && items.all(ok)
        }

        fun country(vararg keys: String) {
            for (k in keys) {
                val v = get(k) ?: continue
                compared++
                if (!allParts(v) { it.equals(p.country, true) })
                    failures += "$k: $v, want ${p.country.lowercase(Locale.ROOT)}"
            }
        }

        fun plmn(vararg keys: String) {
            for (k in keys) {
                val v = get(k) ?: continue
                compared++
                if (!allParts(v) { it == p.plmn }) failures += "$k: $v, want ${p.plmn}"
            }
        }

        /** A comma list of PLMNs, or "none" (no registrations reported: nothing to compare). */
        fun plmnList(key: String) {
            val v = get(key) ?: return
            if (v == "none" || v.isEmpty()) return
            compared++
            if (!allParts(v) { it == p.plmn }) failures += "$key: $v, want ${p.plmn}"
        }

        fun operator(vararg keys: String) {
            for (k in keys) {
                val v = get(k) ?: continue
                compared++
                if (!allParts(v) { it.equals(p.operator, true) })
                    failures += "$k: \"$v\", want \"${p.operator}\""
            }
        }

        /** "[en-US]", "en-US,et-EE" or "en_US": the first locale must be the profile's. */
        fun locale(key: String, want: String) {
            val v = get(key) ?: return
            compared++
            val first = v.trim('[', ']').split(',').firstOrNull()?.trim()?.replace('_', '-') ?: ""
            if (!first.equals(want, true)) failures += "$key: $v, want $want"
        }
    }
}
