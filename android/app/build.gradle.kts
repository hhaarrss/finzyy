import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Dev backend base URL, resolved in this order:
// 1. -PdevBackendBaseUrl=... passed on the command line
// 2. dev.backend.base.url in local.properties (per-machine, not checked in)
// 3. a LAN fallback (see below)
//
// 127.0.0.1 on a physical phone means the phone itself, not your computer, so
// it can never reach a backend running on your machine — that's the
// "failed to connect to /127.0.0.1:8000" error. Testing on a real device over
// Wi-Fi needs your computer's LAN IP instead. Set it once in local.properties:
//   dev.backend.base.url=http://<your-computer's-LAN-IP>:8000/
// and make sure the backend is running and reachable on that network (it
// already binds 0.0.0.0 via docker-compose; phone and computer must be on the
// same Wi-Fi, and the firewall must allow port 8000).
// On an emulator instead, use http://10.0.2.2:8000/.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val devBackendBaseUrl: String = (project.findProperty("devBackendBaseUrl") as String?)
    ?: localProperties.getProperty("dev.backend.base.url")
    ?: "https://firstproject-smartspend.onrender.com/"

// Release signing (Play upload key). Read from android/keystore.properties, which is never
// committed — see docs/play-store/release-checklist.md. Without it, release builds are unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.smartspend.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.smartspend.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEV_BACKEND_BASE_URL", "\"$devBackendBaseUrl\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // DEV_SKIP_AUTH must never be true outside debug: it also gates the cleartext
        // LAN dev-backend URL and a fabricated login token (see BackendService.kt and
        // MainActivity.kt). Previously set in defaultConfig, which applies to every
        // build type including release — a release build would have skipped real login,
        // minted a fake session, and sent transaction data over plain HTTP to a
        // hardcoded LAN IP.
        debug {
            buildConfigField("Boolean", "DEV_SKIP_AUTH", "false")
        }
        release {
            buildConfigField("Boolean", "DEV_SKIP_AUTH", "false")
            // R8: removes unused code and obfuscates what's left, so the shipped app is much
            // harder to pick apart. Rules for reflection-based code live in proguard-rules.pro.
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    // Category icons (ui/theme/CategoryIcons.kt). Large artifact; R8 strips unused icons once
    // minification is enabled for release.
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.material)
    implementation(libs.work.runtime.ktx)
    
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.google.services.auth)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}





























