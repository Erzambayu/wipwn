package com.wipwn.app.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.wipwn.app.data.WifiNetwork
import kotlinx.coroutines.delay

/**
 * Bertanggung jawab buat scan jaringan WiFi.
 * Dipisah dari WpsRepository biar logic scan bisa di-test
 * dan di-reuse secara independen.
 */
class WifiScanner(private val appContext: Context) {

    /**
     * Scan jaringan WiFi.
     * Force fresh scan dengan manggil startScan() + delay yang cukup.
     * Filter result yang BSSID-nya valid dan urutkan berdasarkan sinyal.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun scan(): Result<List<WifiNetwork>> = runCatching {
        ensurePrerequisites()
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Force fresh scan — panggil startScan() 2x dengan jeda
        // karena panggilan pertama kadang masih return cache lama
        wifi.startScan()
        delay(SCAN_WAIT_MS)
        wifi.startScan()
        delay(SCAN_WAIT_MS)

        val results = wifi.scanResults ?: emptyList()
        results
            .filter { !it.BSSID.isNullOrBlank() }
            .distinctBy { it.BSSID } // hapus duplikat BSSID
            .map { it.toWifiNetwork() }
            .sortedByDescending { it.rssi }
    }

    @Suppress("DEPRECATION")
    suspend fun ensurePrerequisites() {
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Kalo WiFi mati (misal habis attack), coba nyalain otomatis
        if (!wifi.isWifiEnabled) {
            wifi.isWifiEnabled = true
            // Tunggu WiFi boot up
            delay(2500)
            if (!wifi.isWifiEnabled) {
                throw IllegalStateException("WiFi gagal dinyalain otomatis. Nyalain manual dulu.")
            }
        }
        if (!hasLocationPermission()) {
            throw IllegalStateException("Permission lokasi belum dikasih.")
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNearbyWifiPermission()) {
            throw IllegalStateException("Permission Nearby WiFi belum dikasih.")
        }
        if (!isLocationServiceEnabled()) {
            throw IllegalStateException("Location service harus ON buat scan WiFi.")
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasNearbyWifiPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(true)
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toWifiNetwork(): WifiNetwork {
        val caps = this.capabilities ?: ""
        val wpsEnabled = caps.contains("[WPS", ignoreCase = true)
        val freq = this.frequency
        val channel = when {
            freq in 2412..2484 -> (freq - 2412) / 5 + 1
            freq in 5170..5825 -> (freq - 5170) / 5 + 34
            else -> 0
        }
        return WifiNetwork(
            bssid = this.BSSID ?: "",
            ssid = this.SSID ?: "",
            rssi = this.level,
            frequency = freq,
            channel = channel,
            capabilities = caps,
            wpsEnabled = wpsEnabled,
            wpsLocked = false
        )
    }

    companion object {
        private const val SCAN_WAIT_MS = 2000L
    }
}
