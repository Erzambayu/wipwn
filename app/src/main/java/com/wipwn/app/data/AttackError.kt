package com.wipwn.app.data

/**
 * Structured error type for WPS attacks. Use this instead of raw strings so
 * callers can branch on the cause (e.g. show retry for rate limit, disable
 * the network for WPS locked, etc.).
 */
sealed class AttackError(open val message: String) {
    /** Router has disabled WPS / locked it after repeated failed attempts. */
    data class WpsLocked(override val message: String = "WPS terkunci oleh router") : AttackError(message)

    /** SELinux blocked execution of bundled binaries. */
    data class SelinuxBlocked(override val message: String = "SELinux memblokir eksekusi") : AttackError(message)

    /** Router is not vulnerable to Pixie Dust. */
    data class PixieDustNotVulnerable(override val message: String = "Router tidak rentan terhadap Pixie Dust") : AttackError(message)

    /** Environment prep failed — binaries missing, cannot chmod, etc. */
    data class EnvironmentFailed(override val message: String) : AttackError(message)

    /** Device is not rooted or user denied the SU prompt. */
    data class NoRoot(override val message: String = "Tidak ada akses root") : AttackError(message)

    /** Unknown runtime failure, carry the original message/exception. */
    data class Unknown(override val message: String, val cause: Throwable? = null) : AttackError(message)

    companion object {
        /** Map libsu/WpsConnectionLibrary error type codes into structured errors. */
        fun fromLibraryErrorType(type: Int, fallback: String): AttackError = when (type) {
            TYPE_LOCKED -> WpsLocked()
            TYPE_SELINUX -> SelinuxBlocked()
            TYPE_PIXIE_DUST_NOT_COMPATIBLE -> PixieDustNotVulnerable()
            else -> Unknown(fallback)
        }

        // Constants mirror sangiorgi.wps.lib.ConnectionUpdateCallback codes so
        // we don't have to expose that dependency in higher layers.
        const val TYPE_LOCKED = 1
        const val TYPE_SELINUX = 2
        const val TYPE_PIXIE_DUST_NOT_COMPATIBLE = 3
    }
}
