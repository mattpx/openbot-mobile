package com.openbot.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class LlmChatMessage(val role: String, val content: String)

class LlmClient(private val config: LlmConfig) {

    class LlmException(message: String) : Exception(message)

    fun chat(messages: List<LlmChatMessage>): String {
        val endpoint = config.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            setRequestProperty("Accept", "application/json")
        }

        val body = JSONObject().apply {
            put("model", config.model.trim())
            put("temperature", 0.2)
            val arr = JSONArray()
            for (m in messages) {
                arr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
            put("messages", arr)
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw LlmException("LLM HTTP $code: ${text.take(500)}")
            }
            val obj = JSONObject(text)
            val choices = obj.optJSONArray("choices") ?: throw LlmException("LLM response missing choices")
            if (choices.length() == 0) throw LlmException("LLM response has no choices")
            val msg = choices.getJSONObject(0).optJSONObject("message")
                ?: throw LlmException("LLM response missing message")
            // content can be string or null
            val content = msg.optString("content", "")
            if (content.isBlank()) {
                // Some models return reasoning or tool calls; fallback to raw
                return msg.toString()
            }
            return content
        } finally {
            conn.disconnect()
        }
    }
}

fun extractBashCommand(text: String): String? {
    val fenceRegex = Regex("```(?:bash|sh|shell)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    val match = fenceRegex.find(text) ?: return null
    val cmd = match.groupValues[1].trim()
    if (cmd.isEmpty()) return null
    // Only take the first non-empty line/command block as a single command execution request.
    // If multi-line, keep as-is (sh -c can handle it) but trim.
    return cmd
}
