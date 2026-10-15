import java.io.File
import java.net.HttpURLConnection
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.multivpn.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.multivpn.app"
        minSdk = 29
        targetSdk = 35
        versionCode = runNumber + 900
        versionName = "3.0.$runNumber"

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
            pickFirsts += setOf("**/libc++_shared.so")
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // کتابخانه‌های رسمی Tor و Pluggable Transports و Full TUN AAR
    implementation("info.guardianproject:tor-android:0.4.9.6")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("com.netzarchitekten:IPtProxy:5.7.0")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}

val downloadGeoip by tasks.registering {
    doLast {
        val dir = file("src/main/assets/geoip").apply { mkdirs() }
        val urls = listOf(
            "https://raw.githubusercontent.com/torproject/tor/main/src/config/%s",
            "https://gitlab.torproject.org/tpo/core/tor/-/raw/main/src/config/%s",
            "https://cdn.jsdelivr.net/gh/torproject/tor@main/src/config/%s"
        )
        for (name in listOf("geoip", "geoip6")) {
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 50_000L) continue
            for (pattern in urls) {
                try {
                    val c = URI(pattern.format(name)).toURL().openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000
                    c.readTimeout = 60_000
                    if (c.responseCode in 200..299) {
                        c.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                        if (dest.length() > 50_000L) break
                    }
                } catch (_: Exception) {}
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadGeoip) }
