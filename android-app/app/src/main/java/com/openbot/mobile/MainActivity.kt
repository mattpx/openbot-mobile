package com.openbot.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openbot.mobile.service.AgentForegroundService

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

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpenBotApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenBotApp() {
    var inputText by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(
            ChatMessage("1", "OpenBot", "👋 Hello! I am your OpenBot AI coworker running on Android with PRoot sandboxing.", false),
            ChatMessage(
                "2", "OpenBot", "I am requesting to run a network check via curl.", false,
                pendingApproval = PendingApprovalData(
                    id = "appr_101",
                    command = "curl -s https://api.github.com/zen",
                    reason = "External network request via curl",
                    riskDescription = "High"
                )
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenBot Mobile", fontWeight = FontWeight.Bold)
                        Text(
                            "PRoot Sandbox Active • Rootless",
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
                    placeholder = { Text("Ask your coworker...") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            messages.add(
                                ChatMessage(
                                    id = System.currentTimeMillis().toString(),
                                    sender = "You",
                                    text = inputText,
                                    isUser = true
                                )
                            )
                            inputText = ""
                        }
                    },
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Send")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(
                    message = msg,
                    onResolveApproval = { approvalId, approved ->
                        val index = messages.indexOf(msg)
                        if (index != -1) {
                            val status = if (approved) "Approved & Executed ✅" else "Rejected ❌"
                            messages[index] = msg.copy(
                                text = "${msg.text}\n\n[Status: $status]",
                                pendingApproval = null
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    onResolveApproval: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
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
                                    text = "$ " + approval.command,
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
                                    onClick = { onResolveApproval(approval.id, false) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reject")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onResolveApproval(approval.id, true) }
                                ) {
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
