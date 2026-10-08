plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.seyedjafari.wfpspike"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.seyedjafari.wfpspike"
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST") } }
}
dependencies {
    implementation("com.android.tools.build:apksig:8.9.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.google.android.wearable.watchface.validator:validator-push-android:1.1.0-alpha01")
}
