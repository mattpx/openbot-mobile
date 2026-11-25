package com.openbot.mobile

import android.content.Context

data class LlmConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    fun isConfigured(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are OpenBot Mobile, an AI coworker running on Android with a PRoot sandbox. " +
            "Help the user accomplish tasks. When you need to run a shell command in the sandbox, " +
            "reply with a single fenced bash block containing ONLY the command to run. " +
            "Example:\n```bash\nls -la\n```\n" +
            "Do not include dangerous commands. If the command needs approval, the app will handle it. " +
            "After command output is provided, explain the result clearly."
    }
}

class AgentSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("openbot_agent_settings", Context.MODE_PRIVATE)

    fun load(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString("baseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
        apiKey = prefs.getString("apiKey", "") ?: "",
        model = prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini",
        systemPrompt = prefs.getString("systemPrompt", LlmConfig.DEFAULT_SYSTEM_PROMPT) ?: LlmConfig.DEFAULT_SYSTEM_PROMPT
    )

    fun save(config: LlmConfig) {
        prefs.edit()
            .putString("baseUrl", config.baseUrl.trim())
            .putString("apiKey", config.apiKey.trim())
            .putString("model", config.model.trim())
            .putString("systemPrompt", config.systemPrompt)
            .apply()
    }
}
