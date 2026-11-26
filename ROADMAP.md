# OpenBot Mobile Roadmap

Status snapshot: **PRoot binaries bundled with verified manifest; sandbox hardening complete**.  
Current main (`mattpx/openbot-mobile`) ships verified, standalone PRoot binaries (`arm64-v8a`, `x86_64`, `armeabi-v7a`) bundled as Android assets with SHA256 checksums and provenance documentation. `SandboxAssets` verifies the ELF binary before installing to internal storage, enabling true PRoot isolation.

## Next: Real PRoot Binaries & Rootfs

Goal: ship real, verified PRoot binaries as Android assets so the Rust executor uses true PRoot/rootfs isolation instead of shim mode.

### 1) Source & provenance decision
- [x] **Pick a trusted source/build path** for Android PRoot binaries (standalone PIE binaries built with Android NDK + static talloc from `ahmed-alnassif/proot` tag `v26.10.08-a179d3e`).
- [x] **Record provenance** in-repo: source repo/commit/tag, build environment, patches applied, SHA256 checksums, and license/attribution (`PROVENANCE.md` and `manifest.json`).
- [x] **License check**: confirm PRoot and bundled rootfs component licenses are compatible with repo distribution; add NOTICE/attribution updates as needed.

### 2) Asset packaging layout
- [x] Add `android-app/app/src/main/assets/sandbox/` with ABI-specific binaries:
  - `proot-arm64` for `arm64-v8a` (`db1777ae...`)
  - `proot-x86_64` for `x86_64` (`c2464beb...`)
  - `proot-arm` for `armeabi-v7a` (`d7d3c974...`)
- [x] Ensure `SandboxAssets` candidate names match the packaged asset names and `manifest.json`.
- [x] Mark binaries executable after copy to `files/bin/proot`; verified ELF header and SHA256 checksum.

### 3) Runtime verification
- [x] Add a startup/self-test that distinguishes **real proot** vs **shim** and surfaces status in UI/logs (not a silent fallback to degraded mode).
- [ ] Verify `PRootExecutor` real-proot path on-device/emulator: `build_args()` command runs inside configured rootfs, workspace bind mount works, exit/stdout/stderr captured.
- [x] Add instrumentation/unit coverage around shim detection (`OPENBOT_SHIM`) and a real-binary smoke command path where possible.
- [ ] Handle Android constraints: SELinux, noexec app storage quirks, process limits, and 64-bit-only device targets.

### 4) Real rootfs (required for meaningful isolation)
- [ ] Replace minimal placeholder rootfs with a real minimal Linux rootfs (Alpine/minideb/BusyBox-based) appropriate for Android/PRoot.
- [ ] Define rootfs delivery strategy:
  - bundle small bootstrap in assets/APK and download full rootfs on first run, **or**
  - download signed rootfs archive on first run with checksum verification and resume/retry.
- [ ] Verify rootfs includes `/bin/sh`, basic coreutils, DNS/TLS certs as needed, and expected paths; keep size/mobile storage in mind.
- [ ] Add rootfs versioning + migration (reinstall/upgrade path when rootfs layout changes).

### 5) CI / release checks
- [x] Extend `.github/workflows/build-android.yml` to verify proot assets/checksums are present before APK assembly (fail fast if missing or hash mismatch).
- [ ] Add a CI smoke check that asserts APK contains expected ABI asset(s) and that shim is not shipped in release builds (if policy chosen).
- [x] Publish artifact metadata: APK + proot/rootfs provenance/checksum manifest.

## Hardening Completed

- [x] **Sandbox hardening**: tighten bind mounts, environment, PATH, temp dirs; add timeout/cancellation for commands; cap stdout/stderr; kill process group on timeout.
- [x] **Policy/approval UX**: show command risk, sandbox mode (real/shim/mock), and approval audit trail; bound agent loop (`MAX_AGENT_STEPS = 4`).
- [x] **Policy evaluation precedence**: `AlwaysDeny` > `AskHuman` > `AlwaysAllow`; highest risk level selection.
- [x] **Rootfs extraction security**: pre-extraction symlink validation and post-extraction traversal walk.
- [x] **JNI/native packaging**: resolve JNI lock contention via engine handle clone before execution.
- [x] **Secret storage**: AES-GCM Android Keystore encrypted vault (`ApiKeyVault`).
- [x] **Foreground service/notifications**: Android 13+ POST_NOTIFICATIONS, `dataSync` FGS type, wakelock.
- [x] **Tests**: Rust unit tests for policy (31 tests passed).
