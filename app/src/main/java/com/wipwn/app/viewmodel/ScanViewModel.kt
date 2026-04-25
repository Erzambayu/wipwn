package com.wipwn.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wipwn.app.WipwnApp
import com.wipwn.app.data.NetworkSecurityAnalyzer
import com.wipwn.app.data.ScanSecurityAnalysis
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.repository.WpsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanUiState(
    val isScanning: Boolean = false,
    val networks: List<WifiNetwork> = emptyList(),
    val scanError: String? = null,
    val securityAnalysis: ScanSecurityAnalysis = ScanSecurityAnalysis()
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: WpsRepository = (app as WipwnApp).repository

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var previousChannelsByBssid: Map<String, Int> = emptyMap()

    val selectedNetwork: StateFlow<WifiNetwork?> = repo.selectedNetwork

    fun scan() {
        if (_state.value.isScanning) return
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, scanError = null) }
            repo.scanNetworks()
                .onSuccess { list ->
                    val analysis = NetworkSecurityAnalyzer.analyze(
                        networks = list,
                        previousChannelsByBssid = previousChannelsByBssid
                    )
                    previousChannelsByBssid = list.associate { it.bssid to it.channel }
                    _state.update {
                        it.copy(
                            isScanning = false,
                            networks = list,
                            securityAnalysis = analysis
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isScanning = false, scanError = "Scan gagal: ${e.message}")
                    }
                }
        }
    }

    fun select(network: WifiNetwork) = repo.selectNetwork(network)
    fun clearSelection() = repo.clearSelection()
    fun clearError() = _state.update { it.copy(scanError = null) }
}
