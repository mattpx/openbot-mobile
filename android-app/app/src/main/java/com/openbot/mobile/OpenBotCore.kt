package com.openbot.mobile

import org.json.JSONArray
import org.json.JSONObject

data class PendingApprovalData(
    val id: String,
    val command: String,
    val reason: String,
    val riskDescription: String
)

data class ExecutionResultData(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

sealed class ActionOutcomeData {
    data class Executed(val result: ExecutionResultData) : ActionOutcomeData()
    data class NeedsApproval(val approval: PendingApprovalData) : ActionOutcomeData()
    data class Denied(val reason: String) : ActionOutcomeData()
    data class Error(val message: String) : ActionOutcomeData()
}

object OpenBotCore {
    private var nativeLoaded = false

    init {
        nativeLoaded = try {
            System.loadLibrary("openbot_core")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isNativeLoaded(): Boolean = nativeLoaded

    fun initEngine(prootPath: String, rootfsPath: String, workspacePath: String): Boolean {
        if (!nativeLoaded) return true // Mock mode fallback
        return try {
            nativeInitEngine(prootPath, rootfsPath, workspacePath)
        } catch (_: Throwable) {
            false
        }
    }

    fun requestExecute(command: String): ActionOutcomeData {
        if (!nativeLoaded) {
            return mockExecute(command)
        }
        return try {
            parseOutcomeJson(nativeRequestExecute(command))
        } catch (e: Throwable) {
            ActionOutcomeData.Error("Native request failed: ${e.message}")
        }
    }

    fun resolveApproval(approvalId: String, approved: Boolean): ActionOutcomeData {
        if (!nativeLoaded) {
            return if (approved) {
                ActionOutcomeData.Executed(ExecutionResultData(0, "Approved & executed (mock).", ""))
            } else {
                ActionOutcomeData.Denied("Rejected by reviewer.")
            }
        }
        return try {
            parseOutcomeJson(nativeResolveApproval(approvalId, approved))
        } catch (e: Throwable) {
            ActionOutcomeData.Error("Native resolve failed: ${e.message}")
        }
    }

    fun listPendingApprovals(): List<PendingApprovalData> {
        if (!nativeLoaded) return emptyList()
        return try {
            val raw = nativeListPendingApprovals()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        PendingApprovalData(
                            id = obj.optString("id"),
                            command = obj.optString("command"),
                            reason = obj.optString("reason"),
                            riskDescription = obj.optString("risk_description")
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun mockExecute(command: String): ActionOutcomeData {
        val trimmed = command.trim()
        if (trimmed.contains("rm -rf /") || trimmed.contains(":(){ :|:& };:")) {
            return ActionOutcomeData.Denied("Blocked by policy (mock).")
        }
        if (trimmed.contains("curl") || trimmed.contains("wget") || trimmed.contains("ssh")) {
            return ActionOutcomeData.NeedsApproval(
                PendingApprovalData(
                    id = "mock_${System.currentTimeMillis()}",
                    command = trimmed,
                    reason = "External network access requires approval",
                    riskDescription = "High"
                )
            )
        }
        if (trimmed.startsWith("rm ")) {
            return ActionOutcomeData.NeedsApproval(
                PendingApprovalData(
                    id = "mock_${System.currentTimeMillis()}",
                    command = trimmed,
                    reason = "File deletion requires approval",
                    riskDescription = "Medium"
                )
            )
        }
        return ActionOutcomeData.Executed(
            ExecutionResultData(0, "Mock execution: $trimmed", "")
        )
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
                        stderr = resObj.optString("stderr", "")
                    )
                )
            }
            if (obj.has("NeedsApproval")) {
                val apprObj = obj.getJSONObject("NeedsApproval")
                return ActionOutcomeData.NeedsApproval(
                    PendingApprovalData(
                        id = apprObj.optString("id"),
                        command = apprObj.optString("command"),
                        reason = apprObj.optString("reason"),
                        riskDescription = apprObj.optString("risk_description")
                    )
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

    private external fun nativeInitEngine(prootPath: String, rootfsPath: String, workspacePath: String): Boolean
    private external fun nativeRequestExecute(command: String): String
    private external fun nativeResolveApproval(approvalId: String, approved: Boolean): String
    private external fun nativeListPendingApprovals(): String
}
