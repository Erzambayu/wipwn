# Wipwn Android

Aplikasi Android untuk tool [wipwn](https://github.com/anbuinfosec/wipwn) — WiFi WPS Penetration Testing dengan antarmuka Jetpack Compose yang mudah digunakan.

> **Perangkat harus dalam kondisi ROOT.**

## Prasyarat

- Android 8.0+ (API 26+)
- Perangkat sudah di-root (Magisk / SuperSU)
- JDK 17 + Android Studio Koala+ (untuk build)

Library native (`wpa_supplicant`, `wpa_cli`, `pixiewps`) sudah di-bundle via dependensi
`com.github.fulvius31:WpsConnectionLibrary:v2.0.0` — **tidak perlu install Termux** di device
target untuk sekadar memakai APK ini.

## Build APK

### Via Android Studio
1. Buka project.
2. Sync Gradle.
3. `Build > Build APK(s)`.

### Via command line
```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Release build

Release ditandatangani otomatis jika 4 properti signing tersedia (env var atau
`gradle.properties` / `~/.gradle/gradle.properties`):

```
WIPWN_KEYSTORE=/absolute/path/to/keystore.jks
WIPWN_KEYSTORE_PASSWORD=...
WIPWN_KEY_ALIAS=...
WIPWN_KEY_PASSWORD=...
```

Jika tidak tersedia, build release fallback pakai keystore debug supaya build
tetap jalan secara lokal (tapi JANGAN didistribusikan).

Opsional: drop `keystore/debug.keystore` di root project supaya semua dev yang
build menghasilkan signature yang identik (sudah di-`.gitignore`).

```bash
./gradlew assembleRelease
```

## Fitur

### WPS Attacks
- **Scan Jaringan** — deteksi jaringan WiFi + flag WPS aktif
- **Known PIN Attack** — uji 11 PIN default vendor (12345670, 00000000, …)
- **Pixie Dust Attack** — eksploitasi kelemahan RNG offline dengan **live logging lengkap** (narasi tiap tahap, heartbeat, tail logcat native library)
- **Mass Pixie Dust (Queue)** — serang semua jaringan hasil scan sekaligus, sequential; dapetin rekap otomatis `X sukses / Y gagal / Z skip` + PIN & PSK tiap target yang berhasil, plus tombol **Retry Failed**
- **Brute Force** — online PIN bruteforce, delay konfigurable
- **Custom PIN** — test PIN spesifik
- **Algorithmic PIN** — generate & test PIN berdasarkan BSSID + vendor algorithm (ComputePIN, EasyBox, D-Link, ASUS, Belkin, dll)
- **Null PIN Attack** — test empty/null PIN variants

### WiFi Attacks
- **Deauthentication Attack** — kirim deauth frames buat disconnect client dari AP (aireplay-ng / mdk4)
- **WPA Handshake Capture** — capture 4-way handshake + crack offline pake wordlist (aircrack-ng)
- **PMKID Attack** — clientless attack, ambil PMKID langsung dari AP (hcxdumptool)
- **Evil Twin AP** — spawn fake AP + captive portal buat credential phishing (hostapd + dnsmasq)

### Exploit Tools
- **MAC Address Spoofing** — ganti MAC buat bypass filtering & reset WPS rate-limit (random, vendor-based, atau custom)
- **WPS Rate-Limit Bypass** — auto detect lock + cooldown wait + MAC spoof combo buat bypass rate-limit
- **Vulnerability Assessment** — analisis kerentanan target sebelum nyerang (predict success chance per attack type)
- **PIN Generator** — algorithmic PIN generation dari BSSID (ComputePIN, EasyBox, D-Link, ASUS, Belkin, Generic MAC)

### Post-Exploit
- **Network Reconnaissance** — ARP scan, port scanning, router admin panel detection, default credential check
- **Credential Export** — export hasil ke CSV, JSON, atau plain text + share via intent + copy to clipboard

### Infrastructure
- **Riwayat Serangan** — persisten via DataStore (bertahan setelah app ditutup)
- **Log Terminal** — output real-time dari engine attack (narasi + logcat tail tag `PixieDustExecutor`, `WpsNative`, dst.)
- **Error Terstruktur** — rate-limit / WPS locked / SELinux / Pixie-not-vulnerable / monitor-mode-failed / tool-not-found dipisahkan via `sealed class AttackError`
- **Rogue AP Detection** — deteksi evil twin / rogue AP dari scan results
- **Router Fingerprinting** — OUI-based vendor detection + extended database
- **Channel Interference Map** — analisis channel usage 2.4GHz & 5GHz

## Arsitektur

Pola **MVVM + Repository**. Semua business logic (scan, attack, prepare env,
persistence) dipisahkan dari ViewModel supaya bisa diuji dan di-reuse antar
layar.

```
app/src/main/java/com/wipwn/app/
├── MainActivity.kt
├── WipwnApp.kt                 # Application — init Shell + Repository singleton
├── data/
│   ├── Models.kt               # WifiNetwork, AttackType, AttackResult, AttackConfig
│   ├── AttackError.kt          # sealed class — WpsLocked, SelinuxBlocked, RateLimited, MonitorModeFailed, …
│   ├── AttackHistoryStore.kt   # DataStore persistence (JSON-encoded)
│   ├── NetworkSecurityAnalysis.kt # Rogue AP detection, channel map, vendor fingerprint
│   ├── PinGenerator.kt         # Algorithmic WPS PIN generation (ComputePIN, EasyBox, D-Link, ASUS, Belkin)
│   ├── VulnerabilityAssessment.kt # Auto vulnerability scoring per network
│   └── CredentialExporter.kt   # Export to CSV/JSON/Text + share intent
├── util/
│   ├── ShellExecutor.kt        # interface + libsu impl (testable abstraction)
│   ├── RootUtil.kt             # root probe helper
│   ├── MacSpoofer.kt           # MAC address spoofing (random, vendor, custom)
│   └── NetworkRecon.kt         # Post-exploit recon (ARP scan, port scan, default creds)
├── repository/
│   ├── WpsRepository.kt        # Central business logic — scan, WPS attacks, advanced attacks
│   ├── EnvironmentPreparer.kt  # SELinux, wpa_supplicant, binary deployment
│   ├── PixieDustInstrumentation.kt # Pixie Dust narration + heartbeat + logcat tail
│   ├── WifiScanner.kt          # WiFi scan logic
│   ├── RateLimitBypass.kt      # Auto rate-limit bypass (cooldown + MAC spoof)
│   ├── HandshakeCapturer.kt    # WPA handshake capture + PMKID + offline crack
│   ├── DeauthAttacker.kt       # Deauthentication attack module
│   └── EvilTwinAttacker.kt     # Evil Twin AP + captive portal
├── viewmodel/
│   ├── MainViewModel.kt        # root/library init state
│   ├── ScanViewModel.kt        # scan UI state
│   ├── AttackViewModel.kt      # attack UI state + log stream
│   ├── BatchAttackViewModel.kt # mass pixie-dust queue + summary
│   ├── ResultsViewModel.kt     # history (baca dari DataStore)
│   └── ToolsViewModel.kt       # Advanced tools (MAC spoof, vuln assessment, recon, export, advanced attacks)
└── ui/
    ├── WipwnMainScreen.kt      # navigation root — wires VMs ke Screens
    ├── ScanScreen.kt
    ├── AttackScreen.kt
    ├── BatchAttackScreen.kt    # mass pixie-dust picker + live progress
    ├── ResultsScreen.kt
    ├── ToolsScreen.kt          # Advanced tools hub (MAC, Vuln, Deauth, PMKID, Evil Twin, Recon, Export)
    └── theme/                  # Color, Theme, Type (Material 3 dark)
```

### Alur data saat attack

```
User tap "Pixie Dust"
        │
        ▼
AttackViewModel.start() ──► WpsRepository.runAttack()
                                  │
                                  ├── prepareEnvironment()   (libsu shell)
                                  ├── WpsConnectionManager.pixieDust(...)
                                  │        ▲
                                  │        │ callbacks
                                  ▼        │
                     emit AttackEvent.Log / Progress / Finished
                                  │
                                  ▼
                         SharedFlow attackEvents
                                  │
                                  ▼
                     AttackViewModel mengumpulkan ke AttackUiState
                                  │
                                  ▼
                            AttackScreen re-compose

                     Finished ──► historyStore.add() ──► DataStore
```

### Testing

Karena `WpsRepository` inject `ShellExecutor`, lo bisa bikin fake:

```kotlin
class FakeShell : ShellExecutor {
    override fun exec(vararg commands: String) = ShellResult(true, 0, emptyList(), emptyList())
    override fun execAsync(vararg commands: String, onLine: (String) -> Unit) = exec(*commands)
}

val repo = WpsRepository(
    appContext = context,
    shell = FakeShell(),
    historyStore = AttackHistoryStore(context)
)
```

## Setup tambahan (opsional)

Kalo lo juga mau pake CLI `wipwn` dari `anbuinfosec/wipwn` di device yang sama:

```bash
su -c "git clone https://github.com/anbuinfosec/wipwn /data/local/wipwn"
pkg install root-repo -y
pkg install python wpa-supplicant pixiewps iw -y
```

Tapi ini **tidak dibutuhkan** oleh APK ini untuk fungsi normalnya.

## Disclaimer

Aplikasi ini hanya untuk pengujian keamanan yang **diizinkan**. Menyerang
jaringan tanpa izin pemilik melanggar hukum di sebagian besar yurisdiksi.
Pengguna bertanggung jawab penuh atas penggunaan tool ini.

## Lisensi

MIT — berbasis [wipwn](https://github.com/anbuinfosec/wipwn) oleh @anbuinfosec.
