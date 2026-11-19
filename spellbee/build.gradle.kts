plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vj.spellbee"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vj.spellbee"
        minSdk = 26
        targetSdk = 35
        // Same build number as the rest of the repo (CI passes -PbuildNumber)
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0 (build $buildNumber)"
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

// Name the APK SpellBeeChamp-<build>.apk instead of spellbee-release.apk
base { archivesName.set("SpellBeeChamp") }

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
}
