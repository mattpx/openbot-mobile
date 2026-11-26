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
