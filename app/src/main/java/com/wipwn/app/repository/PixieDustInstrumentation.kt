package com.wipwn.app.repository

import android.os.SystemClock
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.util.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumentasi khusus Pixie Dust attack:
 * - Narasi rencana serangan sebelum mulai
 * - Heartbeat ticker biar log ga diem selama proses
 * - Tail logcat dari tag library native
 *
 * Dipisah dari WpsRepository biar repo ga bloated dan
 * logic instrumentasi bisa di-reuse / di-test terpisah.
 */
class PixieDustInstrumentation(
    private val shell: ShellExecutor,
    private val onLog: (stage: String, message: String) -> Unit
) {

    /**
     * Emit narasi step-by-step sebelum pixie dust dimulai.
     * Biar user tau apa yang bakal terjadi.
     */
    fun narratePlan(network: WifiNetwork) {
        onLog("pixie", "═══ Pixie Dust attack mulai ═══")
        onLog("pixie", "Target SSID : ${network.ssid}")
        onLog("pixie", "Target BSSID: ${network.bssid}  (ch ${network.channel})")
        onLog("pixie", "Rencana yang bakal dijalanin library:")
        onLog("pixie", "  1. Spawn wpa_supplicant (root) di wlan0 + ctrl socket")
        onLog("pixie", "  2. Wait ~2s buat association ke AP")
        onLog("pixie", "  3. WPS-REG pake dummy PIN 12345670 → mancing M1-M3")
        onLog("pixie", "  4. Extract E-Nonce / PKE / PKR / AuthKey / E-Hash1 / E-Hash2 (timeout 20s)")
        onLog("pixie", "  5. Offline crack PIN via pixiewps (exploit RNG lemah)")
        onLog("pixie", "  6. Re-connect pake PIN yang ketemu → ambil PSK")
        onLog("pixie", "Kalo step 4 gagal → router ga vulnerable. Itu bukan bug app.")
        onLog("pixie", "──────────────────────────────")
    }

    /**
     * Heartbeat ticker — emit log tiap beberapa detik biar user tau
     * app masih jalan dan lagi di fase apa.
     *
     * Loop ini bakal di-cancel otomatis pas parent job di-cancel.
     */
    suspend fun heartbeat() {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            delay(HEARTBEAT_MS)
            val elapsed = (SystemClock.elapsedRealtime() - start) / 1000
            val phase = when {
                elapsed < 3 -> "booting wpa_supplicant"
                elapsed < 6 -> "associating ke AP + WPS-REG dummy PIN"
                elapsed < 26 -> "nangkep handshake (E-Hash1/2, E-Nonce, PKE/PKR)"
                else -> "cracking PIN via pixiewps / verify PSK"
            }
            onLog("pixie", "⏳ ${elapsed}s — $phase")
        }
    }

    /**
     * Tail logcat buat tag-tag yang dipake library native.
     * Spawn proses `su -c logcat` terpisah biar ga nge-block libsu shell.
     *
     * [procRef] dipake buat nyimpen reference ke proses biar bisa di-destroy
     * dari luar pas attack selesai.
     */
    suspend fun tailLogcat(
        procRef: AtomicReference<Process?>
    ) = withContext(Dispatchers.IO) {
        runCatching { shell.exec("logcat -c") }

        val tags = listOf(
            "PixieDustExecutor",
            "WpsExecutor",
            "WpsNative",
            "ConnectionService",
            "ConnectionHandler",
            "WpsConnectionManager"
        )
        val filter = tags.joinToString(" ") { "$it:V" } + " *:S"

        val process = runCatching {
            ProcessBuilder("su", "-c", "logcat -v brief $filter")
                .redirectErrorStream(true)
                .start()
        }.getOrElse { e ->
            onLog("lib", "logcat spawn failed: ${e.message}")
            return@withContext
        }
        procRef.set(process)

        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val cleaned = line.substringAfter("): ", line).trim()
                    if (cleaned.isNotEmpty()) onLog("lib", cleaned)
                }
            }
        }.onFailure { e ->
            if (procRef.get() != null) {
                onLog("lib", "logcat tail error: ${e.message}")
            }
        }
    }

    companion object {
        private const val HEARTBEAT_MS = 3000L
    }
}
