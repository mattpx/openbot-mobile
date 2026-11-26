package com.openbot.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openbot.mobile.service.AgentForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpenBotApp(rootfsManager = rootfsManager, settingsStore = settingsStore)
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
fun OpenBotApp(rootfsManager: RootfsManager, settingsStore: AgentSettingsStore) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var initRequested by remember { mutableStateOf(false) }
    var agentMode by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var llmConfig by remember { mutableStateOf(settingsStore.load()) }
    val sandboxStatus by rootfsManager.status.collectAsState()

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                "1",
                "OpenBot",
                "👋 Hello! I am your OpenBot AI coworker running on Android with PRoot sandboxing.\n\n" +
                    "• Direct mode: type shell commands to run in sandbox.\n" +
                    "• Agent mode: configure LLM in Settings, then chat naturally; I'll propose commands in ```bash``` blocks.",
                false
            )
        )
    }
    val agentHistory = remember { mutableStateListOf<LlmChatMessage>() }

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
            val statusMsg = if (ok) {
                val s = rootfsManager.status.value as? SandboxStatus.Ready
                val mode = s?.mode ?: OpenBotCore.sandboxMode()
                buildString {
                    append("Sandbox ready (mode=$mode, rootfs=${s?.rootfsKind ?: "unknown"}")
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
            messages.add(ChatMessage("init-${System.currentTimeMillis()}", "OpenBot", statusMsg, false))
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
        if (outcome is ActionOutcomeData.NeedsApproval) {
            messages.add(
                ChatMessage(
                    id = "appr-${System.currentTimeMillis()}",
                    sender = "OpenBot",
                    text = "I need approval before running this command.",
                    isUser = false,
                    pendingApproval = outcome.approval
                )
            )
        } else {
            messages.add(ChatMessage("exec-${System.currentTimeMillis()}", "OpenBot", text, false))
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

    fun runAgentTurn(userText: String) {
        if (!llmConfig.isConfigured()) {
            messages.add(
                ChatMessage(
                    "cfg-${System.currentTimeMillis()}",
                    "OpenBot",
                    "LLM is not configured. Open Settings and set base URL, API key, and model.",
                    false
                )
            )
            return
        }
        isBusy = true
        scope.launch {
            try {
                ensureAgentSystemPrompt()
                agentHistory.add(LlmChatMessage("user", userText))
                var steps = 0
                while (steps < MAX_AGENT_STEPS) {
                    val reply = withContext(Dispatchers.IO) {
                        LlmClient(llmConfig).chat(agentHistory.toList())
                    }
                    agentHistory.add(LlmChatMessage("assistant", reply))
                    val parsed = parseAgentReply(reply)
                    messages.add(ChatMessage("ai-${System.currentTimeMillis()}-$steps", "OpenBot", parsed.text, false))

                    val cmd = parsed.command ?: break
                    messages.add(
                        ChatMessage(
                            "sys-${System.currentTimeMillis()}-$steps",
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
                    agentHistory.add(LlmChatMessage("user", "Tool result:\n$feedback\n\nExplain the result to the user. If another command is needed, provide exactly one bash block."))
                    steps++

                    // Approval requires a human decision in the UI; stop the loop.
                    if (outcome is ActionOutcomeData.NeedsApproval) break
                }
                if (steps >= MAX_AGENT_STEPS) {
                    messages.add(
                        ChatMessage(
                            "limit-${System.currentTimeMillis()}",
                            "OpenBot",
                            "⚠️ Reached the agent step limit ($MAX_AGENT_STEPS). Ask me to continue if needed.",
                            false
                        )
                    )
                }
            } catch (e: Exception) {
                messages.add(
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
        messages.add(ChatMessage("user-${System.currentTimeMillis()}", "You", trimmed, true))
        inputText = ""

        val directCmd = when {
            trimmed.startsWith("/sh ") -> trimmed.removePrefix("/sh ").trim()
            trimmed.startsWith("/cmd ") -> trimmed.removePrefix("/cmd ").trim()
            !agentMode -> trimmed
            else -> null
        }

        if (directCmd != null) {
            if (directCmd.isEmpty()) return
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
        if (idx == -1) return

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

            if (agentMode && llmConfig.isConfigured()) {
                // Feed approval resolution back to agent context and continue the bounded loop.
                agentHistory.add(LlmChatMessage("user", "Approval resolution for `${approval.command}`:\n$statusLine\n\nExplain the result. If another command is needed, provide exactly one bash block."))
                isBusy = true
                try {
                    val followUp = withContext(Dispatchers.IO) { LlmClient(llmConfig).chat(agentHistory.toList()) }
                    agentHistory.add(LlmChatMessage("assistant", followUp))
                    val parsed = parseAgentReply(followUp)
                    messages.add(ChatMessage("ai-appr-${System.currentTimeMillis()}", "OpenBot", parsed.text, false))
                    val nextCmd = parsed.command
                    if (nextCmd != null) {
                        messages.add(ChatMessage("sys-appr-${System.currentTimeMillis()}", "OpenBot", "Proposed command detected. Executing in sandbox:\n`$nextCmd`", false))
                        val nextOutcome = runSandboxCommand(nextCmd)
                        val nextOutcomeText = appendOutcome(nextCmd, nextOutcome)
                        val feedback = when (nextOutcome) {
                            is ActionOutcomeData.Executed -> "Command executed.\n$nextCmd\n\nResult:\n$nextOutcomeText"
                            is ActionOutcomeData.NeedsApproval -> "Command requires approval in UI.\n$nextCmd\n\nReason: ${nextOutcome.approval.reason}"
                            is ActionOutcomeData.Denied -> "Command denied by policy.\n$nextCmd\n\nReason: ${nextOutcome.reason}"
                            is ActionOutcomeData.Error -> "Command execution error.\n$nextCmd\n\nError: ${nextOutcome.message}"
                        }
                        agentHistory.add(LlmChatMessage("user", "Tool result:\n$feedback\n\nExplain the result to the user. If another command is needed, provide exactly one bash block."))
                        if (nextOutcome !is ActionOutcomeData.NeedsApproval) {
                            val followUp2 = withContext(Dispatchers.IO) { LlmClient(llmConfig).chat(agentHistory.toList()) }
                            agentHistory.add(LlmChatMessage("assistant", followUp2))
                            messages.add(ChatMessage("ai-appr2-${System.currentTimeMillis()}", "OpenBot", parseAgentReply(followUp2).text, false))
                        }
                    }
                } catch (e: Exception) {
                    messages.add(ChatMessage("ai-appr-err-${System.currentTimeMillis()}", "OpenBot", "⚠️ Agent error: ${e.message}", false))
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenBot Mobile", fontWeight = FontWeight.Bold)
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
                        Text(statusText, fontSize = 12.sp, color = statusColor)
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Agent", fontSize = 12.sp)
                        Switch(
                            checked = agentMode,
                            onCheckedChange = { agentMode = it },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        TextButton(onClick = { showSettings = true }) { Text("Settings") }
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

@Composable
fun SettingsDialog(
    initial: LlmConfig,
    onDismiss: () -> Unit,
    onSave: (LlmConfig) -> Unit
) {
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent LLM Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System prompt") },
                    minLines = 3,
                    maxLines = 6
                )
                Text(
                    "OpenAI-compatible /chat/completions endpoint. API key is encrypted with Android Keystore before being stored in app-private preferences.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    LlmConfig(
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
