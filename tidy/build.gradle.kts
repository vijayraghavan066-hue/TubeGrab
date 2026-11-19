plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vj.tidy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vj.tidy"
        // Android 11+: needed for the system trash (deleted items recoverable for 30 days)
        minSdk = 30
        targetSdk = 35
        // Same build number as the rest of the repo (CI passes -PbuildNumber)
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0 (build $buildNumber)"
        // ML Kit ships native code; phones only need the ARM builds
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Signing key comes from GitHub secrets and is never stored in this repo (README → "Signing key").
    // Without VJ_KEYSTORE (e.g. a local build) the release APK is left unsigned.
    val vjKeystore: String? = System.getenv("VJ_KEYSTORE")
    signingConfigs {
        create("vjapps") {
            if (vjKeystore != null) {
                storeFile = file(vjKeystore)
                storePassword = System.getenv("VJ_KEY_PASSWORD")
                keyAlias = "vjapps"
                keyPassword = System.getenv("VJ_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (vjKeystore != null) signingConfigs.getByName("vjapps") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// APK is named PhoneTidy-release.apk (the updater looks for this name)
base { archivesName.set("PhoneTidy") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // On-device AI: face + eyes-open detection to pick the best shot (no internet, nothing uploaded)
    implementation("com.google.mlkit:face-detection:16.1.7")
}
