package com.openbot.mobile

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class SandboxAssetEntry(
    val asset: String,
    val sha256: String,
    val version: String,
    val source: String,
    val license: String
)

data class SandboxManifest(
    val schemaVersion: Int,
    val proot: Map<String, SandboxAssetEntry>,
    val rootfs: Map<String, SandboxAssetEntry>
) {
    companion object {
        const val MANIFEST_ASSET = "sandbox/manifest.json"

        fun empty(): SandboxManifest = SandboxManifest(1, emptyMap(), emptyMap())

        fun parse(json: String): SandboxManifest {
            val root = JSONObject(json)
            return SandboxManifest(
                schemaVersion = root.optInt("schema_version", 1),
                proot = parseEntries(root.optJSONObject("proot")),
                rootfs = parseEntries(root.optJSONObject("rootfs"))
            )
        }

        private fun parseEntries(obj: JSONObject?): Map<String, SandboxAssetEntry> {
            if (obj == null) return emptyMap()
            val result = mutableMapOf<String, SandboxAssetEntry>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val abi = keys.next()
                val entry = obj.optJSONObject(abi) ?: continue
                val asset = entry.optString("asset").trim()
                if (asset.isEmpty()) continue
                result[abi] = SandboxAssetEntry(
                    asset = asset,
                    sha256 = entry.optString("sha256").trim().lowercase(),
                    version = entry.optString("version").trim(),
                    source = entry.optString("source").trim(),
                    license = entry.optString("license").trim()
                )
            }
            return result
        }
    }
}

sealed class ProotInstallResult {
    data class Installed(val entry: SandboxAssetEntry?, val verified: Boolean) : ProotInstallResult()
    data class Failed(val reason: String) : ProotInstallResult()
    object Missing : ProotInstallResult()
}

sealed class RootfsInstallResult {
    data class Installed(val version: String, val verified: Boolean) : RootfsInstallResult()
    data class Failed(val reason: String) : RootfsInstallResult()
    object Missing : RootfsInstallResult()
}

/**
 * Installs optional PRoot and rootfs payloads shipped under `assets/sandbox/`.
 *
 * Assets are never trusted blindly: an ELF header is required for PRoot, and
 * every payload with a manifest checksum must match it before use. Missing
 * assets are reported explicitly so the UI can show degraded sandbox mode.
 */
class SandboxAssets(private val context: Context) {

    fun loadManifest(): SandboxManifest {
        return try {
            context.assets.open(SandboxManifest.MANIFEST_ASSET).use { input ->
                SandboxManifest.parse(input.bufferedReader().readText())
            }
        } catch (e: Exception) {
            Log.i(TAG, "No sandbox manifest bundled; using legacy asset fallbacks", e)
            SandboxManifest.empty()
        }
    }

    fun installProot(abi: String, target: File): ProotInstallResult {
        val entry = loadManifest().proot[abi]
        if (entry != null) {
            return installProotAsset(entry, target)
        }
        val legacyCandidates = listOf(
            "sandbox/proot-${shortAbi(abi)}",
            "proot-${shortAbi(abi)}",
            "proot"
        )
        for (name in legacyCandidates) {
            val found = copyAsset(name, target)
            if (found) {
                if (!isElf(target)) {
                    target.delete()
                    return ProotInstallResult.Failed("$name is not an ELF executable")
                }
                target.setExecutable(true, false)
                return ProotInstallResult.Installed(entry = null, verified = false)
            }
        }
        return ProotInstallResult.Missing
    }

    private fun installProotAsset(entry: SandboxAssetEntry, target: File): ProotInstallResult {
        if (!copyAsset(entry.asset, target)) {
            return ProotInstallResult.Failed("Declared asset ${entry.asset} is missing")
        }
        val actual = sha256(target)
        val verified = entry.sha256.isNotBlank() && actual.equals(entry.sha256, ignoreCase = true)
        if (entry.sha256.isNotBlank() && !verified) {
            target.delete()
            return ProotInstallResult.Failed("Checksum mismatch for ${entry.asset}")
        }
        if (!isElf(target)) {
            target.delete()
            return ProotInstallResult.Failed("${entry.asset} is not an ELF executable")
        }
        target.setExecutable(true, false)
        return ProotInstallResult.Installed(entry, verified)
    }

    fun installRootfs(abi: String, rootfsDir: File): RootfsInstallResult {
        val entry = loadManifest().rootfs[abi] ?: return RootfsInstallResult.Missing
        val archive = File(context.cacheDir, "openbot-rootfs-${shortAbi(abi)}.tar.gz")
        if (!copyAsset(entry.asset, archive)) {
            return RootfsInstallResult.Failed("Declared rootfs asset ${entry.asset} is missing")
        }
        try {
            val actual = sha256(archive)
            val verified = entry.sha256.isNotBlank() && actual.equals(entry.sha256, ignoreCase = true)
            if (entry.sha256.isNotBlank() && !verified) {
                return RootfsInstallResult.Failed("Rootfs checksum mismatch for ${entry.asset}")
            }
            val error = extractArchive(archive, rootfsDir)
            if (error != null) {
                return RootfsInstallResult.Failed(error)
            }
            val marker = File(rootfsDir, "etc/openbot-rootfs")
            if (!marker.isFile) {
                return RootfsInstallResult.Failed("Rootfs archive did not contain etc/openbot-rootfs")
            }
            return RootfsInstallResult.Installed(
                version = entry.version.ifBlank { "unversioned" },
                verified = verified
            )
        } finally {
            archive.delete()
        }
    }

