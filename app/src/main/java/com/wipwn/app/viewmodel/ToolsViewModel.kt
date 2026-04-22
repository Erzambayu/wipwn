package com.wipwn.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wipwn.app.WipwnApp
import com.wipwn.app.data.AttackConfig
import com.wipwn.app.data.AttackResult
import com.wipwn.app.data.AttackType
import com.wipwn.app.data.CredentialExporter
import com.wipwn.app.data.PinGenerator
import com.wipwn.app.data.VulnerabilityAssessor
import com.wipwn.app.data.NetworkSecurityAnalyzer
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.repository.WpsRepository
import com.wipwn.app.util.MacSpoofer
import com.wipwn.app.util.NetworkRecon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── MAC Spoof State ────────────────────────────────────────────────

data class MacSpoofState(
    val currentMac: String? = null,
    val originalMac: String? = null,
    val isSpoofed: Boolean = false,
    val isWorking: Boolean = false,
    val lastError: String? = null
)

// ── Recon State ────────────────────────────────────────────────────

data class ReconState(
    val isRunning: Boolean = false,
    val result: NetworkRecon.ReconResult? = null,
    val portScanResult: NetworkRecon.PortScanResult? = null,
    val defaultCreds: List<String> = emptyList(),
    val logs: List<String> = emptyList(),
    val error: String? = null
)

// ── Vulnerability Assessment State ─────────────────────────────────

data class VulnAssessmentState(
    val assessment: VulnerabilityAssessor.Assessment? = null,
    val generatedPins: List<PinGenerator.GeneratedPin> = emptyList()
)

// ── Advanced Attack State ──────────────────────────────────────────

data class AdvancedAttackState(
    val isRunning: Boolean = false,
    val currentType: AttackType? = null,
    val logs: List<String> = emptyList(),
    val lastResult: AttackResult? = null,
    val error: String? = null,
    // Evil Twin specific
    val evilTwinActive: Boolean = false,
    val capturedCreds: List<String> = emptyList(),
    // Deauth specific
    val deauthActive: Boolean = false
)

/**
 * ViewModel for advanced tools:
 * - MAC Spoofing
 * - Network Recon
 * - Vulnerability Assessment
 * - PIN Generator
 * - Credential Export
 * - Advanced attacks (Deauth, Handshake, PMKID, Evil Twin)
 */
class ToolsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: WpsRepository = (app as WipwnApp).repository

    // ── Scan + Target selection (inline, biar ga perlu bolak-balik) ────

    private val _scannedNetworks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val scannedNetworks: StateFlow<List<WifiNetwork>> = _scannedNetworks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _selectedTarget = MutableStateFlow<WifiNetwork?>(null)
    val selectedTarget: StateFlow<WifiNetwork?> = _selectedTarget.asStateFlow()

    fun scanForTargets() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            repo.scanNetworks()
                .onSuccess { list -> _scannedNetworks.value = list }
                .onFailure { /* silent */ }
            _isScanning.value = false
        }
    }

    fun selectTarget(network: WifiNetwork) {
        _selectedTarget.value = network
        repo.selectNetwork(network) // sync ke repo juga biar consistent
    }

    fun clearTarget() {
        _selectedTarget.value = null
    }

    // ── MAC Spoof ──────────────────────────────────────────────────────

    private val _macState = MutableStateFlow(MacSpoofState())
    val macState: StateFlow<MacSpoofState> = _macState.asStateFlow()

    fun refreshMac() {
        viewModelScope.launch(Dispatchers.IO) {
            val mac = repo.getCurrentMac()
            _macState.update { it.copy(currentMac = mac) }
        }
    }

    fun spoofMac(targetMac: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _macState.update { it.copy(isWorking = true, lastError = null) }

            val currentMac = repo.getCurrentMac()
            val result = repo.spoofMac(targetMac)

            _macState.update {
                it.copy(
                    isWorking = false,
                    currentMac = result.newMac ?: it.currentMac,
                    originalMac = if (it.originalMac == null) currentMac else it.originalMac,
                    isSpoofed = result.success,
                    lastError = result.error
                )
            }
        }
    }

    fun restoreMac() {
        val origMac = _macState.value.originalMac ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _macState.update { it.copy(isWorking = true) }
            val result = repo.restoreMac(origMac)
            _macState.update {
                it.copy(
                    isWorking = false,
                    currentMac = if (result.success) origMac else it.currentMac,
                    isSpoofed = !result.success,
                    lastError = result.error
                )
            }
        }
    }

    fun generateRandomMac(): String = repo.macSpoofer.generateRandomMac()

    fun generateVendorMac(vendor: String): String {
        val oui = MacSpoofer.COMMON_OUIS[vendor] ?: return generateRandomMac()
        return repo.macSpoofer.generateVendorMac(oui)
    }

    // ── Vulnerability Assessment ───────────────────────────────────────

    private val _vulnState = MutableStateFlow(VulnAssessmentState())
    val vulnState: StateFlow<VulnAssessmentState> = _vulnState.asStateFlow()

    fun assessNetwork(network: WifiNetwork) {
        val fingerprint = NetworkSecurityAnalyzer.fingerprintFromBssidPublic(network.bssid)
        val assessment = VulnerabilityAssessor.assess(network, fingerprint)
        val pins = PinGenerator.generate(network.bssid, fingerprint.vendor)

        _vulnState.value = VulnAssessmentState(
            assessment = assessment,
            generatedPins = pins
        )
    }

    // ── Network Recon ──────────────────────────────────────────────────

    private val _reconState = MutableStateFlow(ReconState())
    val reconState: StateFlow<ReconState> = _reconState.asStateFlow()

    fun runRecon(network: WifiNetwork) {
        viewModelScope.launch {
            _reconState.update { it.copy(isRunning = true, error = null, logs = emptyList()) }

            try {
                val result = withContext(Dispatchers.IO) {
                    repo.runAdvancedAttack(network, AttackType.RECON)
                }

                _reconState.update {
                    it.copy(
                        isRunning = false,
                        logs = it.logs + (result.reconData ?: "No data"),
                        error = result.error?.message
                    )
                }
            } catch (e: Exception) {
                _reconState.update {
                    it.copy(isRunning = false, error = e.message)
                }
            }
        }
    }

    fun quickRecon() {
        viewModelScope.launch(Dispatchers.IO) {
            _reconState.update { it.copy(isRunning = true, error = null) }
            try {
                val result = repo.networkRecon.quickRecon()
                _reconState.update {
                    it.copy(
                        isRunning = false,
                        result = result
                    )
                }
            } catch (e: Exception) {
                _reconState.update { it.copy(isRunning = false, error = e.message) }
            }
        }
    }

    // ── Advanced Attacks ───────────────────────────────────────────────

    private val _advancedState = MutableStateFlow(AdvancedAttackState())
    val advancedState: StateFlow<AdvancedAttackState> = _advancedState.asStateFlow()

    init {
        // Forward attack events to advanced state logs
        viewModelScope.launch {
            repo.attackEvents.collect { event ->
                when (event) {
                    is WpsRepository.AttackEvent.Log -> {
                        _advancedState.update { st ->
                            st.copy(logs = (st.logs + event.line).takeLast(500))
                        }
                    }
                    is WpsRepository.AttackEvent.Finished -> {
                        _advancedState.update { st ->
                            st.copy(
                                isRunning = false,
                                lastResult = event.result
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun runAdvancedAttack(network: WifiNetwork, type: AttackType, config: AttackConfig = AttackConfig()) {
        if (_advancedState.value.isRunning) return

        _advancedState.update {
            AdvancedAttackState(
                isRunning = true,
                currentType = type,
                logs = listOf("[start] ${type.displayName} on ${network.displayName}")
            )
        }

        viewModelScope.launch {
            val result = repo.runAdvancedAttack(network, type, config)
            _advancedState.update {
                it.copy(
                    isRunning = false,
                    lastResult = result,
                    evilTwinActive = type == AttackType.EVIL_TWIN && result.success,
                    deauthActive = false
                )
            }
        }
    }

    fun stopEvilTwin() {
        repo.stopEvilTwin()
        _advancedState.update { it.copy(evilTwinActive = false) }
    }

    fun stopDeauth() {
        repo.stopDeauth()
        _advancedState.update { it.copy(deauthActive = false) }
    }

    fun cancelAdvanced() {
        repo.cancelAttack()
        _advancedState.update {
            it.copy(isRunning = false, deauthActive = false)
        }
    }

    // ── Credential Export ──────────────────────────────────────────────

    fun exportResults(
        results: List<AttackResult>,
        format: CredentialExporter.ExportFormat
    ): CredentialExporter.ExportResult {
        return CredentialExporter.export(results, format)
    }

    fun exportCredentialsOnly(
        results: List<AttackResult>,
        format: CredentialExporter.ExportFormat
    ): CredentialExporter.ExportResult {
        return CredentialExporter.exportCredentialsOnly(results, format)
    }

    fun formatForClipboard(result: AttackResult): String {
        return CredentialExporter.formatForClipboard(result)
    }

    fun createShareIntent(content: String) = CredentialExporter.createShareIntent(content)
}
