# OpenBot Mobile Roadmap

Status snapshot: **MVP scaffolded, sandbox is degraded without real PRoot binaries**.  
Current main (`mattpx/openbot-mobile`) builds the Android app + Rust JNI core, but `RootfsManager` falls back to an `OPENBOT_SHIM` script when no bundled PRoot binary is found. In shim mode `rust-core/src/proot.rs` executes commands via system `sh` in the app workspace (no rootfs isolation).

## Next: Real PRoot Binaries (before changing runtime behavior)

Goal: ship real, verified PRoot binaries as Android assets so the Rust executor uses true PRoot/rootfs isolation instead of shim mode.

### 1) Source & provenance decision
- [ ] **Pick a trusted source/build path** for Android PRoot binaries (e.g., build from upstream PRoot/proot-ng source with Android NDK patches, or use reproducible Termux packaging artifacts). Do not commit opaque binaries without provenance.
- [ ] **Record provenance** in-repo: source repo/commit/tag, build environment, patches applied, SHA256 checksums, and license/attribution.
- [ ] **License check**: confirm PRoot and bundled rootfs component licenses are compatible with repo distribution; add NOTICE/attribution updates as needed.

### 2) Asset packaging layout
- [ ] Add `android-app/app/src/main/assets/proot/` (or top-level assets) with ABI-specific binaries:
  - `proot-arm64` for `arm64-v8a`
  - `proot-x86_64` for `x86_64`
  - `proot-arm` for `armeabi-v7a` (only if ABI supported; currently `build.gradle.kts` filters `arm64-v8a,x86_64`)
- [ ] Ensure `RootfsManager.installProotBinary()` candidate names match the packaged asset names.
- [ ] Mark binaries executable after copy to `files/bin/proot`; keep shim fallback only for explicit dev/debug builds or remove after real binaries are mandatory.

### 3) Runtime verification
- [ ] Add a startup/self-test that distinguishes **real proot** vs **shim** and surfaces status in UI/logs (not a silent fallback to degraded mode).
- [ ] Verify `PRootExecutor` real-proot path on-device/emulator: `build_args()` command runs inside configured rootfs, workspace bind mount works, exit/stdout/stderr captured.
- [ ] Add instrumentation/unit coverage around shim detection (`OPENBOT_SHIM`) and a real-binary smoke command path where possible.
- [ ] Handle Android constraints: SELinux, noexec app storage quirks, process limits, and 64-bit-only device targets.

### 4) Real rootfs (required for meaningful isolation)
- [ ] Replace minimal placeholder rootfs with a real minimal Linux rootfs (Alpine/minideb/BusyBox-based) appropriate for Android/PRoot.
- [ ] Define rootfs delivery strategy:
  - bundle small bootstrap in assets/APK and download full rootfs on first run, **or**
  - download signed rootfs archive on first run with checksum verification and resume/retry.
- [ ] Verify rootfs includes `/bin/sh`, basic coreutils, DNS/TLS certs as needed, and expected paths; keep size/mobile storage in mind.
- [ ] Add rootfs versioning + migration (reinstall/upgrade path when rootfs layout changes).

### 5) CI / release checks
- [ ] Extend `.github/workflows/build-android.yml` to verify proot assets/checksums are present before APK assembly (fail fast if missing or hash mismatch).
- [ ] Add a CI smoke check that asserts APK contains expected ABI asset(s) and that shim is not shipped in release builds (if policy chosen).
- [ ] Publish artifact metadata: APK + proot/rootfs provenance/checksum manifest.

## After PRoot Binaries Land

- [ ] **Sandbox hardening**: tighten bind mounts, environment, PATH, temp dirs; add timeout/cancellation for commands; cap stdout/stderr.
- [ ] **Policy/approval UX**: show command risk, sandbox mode (real/shim), and approval audit trail; persist approvals only with explicit scope.
- [ ] **JNI/native packaging**: confirm `libopenbot_core.so` is built for all shipped ABIs; fail CI on missing JNI libs.
- [ ] **Agent loop**: replace mock-first flows with real LLM tool-calling protocol, streaming, and structured tool results; keep human approval before high-risk actions.
- [ ] **Foreground service/notifications**: ensure Android 13+ notification permission flow is complete; verify FGS type/behavior on target SDK.
- [ ] **Security review**: command injection boundaries, file access scope, network egress policy, secret storage for API keys, and clear "degraded sandbox" warnings.
- [ ] **Tests**: Rust unit/integration for policy+proot execution; Android instrumented tests for sandbox bootstrap and approval flow; CI green gate before release.
