import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing reads a properties file kept OUTSIDE the source tree
// (storeFile / storePassword / keyAlias / keyPassword).
// Override the path with the LITECHAT_KEYSTORE_PROPS env var.
// Without it, release builds are unsigned (assembleDebug still works).
val releaseProps = Properties().apply {
    // Default is <repo>/signing/litechat-release.properties — this file lives in
    // app/, so the signing folder is two levels up.
    val f = file(System.getenv("LITECHAT_KEYSTORE_PROPS")
        ?: "../../signing/litechat-release.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.litechat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.litechat.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 26
        versionName = "1.25"

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // arm64 covers every phone made in the last several years; armeabi-v7a
        // covers the 32-bit ones (Android Go handsets in particular) for 6.5 MB
        // of APK. x86 is left out on purpose - it is emulators and the odd Intel
        // tablet - but can be added for a test build with
        //   -PlitechatAbis=arm64-v8a,x86_64
        val abis = (project.findProperty("litechatAbis") as String?)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "armeabi-v7a")
        ndk {
            abiFilters += abis
        }
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Uncompressed, page-aligned .so files for the 16 KB page-size devices
    // Android 15+ ships; also lets the loader mmap the ML Kit natives.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Off by default in AGP 8. The debug build uses BuildConfig.DEBUG to register
    // a throwaway test chat app alongside the real adapters.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The android.jar on the unit-test classpath stubs org.json out, so JSON
    // parsing throws "not mocked" off-device. A real implementation is needed
    // for the parser tests to mean anything.
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // On-device OCR. Bundled (not play-services) Chinese model: ships inside the
    // APK, so it works with no Google Play services and never downloads anything.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
