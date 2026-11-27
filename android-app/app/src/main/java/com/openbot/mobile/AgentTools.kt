package com.openbot.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

sealed class ToolExecutionOutcome {
    data class Completed(
        val toolName: String,
        val commandLabel: String,
        val output: String,
        val auditDecision: String = "executed",
        val exitCode: Int? = null,
        val sandboxMode: String? = null
    ) : ToolExecutionOutcome()

    data class NeedsApproval(
        val command: String,
        val approval: PendingApprovalData
    ) : ToolExecutionOutcome()

    data class Denied(
        val command: String,
        val reason: String
    ) : ToolExecutionOutcome()

    data class Failed(
        val toolName: String,
        val commandLabel: String,
        val error: String
    ) : ToolExecutionOutcome()
}

object AgentToolSchemas {

    private data class ToolSpec(
        val name: String,
        val description: String,
        val properties: List<Triple<String, String, String>>,
        val required: List<String>
    )

    private val specs = listOf(
        ToolSpec(
            name = "shell",
            description = "Run a shell command inside the PRoot Alpine Linux sandbox (working directory /workspace). High-risk commands require human approval.",
            properties = listOf(
                Triple("command", "string", "The POSIX shell command to execute.")
            ),
            required = listOf("command")
        ),
        ToolSpec(
            name = "read_file",
            description = "Read a UTF-8 text file inside /workspace (up to 64 KB).",
            properties = listOf(
                Triple("path", "string", "Relative path inside /workspace, e.g. notes.txt or src/app.py.")
            ),
            required = listOf("path")
        ),
        ToolSpec(
            name = "write_file",
            description = "Create or overwrite a UTF-8 text file inside /workspace.",
            properties = listOf(
                Triple("path", "string", "Relative path inside /workspace."),
                Triple("content", "string", "Full file content to write.")
            ),
            required = listOf("path", "content")
        ),
        ToolSpec(
            name = "list_files",
            description = "List files and directories inside /workspace.",
            properties = listOf(
                Triple("path", "string", "Optional relative subdirectory inside /workspace.")
            ),
            required = emptyList()
        ),
        ToolSpec(
            name = "web_fetch",
            description = "Fetch a web page or API endpoint over HTTP/HTTPS and return extracted text.",
            properties = listOf(
                Triple("url", "string", "Full http:// or https:// URL to fetch.")
            ),
            required = listOf("url")
        ),
        ToolSpec(
            name = "web_browse",
            description = "Render a web page in Android WebView, extract visible text, and optionally capture a PNG screenshot into /workspace/screenshots/.",
            properties = listOf(
                Triple("url", "string", "Full http:// or https:// URL to open."),
                Triple("screenshot", "boolean", "Whether to save a PNG screenshot to /workspace/screenshots/.")
            ),
            required = listOf("url")
        )
    )

    private fun parametersSchema(spec: ToolSpec): JSONObject {
        val props = JSONObject()
        for ((name, type, desc) in spec.properties) {
            props.put(
                name,
                JSONObject().apply {
                    put("type", type)
                    put("description", desc)
                }
            )
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (spec.required.isNotEmpty()) {
                put("required", JSONArray(spec.required))
            }
        }
    }

    fun openAiToolsJson(): JSONArray {
        val arr = JSONArray()
        for (spec in specs) {
            arr.put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", parametersSchema(spec))
                        }
                    )
                }
            )
        }
        return arr
    }

    fun anthropicToolsJson(): JSONArray {
        val arr = JSONArray()
        for (spec in specs) {
            arr.put(
                JSONObject().apply {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("input_schema", parametersSchema(spec))
                }
            )
        }
        return arr
    }

    fun geminiToolsJson(): JSONArray {
        val declarations = JSONArray()
        for (spec in specs) {
            declarations.put(
                JSONObject().apply {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("parameters", parametersSchema(spec))
                }
            )
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
    }
}

