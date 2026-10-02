plugins {
    id("com.android.application")
}

android {
    namespace = "com.seyedjafari.analogwatchface"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seyedjafari.analogwatchface"
        // WFF v3 (auto-sizing text) needs Wear OS 5.1 = API 35.
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
