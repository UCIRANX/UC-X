plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.multivpn.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.multivpn.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 600
        versionName = "2.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
