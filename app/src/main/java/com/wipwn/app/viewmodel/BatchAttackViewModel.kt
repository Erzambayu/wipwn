package com.wipwn.app.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wipwn.app.WipwnApp
import com.wipwn.app.data.AttackConfig
import com.wipwn.app.data.AttackResult
import com.wipwn.app.data.AttackType
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.repository.WpsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of a single target inside a batch queue.
 */
enum class BatchTargetState {
    PENDING,   // belum diserang
    RUNNING,   // lagi jalan
    SUCCESS,   // sukses (PIN/PSK ketemu)
    FAILED,    // gagal (ga vulnerable / error)
    SKIPPED,   // di-skip user (atau batch di-cancel sebelum ke situ)
    CANCELLED  // batch dicancel saat target ini lagi running
}

data class BatchTarget(
    val network: WifiNetwork,
    val state: BatchTargetState = BatchTargetState.PENDING,
    val result: AttackResult? = null,
    val errorMessage: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null
) {
    val durationMs: Long?
        get() = if (startedAt != null && endedAt != null) endedAt - startedAt else null
}

data class BatchUiState(
    val isRunning: Boolean = false,
    val currentIndex: Int = -1,          // index target yang lagi jalan; -1 kalo belum / udah selesai
    val targets: List<BatchTarget> = emptyList(),
    val currentLogs: List<String> = emptyList(), // log live dari target aktif
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val lastError: String? = null
) {
    val total: Int get() = targets.size
    val successCount: Int get() = targets.count { it.state == BatchTargetState.SUCCESS }
    val failedCount: Int get() = targets.count { it.state == BatchTargetState.FAILED || it.state == BatchTargetState.CANCELLED }
    val skippedCount: Int get() = targets.count { it.state == BatchTargetState.SKIPPED }
    val pendingCount: Int get() = targets.count { it.state == BatchTargetState.PENDING || it.state == BatchTargetState.RUNNING }
    val finished: Boolean get() = !isRunning && targets.isNotEmpty() && pendingCount == 0
}

/**
 * Batch / queue pixie-dust runner. Runs the same [WpsRepository.runAttack]
 * pipeline against a list of networks sequentially and accumulates a summary.
 *
 * We intentionally share [WpsRepository.attackEvents] with [AttackViewModel] —
 * the repo's attack lock guarantees only one attack runs at a time, and both
 * VMs can coexist (screens that aren't visible just accumulate state cheaply).
 */
class BatchAttackViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: WpsRepository = (app as WipwnApp).repository

    private val _state = MutableStateFlow(BatchUiState())
    val state: StateFlow<BatchUiState> = _state.asStateFlow()

    private var batchJob: Job? = null

    init {
        viewModelScope.launch {
            repo.attackEvents.collect { event ->
                if (!_state.value.isRunning) return@collect
                when (event) {
                    is WpsRepository.AttackEvent.Log -> _state.update { st ->
                        // Keep the live log bounded so we don't OOM during
                        // a long batch run.
                        val logs = (st.currentLogs + event.line).takeLast(MAX_LIVE_LOG_LINES)
                        st.copy(currentLogs = logs)
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Kick off the queue. Safe to call only when `isRunning == false`.
     * Runs each network in [networks] sequentially with the given [type]
     * (defaults to Pixie Dust since that's the main use case).
     */
    fun start(
        networks: List<WifiNetwork>,
        type: AttackType = AttackType.PIXIE_DUST,
        config: AttackConfig = AttackConfig()
    ) {
        if (_state.value.isRunning) return
        if (networks.isEmpty()) {
            _state.update { it.copy(lastError = "Belum ada target kepilih, bre.") }
            return
        }

        val initialTargets = networks.map { BatchTarget(network = it) }
        _state.value = BatchUiState(
            isRunning = true,
            currentIndex = -1,
            targets = initialTargets,
            startedAt = System.currentTimeMillis()
        )

        batchJob = viewModelScope.launch {
            for (index in initialTargets.indices) {
                // User may have cancelled mid-loop.
                if (!_state.value.isRunning) {
                    markRemainingSkipped(index)
                    return@launch
                }

                val target = _state.value.targets[index]
                val startTs = SystemClock.elapsedRealtime()
                _state.update { st ->
                    st.copy(
                        currentIndex = index,
                        currentLogs = listOf(
                            "──────────────",
                            "[batch] target ${index + 1}/${st.total}: ${target.network.displayName} (${target.network.bssid})"
                        ),
                        targets = st.targets.mapIndexed { i, t ->
                            if (i == index) t.copy(
                                state = BatchTargetState.RUNNING,
                                startedAt = System.currentTimeMillis()
                            ) else t
                        }
                    )
                }

                val result = runCatching {
                    repo.runAttack(target.network, type, customPin = null, config = config)
                }.getOrElse { e ->
                    AttackResult(
                        bssid = target.network.bssid,
                        ssid = target.network.ssid,
                        success = false,
                        error = com.wipwn.app.data.AttackError.Unknown(
                            "Exception: ${e.message}", e
                        )
                    )
                }

                val durMs = SystemClock.elapsedRealtime() - startTs
                val finalState = when {
                    !_state.value.isRunning -> BatchTargetState.CANCELLED
                    result.success -> BatchTargetState.SUCCESS
                    else -> BatchTargetState.FAILED
                }

                _state.update { st ->
                    st.copy(
                        targets = st.targets.mapIndexed { i, t ->
                            if (i == index) t.copy(
                                state = finalState,
                                result = result,
                                errorMessage = result.error?.message,
                                endedAt = System.currentTimeMillis()
                            ) else t
                        },
                        currentLogs = st.currentLogs + listOf(
                            "[batch] ${target.network.displayName} → ${finalState.name} (${durMs}ms)"
                        )
                    )
                }

                if (!_state.value.isRunning) {
                    markRemainingSkipped(index + 1)
                    return@launch
                }
            }

            _state.update {
                it.copy(
                    isRunning = false,
                    currentIndex = -1,
                    endedAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun cancel() {
        if (!_state.value.isRunning) return
        repo.cancelAttack()
        batchJob?.cancel()
        _state.update {
            it.copy(
                isRunning = false,
                endedAt = System.currentTimeMillis(),
                targets = it.targets.map { t ->
                    when (t.state) {
                        BatchTargetState.RUNNING -> t.copy(
                            state = BatchTargetState.CANCELLED,
                            endedAt = System.currentTimeMillis()
                        )
                        BatchTargetState.PENDING -> t.copy(state = BatchTargetState.SKIPPED)
                        else -> t
                    }
                }
            )
        }
    }

    fun clearResults() {
        if (_state.value.isRunning) return
        _state.value = BatchUiState()
    }

    fun retryFailed(type: AttackType = AttackType.PIXIE_DUST, config: AttackConfig = AttackConfig()) {
        val failed = _state.value.targets
            .filter { it.state == BatchTargetState.FAILED || it.state == BatchTargetState.CANCELLED }
            .map { it.network }
        if (failed.isNotEmpty()) start(failed, type, config)
    }

    private fun markRemainingSkipped(fromIndex: Int) {
        _state.update { st ->
            st.copy(
                isRunning = false,
                currentIndex = -1,
                endedAt = System.currentTimeMillis(),
                targets = st.targets.mapIndexed { i, t ->
                    if (i >= fromIndex && t.state == BatchTargetState.PENDING) {
                        t.copy(state = BatchTargetState.SKIPPED)
                    } else t
                }
            )
        }
    }

    companion object {
        private const val MAX_LIVE_LOG_LINES = 400
    }
}
