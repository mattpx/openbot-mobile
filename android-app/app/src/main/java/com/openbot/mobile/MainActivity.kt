package com.openbot.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentForegroundService.start(this)

        val rootfsManager = RootfsManager(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpenBotApp(rootfsManager = rootfsManager)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenBotApp(rootfsManager: RootfsManager) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    val sandboxStatus by rootfsManager.status.collectAsState()

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                "1",
                "OpenBot",
                "👋 Hello! I am your OpenBot AI coworker running on Android with PRoot sandboxing.",
                false
            )
        )
    }

    LaunchedEffect(Unit) {
        when (val status = sandboxStatus) {
            is SandboxStatus.Ready -> Unit
            is SandboxStatus.SettingUp -> Unit
            is SandboxStatus.Failed -> Unit
            SandboxStatus.Uninitialized -> {
                val ok = rootfsManager.initializeSandbox()
                val statusMsg = if (ok) {
                    val mode = if (OpenBotCore.isNativeLoaded()) "native Rust core" else "mock fallback core"
                    "Sandbox ready ($mode). Try commands like `ls`, `pwd`, or `curl https://example.com`."
                } else {
                    "Sandbox initialization failed. Check logs and retry."
                }
                messages.add(
                    ChatMessage(
                        id = System.currentTimeMillis().toString(),
                        sender = "OpenBot",
                        text = statusMsg,
                        isUser = false
                    )
                )
            }
        }
    }

    LaunchedEffect(sandboxStatus) {
        when (val status = sandboxStatus) {
            is SandboxStatus.Failed -> {
                val last = messages.lastOrNull()?.text.orEmpty()
                if (!last.contains(status.error)) {
                    messages.add(
                        ChatMessage(
                            id = "status-${System.currentTimeMillis()}",
                            sender = "OpenBot",
                            text = "⚠️ Sandbox error: ${status.error}",
                            isUser = false
                        )
                    )
                }
            }
            else -> Unit
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun appendOutcome(command: String, outcome: ActionOutcomeData) {
        when (outcome) {
            is ActionOutcomeData.Executed -> {
                val result = outcome.result
                val body = buildString {
                    append("`$command`\n")
                    append("exit: ${result.exitCode}")
                    if (result.stdout.isNotBlank()) {
                        append("\n\nstdout:\n${result.stdout.trimEnd()}")
                    }
                    if (result.stderr.isNotBlank()) {
                        append("\n\nstderr:\n${result.stderr.trimEnd()}")
                    }
                }
                messages.add(
                    ChatMessage(
                        id = "exec-${System.currentTimeMillis()}",
                        sender = "OpenBot",
                        text = body,
                        isUser = false
                    )
                )
            }
            is ActionOutcomeData.NeedsApproval -> {
                messages.add(
                    ChatMessage(
                        id = "appr-${System.currentTimeMillis()}",
                        sender = "OpenBot",
                        text = "I need approval before running this command.",
                        isUser = false,
                        pendingApproval = outcome.approval
                    )
                )
            }
            is ActionOutcomeData.Denied -> {
                messages.add(
                    ChatMessage(
                        id = "deny-${System.currentTimeMillis()}",
                        sender = "OpenBot",
                        text = "❌ Denied: ${outcome.reason}\nCommand: `$command`",
                        isUser = false
                    )
                )
            }
            is ActionOutcomeData.Error -> {
                messages.add(
                    ChatMessage(
                        id = "err-${System.currentTimeMillis()}",
                        sender = "OpenBot",
                        text = "⚠️ Error: ${outcome.message}\nCommand: `$command`",
                        isUser = false
                    )
                )
            }
        }
    }

    fun submitCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || isBusy) return
        messages.add(
            ChatMessage(
                id = "user-${System.currentTimeMillis()}",
                sender = "You",
                text = trimmed,
                isUser = true
            )
        )
        inputText = ""
        isBusy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val ready = when (rootfsManager.status.value) {
                    is SandboxStatus.Ready -> true
                    SandboxStatus.Uninitialized -> rootfsManager.initializeSandbox()
                    is SandboxStatus.SettingUp -> false
                    is SandboxStatus.Failed -> rootfsManager.initializeSandbox()
                }
                if (!ready) {
                    ActionOutcomeData.Error("Sandbox is not ready yet.")
                } else {
                    OpenBotCore.requestExecute(trimmed)
                }
            }
            appendOutcome(trimmed, outcome)
            isBusy = false
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
        }
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
                            is SandboxStatus.Ready -> if (OpenBotCore.isNativeLoaded()) "Native core ready • PRoot sandbox" else "Mock core ready • Native lib unavailable"
                            is SandboxStatus.Failed -> "Sandbox failed"
                        }
                        Text(
                            statusText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
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
                    placeholder = { Text("Ask your coworker / run command...") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { submitCommand(inputText) },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(if (isBusy) "..." else "Send")
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
                MessageBubble(
                    message = msg,
                    onResolveApproval = { approved -> resolveApproval(msg, approved) }
                )
            }
        }
    }
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
