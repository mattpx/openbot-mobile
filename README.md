# OpenBot Mobile 🤖📱

**OpenBot Mobile** is a native Android application bringing the OpenBot AI Coworker experience to mobile devices. It pairs a **Jetpack Compose** frontend with an embedded **Rust core engine** and an unprivileged **PRoot Linux sandbox** for secure, on-device agent execution.

---

## Architecture

- **UI & Experience**: Native Android with Jetpack Compose (Material 3).
- **Core Engine (`rust-core`)**: High-performance Rust engine handling AG-UI protocol streaming, policy checking, and sandboxed tool execution.
- **Rootless Sandbox**: Uses PRoot (`ptrace` syscall emulation) to run Linux commands inside an unprivileged Alpine rootfs in Android internal storage without requiring root.
- **Human-in-the-Loop Governance**: Real-time approval dialogs with risk assessment before executing sensitive tool calls (outbound network, file modifications).
- **Android Service**: An Android `ForegroundService` to ensure coworker tasks continue uninterrupted while multitasking.

---

## Directory Layout

```
openbot-mobile/
├── .github/workflows/
│   └── build-android.yml       # GitHub Actions CI for building debug APK
├── rust-core/                  # Rust engine (PRoot executor, policy engine, UniFFI)
│   ├── Cargo.toml
│   └── src/
│       ├── engine.rs
│       ├── lib.rs
│       ├── policy.rs
│       └── proot.rs
└── android-app/                # Native Android Jetpack Compose application
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       └── java/com/openbot/mobile/
    ├── build.gradle.kts
    └── settings.gradle.kts
```

---

## Building

### Automated via GitHub Actions
Pushing to `main` automatically triggers `.github/workflows/build-android.yml` to compile the Rust core for `arm64-v8a` and `x86_64` using `cargo-ndk` and assemble `app-debug.apk`.

### Manual Build
1. Build Rust core:
   ```bash
   cd rust-core
   cargo test
   cargo ndk -t arm64-v8a -o ../android-app/app/src/main/jniLibs build --release
   ```
2. Build Android APK:
   ```bash
   cd ../android-app
   ./gradlew assembleDebug
   ```