class AgentToolExecutor(
    private val context: Context,
    private val workspaceDir: File,
    private val runShell: suspend (String) -> ActionOutcomeData
) {

    suspend fun execute(call: ToolCallRequest): ToolExecutionOutcome {
        return when (call.name) {
            "shell" -> {
                val raw = call.arguments.optString("command", "")
                val cmd = sanitizeCommand(raw)
                    ?: return ToolExecutionOutcome.Failed("shell", "shell", "Invalid or empty command.")
                when (val outcome = runShell(cmd)) {
                    is ActionOutcomeData.Executed -> {
                        val r = outcome.result
                        val formatted = buildString {
                            append("exit: ${r.exitCode}")
                            if (r.timedOut) append(" (timed out)")
                            if (r.stdout.isNotBlank()) append("\nstdout:\n${r.stdout.trimEnd()}")
                            if (r.stderr.isNotBlank()) append("\nstderr:\n${r.stderr.trimEnd()}")
                        }
                        ToolExecutionOutcome.Completed(
                            toolName = "shell",
                            commandLabel = cmd,
                            output = formatted,
                            exitCode = r.exitCode,
                            sandboxMode = r.sandboxMode
                        )
                    }
                    is ActionOutcomeData.NeedsApproval -> ToolExecutionOutcome.NeedsApproval(cmd, outcome.approval)
                    is ActionOutcomeData.Denied -> ToolExecutionOutcome.Denied(cmd, outcome.reason)
                    is ActionOutcomeData.Error -> ToolExecutionOutcome.Failed("shell", cmd, outcome.message)
                }
            }
            "read_file" -> executeReadFile(call.arguments.optString("path", ""))
            "write_file" -> executeWriteFile(
                call.arguments.optString("path", ""),
                call.arguments.optString("content", "")
            )
            "list_files" -> executeListFiles(call.arguments.optString("path", ""))
            "web_fetch" -> executeWebFetch(call.arguments.optString("url", ""))
            "web_browse" -> executeWebBrowse(
                call.arguments.optString("url", ""),
                call.arguments.optBoolean("screenshot", false)
            )
            else -> ToolExecutionOutcome.Failed(call.name, call.name, "Unknown tool: ${call.name}")
        }
    }

    private suspend fun executeReadFile(relPath: String): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        val label = "read_file $relPath"
        val file = resolveWorkspacePath(relPath)
            ?: return@withContext ToolExecutionOutcome.Denied(label, "Path escapes /workspace")
        if (!file.isFile) {
            return@withContext ToolExecutionOutcome.Failed("read_file", label, "File not found: $relPath")
        }
        try {
            val bytes = file.inputStream().use { input ->
                val buf = ByteArray(MAX_READ_BYTES)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
            val text = bytes.toString(Charsets.UTF_8)
            val suffix = if (file.length() > MAX_READ_BYTES) "\n...[truncated at 64 KB]" else ""
            ToolExecutionOutcome.Completed("read_file", label, text + suffix)
        } catch (e: Exception) {
            ToolExecutionOutcome.Failed("read_file", label, e.message ?: "Read failed")
        }
    }

    private suspend fun executeWriteFile(relPath: String, content: String): ToolExecutionOutcome =
        withContext(Dispatchers.IO) {
            val label = "write_file $relPath"
            val file = resolveWorkspacePath(relPath)
                ?: return@withContext ToolExecutionOutcome.Denied(label, "Path escapes /workspace")
            try {
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                ToolExecutionOutcome.Completed(
                    "write_file",
                    label,
                    "Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to /workspace/${relPath.trimStart('/')}"
                )
            } catch (e: Exception) {
                ToolExecutionOutcome.Failed("write_file", label, e.message ?: "Write failed")
            }
        }

    private suspend fun executeListFiles(relPath: String): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        val label = if (relPath.isBlank()) "list_files /workspace" else "list_files $relPath"
        val dir = if (relPath.isBlank()) workspaceDir else resolveWorkspacePath(relPath)
            ?: return@withContext ToolExecutionOutcome.Denied(label, "Path escapes /workspace")
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory) {
            return@withContext ToolExecutionOutcome.Failed("list_files", label, "Not a directory: $relPath")
        }
        val entries = dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
            .orEmpty()
        val listing = if (entries.isEmpty()) {
            "(empty directory)"
        } else {
            entries.joinToString("\n") { f ->
                if (f.isDirectory) "${f.name}/" else "${f.name} (${f.length()} B)"
            }
        }
        ToolExecutionOutcome.Completed("list_files", label, listing)
    }

    private suspend fun executeWebFetch(url: String): ToolExecutionOutcome {
        val label = "web_fetch $url"
        return try {
            val page = WebBrowserTool.fetchText(url)
            val body = buildString {
                if (page.title.isNotBlank()) append("Title: ${page.title}\n")
                append("URL: ${page.url}\n\n")
                append(page.text)
            }
            ToolExecutionOutcome.Completed("web_fetch", label, body)
        } catch (e: Exception) {
            ToolExecutionOutcome.Failed("web_fetch", label, e.message ?: "Fetch failed")
        }
    }

    private suspend fun executeWebBrowse(url: String, screenshot: Boolean): ToolExecutionOutcome {
        val label = "web_browse $url"
        return try {
            val page = WebBrowserTool.browseWithWebView(context, url, screenshot, workspaceDir)
            val body = buildString {
                if (page.title.isNotBlank()) append("Title: ${page.title}\n")
                append("URL: ${page.url}\n")
                page.screenshotPath?.let { append("Screenshot: $it\n") }
                append("\n")
                append(page.text)
            }
            ToolExecutionOutcome.Completed("web_browse", label, body)
        } catch (e: Exception) {
            ToolExecutionOutcome.Failed("web_browse", label, e.message ?: "Browse failed")
        }
    }

    private fun resolveWorkspacePath(raw: String): File? {
        val cleaned = raw.trim().removePrefix("/workspace/").removePrefix("/workspace").trimStart('/')
        if (cleaned.isEmpty() || cleaned.contains('\u0000')) return null
        val rootCanonical = runCatching { workspaceDir.canonicalFile }.getOrElse { workspaceDir.absoluteFile }
        val candidate = File(rootCanonical, cleaned)
        val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return if (resolved.path == rootCanonical.path ||
            resolved.path.startsWith(rootCanonical.path + File.separator)
        ) {
            resolved
        } else {
            null
        }
    }

    private companion object {
        const val MAX_READ_BYTES = 64 * 1024
    }
}
