plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.multivpn.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.multivpn.app"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.3.$runNumber"
    }

    signingConfigs {
        create("permanentSign") {
            storeFile = rootProject.file("keystore/app.jks")
            storePassword = "MultiVpnPassword123"
            keyAlias = "multivpn"
            keyPassword = "MultiVpnPassword123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("permanentSign")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("permanentSign")
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
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
