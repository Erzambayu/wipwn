package com.wipwn.app.repository

import com.wipwn.app.util.MacSpoofer
import com.wipwn.app.util.ShellExecutor
import kotlinx.coroutines.delay

/**
 * WPS Rate-Limit Bypass — otomatis handle kalo router nge-lock WPS.
 *
 * Strategy:
 * 1. Detect WPS locked dari error callback
 * 2. Cooldown wait (configurable, default 60s)
 * 3. MAC spoof buat instant reset (router anggep device baru)
 * 4. Retry attack
 *
 * Combine cooldown + MAC spoof = maximum bypass chance.
 */
class RateLimitBypass(
    private val shell: ShellExecutor,
    private val macSpoofer: MacSpoofer,
    private val onLog: (String) -> Unit
) {

    data class BypassConfig(
        val cooldownMs: Long = 60_000L,
        val maxRetries: Int = 3,
        val useMacSpoof: Boolean = true,
        val useWifiToggle: Boolean = true,
        val iface: String = MacSpoofer.DEFAULT_IFACE
    )

    data class BypassResult(
        val success: Boolean,
        val retriesUsed: Int,
        val macChanged: Boolean,
        val newMac: String? = null,
        val error: String? = null
    )

    private var originalMac: String? = null

    /**
     * Attempt to bypass rate-limit.
     * Returns true kalo berhasil bypass dan siap retry attack.
     */
    suspend fun attemptBypass(
        config: BypassConfig = BypassConfig(),
        currentRetry: Int = 0
    ): BypassResult {
        if (currentRetry >= config.maxRetries) {
            return BypassResult(
                success = false,
                retriesUsed = currentRetry,
                macChanged = false,
                error = "Max retries (${config.maxRetries}) reached"
            )
        }

        onLog("[rate-limit] Bypass attempt ${currentRetry + 1}/${config.maxRetries}")

        // Step 1: Save original MAC kalo belum
        if (originalMac == null) {
            originalMac = macSpoofer.getCurrentMac(config.iface)
            onLog("[rate-limit] Original MAC: $originalMac")
        }

        // Step 2: MAC Spoof (instant reset)
        var macChanged = false
        var newMac: String? = null

        if (config.useMacSpoof) {
            onLog("[rate-limit] Spoofing MAC address...")
            val spoofResult = macSpoofer.spoofRandom(config.iface)
            if (spoofResult.success) {
                macChanged = true
                newMac = spoofResult.newMac
                onLog("[rate-limit] MAC spoofed: ${spoofResult.originalMac} → ${spoofResult.newMac}")
            } else {
                onLog("[rate-limit] MAC spoof gagal: ${spoofResult.error}")
                onLog("[rate-limit] Fallback ke cooldown wait...")
            }
        }

        // Step 3: WiFi toggle (force re-associate)
        if (config.useWifiToggle) {
            onLog("[rate-limit] Toggling WiFi...")
            shell.exec("svc wifi disable")
            delay(2000)
            shell.exec("svc wifi enable")
            delay(3000)
        }

        // Step 4: Cooldown wait kalo MAC spoof gagal
        if (!macChanged) {
            val cooldownSec = config.cooldownMs / 1000
            onLog("[rate-limit] Waiting cooldown ${cooldownSec}s...")
            val steps = 10
            val stepMs = config.cooldownMs / steps
            for (i in 1..steps) {
                delay(stepMs)
                val elapsed = (i * stepMs) / 1000
                onLog("[rate-limit] ⏳ ${elapsed}s / ${cooldownSec}s")
            }
        } else {
            // Short delay after MAC spoof
            onLog("[rate-limit] Short delay after MAC spoof...")
            delay(3000)
        }

        onLog("[rate-limit] Bypass attempt done — ready to retry")

        return BypassResult(
            success = true,
            retriesUsed = currentRetry + 1,
            macChanged = macChanged,
            newMac = newMac
        )
    }

    /**
     * Restore original MAC setelah selesai.
     */
    fun restoreOriginalMac(iface: String = MacSpoofer.DEFAULT_IFACE) {
        val orig = originalMac ?: return
        onLog("[rate-limit] Restoring original MAC: $orig")
        val result = macSpoofer.restore(iface, orig)
        if (result.success) {
            onLog("[rate-limit] MAC restored successfully")
        } else {
            onLog("[rate-limit] MAC restore failed: ${result.error}")
        }
        originalMac = null
    }
}
