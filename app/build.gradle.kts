import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing config is read from keystore.properties (gitignored) if present,
// otherwise from environment variables. If neither is available the release
// build is left unsigned so the project still builds for anyone cloning it.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "cloud.lork.mursal"
    compileSdk = 34

    defaultConfig {
        applicationId = "cloud.lork.mursal"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Optional update feed. Points at THIS public repo's releases, never erp.lork.cloud.
        // Updates are never forced (see UpdateClient / AppUpdateManager).
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"https://raw.githubusercontent.com/loorksy/Wh-sender-/main/releases/app-update.json\"",
        )
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "MURSAL_STORE_FILE")
            if (storePath != null) {
                // Resolve relative to the repo root (where keystore.properties lives).
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "MURSAL_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MURSAL_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MURSAL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use the release signing config only when a keystore was provided.
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
