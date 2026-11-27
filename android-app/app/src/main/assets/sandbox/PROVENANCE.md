# Sandbox PRoot Binaries Provenance & Attribution

This directory contains standalone, PIE-compatible PRoot binaries compiled specifically for Android devices without root requirements.

## 1. Upstream Source & Build Path

- **Source Code Base**: [proot-me/proot](https://github.com/proot-me/proot) with Android Bionic compatibility and ptrace patches.
- **Build Repository**: [ahmed-alnassif/proot](https://github.com/ahmed-alnassif/proot)
- **Release Version**: `5.1.107-a179d3e`
- **Release Tag**: [`v26.10.08-a179d3e`](https://github.com/ahmed-alnassif/proot/releases/tag/v26.10.08-a179d3e)
- **Build Toolchain**: Android NDK with statically compiled `talloc` library to eliminate external library dependencies (`libtalloc.so`). Only standard Android Bionic system libraries (`libc.so`, `libdl.so`) are dynamically linked.

## 2. Binary Verification & Checksums

| Asset | ABI Target | Architecture | Size | SHA256 Checksum |
|---|---|---|---|---|
| `proot-arm64` | `arm64-v8a` | AArch64 (64-bit ARM) | 284,792 bytes | `db1777ae6e1cd67164360b7f4f22042de98406a1a1141453962521c134ff4a0a` |
| `proot-x86_64` | `x86_64` | x86_64 (64-bit AMD/Intel) | 292,896 bytes | `c2464beb160ff54b351dcef84311d2a38dcda21fba92f486b8bf12cf50ddd8d6` |
| `proot-arm` | `armeabi-v7a` | ARMv7 (32-bit ARM) | 232,832 bytes | `d7d3c974280b16b1ef7c7e48bf2d624dbff1098937eb980699d375d8ba6c6546` |

All checksums are verified at install time by `com.openbot.mobile.SandboxAssets` before binary execution.

## 3. License & Attribution

- **License**: GNU General Public License v2.0 or later ([GPL-2.0-or-later](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)).
- **Copyright**: (C) 2014-2023 PRoot contributors.
- **Embedded Libraries**: `talloc` is licensed under the GNU Lesser General Public License (LGPLv3+).

---

# Bundled Linux Rootfs Provenance & Attribution

This directory also bundles official Alpine Linux minirootfs archives for 64-bit Android targets (`arm64-v8a`, `x86_64`), providing musl libc, BusyBox (`/bin/sh` + coreutils), `apk-tools`, and Mozilla CA certificates (`/etc/ssl/certs/ca-certificates.crt`).

## 1. Upstream Source

- **Distribution**: [Alpine Linux](https://alpinelinux.org/) minirootfs
- **Release Version**: `3.21.10`
- **Upstream Mirror**: `https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/`

## 2. Rootfs Verification & Checksums

| Asset | ABI Target | Upstream File | Size | SHA256 Checksum |
|---|---|---|---|---|
| `rootfs-arm64.tgz` | `arm64-v8a` | `alpine-minirootfs-3.21.10-aarch64.tar.gz` | 3,831,092 bytes | `fc957a820e80660eec7c1dde2b619385f153f1c04d0eb537449eb6b6c24ba107` |
| `rootfs-x86_64.tgz` | `x86_64` | `alpine-minirootfs-3.21.10-x86_64.tar.gz` | 3,490,079 bytes | `d225c786541b5ae5ba006ef541701d9ecc7e732bd16317c08adfb93212345efa` |

Checksums match the official upstream `.sha256` digests and are verified at install time by `com.openbot.mobile.SandboxAssets` before extraction.

## 3. License & Attribution

- **Alpine Linux / musl**: MIT License.
- **BusyBox**: GNU General Public License v2.0 ([GPL-2.0-only](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)).
- **OpenSSL / libcrypto / libssl**: Apache License 2.0.
- **CA Certificates**: MPL-2.0.
