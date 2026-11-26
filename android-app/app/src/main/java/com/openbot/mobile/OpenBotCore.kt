package com.openbot.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PendingApprovalData(
    val id: String,
    val command: String,
    val reason: String,
    val riskDescription: String
)

data class ExecutionResultData(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val durationMs: Long = 0L,
    val sandboxMode: String? = null
)

sealed class ActionOutcomeData {
    data class Executed(val result: ExecutionResultData) : ActionOutcomeData()
    data class NeedsApproval(val approval: PendingApprovalData) : ActionOutcomeData()
    data class Denied(val reason: String) : ActionOutcomeData()
    data class Error(val message: String) : ActionOutcomeData()
}

enum class CoreMode {
    /** The Rust JNI library loaded successfully. */
    NATIVE,

    /** Debug-only fallback used when the native library is unavailable. */
    DEBUG_MOCK,

    /** Release build without a working native library: no command execution. */
    UNAVAILABLE
}

object OpenBotCore {
    private const val MOCK_TIMEOUT_SECONDS = 30L
    private const val MAX_MOCK_OUTPUT = 256 * 1024

    private val mockPending = linkedMapOf<String, PendingApprovalData>()

    val mode: CoreMode = try {
        System.loadLibrary("openbot_core")
        CoreMode.NATIVE
    } catch (_: Throwable) {
        if (BuildConfig.DEBUG) CoreMode.DEBUG_MOCK else CoreMode.UNAVAILABLE
    }

    fun isNativeLoaded(): Boolean = mode == CoreMode.NATIVE

    fun coreMode(): CoreMode = mode

    fun initEngine(prootPath: String, rootfsPath: String, workspacePath: String): Boolean {
        return when (mode) {
            CoreMode.NATIVE -> try {
                nativeInitEngine(prootPath, rootfsPath, workspacePath)
            } catch (_: Throwable) {
                false
            }
            CoreMode.DEBUG_MOCK -> true
            CoreMode.UNAVAILABLE -> false
        }
    }

    fun sandboxMode(): String {
        return when (mode) {
            CoreMode.NATIVE -> try {
                nativeSandboxMode().ifBlank { "unknown" }
            } catch (_: Throwable) {
                "unknown"
            }
            CoreMode.DEBUG_MOCK -> "mock"
            CoreMode.UNAVAILABLE -> "unavailable"
        }
    }

    fun requestExecute(command: String): ActionOutcomeData {
        return when (mode) {
            CoreMode.NATIVE -> try {
                parseOutcomeJson(nativeRequestExecute(command))
            } catch (e: Throwable) {
                ActionOutcomeData.Error("Native request failed: ${e.message}")
            }
            CoreMode.DEBUG_MOCK -> mockExecute(command)
            CoreMode.UNAVAILABLE -> ActionOutcomeData.Error(
                "The native OpenBot core is unavailable in this build, so commands cannot run."
            )
        }
    }

    fun resolveApproval(approvalId: String, approved: Boolean): ActionOutcomeData {
        return when (mode) {
            CoreMode.NATIVE -> try {
                parseOutcomeJson(nativeResolveApproval(approvalId, approved))
            } catch (e: Throwable) {
                ActionOutcomeData.Error("Native resolve failed: ${e.message}")
            }
            CoreMode.DEBUG_MOCK -> {
                val pending = mockPending.remove(approvalId)
                if (pending == null) {
                    ActionOutcomeData.Error("Approval not found: $approvalId")
                } else if (approved) {
                    mockRunCommand(pending.command)
                } else {
                    ActionOutcomeData.Denied("Rejected by reviewer.")
                }
            }
            CoreMode.UNAVAILABLE -> ActionOutcomeData.Error(
                "The native OpenBot core is unavailable in this build."
            )
        }
    }

    fun listPendingApprovals(): List<PendingApprovalData> {
        return when (mode) {
            CoreMode.NATIVE -> try {
                parsePendingApprovals(nativeListPendingApprovals())
            } catch (_: Throwable) {
                emptyList()
            }
            CoreMode.DEBUG_MOCK -> mockPending.values.toList()
            CoreMode.UNAVAILABLE -> emptyList()
        }
    }

