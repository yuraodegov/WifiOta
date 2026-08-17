plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.strauss.wifiota"
    compileSdk = 34
    // No native code in this project, so pin the NDK version away from AGP's
    // default - otherwise it insists on downloading one that isn't here.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        // Deliberately different from namespace: Android treats this as a brand
        // new app, so it cannot clash with the copy already on the phone.
        applicationId = "com.strauss.wifiota.v2"
        // WifiNetworkSpecifier requires API 29. Nothing older can do this.
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
