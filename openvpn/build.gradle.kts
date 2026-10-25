/*
 * ماژول OpenVPN: هسته‌ی ics-openvpn (ovpn3) به‌صورت Android library.
 * منبع: github.com/schwabe/ics-openvpn — لایسنس: GPL-2.0-or-later با شرایط اضافه (doc/LICENSE.txt)
 * هسته‌ی ovpn3: MPL 2.0 (انتخاب‌شده در این پروژه).
 */
plugins {
    id("com.android.library")
}

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 36

    // همان نسخه‌ای که ics-openvpn استفاده می‌کند
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 29
        buildConfigField("boolean", "openvpn3", "true")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                // سازگار با ics: بدون آرگومان اضافه
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = File("${projectDir}/src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        disable += setOf("MissingTranslation", "UnsafeNativeCodeLocation")
    }
}

// ───── تولید کد SWIG برای کلاس‌های net.openvpn.ovpn3 (همان روش ics-openvpn) ─────
var swigcmd = "swig"
if (file("/opt/homebrew/bin/swig").exists()) swigcmd = "/opt/homebrew/bin/swig"
else if (file("/usr/local/bin/swig").exists()) swigcmd = "/usr/local/bin/swig"

abstract class GenerateSwigTask : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

fun registerGenSwigTask(variantName: String, variantDirName: String): TaskProvider<GenerateSwigTask> {
    val baseDir = layout.buildDirectory.dir("generated/source/ovpn3swig/${variantDirName}")

    val genTask = tasks.register<GenerateSwigTask>("generateOpenVPN3Swig${variantName}") {
        val genDir = baseDir.get().asFile.resolve("net/openvpn/ovpn3")
        outputDir.set(baseDir)

        doFirst {
            mkdir(genDir)
        }
        commandLine(
            listOf(
                swigcmd,
                "-outdir", genDir.absolutePath,
                "-outcurrentdir",
                "-c++",
                "-java",
                "-package", "net.openvpn.ovpn3",
                "-Isrc/main/cpp/openvpn3/client",
                "-Isrc/main/cpp/openvpn3/",
                "-DOPENVPN_PLATFORM_ANDROID",
                "-o", "${genDir}/ovpncli_wrap.cxx",
                "-oh", "${genDir}/ovpncli_wrap.h",
                "src/main/cpp/openvpn3/client/ovpncli.i"
            )
        )
        inputs.files("src/main/cpp/openvpn3/client/ovpncli.i")
    }
    return genTask
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val execTask = registerGenSwigTask(variant.name, variant.name.replace("-", "/"))
        variant.sources.java?.addGeneratedSourceDirectory(execTask, GenerateSwigTask::outputDir)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core:1.13.1")
}
