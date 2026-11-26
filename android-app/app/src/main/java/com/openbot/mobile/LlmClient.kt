package com.openbot.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class LlmChatMessage(val role: String, val content: String)

/** A parsed model reply: display text plus an optional sandbox command. */
data class AgentReply(val text: String, val command: String?)

class LlmClient(private val config: LlmConfig) {

    class LlmException(message: String) : Exception(message)

    fun chat(messages: List<LlmChatMessage>): String {
        if (!LlmConfig.isHttpUrl(config.baseUrl)) {
            throw LlmException("Base URL must start with http:// or https://")
        }
        if (config.apiKey.isBlank()) {
            throw LlmException("API key is not configured")
        }
        if (config.model.isBlank()) {
            throw LlmException("Model is not configured")
        }

        val endpoint = config.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            setRequestProperty("Accept", "application/json")
        }

        val body = JSONObject().apply {
            put("model", config.model.trim())
            put("temperature", 0.2)
            val arr = JSONArray()
            for (message in messages) {
                arr.put(
                    JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                    }
                )
            }
            put("messages", arr)
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = try {
                stream?.let { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
                } ?: ""
            } catch (e: Exception) {
                ""
            }
            if (code !in 200..299) {
                throw LlmException("LLM HTTP $code: ${text.take(500)}")
            }
            val obj = JSONObject(text)
            val choices = obj.optJSONArray("choices")
                ?: throw LlmException("LLM response missing choices")
            if (choices.length() == 0) {
                throw LlmException("LLM response has no choices")
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw LlmException("LLM response missing message")
            val content = message.optString("content", "")
            if (content.isBlank()) {
                throw LlmException("LLM response had no text content")
            }
            return content
        } finally {
            conn.disconnect()
        }
    }
}

fun extractBashCommand(text: String): String? {
    val match = FENCE_REGEX.find(text) ?: return null
    return sanitizeCommand(match.groupValues[1])
}

/**
 * Parses the model's preferred JSON protocol, falling back to a fenced bash
 * block. Anything that cannot be treated as one bounded command is ignored.
 */
fun parseAgentReply(raw: String): AgentReply {
    val trimmed = raw.trim()
    val jsonText = extractJsonObject(trimmed)
    if (jsonText != null) {
        try {
            val obj = JSONObject(jsonText)
            val message = obj.optString("message").trim()
            val command = sanitizeCommand(obj.optString("command"))
            if (command != null) {
                return AgentReply(
                    text = message.ifBlank { "Running: $command" },
                    command = command
                )
            }
            if (message.isNotBlank()) {
                return AgentReply(text = message, command = null)
            }
        } catch (_: Exception) {
            // Not the JSON protocol; fall through to the bash-block fallback.
        }
    }

    val command = extractBashCommand(trimmed)
    if (command == null) {
        return AgentReply(text = trimmed, command = null)
    }
    val prose = FENCE_REGEX.replace(trimmed, "").trim()
    return AgentReply(
        text = prose.ifBlank { "Running: $command" },
        command = command
    )
}

fun sanitizeCommand(raw: String): String? {
    val command = raw.trim().removePrefix("$").trim()
    if (command.isEmpty() || command.length > MAX_COMMAND_LENGTH) return null
    if (command.contains('\u0000')) return null
    return command
}

private fun extractJsonObject(text: String): String? {
    var start = text.indexOf('{')
    while (start >= 0) {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(start, index + 1)
                        }
                    }
                }
            }
        }
        start = text.indexOf('{', start + 1)
    }
    return null
}

private const val MAX_COMMAND_LENGTH = 4096
private val FENCE_REGEX =
    Regex("```(?:bash|sh|shell)?\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
