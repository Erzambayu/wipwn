package com.wipwn.app.repository

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.wipwn.app.util.ShellExecutor
import kotlinx.coroutines.delay

/**
 * Bertanggung jawab menyiapkan environment sebelum serangan WPS:
 * - SELinux permissive
 * - Kill system wpa_supplicant
 * - Deploy binary native (wpa_supplicant, wpa_cli, pixiewps)
 * - Bikin ctrl dir + wpa_supplicant.conf
 * - Deteksi WiFi interface
 */
class EnvironmentPreparer(
    private val appContext: Context,
    private val shell: ShellExecutor,
    private val onLog: (String) -> Unit
) {

    sealed interface EnvPrep {
        data object Ok : EnvPrep
        data class Failed(val reason: String) : EnvPrep
    }

    suspend fun prepare(): EnvPrep {
        return try {
            onLog("[1/7] Setting SELinux permissive...")
            shell.exec("setenforce 0")

            onLog("[2/7] Stopping system wpa_supplicant...")
            shell.exec("killall wpa_supplicant 2>/dev/null")
            shell.exec("killall -9 wpa_supplicant 2>/dev/null")
            delay(1000)

            val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
            onLog("[3/7] nativeLibDir: $nativeLibDir")

            val check = shell.exec("ls -la $nativeLibDir/libwpa_supplicant_exec.so 2>&1")
            if (!check.isSuccess) {
                onLog("  wpa_supplicant binary: NOT FOUND")
                onLog("  ls output: ${check.combined}")

                val found = shell.exec(
                    "find /data/app -name 'libwpa_supplicant_exec.so' 2>/dev/null | head -1"
                ).out.firstOrNull()
                if (found == null) {
                    onLog("  ERROR: Cannot find wpa_supplicant binary anywhere!")
                    return EnvPrep.Failed("wpa_supplicant binary tidak ditemukan")
                }
                val actualLibDir = found.substringBeforeLast("/")
                onLog("  Found at: $actualLibDir")
                return deployWithLibDir(actualLibDir)
            }
            onLog("  wpa_supplicant binary: FOUND")
            deployWithLibDir(nativeLibDir)
        } catch (e: Exception) {
            onLog("ERROR: ${e.message}")
            EnvPrep.Failed(e.message ?: "unknown")
        }
    }

    private fun deployWithLibDir(libDir: String): EnvPrep {
        return try {
            val filesDir = appContext.filesDir.absolutePath

            onLog("[4/7] Deploying binaries to /data/local/tmp/wpswpa/...")
            val deploy = shell.exec(
                "mkdir -p /data/local/tmp/wpswpa",
                "cp '$libDir/libwpa_supplicant_exec.so' /data/local/tmp/wpswpa/libwpa_supplicant_exec.so",
                "cp '$libDir/libwpa_cli_exec.so' /data/local/tmp/wpswpa/libwpa_cli_exec.so",
                "cp '$libDir/libpixiewps_exec.so' /data/local/tmp/wpswpa/libpixiewps_exec.so",
                "chmod 755 /data/local/tmp/wpswpa/*"
            )
            onLog("  Deploy: ${if (deploy.isSuccess) "OK" else "FAILED"}")
            if (!deploy.isSuccess) onLog("  ${deploy.stderr}")

            val verify = shell.exec("ls -la /data/local/tmp/wpswpa/")
            onLog("  Files: ${verify.out.joinToString(", ")}")

            val ctrlDir = if (Build.VERSION.SDK_INT >= 28) {
                "/data/vendor/wifi/wpa/wpswpatester"
            } else {
                "/data/misc/wifi/wpswpatester"
            }
            onLog("[5/7] Creating ctrl dir: $ctrlDir")
            shell.exec(
                "mkdir -p $ctrlDir",
                "chmod 770 $ctrlDir",
                "chown wifi:wifi $ctrlDir 2>/dev/null || true"
            )

            onLog("[6/7] Creating wpa_supplicant.conf...")
            shell.exec("mkdir -p $filesDir")
            shell.exec(
                "printf 'ctrl_interface=DIR=$ctrlDir GROUP=wifi\nupdate_config=0\n' > $filesDir/wpa_supplicant.conf"
            )
            shell.exec("chmod 644 $filesDir/wpa_supplicant.conf")

            shell.exec("rm -f /data/local/tmp/wpa_ctrl_* 2>/dev/null")
            shell.exec("rm -f $ctrlDir/* 2>/dev/null")

            onLog("[7/7] Finding WiFi interface...")
            val iface = shell.exec("ls /sys/class/net/ | grep -E 'wlan|wifi|wl' | head -1")
                .out.firstOrNull() ?: "wlan0"
            onLog("  Interface: $iface")

            val ifaceCheck = shell.exec("ip link show $iface 2>&1")
            onLog("  Interface status: ${if (ifaceCheck.isSuccess) "UP" else "DOWN/MISSING"}")

            onLog("✓ Environment ready")
            EnvPrep.Ok
        } catch (e: Exception) {
            onLog("ERROR: ${e.message}")
            EnvPrep.Failed(e.message ?: "unknown")
        }
    }

    /**
     * Restore WiFi setelah serangan selesai.
     * Attack flow matiin system wpa_supplicant, jadi WiFi user ikut mati.
     * Fungsi ini nyalain balik WiFi + restart wpa_supplicant biar user
     * ga perlu manual toggle WiFi.
     */
    @Suppress("DEPRECATION")
    suspend fun restoreWifi() {
        try {
            onLog("[restore] Mengembalikan WiFi...")

            // Kill sisa-sisa wpa_supplicant dari attack
            shell.exec("killall wpa_supplicant 2>/dev/null")
            shell.exec("killall -9 wpa_supplicant 2>/dev/null")
            delay(500)

            val wifi = appContext.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Kalo WiFi mati, nyalain balik
            if (!wifi.isWifiEnabled) {
                onLog("[restore] WiFi mati, nyalain ulang...")
                wifi.isWifiEnabled = true
                delay(2000) // tunggu WiFi boot
            } else {
                // WiFi masih nyala tapi wpa_supplicant mati — toggle biar restart
                onLog("[restore] Toggle WiFi buat restart wpa_supplicant...")
                wifi.isWifiEnabled = false
                delay(1000)
                wifi.isWifiEnabled = true
                delay(2000)
            }

            onLog("[restore] ✓ WiFi restored")
        } catch (e: Exception) {
            onLog("[restore] Gagal restore WiFi: ${e.message}")
            // Fallback: coba via shell
            runCatching {
                shell.exec("svc wifi enable")
                onLog("[restore] Fallback svc wifi enable")
            }
        }
    }
}
