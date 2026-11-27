package com.openbot.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GovernanceTabContent(
    policies: List<CustomPolicyRule>,
    profiles: List<BotProfile>,
    activeProfileId: String?,
    skills: List<SavedSkill>,
    onSavePolicies: (List<CustomPolicyRule>) -> Unit,
    onSelectProfile: (BotProfile) -> Unit,
    onSaveProfiles: (List<BotProfile>) -> Unit,
    onSaveSkills: (List<SavedSkill>) -> Unit,
    onRunSkill: (SavedSkill) -> Unit
) {
    var subTab by remember { mutableStateOf(0) }
    var newPattern by remember { mutableStateOf("") }
    var newAction by remember { mutableStateOf("AskHuman") }
    var newRisk by remember { mutableStateOf("High") }
    var newDesc by remember { mutableStateOf("") }

    var newProfileName by remember { mutableStateOf("") }
    var newProfilePrompt by remember { mutableStateOf("") }

    var newSkillName by remember { mutableStateOf("") }
    var newSkillInstructions by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                label = { Text("Policies (${policies.size})", fontSize = 11.sp) }
            )
            FilterChip(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                label = { Text("Profiles", fontSize = 11.sp) }
            )
            FilterChip(
                selected = subTab == 2,
                onClick = { subTab = 2 },
                label = { Text("Skills", fontSize = 11.sp) }
            )
        }

        when (subTab) {
            0 -> {
                OutlinedTextField(
                    value = newPattern,
                    onValueChange = { newPattern = it },
                    label = { Text("Command pattern (e.g. git push)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newDesc,
                    onValueChange = { newDesc = it },
                    label = { Text("Reason / description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AlwaysDeny", "AskHuman", "AlwaysAllow").forEach { act ->
                        FilterChip(
                            selected = newAction == act,
                            onClick = { newAction = act },
                            label = {
                                Text(
                                    when (act) {
                                        "AlwaysDeny" -> "Deny"
                                        "AskHuman" -> "Ask"
                                        else -> "Allow"
                                    },
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                    if (newAction == "AskHuman") {
                        FilterChip(
                            selected = true,
                            onClick = {
                                newRisk = when (newRisk) {
                                    "Low" -> "Medium"
                                    "Medium" -> "High"
                                    "High" -> "Critical"
                                    else -> "Low"
                                }
                            },
                            label = { Text("Risk: $newRisk", fontSize = 11.sp) }
                        )
                    }
                }
                Button(
                    onClick = {
                        val pat = newPattern.trim()
                        if (pat.isNotEmpty()) {
                            onSavePolicies(
                                policies + CustomPolicyRule(
                                    pattern = pat,
                                    action = newAction,
                                    risk = newRisk,
                                    description = newDesc.trim().ifBlank { "Custom rule for $pat" }
                                )
                            )
                            newPattern = ""
                            newDesc = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Add Policy Rule") }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(policies, key = { it.id }) { rule ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${rule.action}${if (rule.action == "AskHuman") "(${rule.risk})" else ""}: ${rule.pattern}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = rule.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = { onSavePolicies(policies.filterNot { it.id == rule.id }) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { Text("Remove", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }

            1 -> {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("New profile name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newProfilePrompt,
                    onValueChange = { newProfilePrompt = it },
                    label = { Text("System prompt") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val n = newProfileName.trim()
                        val p = newProfilePrompt.trim()
                        if (n.isNotEmpty() && p.isNotEmpty()) {
                            onSaveProfiles(profiles + BotProfile(name = n, systemPrompt = p))
                            newProfileName = ""
                            newProfilePrompt = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Save Profile") }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val active = profile.id == activeProfileId
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(profile.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text(
                                        profile.systemPrompt,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { onSelectProfile(profile) }) {
                                    Text(if (active) "Active" else "Use", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                OutlinedTextField(
                    value = newSkillName,
                    onValueChange = { newSkillName = it },
                    label = { Text("Skill name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newSkillInstructions,
                    onValueChange = { newSkillInstructions = it },
                    label = { Text("Skill instructions") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val n = newSkillName.trim()
                        val inst = newSkillInstructions.trim()
                        if (n.isNotEmpty() && inst.isNotEmpty()) {
                            onSaveSkills(
                                skills + SavedSkill(
                                    name = n,
                                    description = inst.take(60),
                                    instructions = inst
                                )
                            )
                            newSkillName = ""
                            newSkillInstructions = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Save Skill") }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(skills, key = { it.id }) { skill ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(skill.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text(
                                        skill.instructions,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    TextButton(onClick = { onRunSkill(skill) }) { Text("Run", fontSize = 11.sp) }
                                    TextButton(
                                        onClick = { onSaveSkills(skills.filterNot { it.id == skill.id }) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) { Text("Del", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
