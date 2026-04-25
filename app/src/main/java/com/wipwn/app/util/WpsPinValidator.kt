package com.wipwn.app.util

/**
 * WPS PIN validator with checksum verification.
 * WPS PIN format: 8 digits where the last digit is a checksum.
 */
object WpsPinValidator {
    /**
     * Validate WPS PIN format and checksum.
     * Returns true if PIN is valid (8 digits + correct checksum).
     */
    fun isValid(pin: String): Boolean {
        if (pin.length != 8) return false
        if (!pin.all { it.isDigit() }) return false
        
        val digits = pin.map { it.toString().toInt() }
        val checksum = digits[7]
        val computed = computeChecksum(digits.take(7))
        
        return checksum == computed
    }
    
    /**
     * Compute WPS PIN checksum using the algorithm from WPS spec.
     * Checksum = (10 - ((3 * (d1 + d3 + d5 + d7) + d2 + d4 + d6) % 10)) % 10
     */
    private fun computeChecksum(first7Digits: List<Int>): Int {
        val sum = 3 * (first7Digits[0] + first7Digits[2] + first7Digits[4] + first7Digits[6]) +
                  first7Digits[1] + first7Digits[3] + first7Digits[5]
        return (10 - (sum % 10)) % 10
    }
    
    /**
     * Generate valid WPS PIN from first 7 digits by computing checksum.
     */
    fun generateValidPin(first7Digits: String): String? {
        if (first7Digits.length != 7) return null
        if (!first7Digits.all { it.isDigit() }) return null
        
        val digits = first7Digits.map { it.toString().toInt() }
        val checksum = computeChecksum(digits)
        return first7Digits + checksum
    }
    
    /**
     * Get validation error message for UI display.
     */
    fun getValidationError(pin: String): String? {
        return when {
            pin.isEmpty() -> "PIN tidak boleh kosong"
            pin.length < 8 -> "PIN harus 8 digit"
            pin.length > 8 -> "PIN maksimal 8 digit"
            !pin.all { it.isDigit() } -> "PIN hanya boleh angka"
            !isValid(pin) -> "Checksum PIN tidak valid"
            else -> null
        }
    }
}
