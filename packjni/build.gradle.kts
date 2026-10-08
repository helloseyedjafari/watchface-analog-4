import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val NDK_VERSION = "28.2.13676358"
// ABIs of the native library; override with -PpackjniAbis=arm64-v8a,x86_64 for a quicker build.
val packjniAbis = (findProperty("packjniAbis") as String? ?: "arm64-v8a,armeabi-v7a,x86_64").split(",").map { it.trim() }

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Builds packjni (Rust, google/pack) for Android with cargo-ndk into src/main/jniLibs"
    val crate = layout.projectDirectory
    // Up to date unless the Rust sources, manifest/lock, or the ABI list changed.
    inputs.files(crate.file("Cargo.toml"), crate.file("Cargo.lock"))
    inputs.files(fileTree(crate.dir("src")) { include("**/*.rs") }).withPropertyName("rustSources")
    inputs.property("abis", packjniAbis)
    inputs.property("ndkVersion", NDK_VERSION)
    outputs.dir(crate.dir("src/main/jniLibs"))
    workingDir = crate.asFile

    val home = System.getProperty("user.home")
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.removePrefix("sdk.dir=")
        ?: "$home/Library/Android/sdk"
    val ndkHome = System.getenv("ANDROID_NDK_HOME") ?: "$sdk/ndk/$NDK_VERSION"
    environment("ANDROID_NDK_HOME", ndkHome)
    environment("PATH", "$home/.cargo/bin:" + System.getenv("PATH"))
    // one build cache for every worktree
    environment("CARGO_TARGET_DIR", System.getenv("CARGO_TARGET_DIR") ?: "$home/.cache/analogplus/cargo-target")

    val cargo = File("$home/.cargo/bin/cargo").takeIf { it.canExecute() }?.path ?: "cargo"
    val cmd = mutableListOf(cargo, "ndk", "--platform", "28")
    packjniAbis.forEach { cmd += listOf("-t", it) }
    // --locked: fail instead of silently re-resolving and rewriting the committed Cargo.lock
    cmd += listOf("-o", "src/main/jniLibs", "build", "--release", "--locked")
    commandLine(cmd)

    doFirst {
        // cargo-ndk never deletes other ABIs, so a reduced -PpackjniAbis would leave old ABI directories behind
        // and package them out of sync with the Rust sources. Keep exactly the requested ones.
        val jniLibs = crate.dir("src/main/jniLibs").asFile
        jniLibs.listFiles { f -> f.isDirectory && f.name !in packjniAbis }?.forEach {
            // links are unlinked, never followed (File.deleteRecursively follows nested symlinks to what they point to)
            fun deleteTree(p: Path) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.list(p).use { s -> s.toList().forEach { c -> deleteTree(c) } }
                Files.delete(p)
            }
            deleteTree(it.toPath())
        }
        val cargoOk = listOf("$home/.cargo/bin/cargo").any { File(it).canExecute() } ||
            System.getenv("PATH").split(File.pathSeparator).any { File(it, "cargo").canExecute() }
        if (!cargoOk) throw GradleException("cargo not found. Install Rust (https://rustup.rs) and cargo-ndk; see docs/poc/pack-native.md")
        if (!File("$home/.cargo/bin/cargo-ndk").canExecute() &&
            System.getenv("PATH").split(File.pathSeparator).none { File(it, "cargo-ndk").canExecute() }
        ) throw GradleException("cargo-ndk not found. Run: cargo install cargo-ndk (see docs/poc/pack-native.md)")
        if (!File(ndkHome).isDirectory) throw GradleException("Android NDK not found at $ndkHome. Install NDK $NDK_VERSION or set ANDROID_NDK_HOME (see docs/poc/pack-native.md)")
    }
}

tasks.named("preBuild") { dependsOn(cargoNdkBuild) }

android {
    namespace = "com.seyedjafari.analogplus.packjni"
    compileSdk = 36
    ndkVersion = NDK_VERSION

    defaultConfig {
        minSdk = 28
        // R8 must not rename PackException (the native code finds it by name) or the native methods
        consumerProguardFiles("consumer-rules.pro")
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
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
