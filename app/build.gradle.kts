plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.orbit.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orbit.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Orbit's offline brain ships native code; this phone is 64-bit ARM, so only that is packed
        ndk { abiFilters += "arm64-v8a" }
    }
    // One fixed key, so each new build installs over the old one (keeps your tasks and Orbit's permissions)
    signingConfigs {
        getByName("debug") {
            storeFile = file("orbit-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // On-device language model for Orbit's offline brain
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}
