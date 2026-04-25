package com.wipwn.app.data

/**
 * Represents a WiFi network discovered during scanning.
 */
data class WifiNetwork(
    val bssid: String,
    val ssid: String,
    val rssi: Int = -100,
    val frequency: Int = 0,
    val channel: Int = 0,
    val capabilities: String = "",
    val wpsEnabled: Boolean = false,
    val wpsLocked: Boolean = false
) {
    val signalLevel: SignalLevel
        get() = when {
            rssi >= -50 -> SignalLevel.EXCELLENT
            rssi >= -60 -> SignalLevel.GOOD
            rssi >= -70 -> SignalLevel.FAIR
            else -> SignalLevel.WEAK
        }

    val displayName: String
        get() = ssid.ifBlank { "<Hidden>" }
}

enum class SignalLevel { EXCELLENT, GOOD, FAIR, WEAK }

/**
 * Type of attack to perform.
 */
enum class AttackType(val displayName: String, val category: AttackCategory = AttackCategory.WPS) {
    KNOWN_PINS("Test Known PINs"),
    PIXIE_DUST("Pixie Dust Attack"),
    BRUTE_FORCE("Brute Force"),
    CUSTOM_PIN("Custom PIN"),
    ALGORITHMIC_PIN("Algorithmic PIN"),
    NULL_PIN("Null PIN Attack"),
    DEAUTH("Deauthentication", AttackCategory.WIFI),
    HANDSHAKE_CAPTURE("Handshake Capture", AttackCategory.WIFI),
    PMKID("PMKID Attack", AttackCategory.WIFI),
    EVIL_TWIN("Evil Twin AP", AttackCategory.WIFI),
    RECON("Network Recon", AttackCategory.POST_EXPLOIT)
}

enum class AttackCategory(val label: String) {
    WPS("WPS Attacks"),
    WIFI("WiFi Attacks"),
    POST_EXPLOIT("Post-Exploit")
}

/**
 * Tunables for a single attack invocation. Centralising this here instead of
 * hardcoding into the ViewModel makes the values reusable and testable.
 */
data class AttackConfig(
    val bruteForceDelayMs: Int = 1000,
    val attackTimeoutMs: Long = getDefaultTimeout(AttackType.PIXIE_DUST),
    val knownPins: List<String> = DEFAULT_KNOWN_PINS,
    // MAC Spoofing
    val macSpoofEnabled: Boolean = false,
    val macSpoofTarget: String? = null, // null = random
    // Rate-limit bypass
    val rateLimitBypass: Boolean = true,
    val rateLimitCooldownMs: Long = 60_000L,
    val rateLimitMaxRetries: Int = 3,
    // Deauth
    val deauthCount: Int = 10,
    val deauthTargetClient: String? = null,
    // Handshake / PMKID
    val captureTimeoutSec: Int = 30,
    val wordlistPath: String? = null,
    // Evil Twin
    val evilTwinChannel: Int = 6,
    val evilTwinCaptivePortal: Boolean = true,
    // Recon
    val reconPortScan: Boolean = true,
    val reconDefaultCreds: Boolean = true
) {
    companion object {
        val DEFAULT_KNOWN_PINS: List<String> = listOf(
            "12345670", "00000000", "11111111",
            "22222222", "33333333", "44444444",
            "55555555", "66666666", "77777777",
            "88888888", "99999999"
        )
        
        /**
         * Get default timeout based on attack type.
         * Pixie Dust: 60s, Known PINs: 120s, Brute Force: 24h, others: 120s
         */
        fun getDefaultTimeout(type: AttackType): Long = when (type) {
            AttackType.PIXIE_DUST -> 60_000L        // 1 minute
            AttackType.KNOWN_PINS -> 120_000L       // 2 minutes
            AttackType.ALGORITHMIC_PIN -> 90_000L   // 1.5 minutes
            AttackType.NULL_PIN -> 30_000L          // 30 seconds
            AttackType.CUSTOM_PIN -> 30_000L        // 30 seconds
            AttackType.BRUTE_FORCE -> 86_400_000L   // 24 hours
            else -> 120_000L                        // 2 minutes default
        }
        
        /**
         * Create config with timeout specific to attack type.
         */
        fun forAttackType(type: AttackType): AttackConfig = AttackConfig(
            attackTimeoutMs = getDefaultTimeout(type)
        )
    }
}

/**
 * Result from a WPS attack. [error] is populated when [success] is false.
 * The legacy [errorMessage] getter delegates to [error] for UI backward
 * compatibility.
 */
data class AttackResult(
    val bssid: String,
    val ssid: String,
    val pin: String? = null,
    val password: String? = null,
    val success: Boolean = false,
    val error: AttackError? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val attackType: AttackType? = null,
    // Extra data for advanced attacks
    val captureFile: String? = null,
    val reconData: String? = null,
    val macUsed: String? = null,
    val algorithmUsed: String? = null
) {
    val errorMessage: String? get() = error?.message
}
