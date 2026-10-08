# Throwaway spike sources (2026-10-08)

Not part of any build. Kept so the experiments can be reproduced. See `docs/handoff/2026-10-08-builder-session.md` for what they proved.

| File | What it was |
|---|---|
| `watch-MainActivity.kt`, `watch-build.gradle.kts`, `watch-AndroidManifest.xml` | Test "marketplace" app on the watch: drives `WatchFacePushManager` by intent (`--es cmd list|add1|addf|upd1|upd2|updf|active|isactive|remove|perm`), reads `files/in.apk` and `files/in.token` for `addf`/`updf` |
| `phone-BuilderActivity.kt`, `phone-build.gradle.kts` | Phone app that builds a face on the phone by template patching, signs it with apksig and an Android Keystore key, and validates it with `validator-push-android` |
| `push-face-AndroidManifest.xml` | Manifest of a push-compliant face (no service, no code) |
| `mkface.sh` | Builds, strips `classes.dex`, aligns, signs and validates a face on the Mac |
| `e2e.sh`, `seq.sh` | Scripts that drove the emulators and the real watch |
| `pack-cargo-config.toml` | Cargo config for cross-compiling `pack-cli` for Android (also set `CC_aarch64_linux_android`, `AR_aarch64_linux_android`, `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER`) |

Reproduce the key steps:

- Validator CLI: `https://dl.google.com/dl/android/maven2/com/google/android/wearable/watchface/validator/validator-push-cli/1.1.0-alpha01/validator-push-cli-1.1.0-alpha01.jar`, run `java -jar <jar> --apk_path=<apk> --package_name=<marketplace package>`.
- `pack`: `git clone https://github.com/google/pack` (commit 7b60931), `cargo build -p pack-cli --release`, then `pack-cli <dir> <out> [keys.pem]` where `<dir>` has a text `AndroidManifest.xml` and `res/{raw,drawable,xml,values}/…`. Make a PEM with `openssl req -x509 -newkey rsa:2048 -nodes -keyout k.pem -out c.pem -subj /CN=face -days 3650; cat c.pem k.pem > keys.pem` (never commit it).
- Marketplace app Gradle: AGP 8.9.1, Gradle 8.11.1, `androidx.wear.watchfacepush:watchfacepush:1.0.0`, `kotlinx-coroutines-android`; phone app also needs `maven("https://jitpack.io")`.
