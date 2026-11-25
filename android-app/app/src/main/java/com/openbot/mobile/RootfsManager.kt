package com.openbot.mobile

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class SandboxStatus {
    object Uninitialized : SandboxStatus()
    data class SettingUp(val message: String, val progress: Float) : SandboxStatus()
    data class Ready(val prootPath: String, val rootfsPath: String, val workspacePath: String) : SandboxStatus()
    data class Failed(val error: String) : SandboxStatus()
}

class RootfsManager(private val context: Context) {

    private val _status = MutableStateFlow<SandboxStatus>(SandboxStatus.Uninitialized)
    val status: StateFlow<SandboxStatus> = _status

    private val binDir: File by lazy { File(context.filesDir, "bin").apply { mkdirs() } }
    private val rootfsDir: File by lazy { File(context.filesDir, "rootfs").apply { mkdirs() } }
    private val workspaceDir: File by lazy { File(context.filesDir, "workspace").apply { mkdirs() } }
    private val prootBin: File by lazy { File(binDir, "proot") }

    suspend fun initializeSandbox(): Boolean = withContext(Dispatchers.IO) {
        try {
            _status.value = SandboxStatus.SettingUp("Checking PRoot environment...", 0.1f)

            if (!prootBin.exists() || prootBin.length() == 0L) {
                _status.value = SandboxStatus.SettingUp("Installing PRoot engine...", 0.3f)
                installProotBinary()
            }
            prootBin.setExecutable(true, false)
            prootBin.setReadable(true, false)

            _status.value = SandboxStatus.SettingUp("Configuring rootfs filesystem...", 0.6f)
            ensureMinimalRootfsStructure()

            _status.value = SandboxStatus.SettingUp("Starting OpenBot core engine...", 0.9f)
            val initialized = OpenBotCore.initEngine(
                prootPath = prootBin.absolutePath,
                rootfsPath = rootfsDir.absolutePath,
                workspacePath = workspaceDir.absolutePath
            )

            if (initialized) {
                _status.value = SandboxStatus.Ready(
                    prootPath = prootBin.absolutePath,
                    rootfsPath = rootfsDir.absolutePath,
                    workspacePath = workspaceDir.absolutePath
                )
                true
            } else {
                _status.value = SandboxStatus.Failed("Failed to initialize Rust Core OpenBotEngine")
                false
            }
        } catch (e: Exception) {
            _status.value = SandboxStatus.Failed("Setup error: ${e.message}")
            false
        }
    }

    private fun installProotBinary() {
        val assetName = preferredProotAsset()
        val candidates = listOfNotNull(assetName, "proot", "proot-${abi()}")
        for (name in candidates) {
            try {
                context.assets.open(name).use { input ->
                    FileOutputStream(prootBin).use { output -> input.copyTo(output) }
                }
                prootBin.setExecutable(true, false)
                if (prootBin.length() > 0L) return
            } catch (_: Exception) {
                // try next candidate
            }
        }

        // Fallback shim. Rust proot.rs detects OPENBOT_SHIM and executes via system sh.
        prootBin.writeText(
            """
            #!/system/bin/sh
            # OPENBOT_SHIM
            # Real proot binary not bundled; Rust engine will fall back to direct shell execution.
            exec "$@"
            """.trimIndent() + "\n"
        )
        prootBin.setExecutable(true, false)
    }

    private fun preferredProotAsset(): String? {
        return when (abi()) {
            "arm64-v8a" -> "proot-arm64"
            "x86_64" -> "proot-x86_64"
            "armeabi-v7a" -> "proot-arm"
            else -> null
        }
    }

    private fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private fun ensureMinimalRootfsStructure() {
        val dirs = listOf("bin", "etc", "dev", "proc", "sys", "tmp", "usr/bin", "workspace", "home")
        for (dir in dirs) File(rootfsDir, dir).mkdirs()

        // Minimal /etc files for tools that expect them
        val passwd = File(rootfsDir, "etc/passwd")
        if (!passwd.exists()) passwd.writeText("root:x:0:0:root:/workspace:/bin/sh\n")
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost\n")

        // Marker for diagnostics
        File(rootfsDir, "etc/openbot-rootfs").writeText("openbot-mobile-minimal-rootfs\n")

        // Symlink-ish sh helper is not reliable on Android; just place a script.
        val shFile = File(rootfsDir, "bin/sh")
        if (!shFile.exists()) {
            shFile.writeText("#!/system/bin/sh\nexec /system/bin/sh \"$@\"\n")
            shFile.setExecutable(true, false)
        }
    }
}
