package com.openbot.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openbot.mobile.service.AgentForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val id: String,
    val sender: String,
    val text: String,
    val isUser: Boolean,
    val pendingApproval: PendingApprovalData? = null
)

private const val MAX_AGENT_STEPS = 4

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        AgentForegroundService.start(this)

        val rootfsManager = RootfsManager(applicationContext)
        val settingsStore = AgentSettingsStore(applicationContext)
        val threadStore = ThreadStore(applicationContext)
        val governanceStore = GovernanceStore(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpenBotApp(
                        rootfsManager = rootfsManager,
                        settingsStore = settingsStore,
                        threadStore = threadStore,
                        governanceStore = governanceStore
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenBotApp(
    rootfsManager: RootfsManager,
    settingsStore: AgentSettingsStore,
    threadStore: ThreadStore,
    governanceStore: GovernanceStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var initRequested by remember { mutableStateOf(false) }
    var agentMode by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showThreadsDialog by remember { mutableStateOf(false) }
    var llmConfig by remember { mutableStateOf(settingsStore.load()) }
    var customPolicies by remember { mutableStateOf(governanceStore.loadPolicies()) }
    var botProfiles by remember { mutableStateOf(governanceStore.loadProfiles()) }
    var activeProfileId by remember { mutableStateOf(governanceStore.activeProfileId()) }
    var savedSkills by remember { mutableStateOf(governanceStore.loadSkills()) }
    val sandboxStatus by rootfsManager.status.collectAsState()

    var threads by remember { mutableStateOf<List<ThreadSummary>>(emptyList()) }
    var activeThreadId by remember { mutableStateOf<String?>(null) }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    val agentHistory = remember { mutableStateListOf<LlmChatMessage>() }
    // A pending approval bubble can be clicked twice before recomposition removes it.
    val resolvingApprovals = remember { mutableSetOf<String>() }

    fun welcomeMessage(threadId: String) = ChatMessage(
        id = "welcome-$threadId",
        sender = "OpenBot",
        text = "👋 Hello! I am your OpenBot AI coworker running on Android with PRoot sandboxing.\n\n" +
            "• Direct mode: type shell commands to run in sandbox.\n" +
            "• Agent mode: configure LLM in Settings, then chat naturally; I'll propose commands in ```bash``` blocks.",
        isUser = false
    )

    fun persistMessage(msg: ChatMessage) {
        val tid = activeThreadId ?: return
        scope.launch(Dispatchers.IO) {
            threadStore.appendMessage(tid, msg)
            val updated = threadStore.listThreads()
            withContext(Dispatchers.Main) { threads = updated }
        }
    }

    fun addMessage(msg: ChatMessage, persist: Boolean = true) {
        messages.add(msg)
        if (persist) persistMessage(msg)
    }

    fun addAgentHistory(msg: LlmChatMessage) {
        agentHistory.add(msg)
        val tid = activeThreadId ?: return
        scope.launch(Dispatchers.IO) {
            threadStore.appendAgentHistory(tid, msg)
        }
    }

    fun recordAudit(
        command: String,
        decision: String,
        risk: String? = null,
        exitCode: Int? = null,
        sandboxMode: String? = null,
        summary: String? = null
    ) {
        val tid = activeThreadId ?: return
        scope.launch(Dispatchers.IO) {
            threadStore.recordAudit(
                threadId = tid,
                command = command,
                decision = decision,
                risk = risk,
                exitCode = exitCode,
                sandboxMode = sandboxMode,
                summary = summary
            )
        }
    }

    suspend fun loadThread(threadId: String) {
        val (loadedMessages, loadedHistory, allThreads) = withContext(Dispatchers.IO) {
            Triple(
                threadStore.loadMessages(threadId),
                threadStore.loadAgentHistory(threadId),
                threadStore.listThreads()
            )
        }
        activeThreadId = threadId
        threads = allThreads
        messages.clear()
        if (loadedMessages.isEmpty()) {
            messages.add(welcomeMessage(threadId))
        } else {
            messages.addAll(loadedMessages)
        }
        agentHistory.clear()
        agentHistory.addAll(loadedHistory)
    }

    LaunchedEffect(Unit) {
        val initial = withContext(Dispatchers.IO) { threadStore.ensureInitialThread() }
        loadThread(initial.id)
    }

    fun ensureAgentSystemPrompt() {
        if (agentHistory.none { it.role == "system" }) {
            agentHistory.add(0, LlmChatMessage("system", llmConfig.systemPrompt))
        } else {
            val idx = agentHistory.indexOfFirst { it.role == "system" }
            if (idx >= 0) agentHistory[idx] = LlmChatMessage("system", llmConfig.systemPrompt)
        }
    }

    LaunchedEffect(sandboxStatus) {
        if (!initRequested && sandboxStatus is SandboxStatus.Uninitialized) {
            initRequested = true
            val ok = rootfsManager.initializeSandbox()
            if (ok && customPolicies.isNotEmpty()) {
                withContext(Dispatchers.IO) { OpenBotCore.setCustomPolicyRules(customPolicies) }
            }
            val statusMsg = if (ok) {
                val s = rootfsManager.status.value as? SandboxStatus.Ready
                val mode = s?.mode ?: OpenBotCore.sandboxMode()
                buildString {
                    append("Sandbox ready (mode=$mode, rootfs=${s?.rootfsKind ?: "unknown"})")
                    if (!s?.prootVersion.isNullOrBlank()) append(", proot=${s?.prootVersion}")
                    append(").")
                    if (!s?.warning.isNullOrBlank()) {
                        append("\n\n⚠️ ${s?.warning}")
                    }
                    append("\n\nTry `ls`, `pwd`, or enable Agent mode after configuring LLM settings.")
                }
            } else {
                val err = (rootfsManager.status.value as? SandboxStatus.Failed)?.error ?: "unknown error"
                "Sandbox initialization failed: $err"
            }
            addMessage(
                ChatMessage("init-${System.currentTimeMillis()}", "OpenBot", statusMsg, false),
                persist = false
            )
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun appendOutcome(command: String, outcome: ActionOutcomeData): String {
        val text = when (outcome) {
            is ActionOutcomeData.Executed -> {
                val result = outcome.result
                buildString {
                    append("`$command`\nexit: ${result.exitCode}")
                    if (result.timedOut) append(" (timed out)")
                    if (result.stdout.isNotBlank()) {
                        append("\n\nstdout:\n${result.stdout.trimEnd()}")
                        if (result.stdoutTruncated) append("\n...[stdout truncated]")
                    }
                    if (result.stderr.isNotBlank()) {
                        append("\n\nstderr:\n${result.stderr.trimEnd()}")
                        if (result.stderrTruncated) append("\n...[stderr truncated]")
                    }
                    result.sandboxMode?.let { if (it == "mock") append("\n\n⚠️ Ran in mock mode (not sandbox-isolated).") }
                }
            }
            is ActionOutcomeData.NeedsApproval -> "Approval required for `$command`: ${outcome.approval.reason}"
            is ActionOutcomeData.Denied -> "❌ Denied: ${outcome.reason}\nCommand: `$command`"
            is ActionOutcomeData.Error -> "⚠️ Error: ${outcome.message}\nCommand: `$command`"
        }
        when (outcome) {
            is ActionOutcomeData.Executed -> recordAudit(
                command = command,
                decision = "executed",
                exitCode = outcome.result.exitCode,
                sandboxMode = outcome.result.sandboxMode,
                summary = text
            )
            is ActionOutcomeData.NeedsApproval -> recordAudit(
                command = command,
                decision = "approval_requested",
                risk = outcome.approval.riskDescription,
                summary = outcome.approval.reason
            )
            is ActionOutcomeData.Denied -> recordAudit(
                command = command,
                decision = "denied",
                summary = outcome.reason
            )
            is ActionOutcomeData.Error -> recordAudit(
                command = command,
                decision = "error",
                summary = outcome.message
            )
        }
        if (outcome is ActionOutcomeData.NeedsApproval) {
            addMessage(
                ChatMessage(
                    id = "appr-${System.currentTimeMillis()}",
                    sender = "OpenBot",
                    text = "I need approval before running this command.",
                    isUser = false,
                    pendingApproval = outcome.approval
                )
            )
        } else {
            addMessage(ChatMessage("exec-${System.currentTimeMillis()}", "OpenBot", text, false))
        }
        return text
    }

    suspend fun ensureSandboxReady(): Boolean = withContext(Dispatchers.IO) {
        when (rootfsManager.status.value) {
            is SandboxStatus.Ready -> true
            SandboxStatus.Uninitialized -> rootfsManager.initializeSandbox()
            is SandboxStatus.SettingUp -> {
                var attempts = 0
                while (rootfsManager.status.value is SandboxStatus.SettingUp && attempts < 80) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }
                rootfsManager.status.value is SandboxStatus.Ready
            }
            is SandboxStatus.Failed -> rootfsManager.initializeSandbox()
        }
    }

    suspend fun runSandboxCommand(command: String): ActionOutcomeData = withContext(Dispatchers.IO) {
        if (!ensureSandboxReady()) {
            ActionOutcomeData.Error("Sandbox is not ready yet.")
        } else {
            OpenBotCore.requestExecute(command)
        }
    }

    val toolExecutor = remember(context, rootfsManager) {
        AgentToolExecutor(context, rootfsManager.workspaceDirectory) { cmd -> runSandboxCommand(cmd) }
    }

    /** Shared bounded agent loop with live token streaming and structured tool-calling. */
    suspend fun runAgentLoop(idPrefix: String) {
        var steps = 0
        var hitLimit = true
        while (steps < MAX_AGENT_STEPS) {
            val streamMsgId = "$idPrefix-${System.currentTimeMillis()}-$steps"
            messages.add(ChatMessage(streamMsgId, "OpenBot", "…", false))
            val streamed = StringBuilder()

            val response = withContext(Dispatchers.IO) {
                LlmClient(llmConfig).chatWithTools(
                    messages = agentHistory.toList(),
                    enableTools = llmConfig.provider != LlmProvider.AG_UI
                ) { token ->
                    streamed.append(token)
                    val snapshot = streamed.toString()
                    scope.launch(Dispatchers.Main) {
                        val idx = messages.indexOfFirst { it.id == streamMsgId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(text = snapshot)
                        }
                    }
                }
            }

            if (response.toolCalls.isNotEmpty()) {
                val call = response.toolCalls.first()
                val displayText = response.text.trim().ifBlank { "Calling tool `${call.name}`…" }
                val idx = messages.indexOfFirst { it.id == streamMsgId }
                if (idx != -1) {
                    val finalized = messages[idx].copy(text = displayText)
                    messages[idx] = finalized
                    persistMessage(finalized)
                }
                addAgentHistory(
                    LlmChatMessage(
                        "assistant",
                        if (response.text.isNotBlank()) response.text else "Tool call: ${call.name}(${call.arguments})"
                    )
                )

                val toolOutcome = toolExecutor.execute(call)
                val feedback: String
                var needsHumanApproval = false
                when (toolOutcome) {
                    is ToolExecutionOutcome.Completed -> {
                        recordAudit(
                            command = toolOutcome.commandLabel,
                            decision = toolOutcome.auditDecision,
                            exitCode = toolOutcome.exitCode,
                            sandboxMode = toolOutcome.sandboxMode,
                            summary = toolOutcome.output
                        )
                        val body = "🔧 `${toolOutcome.toolName}` (${toolOutcome.commandLabel})\n\n${toolOutcome.output}"
                        addMessage(ChatMessage("tool-${System.currentTimeMillis()}-$steps", "OpenBot", body, false))
                        feedback = "Tool `${toolOutcome.toolName}` succeeded:\n${toolOutcome.output}"
                    }
                    is ToolExecutionOutcome.NeedsApproval -> {
                        needsHumanApproval = true
                        recordAudit(
                            command = toolOutcome.command,
                            decision = "approval_requested",
                            risk = toolOutcome.approval.riskDescription,
                            summary = toolOutcome.approval.reason
                        )
                        addMessage(
                            ChatMessage(
                                id = "appr-${System.currentTimeMillis()}-$steps",
                                sender = "OpenBot",
                                text = "I need approval before running this command.",
                                isUser = false,
                                pendingApproval = toolOutcome.approval
                            )
                        )
                        feedback = "Command requires approval in UI: `${toolOutcome.command}` (${toolOutcome.approval.reason}). Stop and wait for user."
                    }
                    is ToolExecutionOutcome.Denied -> {
                        recordAudit(command = toolOutcome.command, decision = "denied", summary = toolOutcome.reason)
                        val body = "❌ Denied: ${toolOutcome.reason}\nCommand: `${toolOutcome.command}`"
                        addMessage(ChatMessage("tool-deny-${System.currentTimeMillis()}-$steps", "OpenBot", body, false))
                        feedback = "Tool denied by policy: ${toolOutcome.reason}"
                    }
                    is ToolExecutionOutcome.Failed -> {
                        recordAudit(command = toolOutcome.commandLabel, decision = "error", summary = toolOutcome.error)
                        val body = "⚠️ Tool `${toolOutcome.toolName}` error: ${toolOutcome.error}"
                        addMessage(ChatMessage("tool-err-${System.currentTimeMillis()}-$steps", "OpenBot", body, false))
                        feedback = "Tool `${toolOutcome.toolName}` failed: ${toolOutcome.error}"
                    }
                }
                addAgentHistory(LlmChatMessage("user", "Tool result:\n$feedback"))
                steps++
                if (needsHumanApproval) {
                    hitLimit = false
                    break
                }
                continue
            }

            // Fallback path for models that emit JSON command protocol or fenced bash blocks.
            val reply = response.text
            addAgentHistory(LlmChatMessage("assistant", reply))
            val parsed = parseAgentReply(reply)
            val idx = messages.indexOfFirst { it.id == streamMsgId }
            if (idx != -1) {
                val finalized = messages[idx].copy(text = parsed.text)
                messages[idx] = finalized
                persistMessage(finalized)
            }

            val cmd = parsed.command
            if (cmd == null) {
                hitLimit = false
                break
            }
            addMessage(
                ChatMessage(
                    "sys-$idPrefix-${System.currentTimeMillis()}-$steps",
                    "OpenBot",
                    "Proposed command detected. Executing in sandbox:\n`$cmd`",
                    false
                )
            )
            val outcome = runSandboxCommand(cmd)
            val outcomeText = appendOutcome(cmd, outcome)
            val feedback = when (outcome) {
                is ActionOutcomeData.Executed -> "Command executed.\n$cmd\n\nResult:\n$outcomeText"
                is ActionOutcomeData.NeedsApproval ->
                    "Command requires approval in UI.\n$cmd\n\nReason: ${outcome.approval.reason}\n" +
                        "Stop here; the user will approve or reject in the UI."
                is ActionOutcomeData.Denied -> "Command denied by policy.\n$cmd\n\nReason: ${outcome.reason}"
                is ActionOutcomeData.Error -> "Command execution error.\n$cmd\n\nError: ${outcome.message}"
            }
            addAgentHistory(
                LlmChatMessage(
                    "user",
                    "Tool result:\n$feedback\n\nExplain the result to the user. If another step is needed, call a tool or provide a single bash block."
                )
            )
            steps++
            if (outcome is ActionOutcomeData.NeedsApproval) {
                hitLimit = false
                break
            }
        }
        if (hitLimit) {
            addMessage(
                ChatMessage(
                    "limit-$idPrefix-${System.currentTimeMillis()}",
                    "OpenBot",
                    "⚠️ Reached the agent step limit ($MAX_AGENT_STEPS). Ask me to continue if needed.",
                    false
                )
            )
        }
    }

    fun runAgentTurn(userText: String) {
        if (!llmConfig.isConfigured()) {
            addMessage(
                ChatMessage(
                    "cfg-${System.currentTimeMillis()}",
                    "OpenBot",
                    "LLM is not configured. Open Settings and set base URL, API key, and model.",
                    false
                ),
                persist = false
            )
            return
        }
        isBusy = true
        scope.launch {
            try {
                ensureAgentSystemPrompt()
                addAgentHistory(LlmChatMessage("user", userText))
                runAgentLoop("ai")
            } catch (e: Exception) {
                addMessage(
                    ChatMessage(
                        "err-${System.currentTimeMillis()}",
                        "OpenBot",
                        "⚠️ Agent error: ${e.message ?: e.toString()}",
                        false
                    )
                )
            } finally {
                isBusy = false
            }
        }
    }

    fun submitInput(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || isBusy) return

        val directCmd = when {
            trimmed.startsWith("/sh ") -> trimmed.removePrefix("/sh ").trim()
            trimmed.startsWith("/cmd ") -> trimmed.removePrefix("/cmd ").trim()
            !agentMode -> trimmed
            else -> null
        }
        if (directCmd?.isEmpty() == true) return

        addMessage(ChatMessage("user-${System.currentTimeMillis()}", "You", trimmed, true))
        inputText = ""

        if (directCmd != null) {
            isBusy = true
            scope.launch {
                val outcome = runSandboxCommand(directCmd)
                appendOutcome(directCmd, outcome)
                isBusy = false
            }
        } else {
            runAgentTurn(trimmed)
        }
    }

    fun resolveApproval(message: ChatMessage, approved: Boolean) {
        val approval = message.pendingApproval ?: return
        val idx = messages.indexOfFirst { it.id == message.id }
        if (idx == -1 || !resolvingApprovals.add(approval.id)) return

        messages[idx] = message.copy(
            text = if (approved) "Approving `${approval.command}`..." else "Rejecting `${approval.command}`...",
            pendingApproval = null
        )

        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                OpenBotCore.resolveApproval(approval.id, approved)
            }
            val statusLine = when (outcome) {
                is ActionOutcomeData.Executed -> {
                    val r = outcome.result
                    buildString {
                        append("✅ Approved & executed: `${approval.command}`\nexit: ${r.exitCode}")
                        if (r.stdout.isNotBlank()) append("\n\nstdout:\n${r.stdout.trimEnd()}")
                        if (r.stderr.isNotBlank()) append("\n\nstderr:\n${r.stderr.trimEnd()}")
                    }
                }
                is ActionOutcomeData.Denied -> "❌ Rejected: ${outcome.reason}\nCommand: `${approval.command}`"
                is ActionOutcomeData.Error -> "⚠️ Error resolving approval: ${outcome.message}"
                is ActionOutcomeData.NeedsApproval -> "⚠️ Unexpected: approval still pending."
            }
            val currentIdx = messages.indexOfFirst { it.id == message.id }
            if (currentIdx != -1) {
                messages[currentIdx] = messages[currentIdx].copy(text = statusLine, pendingApproval = null)
            }
            activeThreadId?.let { tid ->
                withContext(Dispatchers.IO) {
                    threadStore.updateMessageText(tid, message.id, statusLine)
                }
            }
            when (outcome) {
                is ActionOutcomeData.Executed -> recordAudit(
                    command = approval.command,
                    decision = "approved_executed",
                    risk = approval.riskDescription,
                    exitCode = outcome.result.exitCode,
                    sandboxMode = outcome.result.sandboxMode,
                    summary = statusLine
                )
                is ActionOutcomeData.Denied -> recordAudit(
                    command = approval.command,
                    decision = if (approved) "denied" else "rejected",
                    risk = approval.riskDescription,
                    summary = outcome.reason
                )
                is ActionOutcomeData.Error -> recordAudit(
                    command = approval.command,
                    decision = "error",
                    risk = approval.riskDescription,
                    summary = outcome.message
                )
                is ActionOutcomeData.NeedsApproval -> Unit
            }

            if (agentMode && llmConfig.isConfigured()) {
                // Feed the resolution back to the same bounded loop used by chat turns.
                // The old follow-up code only inspected one reply and silently
                // discarded a command proposed in the next one.
                addAgentHistory(
                    LlmChatMessage(
                        "user",
                        "Approval resolution for `${approval.command}`:\n$statusLine\n\n" +
                            "Explain the result. If another command is needed, provide exactly one bash block."
                    )
                )
                isBusy = true
                try {
                    runAgentLoop("ai-appr")
                } catch (e: Exception) {
                    addMessage(
                        ChatMessage(
                            "ai-appr-err-${System.currentTimeMillis()}",
                            "OpenBot",
                            "⚠️ Agent error: ${e.message}",
                            false
                        )
                    )
                } finally {
                    isBusy = false
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = llmConfig,
            onDismiss = { showSettings = false },
            onSave = { cfg ->
                val saved = settingsStore.save(cfg)
                if (saved) {
                    llmConfig = cfg
                    ensureAgentSystemPrompt()
                    showSettings = false
                    messages.add(
                        ChatMessage(
                            "cfg-save-${System.currentTimeMillis()}",
                            "OpenBot",
                            "Settings saved. API key stored with Android Keystore encryption.",
                            false
                        )
                    )
                } else {
                    messages.add(
                        ChatMessage(
                            "cfg-save-err-${System.currentTimeMillis()}",
                            "OpenBot",
                            "⚠️ Could not securely persist the API key. Settings were not saved.",
                            false
                        )
                    )
                }
            }
        )
    }

    if (showThreadsDialog) {
        ThreadsAndAuditDialog(
            threads = threads,
            activeThreadId = activeThreadId,
            threadStore = threadStore,
            workspaceRoot = rootfsManager.workspaceDirectory,
            isBusy = isBusy,
            policies = customPolicies,
            profiles = botProfiles,
            activeProfileId = activeProfileId,
            skills = savedSkills,
            onDismiss = { showThreadsDialog = false },
            onSelectThread = { id ->
                showThreadsDialog = false
                if (id != activeThreadId && !isBusy) {
                    scope.launch { loadThread(id) }
                }
            },
            onCreateThread = {
                showThreadsDialog = false
                if (!isBusy) {
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { threadStore.createThread() }
                        loadThread(created.id)
                    }
                }
            },
            onRenameThread = { id, newTitle ->
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        threadStore.renameThread(id, newTitle)
                        threadStore.listThreads()
                    }
                    threads = updated
                }
            },
            onDeleteThread = { id ->
                if (!isBusy) {
                    scope.launch {
                        val nextId = withContext(Dispatchers.IO) {
                            threadStore.deleteThread(id)
                            if (id == activeThreadId) {
                                threadStore.ensureInitialThread().id
                            } else {
                                activeThreadId
                            }
                        }
                        if (nextId != null && id == activeThreadId) {
                            loadThread(nextId)
                        } else {
                            threads = withContext(Dispatchers.IO) { threadStore.listThreads() }
                        }
                    }
                }
            },
            onExportAudit = { json ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, "OpenBot Mobile Audit Log")
                    putExtra(Intent.EXTRA_TEXT, json)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Audit Log"))
            },
            onResetWorkspace = {
                if (!isBusy) {
                    isBusy = true
                    scope.launch {
                        val ok = rootfsManager.resetWorkspace()
                        recordAudit(
                            command = "workspace:reset",
                            decision = if (ok) "executed" else "error",
                            summary = if (ok) "Workspace cleared" else "Workspace reset failed"
                        )
                        addMessage(
                            ChatMessage(
                                "ws-reset-${System.currentTimeMillis()}",
                                "OpenBot",
                                if (ok) "🧹 Workspace `/workspace` has been reset." else "⚠️ Failed to reset workspace.",
                                false
                            ),
                            persist = false
                        )
                        isBusy = false
                    }
                }
            },
            onReinstallRootfs = {
                if (!isBusy) {
                    showThreadsDialog = false
                    isBusy = true
                    scope.launch {
                        val ok = rootfsManager.reinstallRootfs()
                        if (ok && customPolicies.isNotEmpty()) {
                            withContext(Dispatchers.IO) { OpenBotCore.setCustomPolicyRules(customPolicies) }
                        }
                        recordAudit(
                            command = "rootfs:reinstall",
                            decision = if (ok) "executed" else "error",
                            summary = if (ok) "Rootfs reinstalled" else "Rootfs reinstall failed"
                        )
                        val msg = if (ok) {
                            val s = rootfsManager.status.value as? SandboxStatus.Ready
                            "♻️ Rootfs reinstalled (mode=${s?.mode ?: "unknown"}, rootfs=${s?.rootfsKind ?: "unknown"} ${s?.rootfsVersion ?: ""})."
                        } else {
                            val err = (rootfsManager.status.value as? SandboxStatus.Failed)?.error ?: "unknown error"
                            "⚠️ Rootfs reinstall failed: $err"
                        }
                        addMessage(
                            ChatMessage("rootfs-reinstall-${System.currentTimeMillis()}", "OpenBot", msg, false),
                            persist = false
                        )
                        isBusy = false
                    }
                }
            },
            onSavePolicies = { updated ->
                customPolicies = updated
                governanceStore.savePolicies(updated)
                scope.launch(Dispatchers.IO) { OpenBotCore.setCustomPolicyRules(updated) }
            },
            onSelectProfile = { profile ->
                activeProfileId = profile.id
                governanceStore.setActiveProfileId(profile.id)
                val updatedCfg = llmConfig.copy(
                    systemPrompt = profile.systemPrompt,
                    model = profile.modelOverride.ifBlank { llmConfig.model }
                )
                if (settingsStore.save(updatedCfg)) {
                    llmConfig = updatedCfg
                    ensureAgentSystemPrompt()
                }
                addMessage(
                    ChatMessage(
                        "profile-${System.currentTimeMillis()}",
                        "OpenBot",
                        "Switched bot profile to **${profile.name}**.",
                        false
                    ),
                    persist = false
                )
            },
            onSaveProfiles = { updated ->
                botProfiles = updated
                governanceStore.saveProfiles(updated)
            },
            onSaveSkills = { updated ->
                savedSkills = updated
                governanceStore.saveSkills(updated)
            },
            onRunSkill = { skill ->
                showThreadsDialog = false
                agentMode = true
                submitInput("Run skill '${skill.name}': ${skill.instructions}")
            }
        )
    }

    Scaffold(
        topBar = {
            val activeTitle = threads.firstOrNull { it.id == activeThreadId }?.title
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (activeTitle.isNullOrBlank() || activeTitle == ThreadStore.DEFAULT_TITLE) {
                                "OpenBot Mobile"
                            } else {
                                activeTitle
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val statusText = when (val s = sandboxStatus) {
                            SandboxStatus.Uninitialized -> "Sandbox uninitialized"
                            is SandboxStatus.SettingUp -> s.message
                            is SandboxStatus.Ready -> {
                                val isolation = when (s.mode) {
                                    "proot" -> "PRoot sandbox"
                                    "shim" -> "shim (no isolation)"
                                    "mock" -> "mock (no isolation)"
                                    else -> s.mode
                                }
                                "Core ${s.mode} • $isolation"
                            }
                            is SandboxStatus.Failed -> "Sandbox failed"
                        }
                        val statusColor = when (val s = sandboxStatus) {
                            is SandboxStatus.Ready -> if (s.mode == "proot") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            is SandboxStatus.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Text(statusText, fontSize = 12.sp, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { showThreadsDialog = true },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("Threads") }
                        Text("Agent", fontSize = 11.sp)
                        Switch(
                            checked = agentMode,
                            onCheckedChange = { agentMode = it },
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        TextButton(
                            onClick = { showSettings = true },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("Settings") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        bottomBar = {
            Column {
                if (agentMode && !llmConfig.isConfigured()) {
                    Text(
                        "Agent mode on but LLM not configured.",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (agentMode) "Ask your AI coworker..." else "Run command in sandbox...") },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { submitInput(inputText) },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text(if (isBusy) "..." else "Send")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(message = msg, onResolveApproval = { approved -> resolveApproval(msg, approved) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initial: LlmConfig,
    onDismiss: () -> Unit,
    onSave: (LlmConfig) -> Unit
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent LLM Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LlmProvider.values().forEach { p ->
                        FilterChip(
                            selected = provider == p,
                            onClick = {
                                if (provider != p) {
                                    provider = p
                                    baseUrl = p.defaultBaseUrl
                                    model = p.defaultModel
                                }
                            },
                            label = {
                                Text(
                                    when (p) {
                                        LlmProvider.OPENAI -> "OpenAI"
                                        LlmProvider.ANTHROPIC -> "Claude"
                                        LlmProvider.GEMINI -> "Gemini"
                                        LlmProvider.AG_UI -> "AG-UI"
                                    },
                                    fontSize = 10.sp
                                )
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(if (provider == LlmProvider.AG_UI) "AG-UI Endpoint URL" else "Base URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (provider.requiresApiKey) "API Key" else "Bearer Token (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (provider != LlmProvider.AG_UI) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System prompt") },
                    minLines = 3,
                    maxLines = 5
                )
                Text(
                    "${provider.label} with live SSE streaming & native tool calling. API key is encrypted with Android Keystore.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    LlmConfig(
                        provider = provider,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey,
                        model = model.trim(),
                        systemPrompt = systemPrompt
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    onResolveApproval: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.sender,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (message.isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message.text,
                    color = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                message.pendingApproval?.let { approval ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "⚠️ Action Approval Required (${approval.riskDescription} Risk)",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = approval.reason,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                color = Color.Black.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "$ ${approval.command}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color.Green,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { onResolveApproval(false) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reject")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { onResolveApproval(true) }) {
                                    Text("Approve")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsAndAuditDialog(
    threads: List<ThreadSummary>,
    activeThreadId: String?,
    threadStore: ThreadStore,
    workspaceRoot: java.io.File,
    isBusy: Boolean,
    policies: List<CustomPolicyRule>,
    profiles: List<BotProfile>,
    activeProfileId: String?,
    skills: List<SavedSkill>,
    onDismiss: () -> Unit,
    onSelectThread: (String) -> Unit,
    onCreateThread: () -> Unit,
    onRenameThread: (String, String) -> Unit,
    onDeleteThread: (String) -> Unit,
    onExportAudit: (String) -> Unit,
    onResetWorkspace: () -> Unit,
    onReinstallRootfs: () -> Unit,
    onSavePolicies: (List<CustomPolicyRule>) -> Unit,
    onSelectProfile: (BotProfile) -> Unit,
    onSaveProfiles: (List<BotProfile>) -> Unit,
    onSaveSkills: (List<SavedSkill>) -> Unit,
    onRunSkill: (SavedSkill) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var renamingThread by remember { mutableStateOf<ThreadSummary?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var auditEntries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            auditEntries = withContext(Dispatchers.IO) { threadStore.listAuditLogs(limit = 100) }
        }
    }

    renamingThread?.let { target ->
        AlertDialog(
            onDismissRequest = { renamingThread = null },
            title = { Text("Rename Thread") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Title") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val trimmed = renameInput.trim()
                    if (trimmed.isNotEmpty()) {
                        onRenameThread(target.id, trimmed)
                    }
                    renamingThread = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingThread = null }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Chats", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("Files", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = { Text("Audit", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    label = { Text("Rules", fontSize = 11.sp) }
                )
            }
        },
        text = {
            if (selectedTab == 0) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCreateThread,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ New Thread")
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(threads, key = { it.id }) { thread ->
                            val isActive = thread.id == activeThreadId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectThread(thread.id) }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = thread.title,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${thread.messageCount} msgs • ${timeFmt.format(Date(thread.updatedAt))}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = {
                                            renameInput = thread.title
                                            renamingThread = thread
                                        }) { Text("Rename", fontSize = 12.sp) }
                                        TextButton(
                                            onClick = { onDeleteThread(thread.id) },
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            )
                                        ) { Text("Delete", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (selectedTab == 1) {
                WorkspaceTabContent(
                    workspaceRoot = workspaceRoot,
                    isBusy = isBusy,
                    onResetWorkspace = onResetWorkspace,
                    onReinstallRootfs = onReinstallRootfs
                )
            } else if (selectedTab == 3) {
                GovernanceTabContent(
                    policies = policies,
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    skills = skills,
                    onSavePolicies = onSavePolicies,
                    onSelectProfile = onSelectProfile,
                    onSaveProfiles = onSaveProfiles,
                    onSaveSkills = onSaveSkills,
                    onRunSkill = onRunSkill
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val json = withContext(Dispatchers.IO) { threadStore.exportAuditJson() }
                                onExportAudit(json)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Audit Log (JSON)")
                    }
                    if (auditEntries.isEmpty()) {
                        Text(
                            "No commands or policy decisions recorded yet.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(auditEntries, key = { it.id }) { entry ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        val meta = buildString {
                                            append(timeFmt.format(Date(entry.createdAt)))
                                            append(" • ")
                                            append(entry.decision)
                                            entry.risk?.let { append(" • $it") }
                                            entry.exitCode?.let { append(" • exit=$it") }
                                        }
                                        Text(
                                            text = meta,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "$ ${entry.command}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
