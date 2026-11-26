package com.openbot.mobile

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed class SandboxStatus {
    object Uninitialized : SandboxStatus()
    data class SettingUp(val message: String, val progress: Float) : SandboxStatus()

    data class Ready(
        val prootPath: String,
        val rootfsPath: String,
        val workspacePath: String,
        /** `proot` for real isolation, `shim` for degraded fallback, `mock` for debug builds. */
        val mode: String,
        val prootVersion: String,
        val rootfsVersion: String,
        /** `bundled` when a real rootfs archive was extracted, `minimal` otherwise. */
        val rootfsKind: String,
        val warning: String?
    ) : SandboxStatus()

    data class Failed(val error: String) : SandboxStatus()
}

class RootfsManager(private val context: Context) {

    private val assets = SandboxAssets(context)

    private val _status = MutableStateFlow<SandboxStatus>(SandboxStatus.Uninitialized)
    val status: StateFlow<SandboxStatus> = _status

    private val binDir: File by lazy { File(context.filesDir, "bin").apply { mkdirs() } }
    private val rootfsDir: File by lazy { File(context.filesDir, "rootfs").apply { mkdirs() } }
    private val workspaceDir: File by lazy { File(context.filesDir, "workspace").apply { mkdirs() } }
    private val prootBin: File by lazy { File(binDir, "proot") }

    suspend fun initializeSandbox(): Boolean = withContext(Dispatchers.IO) {
        try {
            val abi = abi()
            val warnings = mutableListOf<String>()
            val manifest = assets.loadManifest()

            _status.value = SandboxStatus.SettingUp("Checking sandbox assets for $abi...", 0.05f)

            if (manifest.proot.isEmpty()) {
                warnings += "No PRoot binary is bundled with this build."
            }

            _status.value = SandboxStatus.SettingUp("Installing PRoot binary...", 0.2f)
            var installedProot = false
            var prootVersion = "unbundled-shim"
            when (val proot = assets.installProot(abi, prootBin)) {
                is ProotInstallResult.Installed -> {
                    installedProot = true
                    prootVersion = proot.entry?.version?.ifBlank { "unversioned" } ?: "unverified"
                    if (!proot.verified) {
                        warnings += "PRoot binary was installed without a verified checksum."
                    }
                }
                is ProotInstallResult.Failed -> {
                    warnings += "PRoot install failed: ${proot.reason}"
                    installShim()
                }
                ProotInstallResult.Missing -> installShim()
            }
            if (!installedProot) {
                warnings += "Running in degraded shim mode: commands are not filesystem-isolated."
            }

            _status.value = SandboxStatus.SettingUp("Provisioning Linux rootfs...", 0.55f)
            var rootfsKind = "minimal"
            var rootfsVersion = "minimal"
            when (val rootfs = assets.installRootfs(abi, rootfsDir)) {
                is RootfsInstallResult.Installed -> {
                    rootfsKind = "bundled"
                    rootfsVersion = rootfs.version
                    if (!rootfs.verified) {
                        warnings += "Rootfs was installed without a verified checksum."
                    }
                }
                is RootfsInstallResult.Failed -> {
                    warnings += "Bundled rootfs rejected: ${rootfs.reason}"
                }
                RootfsInstallResult.Missing -> Unit
            }

            // Always ensure the minimal layout exists: it supplies bind-mount
            // targets and lets real PRoot run with /system bound into the guest.
            ensureMinimalRootfsStructure(rootfsKind, rootfsVersion)

            _status.value = SandboxStatus.SettingUp("Starting OpenBot core engine...", 0.85f)
            val initialized = OpenBotCore.initEngine(
                prootPath = prootBin.absolutePath,
                rootfsPath = rootfsDir.absolutePath,
                workspacePath = workspaceDir.absolutePath
            )
            if (!initialized) {
                _status.value = SandboxStatus.Failed("Failed to initialize the Rust core engine")
                return@withContext false
            }

            val engineMode = OpenBotCore.sandboxMode()
            if (engineMode == "proot" && !installedProot) {
                warnings += "Engine reported PRoot mode without an installed PRoot binary."
            }
            if (engineMode == "shim" || engineMode == "mock") {
                warnings += "This build is not sandboxed: filesystem isolation is unavailable."
            }

            _status.value = SandboxStatus.Ready(
                prootPath = prootBin.absolutePath,
                rootfsPath = rootfsDir.absolutePath,
                workspacePath = workspaceDir.absolutePath,
                mode = engineMode.ifBlank { if (installedProot) "proot" else "shim" },
                prootVersion = prootVersion,
                rootfsVersion = rootfsVersion,
                rootfsKind = rootfsKind,
                warning = warnings.distinct().joinToString(" ").ifBlank { null }
            )
            true
        } catch (e: Exception) {
            _status.value = SandboxStatus.Failed("Setup error: ${e.message ?: e.toString()}")
            false
        }
    }

    private fun installShim() {
        prootBin.delete()
        prootBin.writeText(
            """
            #!/system/bin/sh
            # OPENBOT_SHIM
            # Degraded fallback: the Rust executor runs commands directly with sh.
            exec "$@"
            """.trimIndent() + "\n"
        )
        prootBin.setExecutable(true, false)
        prootBin.setReadable(true, false)
    }

    private fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private fun ensureMinimalRootfsStructure(rootfsKind: String, rootfsVersion: String) {
        val dirs = listOf(
            "bin", "etc", "dev", "proc", "sys", "tmp", "usr/bin",
            "workspace", "home", "root", "var/tmp"
        )
        for (dir in dirs) File(rootfsDir, dir).mkdirs()

        val passwd = File(rootfsDir, "etc/passwd")
        if (!passwd.exists()) passwd.writeText("root:x:0:0:root:/root:/bin/sh\n")
        val group = File(rootfsDir, "etc/group")
        if (!group.exists()) group.writeText("root:x:0:\n")
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")

        val marker = File(rootfsDir, "etc/openbot-rootfs")
        marker.writeText("openbot-rootfs:$rootfsKind:$rootfsVersion\n")

        // A rootfs shell helper for PRoot. /system is bind-mounted so this
        // resolves both inside real PRoot and in the degraded shim.
        val shFile = File(rootfsDir, "bin/sh")
        if (!shFile.exists()) {
            shFile.writeText("#!/system/bin/sh\nexec /system/bin/sh \"$@\"\n")
            shFile.setExecutable(true, false)
        }
    }
}
