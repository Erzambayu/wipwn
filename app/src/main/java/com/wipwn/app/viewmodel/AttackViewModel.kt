package com.wipwn.app.viewmodel

import android.app.Application
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
import java.util.UUID

enum class AttackRunState {
    IDLE, PREPARING, RUNNING, SUCCESS, FAILED, CANCELLED
}

data class AttackSession(
    val sessionId: String,
    val targetBssid: String,
    val targetSsid: String,
    val attackType: AttackType,
    val customPin: String?,
    val startedAt: Long,
    val endedAt: Long? = null,
    val state: AttackRunState = AttackRunState.IDLE
)

data class AttackUiState(
    val isAttacking: Boolean = false,
    val runState: AttackRunState = AttackRunState.IDLE,
    val progress: String = "",
    val pinCount: Int = 0,
    val totalPins: Int = 0,
    val logs: List<String> = emptyList(),
    val filteredLogs: List<String> = emptyList(),
    val logFilter: String = "all",
    val lastResult: AttackResult? = null,
    val currentSession: AttackSession? = null,
    val recentSessions: List<AttackSession> = emptyList(),
    val lastAttackType: AttackType? = null,
    val lastCustomPin: String? = null
)

class AttackViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: WpsRepository = (app as WipwnApp).repository

    private val _state = MutableStateFlow(AttackUiState())
    val state: StateFlow<AttackUiState> = _state.asStateFlow()

    val selectedNetwork: StateFlow<WifiNetwork?> = repo.selectedNetwork

    private var attackJob: Job? = null

    init {
        // Forward repository events into local state. Runs for the lifetime
        // of the VM. Using viewModelScope ensures this is cancelled when VM is cleared.
        viewModelScope.launch {
            repo.attackEvents.collect { event ->
                when (event) {
                    is WpsRepository.AttackEvent.SessionStarted -> _state.update {
                        val existing = it.currentSession
                        val base = existing ?: repo.selectedNetwork.value?.let { n ->
                            AttackSession(
                                sessionId = event.sessionId,
                                targetBssid = n.bssid,
                                targetSsid = n.ssid,
                                attackType = it.lastAttackType ?: AttackType.KNOWN_PINS,
                                customPin = it.lastCustomPin,
                                startedAt = System.currentTimeMillis(),
                                state = AttackRunState.PREPARING
                            )
                        }
                        it.copy(
                            runState = AttackRunState.PREPARING,
                            currentSession = base?.copy(sessionId = event.sessionId)
                        )
                    }
                    is WpsRepository.AttackEvent.Log -> _state.update {
                        val newRunState = when {
                            event.line.contains("[prepare]") -> AttackRunState.PREPARING
                            event.line.contains("[run]") -> AttackRunState.RUNNING
                            else -> it.runState
                        }
                        val newLogs = it.logs + event.line
                        it.copy(
                            logs = newLogs,
                            filteredLogs = filterLogs(newLogs, it.logFilter),
                            runState = newRunState
                        )
                    }
                    is WpsRepository.AttackEvent.Progress -> _state.update { prev ->
                        prev.copy(
                            progress = event.message,
                            totalPins = if (event.totalPins >= 0) event.totalPins else prev.totalPins
                        )
                    }
                    is WpsRepository.AttackEvent.PinCountDelta -> _state.update {
                        it.copy(pinCount = it.pinCount + event.delta)
                    }
                    is WpsRepository.AttackEvent.Finished -> _state.update {
                        val finalState = when {
                            !it.isAttacking -> AttackRunState.CANCELLED
                            event.result.success -> AttackRunState.SUCCESS
                            else -> AttackRunState.FAILED
                        }
                        val closedSession = it.currentSession?.copy(
                            endedAt = System.currentTimeMillis(),
                            state = finalState
                        )
                        it.copy(
                            isAttacking = false,
                            runState = finalState,
                            lastResult = event.result,
                            currentSession = closedSession,
                            recentSessions = listOfNotNull(closedSession) + it.recentSessions
                        )
                    }
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Cancel any ongoing attack when VM is cleared
        attackJob?.cancel()
    }

    fun start(type: AttackType, customPin: String? = null, config: AttackConfig = AttackConfig()) {
        val network = repo.selectedNetwork.value ?: return
        if (_state.value.isAttacking) return

        _state.update {
            AttackUiState(
                isAttacking = true,
                runState = AttackRunState.PREPARING,
                progress = "Menyiapkan...",
                logs = emptyList(),
                currentSession = AttackSession(
                    sessionId = "local-${UUID.randomUUID()}",
                    targetBssid = network.bssid,
                    targetSsid = network.ssid,
                    attackType = type,
                    customPin = customPin,
                    startedAt = System.currentTimeMillis(),
                    state = AttackRunState.PREPARING
                ),
                recentSessions = _state.value.recentSessions.take(20),
                lastAttackType = type,
                lastCustomPin = customPin
            )
        }
        attackJob = viewModelScope.launch {
            repo.runAttack(network, type, customPin, config)
        }
    }

    fun cancel() {
        repo.cancelAttack()
        attackJob?.cancel()
        _state.update {
            it.copy(
                isAttacking = false,
                runState = AttackRunState.CANCELLED,
                progress = "Dibatalkan",
                currentSession = it.currentSession?.copy(
                    endedAt = System.currentTimeMillis(),
                    state = AttackRunState.CANCELLED
                )
            )
        }
    }

    fun retryLast() {
        val type = _state.value.lastAttackType ?: return
        start(type, _state.value.lastCustomPin)
    }

    fun clearLastResult() = _state.update { it.copy(lastResult = null) }
    fun clearLogs() = _state.update { it.copy(logs = emptyList(), filteredLogs = emptyList()) }
    
    fun setLogFilter(filter: String) = _state.update {
        it.copy(
            logFilter = filter,
            filteredLogs = filterLogs(it.logs, filter)
        )
    }
    
    private fun filterLogs(logs: List<String>, filter: String): List<String> {
        return when (filter) {
            "prepare" -> logs.filter { it.contains("[prepare]") }
            "run" -> logs.filter { it.contains("[run]") }
            "io" -> logs.filter { it.contains("[io]") }
            "result" -> logs.filter { it.contains("[result]") || it.startsWith("✓") || it.startsWith("✗") }
            else -> logs
        }
    }
}
