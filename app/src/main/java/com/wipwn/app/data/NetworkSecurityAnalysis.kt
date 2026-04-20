package com.wipwn.app.data

import kotlin.math.abs

data class RouterFingerprint(
    val oui: String,
    val vendor: String,
    val likelyModelFamily: String?,
    val hardeningChecklist: List<String>
)

data class RogueAlert(
    val ssid: String,
    val severity: RogueSeverity,
    val reason: String,
    val involvedBssids: List<String>
)

enum class RogueSeverity { LOW, MEDIUM, HIGH }

data class ChannelUsage(
    val channel: Int,
    val networkCount: Int,
    val strongestRssi: Int
)

data class ChannelInterferenceMap(
    val bandLabel: String,
    val usage: List<ChannelUsage>,
    val recommendedChannels: List<Int>
)

data class NetworkInsight(
    val fingerprint: RouterFingerprint?,
    val anomalyTags: List<String>
)

data class ScanSecurityAnalysis(
    val rogueAlerts: List<RogueAlert> = emptyList(),
    val map2Ghz: ChannelInterferenceMap = ChannelInterferenceMap("2.4 GHz", emptyList(), emptyList()),
    val map5Ghz: ChannelInterferenceMap = ChannelInterferenceMap("5 GHz", emptyList(), emptyList()),
    val vendorDistribution: Map<String, Int> = emptyMap(),
    val insightsByBssid: Map<String, NetworkInsight> = emptyMap()
)

object NetworkSecurityAnalyzer {
    fun analyze(
        networks: List<WifiNetwork>,
        previousChannelsByBssid: Map<String, Int>
    ): ScanSecurityAnalysis {
        val fingerprintsByBssid = networks.associate { n ->
            n.bssid to fingerprintFromBssid(n.bssid)
        }

        val insights = networks.associate { n ->
            val fingerprint = fingerprintsByBssid[n.bssid]
            val tags = buildList {
                if (n.wpsEnabled) add("WPS enabled")
                val prevChannel = previousChannelsByBssid[n.bssid]
                if (prevChannel != null && prevChannel != n.channel) {
                    add("Channel hop $prevChannel -> ${n.channel}")
                }
            }
            n.bssid to NetworkInsight(fingerprint = fingerprint, anomalyTags = tags)
        }

        val rogueAlerts = detectRogueAlerts(networks, fingerprintsByBssid)
        val map2G = buildChannelMap(networks, is2G = true)
        val map5G = buildChannelMap(networks, is2G = false)
        val vendorDist = fingerprintsByBssid.values
            .groupingBy { it.vendor }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .toMap()

        return ScanSecurityAnalysis(
            rogueAlerts = rogueAlerts,
            map2Ghz = map2G,
            map5Ghz = map5G,
            vendorDistribution = vendorDist,
            insightsByBssid = insights
        )
    }

    private fun detectRogueAlerts(
        networks: List<WifiNetwork>,
        fingerprintsByBssid: Map<String, RouterFingerprint>
    ): List<RogueAlert> {
        val bySsid = networks
            .filter { it.ssid.isNotBlank() }
            .groupBy { it.ssid.trim() }

        return buildList {
            bySsid.forEach { (ssid, aps) ->
                if (aps.size < 2) return@forEach

                val bssids = aps.map { it.bssid }
                val vendors = aps.mapNotNull { fingerprintsByBssid[it.bssid]?.vendor }.toSet()
                val capabilities = aps.map { normalizeCaps(it.capabilities) }.toSet()
                val channels = aps.map { it.channel }.filter { it > 0 }.distinct()

                if (vendors.size >= 2 && capabilities.size >= 2) {
                    add(
                        RogueAlert(
                            ssid = ssid,
                            severity = RogueSeverity.HIGH,
                            reason = "SSID kembar dari vendor berbeda + security profile beda",
                            involvedBssids = bssids
                        )
                    )
                } else if (capabilities.size >= 2) {
                    add(
                        RogueAlert(
                            ssid = ssid,
                            severity = RogueSeverity.MEDIUM,
                            reason = "SSID sama tapi mode security beda (indikasi evil twin)",
                            involvedBssids = bssids
                        )
                    )
                } else if (channels.size >= 2 && channels.any { ch -> channels.any { other -> abs(ch - other) >= 8 } }) {
                    add(
                        RogueAlert(
                            ssid = ssid,
                            severity = RogueSeverity.LOW,
                            reason = "SSID sama muncul di channel berjauhan, perlu verifikasi mesh/rogue",
                            involvedBssids = bssids
                        )
                    )
                }
            }
        }.sortedByDescending { it.severity.ordinal }
    }

