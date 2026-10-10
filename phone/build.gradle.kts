plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase is wired only when the owner has put google-services.json here (see docs/accounts/owner-prerequisites.md).
// Without it the plugin is not applied, no FirebaseApp exists, and the app builds and runs with accounts switched off.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.seyedjafari.analogplus.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seyedjafari.analogplus"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // writes the debug build's emulator marker before the app starts, so the tests also work once google-services.json exists
        testInstrumentationRunner = "com.seyedjafari.analogplus.phone.AccountsTestRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // firebase-auth 24.x is compiled with Kotlin 2.3 metadata; this project builds with Kotlin 2.0.21, which would refuse to
    // read it. The flag only skips that version check (we call the Java API); remove it when Kotlin is upgraded.
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
        getByName("androidTest").java.srcDir("src/androidTest/kotlin")
        // fakes (FakeRemoteStore, FakeAccountManager, ...) shared by the JVM and the instrumented tests
        getByName("test").java.srcDir("src/sharedTest/kotlin")
        getByName("androidTest").java.srcDir("src/sharedTest/kotlin")
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
    inputs.dir("src/debug/kotlin").withPropertyName("debugSourcesRead")
    inputs.dir("src/release/kotlin").withPropertyName("releaseSourcesRead")
    inputs.dir("src/main/res/values").withPropertyName("stringsRead")
}

// Firebase brings the lite protobuf runtime (protobuf-javalite + protolite-well-known-types) and the face validator brings the
// full one (protobuf-java, via aapt2-proto). They hold the same classes, so the build fails on duplicates. Keep the lite runtime:
// the validator was checked to run with it (DebugBuildReceiver builds and validates a face).
// Only the app's own classpaths: the Android test tooling (UTP) needs protobuf-java on its classpath.
configurations.matching { it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "com.google.protobuf", module = "protobuf-java")
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
    // accounts and sync (docs/superpowers/plans/2026-10-09-accounts-sync.md)
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
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
