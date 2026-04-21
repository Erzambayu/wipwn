# Changelog

Semua perubahan penting di project ini didokumentasikan di file ini.

Format berdasarkan [Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/),
dan project ini mengikuti [Semantic Versioning](https://semver.org/lang/id/).

---

## [2.2.0] — 2025-06-28

### Fixed
- **WiFi mati setelah exploit, harus manual nyalain** — sekarang WiFi otomatis di-restore setelah serangan selesai (toggle WiFi + restart wpa_supplicant system). user ga perlu lagi manual on-kan WiFi buat scan ulang
- **Scan result nyangkut / stale dari lokasi sebelumnya** — scan sekarang force fresh result dengan double `startScan()` + deduplicate BSSID. kalo WiFi mati habis attack, otomatis dinyalain dulu sebelum scan
- **Mutex leak di `runAttack()`** — sebelumnya kalo ada exception di tengah serangan, mutex bisa terkunci selamanya dan app ga bisa nyerang lagi sampe di-restart. sekarang dibungkus `try/finally` jadi `unlock()` pasti kejalan
- **`Thread.sleep()` nge-block coroutine thread** — `Thread.sleep(2000)` di `scanNetworks()` dan `Thread.sleep(1000)` di `prepareEnvironment()` diganti jadi `delay()` yang coroutine-friendly
- **Versi hardcoded di UI ga sinkron** — `"v2.0.0"` di Home screen diganti pake `BuildConfig.VERSION_NAME` biar otomatis ngikutin build.gradle
- **`Divider` deprecated** — diganti ke `HorizontalDivider` sesuai Material 3 terbaru
- **`Icons.Filled.ArrowBack` deprecated** — diganti ke `Icons.AutoMirrored.Filled.ArrowBack` di semua layar
- **`statusBarColor` / `navigationBarColor` deprecated** — dihapus dari Theme.kt, sekarang ditangani oleh `enableEdgeToEdge()` di MainActivity
- **`extractNativeLibs` warning di AndroidManifest** — dihapus dari manifest karena `jniLibs.useLegacyPackaging = true` di build.gradle udah equivalent
- **`FLAG_REDIRECT_STDERR` deprecated warning** — di-suppress karena belum ada alternatif di libsu 6.0

### Changed
- **Refactor `WpsRepository`** — dipecah dari ~450 baris jadi ~250 baris dengan extract 3 class baru:
  - `WifiScanner` — logic scan jaringan WiFi (permission check, scan, parse result)
  - `EnvironmentPreparer` — persiapan environment sebelum serangan (SELinux, deploy binary, config)
  - `PixieDustInstrumentation` — narasi step-by-step, heartbeat ticker, logcat tail untuk Pixie Dust
- **Zero compiler warning** — semua warning dari Kotlin compiler udah di-fix atau di-suppress dengan alasan yang jelas

---

## [2.1.0] — 2025-06-27

### Added
- **Mass Pixie Dust (Batch Queue)** — serang semua jaringan hasil scan sekaligus secara sequential
  - Picker target dengan filter WPS-only / select all / clear
  - Live progress bar + live log per target
  - Ringkasan otomatis: X sukses / Y gagal / Z skip
  - PIN & PSK tiap target yang berhasil ditampilin di summary
  - Tombol **Retry Failed** buat nyerang ulang yang gagal doang
- **Rogue AP Detection** — deteksi SSID kembar dari vendor/security profile berbeda
  - 3 level severity: HIGH (vendor + security beda), MEDIUM (security beda), LOW (channel berjauhan)
- **Channel Interference Map** — peta penggunaan channel 2.4GHz dan 5GHz dengan rekomendasi channel terbaik
- **OUI Fingerprinting** — identifikasi vendor router dari MAC address (TP-Link, ASUS, Xiaomi, Ubiquiti)
- **Anomaly Tags** — deteksi channel hopping antar scan
- **Pixie Dust Live Instrumentation** — narasi step-by-step, heartbeat ticker setiap 3 detik, tail logcat dari library native
- **Attack Session Tracking** — setiap serangan punya session ID, timestamp mulai/selesai, dan state tracking
- **Log Filter** — filter terminal log berdasarkan stage (all/prepare/run/io/result)
- **Structured Error Handling** — `sealed class AttackError` dengan tipe spesifik (WpsLocked, SelinuxBlocked, PixieDustNotVulnerable, dll.)
- **Search & Filter di Scan** — cari SSID/BSSID + filter WPS-only
- **Attack Mutex** — cuma 1 serangan bisa jalan dalam satu waktu, mencegah race condition

### Changed
- Arsitektur dipindah ke **MVVM + Repository** — semua business logic di `WpsRepository`, ViewModel cuma urus state UI
- `ShellExecutor` dijadiin interface biar testable via fake
- Dark theme di-polish dengan palet warna GitHub-inspired

---

## [2.0.0] — 2025-06-26

### Added
- Initial release sebagai Android app dengan Jetpack Compose
- **Scan Jaringan** — deteksi WiFi + flag WPS aktif
- **Known PIN Attack** — test 11 PIN default vendor
- **Pixie Dust Attack** — eksploitasi kelemahan RNG offline
- **Brute Force** — online PIN bruteforce dengan delay konfigurable
- **Custom PIN** — test PIN spesifik
- **Riwayat Serangan** — persisten via DataStore
- **Log Terminal** — output real-time dari engine attack
- Bundled native binaries via `WpsConnectionLibrary:v2.0.0` (wpa_supplicant, pixiewps, wpa_cli)
- Root shell via libsu 6.0.0