    private fun parsePendingApprovals(raw: String): List<PendingApprovalData> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            if (obj.has("error")) return emptyList()
            return listOf(parseApproval(obj))
        }
        val arr = JSONArray(trimmed)
        return buildList {
            for (i in 0 until arr.length()) {
                add(parseApproval(arr.getJSONObject(i)))
            }
        }
    }

    private fun parseApproval(obj: JSONObject): PendingApprovalData {
        return PendingApprovalData(
            id = obj.optString("id"),
            command = obj.optString("command"),
            reason = obj.optString("reason"),
            riskDescription = obj.optString("risk_description")
        )
    }

    private fun mockExecute(command: String): ActionOutcomeData {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return ActionOutcomeData.Error("Empty command")
        }
        if (trimmed.contains("rm -rf /") ||
            trimmed.contains(":(){ :|:& };:") ||
            trimmed.contains("mkfs")
        ) {
            return ActionOutcomeData.Denied("Blocked by policy (debug mock).")
        }
        if (containsCommand(trimmed, "curl") ||
            containsCommand(trimmed, "wget") ||
            containsCommand(trimmed, "ssh") ||
            containsCommand(trimmed, "nc")
        ) {
            return mockNeedsApproval(trimmed, "Network command requires approval", "High")
        }
        if (containsCommand(trimmed, "rm") ||
            containsCommand(trimmed, "mv") ||
            trimmed.contains("chmod 777")
        ) {
            return mockNeedsApproval(trimmed, "Potentially destructive command requires approval", "Medium")
        }
        return mockRunCommand(trimmed)
    }

    private fun containsCommand(command: String, name: String): Boolean {
        val pattern = Regex("(^|[\\s;|&(])" + Regex.escape(name) + "($|[\\s;|&)])")
        return pattern.containsMatchIn(command.lowercase())
    }

    private fun mockNeedsApproval(command: String, reason: String, risk: String): ActionOutcomeData {
        val id = "mock_${System.currentTimeMillis()}_${mockPending.size}"
        val approval = PendingApprovalData(id, command, reason, risk)
        mockPending[id] = approval
        return ActionOutcomeData.NeedsApproval(approval)
    }

    private fun mockRunCommand(command: String): ActionOutcomeData {
        // Debug-only fallback. It runs on the host shell with no isolation, and
        // every result is labelled sandbox_mode=mock so the UI can warn.
        return try {
            val started = System.currentTimeMillis()
            val process = ProcessBuilder("/system/bin/sh", "-c", command).start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val stdoutReader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        stdout.append(reader.readText().take(MAX_MOCK_OUTPUT))
                    }
                }
            }
            val stderrReader = Thread {
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        stderr.append(reader.readText().take(MAX_MOCK_OUTPUT))
                    }
                }
            }
            stdoutReader.start()
            stderrReader.start()

            val finished = process.waitFor(MOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            stdoutReader.join(2000)
            stderrReader.join(2000)

            val exitCode = if (finished) {
                runCatching { process.exitValue() }.getOrDefault(-1)
            } else {
                -1
            }
            val errorText = buildString {
                append(stderr.toString())
                if (!finished) append("\n[mock] command timed out after ${MOCK_TIMEOUT_SECONDS}s\n")
            }
            ActionOutcomeData.Executed(
                ExecutionResultData(
                    exitCode = exitCode,
                    stdout = stdout.toString(),
                    stderr = errorText,
                    timedOut = !finished,
                    durationMs = System.currentTimeMillis() - started,
                    sandboxMode = "mock"
                )
            )
        } catch (e: Exception) {
            ActionOutcomeData.Error("Debug mock execution failed: ${e.message}")
        }
    }

    private fun parseOutcomeJson(jsonStr: String): ActionOutcomeData {
        return try {
            val obj = JSONObject(jsonStr)
            if (obj.has("error")) {
                return ActionOutcomeData.Error(obj.optString("error", "Unknown error"))
            }
            if (obj.has("Executed")) {
                val resObj = obj.getJSONObject("Executed")
                return ActionOutcomeData.Executed(
                    ExecutionResultData(
                        exitCode = resObj.optInt("exit_code", -1),
                        stdout = resObj.optString("stdout", ""),
                        stderr = resObj.optString("stderr", ""),
                        timedOut = resObj.optBoolean("timed_out", false),
                        stdoutTruncated = resObj.optBoolean("stdout_truncated", false),
                        stderrTruncated = resObj.optBoolean("stderr_truncated", false),
                        durationMs = resObj.optLong("duration_ms", 0L),
                        sandboxMode = resObj.optString("sandbox_mode", "").ifBlank { null }
                    )
                )
            }
            if (obj.has("NeedsApproval")) {
                return ActionOutcomeData.NeedsApproval(
                    parseApproval(obj.getJSONObject("NeedsApproval"))
                )
            }
            if (obj.has("Denied")) {
                val deniedObj = obj.optJSONObject("Denied")
                val reason = deniedObj?.optString("reason")
                    ?: obj.optString("Denied", "Denied by policy")
                return ActionOutcomeData.Denied(reason)
            }
            ActionOutcomeData.Error("Unknown response: $jsonStr")
        } catch (e: Exception) {
            ActionOutcomeData.Error("JSON parse error: ${e.message}")
        }
    }

    private external fun nativeInitEngine(
        prootPath: String,
        rootfsPath: String,
        workspacePath: String
    ): Boolean

    private external fun nativeRequestExecute(command: String): String
    private external fun nativeResolveApproval(approvalId: String, approved: Boolean): String
    private external fun nativeListPendingApprovals(): String
    private external fun nativeSandboxMode(): String
}
