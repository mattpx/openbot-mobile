package com.openbot.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomPolicyRule(
    val id: String = UUID.randomUUID().toString(),
    val pattern: String,
    /** `"AlwaysDeny"`, `"AskHuman"`, or `"AlwaysAllow"`. */
    val action: String,
    /** `"Low"`, `"Medium"`, `"High"`, or `"Critical"` (used when action == `"AskHuman"`). */
    val risk: String = "Medium",
    val description: String = ""
)

data class BotProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val systemPrompt: String,
    val modelOverride: String = ""
)

data class SavedSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val instructions: String
)

/**
 * Local persistence for user-defined policy rules, bot profiles, and reusable skills.
 */
class GovernanceStore(context: Context) {

    private val prefs = context.getSharedPreferences("openbot_governance", Context.MODE_PRIVATE)

    fun loadPolicies(): List<CustomPolicyRule> {
        val raw = prefs.getString(KEY_POLICIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val pattern = o.optString("pattern").trim()
                if (pattern.isEmpty()) return@mapNotNull null
                CustomPolicyRule(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    pattern = pattern,
                    action = o.optString("action", "AskHuman"),
                    risk = o.optString("risk", "Medium"),
                    description = o.optString("description", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun savePolicies(rules: List<CustomPolicyRule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("pattern", r.pattern)
                    put("action", r.action)
                    put("risk", r.risk)
                    put("description", r.description)
                }
            )
        }
        prefs.edit().putString(KEY_POLICIES, arr.toString()).apply()
    }

    fun loadProfiles(): List<BotProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return defaultProfiles()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@mapNotNull null
                BotProfile(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    systemPrompt = o.optString("systemPrompt", LlmConfig.DEFAULT_SYSTEM_PROMPT),
                    modelOverride = o.optString("modelOverride", "")
                )
            }
        }.getOrDefault(emptyList())
        return parsed.ifEmpty { defaultProfiles() }
    }

    fun saveProfiles(profiles: List<BotProfile>) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("systemPrompt", p.systemPrompt)
                    put("modelOverride", p.modelOverride)
                }
            )
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun activeProfileId(): String? = prefs.getString(KEY_ACTIVE_PROFILE, null)

    fun setActiveProfileId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE_PROFILE) else putString(KEY_ACTIVE_PROFILE, id)
        }.apply()
    }

    fun loadSkills(): List<SavedSkill> {
        val raw = prefs.getString(KEY_SKILLS, null) ?: return defaultSkills()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@mapNotNull null
                SavedSkill(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    description = o.optString("description", ""),
                    instructions = o.optString("instructions", "")
                )
            }
        }.getOrDefault(defaultSkills())
    }

    fun saveSkills(skills: List<SavedSkill>) {
        val arr = JSONArray()
        for (s in skills) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("description", s.description)
                    put("instructions", s.instructions)
                }
            )
        }
        prefs.edit().putString(KEY_SKILLS, arr.toString()).apply()
    }

    private fun defaultProfiles(): List<BotProfile> = listOf(
        BotProfile(
            id = "default-coworker",
            name = "Default Coworker",
            systemPrompt = LlmConfig.DEFAULT_SYSTEM_PROMPT
        ),
        BotProfile(
            id = "linux-sysadmin",
            name = "Linux Sysadmin",
            systemPrompt = LlmConfig.DEFAULT_SYSTEM_PROMPT +
                "\n\nSpecialize in Alpine Linux diagnostics, shell scripting, and file processing inside /workspace."
        ),
        BotProfile(
            id = "web-researcher",
            name = "Web Researcher",
            systemPrompt = LlmConfig.DEFAULT_SYSTEM_PROMPT +
                "\n\nPrefer web_fetch and web_browse tools to gather information and save structured notes in /workspace."
        )
    )

    private fun defaultSkills(): List<SavedSkill> = listOf(
        SavedSkill(
            id = "skill-workspace-summary",
            name = "Summarize Workspace",
            description = "Inspect /workspace files and produce a concise inventory.",
            instructions = "Use list_files to inspect /workspace, read key README or text files, and summarize what is in the workspace."
        )
    )

    private companion object {
        const val KEY_POLICIES = "custom_policies_v1"
        const val KEY_PROFILES = "bot_profiles_v1"
        const val KEY_ACTIVE_PROFILE = "active_profile_id"
        const val KEY_SKILLS = "saved_skills_v1"
    }
}
