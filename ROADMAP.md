# OpenBot Mobile Roadmap

Status snapshot: **PRoot + Alpine `3.21.10` minirootfs bundled with verified manifest; sandbox hardening complete**.  
Current branch ships verified, standalone PRoot binaries (`arm64-v8a`, `x86_64`, `armeabi-v7a`) and Alpine Linux `3.21.10` minirootfs archives (`arm64-v8a`, `x86_64`) bundled as Android assets with SHA256 checksums, provenance documentation, and versioned atomic rootfs upgrades.

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
- [x] Replace minimal placeholder rootfs with a real minimal Linux rootfs (Alpine `3.21.10` minirootfs: musl + BusyBox + `apk` + CA certs) for `arm64-v8a` and `x86_64`.
- [x] Define rootfs delivery strategy: bundled verified `.tar.gz` assets under `assets/sandbox/` with pinned SHA256 checksums and provenance in `manifest.json` / `PROVENANCE.md`.
- [x] Verify rootfs includes `/bin/sh`, basic coreutils, DNS (`etc/resolv.conf`), TLS certs (`/etc/ssl/certs/ca-certificates.crt`), and expected paths.
- [x] Add rootfs versioning + migration (skip re-extraction when marker matches version + SHA256; atomic staging swap on upgrade).

### 5) CI / release checks
- [x] Extend `.github/workflows/build-android.yml` to verify proot and rootfs assets/checksums are present before APK assembly (fail fast if missing or hash mismatch).
- [x] Add a CI smoke check that asserts the built APK contains expected ABI native libs, PRoot binaries, Alpine rootfs tarballs, and manifest.
- [x] Publish artifact metadata: APK + proot/rootfs provenance/checksum manifest.

## Next: OpenBot Parity (Mobile Equivalents of `CopilotKit/openbot`)

Goal: bring core OpenBot capabilities to Android without server/enterprise bloat (skipping Docker/K8s supervisor, PostgreSQL, SSO/SCIM, and multi-tenant orgs).

### 6) Persistent threads & audit log
- [x] Persist chat threads, messages, policy decisions, and execution audit logs locally (SQLite `ThreadStore`) so history survives app restarts.
- [x] Support multiple conversation threads (create, switch, rename, delete) and exportable JSON audit logs.

### 7) Workspace file browser & sandbox reset
- [x] Add an in-app `/workspace` file browser to view, share, delete, and export files created by the agent (with `FileProvider`).
- [x] Add clean "Reset Workspace" and "Reinstall Rootfs" actions with confirmation dialogs and audit logging.

### 8) Structured tool-calling & file tools
- [x] Replace plain-text bash regex parsing in `LlmClient` with native structured tool/function calling (with JSON/fenced-bash fallback).
- [x] Expose dedicated tools (`shell`, `read_file`, `write_file`, `list_files`, `web_fetch`, `web_browse`) with `/workspace` confinement and Rust policy gating (`AgentTools.kt`).

### 9) Browser / web interaction tool
- [x] Add an Android `WebView`-backed web tool (`WebBrowserTool.kt`) supporting HTTP fetch, rendered DOM text extraction, and PNG screenshot capture into `/workspace/screenshots/`.

### 10) Customizable policies, bot profiles & reusable skills
- [x] Allow editing custom allow/ask/deny policy rules in-app (`GovernanceStore` + `GovernanceDialog` + Rust `set_custom_policy_rules` JNI bridge) while preserving baseline safety rules.
- [x] Support saved bot profiles (custom system prompts/roles) and reusable skills with 1-tap execution.

### 11) Multi-provider LLM streaming & optional AG-UI mode
- [x] Add live SSE token streaming and native adapters for OpenAI-compatible, Anthropic Claude, and Google Gemini in `LlmClient`.
- [x] Support connecting to a remote self-hosted OpenBot server over the AG-UI SSE protocol (`LlmProvider.AG_UI`).

## Hardening Completed

- [x] **Sandbox hardening**: tighten bind mounts, environment, PATH, temp dirs; add timeout/cancellation for commands; cap stdout/stderr; kill process group on timeout.
- [x] **Policy/approval UX**: show command risk, sandbox mode (real/shim/mock), and approval audit trail; bound agent loop (`MAX_AGENT_STEPS = 4`).
- [x] **Policy evaluation precedence**: `AlwaysDeny` > `AskHuman` > `AlwaysAllow`; highest risk level selection.
- [x] **Rootfs extraction security**: pre-extraction symlink validation and post-extraction traversal walk.
- [x] **JNI/native packaging**: resolve JNI lock contention via engine handle clone before execution.
- [x] **Secret storage**: AES-GCM Android Keystore encrypted vault (`ApiKeyVault`).
- [x] **Foreground service/notifications**: Android 13+ POST_NOTIFICATIONS, `dataSync` FGS type, wakelock.
- [x] **Tests**: Rust unit tests for policy (31 tests passed).
