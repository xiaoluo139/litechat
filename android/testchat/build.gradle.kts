// A throwaway app that draws a WeChat-shaped chat screen.
//
// It exists so the screenshot-reading path can be verified on a real device:
// WeChat itself hides its messages from accessibility services, so without this
// there is no way to test "screenshot -> crop -> OCR -> bubble colour -> reply"
// end to end. Never shipped; only built with :testchat:assembleDebug.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.litechat.testchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.litechat.testchat"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
