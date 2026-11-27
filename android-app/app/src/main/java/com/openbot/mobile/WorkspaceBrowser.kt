package com.openbot.mobile

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_PREVIEW_BYTES = 64 * 1024

data class WorkspaceItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

@Composable
fun WorkspaceTabContent(
    workspaceRoot: File,
    isBusy: Boolean,
    onResetWorkspace: () -> Unit,
    onReinstallRootfs: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    var currentDir by remember(workspaceRoot) { mutableStateOf(workspaceRoot) }
    var itemsList by remember { mutableStateOf<List<WorkspaceItem>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var confirmResetWorkspace by remember { mutableStateOf(false) }
    var confirmReinstallRootfs by remember { mutableStateOf(false) }

    val rootCanonical = remember(workspaceRoot) {
        runCatching { workspaceRoot.canonicalFile }.getOrElse { workspaceRoot.absoluteFile }
    }

    LaunchedEffect(currentDir, refreshKey) {
        itemsList = withContext(Dispatchers.IO) {
            if (!currentDir.exists()) currentDir.mkdirs()
            val children = currentDir.listFiles()?.toList().orEmpty()
            children
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
                .map { f ->
                    WorkspaceItem(
                        file = f,
                        name = f.name,
                        isDirectory = f.isDirectory,
                        sizeBytes = if (f.isFile) f.length() else 0L,
                        lastModified = f.lastModified()
                    )
                }
        }
    }

    LaunchedEffect(previewFile) {
        val target = previewFile ?: run {
            previewText = null
            return@LaunchedEffect
        }
        previewText = withContext(Dispatchers.IO) { readFilePreview(target) }
    }

    previewFile?.let { file ->
        AlertDialog(
            onDismissRequest = { previewFile = null },
            title = {
                Text(
                    text = file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${formatSize(file.length())} • ${timeFmt.format(Date(file.lastModified()))}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        Text(
                            text = previewText ?: "Loading...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                file.delete()
                                withContext(Dispatchers.Main) {
                                    previewFile = null
                                    refreshKey++
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                    Button(onClick = { shareWorkspaceFile(context, file) }) {
                        Text("Share")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { previewFile = null }) { Text("Close") }
            }
        )
    }

    if (confirmResetWorkspace) {
        AlertDialog(
            onDismissRequest = { confirmResetWorkspace = false },
            title = { Text("Reset Workspace?") },
            text = { Text("This deletes all files in /workspace. Chat threads and settings are kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResetWorkspace = false
                        currentDir = workspaceRoot
                        onResetWorkspace()
                        refreshKey++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetWorkspace = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmReinstallRootfs) {
        AlertDialog(
            onDismissRequest = { confirmReinstallRootfs = false },
            title = { Text("Reinstall Linux Rootfs?") },
            text = { Text("This wipes and re-extracts the bundled Alpine rootfs. Files in /workspace are kept.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmReinstallRootfs = false
                        onReinstallRootfs()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Reinstall") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReinstallRootfs = false }) { Text("Cancel") }
            }
        )
    }

    val relPath = remember(currentDir, rootCanonical) {
        val cur = runCatching { currentDir.canonicalFile }.getOrElse { currentDir.absoluteFile }
        val rel = cur.path.removePrefix(rootCanonical.path).trimStart('/')
        if (rel.isEmpty()) "/workspace" else "/workspace/$rel"
    }
    val canGoUp = remember(currentDir, rootCanonical) {
        val cur = runCatching { currentDir.canonicalFile }.getOrElse { currentDir.absoluteFile }
        cur.path != rootCanonical.path && cur.path.startsWith(rootCanonical.path)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = relPath,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (canGoUp) {
                TextButton(onClick = {
                    val parent = currentDir.parentFile
                    if (parent != null && isInsideRoot(parent, rootCanonical)) {
                        currentDir = parent
                    }
                }) { Text("↑ Up", fontSize = 12.sp) }
            }
            TextButton(onClick = { refreshKey++ }) { Text("Refresh", fontSize = 12.sp) }
        }

        if (itemsList.isEmpty()) {
            Text(
                "Workspace directory is empty.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(itemsList, key = { it.file.absolutePath }) { item ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item.isDirectory) {
                                    if (isInsideRoot(item.file, rootCanonical)) {
                                        currentDir = item.file
                                    }
                                } else {
                                    previewFile = item.file
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (item.isDirectory) "📁 ${item.name}/" else "📄 ${item.name}",
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (item.isDirectory) {
                                        timeFmt.format(Date(item.lastModified))
                                    } else {
                                        "${formatSize(item.sizeBytes)} • ${timeFmt.format(Date(item.lastModified))}"
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { confirmResetWorkspace = true },
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Reset Workspace", fontSize = 11.sp) }
            OutlinedButton(
                onClick = { confirmReinstallRootfs = true },
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Reinstall Rootfs", fontSize = 11.sp) }
        }
    }
}

private fun isInsideRoot(target: File, rootCanonical: File): Boolean {
    val resolved = runCatching { target.canonicalFile }.getOrNull() ?: return false
    return resolved.path == rootCanonical.path || resolved.path.startsWith(rootCanonical.path + File.separator)
}

private fun readFilePreview(file: File): String {
    if (!file.isFile) return "(Not a regular file)"
    return try {
        val bytes = file.inputStream().use { input ->
            val buf = ByteArray(MAX_PREVIEW_BYTES)
            val read = input.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
        val looksBinary = bytes.any { b -> b == 0.toByte() }
        if (looksBinary) {
            "(Binary file — ${formatSize(file.length())}. Use Share to export.)"
        } else {
            val text = bytes.toString(Charsets.UTF_8)
            if (file.length() > MAX_PREVIEW_BYTES) "$text\n\n...[truncated at 64 KB]" else text
        }
    } catch (e: Exception) {
        "Unable to read file: ${e.message}"
    }
}

private fun shareWorkspaceFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val ext = file.extension.lowercase(Locale.US)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