    private fun extractArchive(archive: File, rootfsDir: File): String? {
        val tar = TAR_CANDIDATES.firstOrNull { File(it).exists() }
            ?: return "No tar binary available to extract the rootfs"
        if (!rootfsDir.exists() && !rootfsDir.mkdirs()) {
            return "Cannot create rootfs directory"
        }

        val listing = runProcess(listOf(tar, "-tzf", archive.absolutePath), 60, LISTING_MAX_CHARS)
            ?: return "Timed out while listing the rootfs archive"
        if (listing.exitCode != 0) {
            return "tar listing failed: ${listing.output.take(300)}"
        }
        val unsafeEntry = listing.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { name ->
                name.startsWith("/") ||
                    name == ".." ||
                    name.startsWith("../") ||
                    name.contains("/../")
            }
        if (unsafeEntry != null) {
            return "Rootfs archive contains an unsafe path: ${unsafeEntry.take(120)}"
        }

        // A safe entry name is not enough: a symlink entry can point outside the
        // rootfs and make a later, otherwise safe, entry write outside it.
        val links = runProcess(listOf(tar, "-tvzf", archive.absolutePath), 60, LISTING_MAX_CHARS)
            ?: return "Timed out while listing rootfs archive links"
        if (links.exitCode != 0) {
            return "tar link listing failed: ${links.output.take(300)}"
        }
        val rootPath = rootfsDir.toPath().normalize()
        val unsafeLink = links.output.lineSequence()
            .mapNotNull { line -> LINK_TARGET_REGEX.find(line.trim())?.groupValues }
            .firstOrNull { groups ->
                val linkPath = rootPath.resolve(groups[1].trim().substringAfterLast(' ').trim())
                unsafeLinkTarget(rootPath, linkPath, groups[2]) != null
            }
        if (unsafeLink != null) {
            return "Rootfs archive contains an unsafe link target: ${unsafeLink[2].take(120)}"
        }

        val extraction = runProcess(
            listOf(tar, "-xzf", archive.absolutePath, "-C", rootfsDir.absolutePath),
            180
        ) ?: return "Timed out while extracting the rootfs archive"
        if (extraction.exitCode != 0) {
            return "tar extraction failed: ${extraction.output.take(300)}"
        }
        val escaped = findEscapingSymlink(rootPath)
        if (escaped != null) {
            return "Rootfs extraction created an unsafe symlink: ${escaped.take(120)}"
        }
        return null
    }

    /** Rejects absolute targets and targets that resolve outside the rootfs. */
    private fun unsafeLinkTarget(root: Path, link: Path, rawTarget: String): String? {
        val target = rawTarget.trim()
        if (target.isEmpty()) return null
        val resolved = if (target.startsWith("/")) {
            root.resolve(target.removePrefix("/"))
        } else {
            (link.parent ?: root).resolve(target)
        }
        return if (resolved.normalize().startsWith(root.normalize())) null else target
    }

    private fun findEscapingSymlink(root: Path): String? {
        val found = arrayOfNulls<String>(1)
        Files.walkFileTree(root.normalize(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink && found[0] == null) {
                    val target = runCatching { Files.readSymbolicLink(file).toString() }.getOrNull()
                    if (target != null && unsafeLinkTarget(root, file, target) != null) {
                        found[0] = "$file -> $target"
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
        return found[0]
    }

    fun sha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to hash ${file.absolutePath}", e)
            null
        }
    }

    private fun copyAsset(name: String, target: File): Boolean {
        return try {
            target.parentFile?.mkdirs()
            context.assets.open(name).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.length() > 0L
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    private fun isElf(file: File): Boolean {
        return try {
            val header = ByteArray(ELF_MAGIC.size)
            file.inputStream().use { input ->
                if (input.read(header) != header.size) return false
            }
            header.contentEquals(ELF_MAGIC)
        } catch (e: Exception) {
            false
        }
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runProcess(
        command: List<String>,
        timeoutSeconds: Long,
        maxChars: Int = 4000
    ): ProcessResult? {
        val logFile = File.createTempFile("openbot-process-", ".log", context.cacheDir)
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                null
            } else {
                ProcessResult(process.exitValue(), logFile.readText().take(maxChars))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Process failed: $command", e)
            null
        } finally {
            logFile.delete()
        }
    }

    private fun shortAbi(abi: String): String = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> abi
    }

    private companion object {
        const val TAG = "SandboxAssets"
        const val LISTING_MAX_CHARS = 1_000_000
        val LINK_TARGET_REGEX = Regex("^(.*?)(?: -> | link to )(.+)$")
        val ELF_MAGIC = byteArrayOf(
            0x7f.toByte(),
            'E'.code.toByte(),
            'L'.code.toByte(),
            'F'.code.toByte()
        )
        val TAR_CANDIDATES = listOf("/system/bin/tar", "/bin/tar", "/usr/bin/tar")
    }
}
