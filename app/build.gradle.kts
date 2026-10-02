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

    signingConfigs {
        create("release") {
            // In CI the workflow decodes KEYSTORE_BASE64 to app/keystore.jks.
            // Locally (no keystore) this stays unset and release builds unsigned.
            val ks = file("keystore.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "analogplus"
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (file("keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
