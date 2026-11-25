package com.openbot.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

            // Setup proot binary if missing
            if (!prootBin.exists() || prootBin.length() == 0L) {
                _status.value = SandboxStatus.SettingUp("Installing PRoot engine...", 0.3f)
                installProotBinary()
            }

            // Ensure executable permissions
            prootBin.setExecutable(true, false)
            prootBin.setReadable(true, false)

            // Setup rootfs base directories
            _status.value = SandboxStatus.SettingUp("Configuring rootfs filesystem...", 0.6f)
            ensureMinimalRootfsStructure()

            // Initialize OpenBot Rust Core Engine
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
        // Look for bundled asset or copy standard proot
        try {
            context.assets.open("proot").use { input ->
                FileOutputStream(prootBin).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            // Create fallback wrapper script if asset not bundled
            prootBin.writeText("#!/bin/sh\nexec \"$@\"\n")
        }
        prootBin.setExecutable(true, false)
    }

    private fun ensureMinimalRootfsStructure() {
        // Ensure standard Linux root directories exist
        val dirs = listOf("bin", "etc", "dev", "proc", "sys", "tmp", "usr/bin", "workspace")
        for (dir in dirs) {
            File(rootfsDir, dir).mkdirs()
        }

        // Install minimal sh fallback
        val shFile = File(rootfsDir, "bin/sh")
        if (!shFile.exists()) {
            shFile.writeText("#!/bin/sh\nexec /bin/sh \"$@\"\n")
            shFile.setExecutable(true, false)
        }
    }
}
