package com.wipwn.app.util

import kotlin.random.Random

/**
 * MAC Address Spoofing utility.
 *
 * Butuh root access. Bisa dipake buat:
 * - Bypass MAC filtering
 * - Reset WPS rate-limit (router anggep device baru)
 * - Avoid detection/logging
 */
class MacSpoofer(private val shell: ShellExecutor) {

    data class SpoofResult(
        val success: Boolean,
        val originalMac: String?,
        val newMac: String?,
        val error: String? = null
    )

    /**
     * Get current MAC address of interface.
     */
    fun getCurrentMac(iface: String = DEFAULT_IFACE): String? {
        val result = shell.exec("cat /sys/class/net/$iface/address 2>/dev/null")
        return if (result.isSuccess) result.out.firstOrNull()?.trim() else null
    }

    /**
     * Spoof MAC address ke random MAC.
     */
    fun spoofRandom(iface: String = DEFAULT_IFACE): SpoofResult {
        val originalMac = getCurrentMac(iface)
        val newMac = generateRandomMac()
        return spoofTo(iface, newMac, originalMac)
    }

    /**
     * Spoof MAC address ke MAC tertentu.
     */
    fun spoofTo(iface: String = DEFAULT_IFACE, targetMac: String, originalMac: String? = null): SpoofResult {
        val origMac = originalMac ?: getCurrentMac(iface)

        return try {
            // Bring interface down
            val down = shell.exec("ip link set $iface down")
            if (!down.isSuccess) {
                return SpoofResult(false, origMac, null, "Gagal matiin interface: ${down.stderr}")
            }

            // Change MAC
            val change = shell.exec("ip link set $iface address $targetMac")
            if (!change.isSuccess) {
                // Try alternative method
                val alt = shell.exec("ifconfig $iface hw ether $targetMac 2>/dev/null")
                if (!alt.isSuccess) {
                    shell.exec("ip link set $iface up")
                    return SpoofResult(false, origMac, null, "Gagal ganti MAC: ${change.stderr}")
                }
            }

            // Bring interface back up
            shell.exec("ip link set $iface up")

            // Verify
            val verifyMac = getCurrentMac(iface)
            val success = verifyMac?.equals(targetMac, ignoreCase = true) == true

            SpoofResult(
                success = success,
                originalMac = origMac,
                newMac = if (success) targetMac else verifyMac,
                error = if (!success) "MAC berubah tapi bukan ke target: $verifyMac" else null
            )
        } catch (e: Exception) {
            SpoofResult(false, origMac, null, "Exception: ${e.message}")
        }
    }

    /**
     * Restore MAC ke original.
     */
    fun restore(iface: String = DEFAULT_IFACE, originalMac: String): SpoofResult {
        return spoofTo(iface, originalMac)
    }

    /**
     * Generate random MAC address.
     * First byte: locally administered, unicast (bit 1 set, bit 0 clear)
     */
    fun generateRandomMac(): String {
        val bytes = ByteArray(6)
        Random.nextBytes(bytes)
        // Set locally administered bit, clear multicast bit
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Generate MAC yang mirip vendor tertentu (buat stealth).
     */
    fun generateVendorMac(vendorOui: String): String {
        val ouiParts = vendorOui.split(":").take(3)
        if (ouiParts.size != 3) return generateRandomMac()

        val randomPart = ByteArray(3)
        Random.nextBytes(randomPart)
        return ouiParts.joinToString(":") + ":" +
                randomPart.joinToString(":") { "%02X".format(it) }
    }

    companion object {
        const val DEFAULT_IFACE = "wlan0"

        // Common vendor OUIs buat stealth spoofing
        val COMMON_OUIS = mapOf(
            "Samsung" to "00:1A:8A",
            "Apple" to "00:1C:B3",
            "Huawei" to "00:E0:FC",
            "Xiaomi" to "64:09:80",
            "OnePlus" to "94:65:2D",
            "Google" to "F4:F5:D8"
        )
    }
}
