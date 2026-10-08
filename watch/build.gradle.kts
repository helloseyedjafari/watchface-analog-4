plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seyedjafari.analogplus.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seyedjafari.analogplus"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("androidTest").java.srcDir("src/androidTest/kotlin")
        getByName("debug").java.srcDir("src/debug/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.wear.watchfacepush:watchfacepush:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