    private fun buildChannelMap(networks: List<WifiNetwork>, is2G: Boolean): ChannelInterferenceMap {
        val filtered = networks.filter { n ->
            if (is2G) n.frequency in 2400..2500 else n.frequency in 4900..5900
        }
        val grouped = filtered
            .filter { it.channel > 0 }
            .groupBy { it.channel }
            .map { (ch, list) ->
                ChannelUsage(
                    channel = ch,
                    networkCount = list.size,
                    strongestRssi = list.maxOf { it.rssi }
                )
            }
            .sortedBy { it.channel }

        val candidates = if (is2G) listOf(1, 6, 11) else grouped.map { it.channel }.distinct().sorted()
        val usageByChannel = grouped.associateBy { it.channel }
        val recommended = candidates
            .sortedBy { usageByChannel[it]?.networkCount ?: 0 }
            .take(3)

        return ChannelInterferenceMap(
            bandLabel = if (is2G) "2.4 GHz" else "5 GHz",
            usage = grouped,
            recommendedChannels = recommended
        )
    }

    private fun normalizeCaps(caps: String): String {
        val upper = caps.uppercase()
        return when {
            "WPA3" in upper -> "WPA3"
            "WPA2" in upper -> "WPA2"
            "WPA" in upper -> "WPA"
            "WEP" in upper -> "WEP"
            else -> "OPEN"
        }
    }

    private fun fingerprintFromBssid(bssid: String): RouterFingerprint {
        val oui = bssid
            .split(":")
            .take(3)
            .joinToString(":") { it.uppercase() }
            .ifBlank { "UNKNOWN" }
        val info = OUI_DB[oui] ?: VendorInfo(
            vendor = "Unknown",
            modelFamily = null,
            checklist = DEFAULT_CHECKLIST
        )
        return RouterFingerprint(
            oui = oui,
            vendor = info.vendor,
            likelyModelFamily = info.modelFamily,
            hardeningChecklist = info.checklist
        )
    }

    private data class VendorInfo(
        val vendor: String,
        val modelFamily: String?,
        val checklist: List<String>
    )

    private val DEFAULT_CHECKLIST = listOf(
        "Disable WPS PIN mode",
        "Use WPA2/WPA3 only",
        "Update firmware regularly"
    )

    private val OUI_DB: Map<String, VendorInfo> = mapOf(
        "FC:EC:DA" to VendorInfo(
            vendor = "TP-Link",
            modelFamily = "Archer series (possible)",
            checklist = listOf(
                "Disable WPS after pairing",
                "Enable WPA2/WPA3 mixed or WPA3",
                "Disable remote management from WAN"
            )
        ),
        "F4:F2:6D" to VendorInfo(
            vendor = "ASUS",
            modelFamily = "RT series (possible)",
            checklist = listOf(
                "Turn off WPS",
                "Enable AiProtection / IPS",
                "Use non-default admin username/password"
            )
        ),
        "E8:DE:27" to VendorInfo(
            vendor = "Xiaomi",
            modelFamily = "Mi Router series (possible)",
            checklist = listOf(
                "Disable legacy encryption modes",
                "Separate guest SSID from LAN",
                "Keep router firmware on latest stable"
            )
        ),
        "AC:86:74" to VendorInfo(
            vendor = "Ubiquiti",
            modelFamily = "UniFi AP (possible)",
            checklist = listOf(
                "Use WPA2-Enterprise/WPA3 if available",
                "Disable open management networks",
                "Rotate admin credentials periodically"
            )
        )
    )
}

