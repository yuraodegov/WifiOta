import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Shared secret behind the activation codes.
 *
 * Kept out of the repository: put ACTIVATION_SECRET in local.properties, or set
 * it as an environment variable on the build machine. The same value has to go
 * into tools/activation-code.ps1, or the codes it prints will not be accepted.
 *
 * The build fails without it rather than falling back to a default - a default
 * would quietly produce an APK whose codes nobody can generate.
 */
val activationSecret: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }
            .getProperty("ACTIVATION_SECRET")
    } else null

    fromFile ?: System.getenv("ACTIVATION_SECRET")
        ?: throw GradleException(
            "ACTIVATION_SECRET is not set.\n" +
                "  Add a line to local.properties:  ACTIVATION_SECRET=<your secret>\n" +
                "  or set it as an environment variable."
        )
}

android {
    namespace = "com.strauss.wifiota"
    compileSdk = 34
    // No native code in this project, so pin the NDK version away from AGP's
    // default - otherwise it insists on downloading one that isn't here.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        // Distinct from both the namespace and the full app's id. Android keys
        // an installation by applicationId, so this is what lets the pilot sit
        // on the phone next to the full version instead of replacing it.
        // Side effect worth knowing: settings live per applicationId, so the
        // pilot starts with an empty Setup - SSID and passphrase go in again.
        applicationId = "com.strauss.wifiota.pilot"
        // WifiNetworkSpecifier requires API 29. Nothing older can do this.
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "1.2-pilot"

        buildConfigField("String", "ACTIVATION_SECRET", "\"$activationSecret\"")
    }

    sourceSets {
        getByName("main") {
            // The firmware folder sits in the project root, outside app/, so it
            // is obvious where to drop a new image and it is never mistaken for
            // source. Its contents land at the root of the APK's assets.
            assets.srcDirs("src/main/assets", rootProject.file("firmware"))
        }
    }

    androidResources {
        // Firmware images are already compressed and are read whole at flash
        // time; packing them again only slows that down.
        noCompress += "bin"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8 renames classes and methods and strips what is unused. It does
            // not encrypt anything - string constants stay readable - but it
            // turns the decompiled source into something that takes real effort
            // to follow rather than something that reads like the original.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // TEMPORARY: signed with the debug key so a release build can be
            // produced and installed at all. Replace with the real signing
            // config once the release keystore exists - see SIGNING_KEYVAULT.md.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

/**
 * Fails the build when the bundled firmware is not usable.
 *
 * Without this an APK can be produced with a missing or mismatched image and
 * nothing says so until a technician is standing at a customer's bar. Better
 * to break here, where the message is free.
 */
val checkBundledFirmware by tasks.registering {
    group = "verification"
    description = "Validates firmware/manifest.json and the image it points to."

    val dir = rootProject.file("firmware")
    // Re-run whenever anything in the folder changes.
    inputs.dir(dir).withPropertyName("firmwareDir")
    outputs.upToDateWhen { false }

    doLast {
        if (!dir.isDirectory) {
            throw GradleException("Missing folder: ${dir.absolutePath}")
        }

        val manifest = File(dir, "manifest.json")
        if (!manifest.isFile) {
            throw GradleException("Missing ${manifest.absolutePath}")
        }

        @Suppress("UNCHECKED_CAST")
        val json = try {
            JsonSlurper().parse(manifest) as Map<String, Any?>
        } catch (e: Exception) {
            throw GradleException("manifest.json is not valid JSON: ${e.message}")
        }

        for (key in listOf("model", "version", "file")) {
            val v = json[key]?.toString()
            if (v.isNullOrBlank()) {
                throw GradleException("manifest.json: \"$key\" is missing or empty")
            }
        }

        val fileName = json["file"].toString()
        val bin = File(dir, fileName)
        if (!bin.isFile) {
            throw GradleException(
                "manifest.json points at \"$fileName\", which is not in ${dir.name}/"
            )
        }

        val declared = json["sha256"]?.toString()?.trim()?.lowercase().orEmpty()
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bin.readBytes())
            .joinToString("") { "%02x".format(it) }

        if (declared.isNotEmpty() && declared != actual) {
            throw GradleException(
                "sha256 mismatch for $fileName\n  manifest: $declared\n  actual:   $actual"
            )
        }

        val note = if (declared.isEmpty()) "  (no sha256 in the manifest: $actual)" else ""
        logger.lifecycle(
            "Bundled firmware: ${json["model"]} " +
                "${json["component"] ?: "hmi"} v${json["version"]} - $fileName$note"
        )
    }
}

tasks.named("preBuild") { dependsOn(checkBundledFirmware) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
