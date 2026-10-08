# `packjni`: Google `pack` as a native library

`packjni/` is an Android library that wraps [`pack`](https://github.com/google/pack) (pinned to commit `7b60931`, `pack-api` crate) through JNI. It builds and signs a face APK in memory on the phone: `PackNative.build(manifest, resources, pem)` and `PackNative.generateKeysPem()`.

## One-off setup

```sh
curl https://sh.rustup.rs -sSf | sh                                   # Rust, installs to ~/.cargo
export PATH=$HOME/.cargo/bin:$PATH
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk                                               # tested with 4.1.2
```

Android NDK 28.2.13676358 must be installed (`sdkmanager "ndk;28.2.13676358"`). The Gradle task looks for it at `$ANDROID_HOME/ndk/28.2.13676358` (or `sdk.dir` from `local.properties`, or `~/Library/Android/sdk`); set `ANDROID_NDK_HOME` to use another NDK. No `.cargo/config.toml` linker settings are needed: `cargo-ndk` sets the compiler, linker and `ar` itself (the spike's `pack-cargo-config.toml` is only for building `pack-cli` by hand).

## How the build works

- `packjni/build.gradle.kts` registers `cargoNdkBuild` (an `Exec` task) and makes `preBuild` depend on it, so every Gradle build of any module that uses `packjni` has the `.so` files.
- It runs `cargo ndk --platform 28 -t arm64-v8a -t armeabi-v7a -t x86_64 -o src/main/jniLibs build --release` in `packjni/`. Output goes to `packjni/src/main/jniLibs/<abi>/libpackjni.so` (git-ignored, about 2.2 MB arm64, 1.4 MB armv7, 2.5 MB x86_64).
- Inputs are `Cargo.toml`, `Cargo.lock`, `src/**/*.rs` and the ABI list; the task is `UP-TO-DATE` when none changed, so Rust is not rebuilt. First build downloads and compiles the crates (about 1 minute for three ABIs); later Rust edits take about 20 s.
- Fewer ABIs while iterating: `./gradlew ... -PpackjniAbis=arm64-v8a,x86_64`.
- If `cargo`, `cargo-ndk` or the NDK is missing the task fails with a message saying what to install.
- `Cargo.lock` is committed. `pack-api` is built with its `cert-gen` feature (random RSA key plus self-signed certificate); `rsa` and `pem` are direct dependencies only to write that key out as PEM.

## API notes

- `FaceResource(type, name, bytes)` lives in package `com.seyedjafari.analogplus.packjni`. `type` is the `res/` subfolder (`raw`, `drawable`, `xml`, `values`); `name` **includes the extension** (`watchface.xml`, `preview.png`, `strings.xml`). A `values/strings.xml` is parsed into string resources, so the manifest can use `@string/app_name`.
- The manifest is text and must have a `package` attribute (pack fails otherwise).
- Resources cross the JNI boundary as parallel arrays (types, names, byte arrays).
- All failures, including Rust panics, are thrown as `PackException` with the Rust error text; the process is never aborted.
- `generateKeysPem()` returns one PEM with `CERTIFICATE` and PKCS#8 `PRIVATE KEY`. Keep it (it is the signer identity forever). The same PEM always gives the same signer certificate; `pack` signs with APK Signature Scheme v2/v3 only (no `META-INF/*.RSA`), so the test reads the signer through `PackageManager.getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)`, which also verifies the signature.

## Measured (phone emulator API 33, release `.so`)

| What | Time |
|---|---|
| `generateKeysPem()` (RSA 2048) | about 0.9 s |
| `build` of a minimal face | 25 ms first call, 13 ms after |
| Minimal face APK (manifest, `watchface.xml`, `watch_face_info.xml`, 31 KB preview) | 37.8 KB |

A debug-profile Rust build would make RSA key generation much slower; the Gradle task always builds `--release`.

Run the device test: `ANDROID_SERIAL=<serial> ./gradlew :packjni:connectedDebugAndroidTest`.
