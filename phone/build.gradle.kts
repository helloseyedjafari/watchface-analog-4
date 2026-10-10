plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.seyedjafari.analogplus.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seyedjafari.analogplus"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
        getByName("androidTest").java.srcDir("src/androidTest/kotlin")
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST", "META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

// Some JVM tests read the sources and strings.xml as files (NoHardCodedLiteralsTest, StringsXmlMatchesCodeTest). They are
// not on the test classpath, so without these inputs Gradle would call the tests up to date after an edit to them.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/kotlin").withPropertyName("mainSourcesRead")
    inputs.dir("src/main/res/values").withPropertyName("stringsRead")
}

dependencies {
    implementation(project(":facegen"))
    implementation(project(":protocol"))
    implementation(project(":packjni"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    // onboarding animations. 6.7.1 is the latest on Maven Central (checked 2026-10-09); its POM imports an older
    // Compose BOM (2024.02.01), so the app's platform BOM wins. If a build ever fails on it, 6.6.7 is the previous good one.
    implementation("com.airbnb.android:lottie-compose:6.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // qr
    implementation("com.google.zxing:core:3.5.3")
    // qr
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Android Studio previews of the components; debug only, so the release APK does not carry it
    debugImplementation("androidx.compose.ui:ui-tooling")
}
