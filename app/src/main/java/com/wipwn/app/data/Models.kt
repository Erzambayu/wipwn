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
 * Type of WPS attack to perform.
 */
enum class AttackType(val displayName: String) {
    KNOWN_PINS("Test Known PINs"),
    PIXIE_DUST("Pixie Dust Attack"),
    BRUTE_FORCE("Brute Force"),
    CUSTOM_PIN("Custom PIN")
}

/**
 * Tunables for a single attack invocation. Centralising this here instead of
 * hardcoding into the ViewModel makes the values reusable and testable.
 */
data class AttackConfig(
    val bruteForceDelayMs: Int = 1000,
    val attackTimeoutMs: Long = 120_000L,
    val knownPins: List<String> = DEFAULT_KNOWN_PINS
) {
    companion object {
        val DEFAULT_KNOWN_PINS: List<String> = listOf(
            "12345670", "00000000", "11111111",
            "22222222", "33333333", "44444444",
            "55555555", "66666666", "77777777",
            "88888888", "99999999"
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
    val timestamp: Long = System.currentTimeMillis()
) {
    val errorMessage: String? get() = error?.message
}
