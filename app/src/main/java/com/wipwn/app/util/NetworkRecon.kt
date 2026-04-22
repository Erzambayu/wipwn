package com.wipwn.app.util

/**
 * Network Reconnaissance — post-exploit scanning.
 *
 * Setelah berhasil connect ke network, bisa:
 * - ARP scan buat discover devices
 * - Port scanning (common ports)
 * - Detect router admin panel
 * - Default credential check
 */
class NetworkRecon(private val shell: ShellExecutor) {

    data class DiscoveredDevice(
        val ip: String,
        val mac: String?,
        val hostname: String? = null,
        val openPorts: List<Int> = emptyList(),
        val isGateway: Boolean = false
    )

    data class ReconResult(
        val gateway: String?,
        val localIp: String?,
        val subnet: String?,
        val devices: List<DiscoveredDevice>,
        val adminPanelUrl: String? = null,
        val adminPanelReachable: Boolean = false
    )

    data class PortScanResult(
        val ip: String,
        val openPorts: List<PortInfo>
    )

    data class PortInfo(
        val port: Int,
        val service: String,
        val isOpen: Boolean
    )

    /**
     * Quick network recon — discover gateway, local IP, and nearby devices.
     */
    fun quickRecon(): ReconResult {
        val gateway = getGateway()
        val localIp = getLocalIp()
        val subnet = gateway?.substringBeforeLast(".") ?: localIp?.substringBeforeLast(".")

        val devices = if (subnet != null) arpScan(subnet) else emptyList()

        val adminUrl = gateway?.let { "http://$it" }
        val adminReachable = gateway?.let { checkPort(it, 80) || checkPort(it, 443) } ?: false

        return ReconResult(
            gateway = gateway,
            localIp = localIp,
            subnet = subnet?.let { "$it.0/24" },
            devices = devices,
            adminPanelUrl = adminUrl,
            adminPanelReachable = adminReachable
        )
    }

    /**
     * Get default gateway IP.
     */
    fun getGateway(): String? {
        val result = shell.exec("ip route | grep default | awk '{print \$3}' | head -1")
        return result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Get local IP address.
     */
    fun getLocalIp(): String? {
        val result = shell.exec(
            "ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print \$2}' | cut -d/ -f1 | head -1"
        )
        return result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * ARP scan — discover devices on the local network.
     * Uses ping sweep + ARP table.
     */
    fun arpScan(subnet: String): List<DiscoveredDevice> {
        // Quick ping sweep (background, parallel)
        shell.exec(
            "for i in \$(seq 1 254); do ping -c 1 -W 1 $subnet.\$i > /dev/null 2>&1 & done; wait"
        )

        // Read ARP table
        val arpResult = shell.exec("cat /proc/net/arp")
        val gateway = getGateway()

        return arpResult.out
            .drop(1) // skip header
            .mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4 && parts[2] != "0x0") {
                    val ip = parts[0]
                    val mac = parts[3].uppercase()
                    if (mac != "00:00:00:00:00:00") {
                        DiscoveredDevice(
                            ip = ip,
                            mac = mac,
                            isGateway = ip == gateway
                        )
                    } else null
                } else null
            }
            .distinctBy { it.ip }
            .sortedBy {
                it.ip.split(".").lastOrNull()?.toIntOrNull() ?: 999
            }
    }

    /**
     * Port scan — check common ports on a target IP.
     */
    fun portScan(targetIp: String, ports: List<Int> = COMMON_PORTS): PortScanResult {
        val openPorts = ports.mapNotNull { port ->
            val isOpen = checkPort(targetIp, port)
            if (isOpen) {
                PortInfo(
                    port = port,
                    service = PORT_SERVICES[port] ?: "unknown",
                    isOpen = true
                )
            } else null
        }
        return PortScanResult(ip = targetIp, openPorts = openPorts)
    }

    /**
     * Check if a specific port is open.
     */
    fun checkPort(ip: String, port: Int): Boolean {
        val result = shell.exec(
            "(echo > /dev/tcp/$ip/$port) 2>/dev/null && echo OPEN || echo CLOSED"
        )
        return result.out.any { it.trim() == "OPEN" }
    }

    /**
     * Try default credentials on router admin panel.
     */
    fun checkDefaultCredentials(gatewayIp: String): List<String> {
        val found = mutableListOf<String>()

        DEFAULT_CREDENTIALS.forEach { (user, pass) ->
            // Try HTTP basic auth
            val result = shell.exec(
                "curl -s -o /dev/null -w '%{http_code}' " +
                "--connect-timeout 3 --max-time 5 " +
                "-u '$user:$pass' 'http://$gatewayIp/' 2>/dev/null"
            )
            val code = result.out.firstOrNull()?.trim()
            if (code == "200" || code == "301" || code == "302") {
                found.add("$user:$pass (HTTP $code)")
            }
        }

        return found
    }

    companion object {
        val COMMON_PORTS = listOf(
            21, 22, 23, 25, 53, 80, 443, 445, 554,
            8080, 8443, 8888, 9090, 3389, 5900
        )

        val PORT_SERVICES = mapOf(
            21 to "FTP",
            22 to "SSH",
            23 to "Telnet",
            25 to "SMTP",
            53 to "DNS",
            80 to "HTTP",
            443 to "HTTPS",
            445 to "SMB",
            554 to "RTSP",
            3389 to "RDP",
            5900 to "VNC",
            8080 to "HTTP-Alt",
            8443 to "HTTPS-Alt",
            8888 to "HTTP-Alt2",
            9090 to "WebUI"
        )

        val DEFAULT_CREDENTIALS = listOf(
            "admin" to "admin",
            "admin" to "password",
            "admin" to "1234",
            "admin" to "12345",
            "admin" to "",
            "root" to "root",
            "root" to "admin",
            "root" to "",
            "user" to "user",
            "user" to "password"
        )
    }
}
