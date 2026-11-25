package com.openbot.mobile

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
}

/**
 * OpenBotCore bridges to the embedded Rust engine.
 */
object OpenBotCore {
    init {
        try {
            System.loadLibrary("openbot_core")
        } catch (e: UnsatisfiedLinkError) {
            // Handled during dry runs or mock setups
        }
    }

    // Native signatures when linked to compiled Rust core
    external fun initEngine(prootPath: String, rootfsPath: String, workspacePath: String): Boolean
    external fun requestExecute(command: String): String
    external fun resolveApproval(approvalId: String, approved: Boolean): String
}
