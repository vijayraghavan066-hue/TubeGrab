plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vj.tubegrab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vj.tubegrab"
        minSdk = 24
        targetSdk = 35
        // CI passes -PbuildNumber=<GitHub run number>; the in-app updater compares against it
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.2 (build $buildNumber)"
    }

    // yt-dlp ships a Python runtime per CPU type, so build one smaller APK per CPU type
    // (arm64-v8a fits almost every phone from the last ~8 years) + one universal APK
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // Signing key comes from CI secrets (never committed). CI decodes the keystore to
    // app/release.jks and passes the passwords as env vars. Keep the same key forever so
    // each new build installs as an update over the old one.
    val ksFile = file(System.getenv("TG_KEYSTORE_PATH") ?: "release.jks")
    val hasKey = ksFile.exists() && System.getenv("TG_KEYSTORE_PASSWORD") != null
    signingConfigs {
        if (hasKey) create("tubegrab") {
            storeFile = ksFile
            storePassword = System.getenv("TG_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("TG_KEY_ALIAS") ?: "tubegrab"
            keyPassword = System.getenv("TG_KEY_PASSWORD") ?: System.getenv("TG_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the secret key (e.g. a local build) fall back to the debug key
            signingConfig = if (hasKey) signingConfigs.getByName("tubegrab") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // youtubedl-android needs its native libs extracted to disk (= extractNativeLibs="true")
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
