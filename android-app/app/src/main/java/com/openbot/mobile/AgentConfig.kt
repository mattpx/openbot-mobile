package com.openbot.mobile

import android.content.Context
import java.net.URI

data class LlmConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    fun isConfigured(): Boolean =
        apiKey.isNotBlank() && isHttpUrl(baseUrl) && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        const val DEFAULT_SYSTEM_PROMPT =
            "You are OpenBot Mobile, an AI coworker running on Android with a PRoot sandbox. " +
                "Help the user accomplish tasks with short, concrete steps.\n\n" +
                "To run a shell command in the sandbox, reply with exactly one JSON object:\n" +
                "{\"action\":\"command\",\"command\":\"ls -la\"}\n" +
                "When the task is finished, reply with:\n" +
                "{\"action\":\"final\",\"message\":\"what you did and what it means\"}\n\n" +
                "If JSON is impossible, a single fenced bash block is accepted as a command. " +
                "Run one command at a time and wait for its tool result before planning the next. " +
                "Never invent command output. High-risk commands require human approval in the app."

        fun isHttpUrl(value: String): Boolean {
            return try {
                val uri = URI(value.trim())
                (uri.scheme?.equals("http", ignoreCase = true) == true ||
                    uri.scheme?.equals("https", ignoreCase = true) == true) &&
                    !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }
    }
}

class AgentSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("openbot_agent_settings", Context.MODE_PRIVATE)
    private val vault = ApiKeyVault(context)

    fun load(): LlmConfig {
        migrateLegacyPlaintextKey()
        return LlmConfig(
            baseUrl = prefs.getString("baseUrl", LlmConfig.DEFAULT_BASE_URL)
                ?: LlmConfig.DEFAULT_BASE_URL,
            apiKey = vault.load(),
            model = prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini",
            systemPrompt = prefs.getString("systemPrompt", LlmConfig.DEFAULT_SYSTEM_PROMPT)
                ?: LlmConfig.DEFAULT_SYSTEM_PROMPT
        )
    }

    /** Returns false when the API key could not be encrypted and persisted. */
    fun save(config: LlmConfig): Boolean {
        if (!vault.save(config.apiKey.trim())) {
            return false
        }
        prefs.edit()
            .putString("baseUrl", config.baseUrl.trim())
            .putString("model", config.model.trim())
            .putString("systemPrompt", config.systemPrompt)
            .remove("apiKey")
            .apply()
        return true
    }

    /** Moves a key written by older app versions into Keystore storage. */
    private fun migrateLegacyPlaintextKey() {
        val legacy = prefs.getString("apiKey", null) ?: return
        if (legacy.isNotBlank() && !vault.save(legacy.trim())) {
            // Keep the plaintext key so the user does not lose it when Keystore
            // is unavailable; a failed encryption must not look like a migration.
            return
        }
        prefs.edit().remove("apiKey").apply()
    }
}
