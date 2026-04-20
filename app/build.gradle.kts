plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wipwn.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wipwn.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.1.0"

        buildConfigField("String", "DATA_DIR", "\"/data/data/com.wipwn.app/\"")
    }

    // ──────────────────────────────────────────────────────────────────
    // Signing
    //
    // We ship a deterministic debug keystore so every dev machine signs
    // the app with the same key (helps when testing updates on a real
    // device). Real release builds should switch to a keystore whose
    // password is NOT committed — read from gradle.properties or env vars.
    // ──────────────────────────────────────────────────────────────────
    signingConfigs {
        getByName("debug") {
            // Prefer the project-local debug keystore if it was committed
            // (so the app is signed identically on every dev machine).
            // Falls back to the AS-generated ~/.android/debug.keystore.
            val localDebug = rootProject.file("keystore/debug.keystore")
            if (localDebug.exists()) {
                storeFile = localDebug
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            val keystoreFile = System.getenv("WIPWN_KEYSTORE")
                ?: (findProperty("WIPWN_KEYSTORE") as String?)
            val keystorePwd = System.getenv("WIPWN_KEYSTORE_PASSWORD")
                ?: (findProperty("WIPWN_KEYSTORE_PASSWORD") as String?)
            val keyAliasProp = System.getenv("WIPWN_KEY_ALIAS")
                ?: (findProperty("WIPWN_KEY_ALIAS") as String?)
            val keyPwd = System.getenv("WIPWN_KEY_PASSWORD")
                ?: (findProperty("WIPWN_KEY_PASSWORD") as String?)

            if (keystoreFile != null && keystorePwd != null && keyAliasProp != null && keyPwd != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePwd
                keyAlias = keyAliasProp
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only apply the release signing config if the env/properties
            // were actually provided; otherwise fall back to debug so the
            // build doesn't fail locally.
            signingConfig = signingConfigs.findByName("release")
                ?.takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // LibSu — root shell access
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)

    // WPS Connection Library — bundles wpa_supplicant, pixiewps, wpa_cli
    implementation("com.github.fulvius31:WpsConnectionLibrary:v2.0.0")

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
}
