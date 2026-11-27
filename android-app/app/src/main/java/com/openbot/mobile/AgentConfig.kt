package com.openbot.mobile

import android.content.Context
import java.net.URI

enum class LlmProvider(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean = true
) {
    OPENAI("openai", "OpenAI / Compatible", "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest"),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
    AG_UI("ag_ui", "AG-UI Remote Server", "http://10.0.2.2:4201", "default", requiresApiKey = false);

    companion object {
        fun fromId(raw: String?): LlmProvider =
            values().firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: OPENAI
    }
}

data class LlmConfig(
    val provider: LlmProvider = LlmProvider.OPENAI,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    fun isConfigured(): Boolean =
        isHttpUrl(baseUrl) &&
            (provider == LlmProvider.AG_UI || model.isNotBlank()) &&
            (!provider.requiresApiKey || apiKey.isNotBlank())

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        const val DEFAULT_SYSTEM_PROMPT =
            "You are OpenBot Mobile, an AI coworker running on Android with an Alpine Linux PRoot sandbox.\n\n" +
                "Available tools: `shell`, `read_file`, `write_file`, `list_files`, `web_fetch`, and `web_browse`.\n" +
                "Prefer native tool calls when supported. If tool calls are unavailable, reply with a single JSON object:\n" +
                "{\"action\":\"command\",\"command\":\"ls -la\"}\n" +
                "or a single fenced bash block. When finished, explain what you did concisely.\n" +
                "Never invent tool output. High-risk shell commands require human approval in the app."

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
        val provider = LlmProvider.fromId(prefs.getString("provider", LlmProvider.OPENAI.id))
        return LlmConfig(
            provider = provider,
            baseUrl = prefs.getString("baseUrl", provider.defaultBaseUrl)
                ?: provider.defaultBaseUrl,
            apiKey = vault.load(),
            model = prefs.getString("model", provider.defaultModel) ?: provider.defaultModel,
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
            .putString("provider", config.provider.id)
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
