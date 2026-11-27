package com.openbot.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class LlmChatMessage(val role: String, val content: String)

/** A parsed model reply: display text plus an optional sandbox command. */
data class AgentReply(val text: String, val command: String?)

/** Unified response from any provider (streamed text + optional structured tool calls). */
data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCallRequest> = emptyList()
)

class LlmClient(private val config: LlmConfig) {

    class LlmException(message: String) : Exception(message)

    fun chat(messages: List<LlmChatMessage>): String {
        return chatWithTools(messages, enableTools = false).text
    }

    fun chatWithTools(
        messages: List<LlmChatMessage>,
        enableTools: Boolean = true,
        onToken: (String) -> Unit = {}
    ): LlmResponse {
        if (!LlmConfig.isHttpUrl(config.baseUrl)) {
            throw LlmException("Base URL must start with http:// or https://")
        }
        if (config.provider.requiresApiKey && config.apiKey.isBlank()) {
            throw LlmException("API key is not configured")
        }
        if (config.provider != LlmProvider.AG_UI && config.model.isBlank()) {
            throw LlmException("Model is not configured")
        }

        return when (config.provider) {
            LlmProvider.OPENAI -> chatOpenAiStream(messages, enableTools, onToken)
            LlmProvider.ANTHROPIC -> chatAnthropicStream(messages, enableTools, onToken)
            LlmProvider.GEMINI -> chatGeminiStream(messages, enableTools, onToken)
            LlmProvider.AG_UI -> chatAgUiStream(messages, onToken)
        }
    }

    private fun chatOpenAiStream(
        messages: List<LlmChatMessage>,
        enableTools: Boolean,
        onToken: (String) -> Unit
    ): LlmResponse {
        val endpoint = config.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val conn = openPost(endpoint).apply {
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            setRequestProperty("Accept", "text/event-stream, application/json")
        }
        val body = JSONObject().apply {
            put("model", config.model.trim())
            put("temperature", 0.2)
            put("stream", true)
            val arr = JSONArray()
            for (m in messages) {
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            put("messages", arr)
            if (enableTools) {
                put("tools", AgentToolSchemas.openAiToolsJson())
                put("tool_choice", "auto")
            }
        }

        val textBuf = StringBuilder()
        val toolAcc = linkedMapOf<Int, MutableToolAccumulator>()
        try {
            writeBody(conn, body.toString())
            val code = conn.responseCode
            if (code !in 200..299) {
                throw LlmException("OpenAI HTTP $code: ${readAll(conn.errorStream).take(500)}")
            }
            val contentType = conn.contentType ?: ""
            if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                return parseOpenAiNonStream(readAll(conn.inputStream), onToken)
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val choices = chunk.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val token = delta.optString("content", "")
                    if (token.isNotEmpty() && !delta.isNull("content")) {
                        textBuf.append(token)
                        onToken(token)
                    }
                    val calls = delta.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (i in 0 until calls.length()) {
                            val tc = calls.optJSONObject(i) ?: continue
                            val idx = tc.optInt("index", i)
                            val acc = toolAcc.getOrPut(idx) { MutableToolAccumulator() }
                            val id = tc.optString("id", "")
                            if (id.isNotBlank()) acc.id = id
                            val fn = tc.optJSONObject("function")
                            if (fn != null) {
                                val name = fn.optString("name", "")
                                if (name.isNotBlank()) acc.name = name
                                val argsPart = fn.optString("arguments", "")
                                if (argsPart.isNotEmpty()) acc.argsJson.append(argsPart)
                            }
                        }
                    }
                }
            }
            val toolCalls = toolAcc.values.mapNotNull { it.toRequest() }
            if (textBuf.isEmpty() && toolCalls.isEmpty()) {
                throw LlmException("LLM response had no content or tool calls")
            }
            return LlmResponse(text = textBuf.toString(), toolCalls = toolCalls)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseOpenAiNonStream(raw: String, onToken: (String) -> Unit): LlmResponse {
        val obj = JSONObject(raw)
        val choices = obj.optJSONArray("choices") ?: throw LlmException("LLM response missing choices")
        if (choices.length() == 0) throw LlmException("LLM response has no choices")
        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: throw LlmException("LLM response missing message")
        val content = if (msg.isNull("content")) "" else msg.optString("content", "")
        if (content.isNotEmpty()) onToken(content)
        val toolCalls = mutableListOf<ToolCallRequest>()
        val calls = msg.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val tc = calls.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name", "").trim()
                if (name.isEmpty()) continue
                val argsRaw = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(argsRaw) }.getOrElse { JSONObject() }
                toolCalls += ToolCallRequest(
                    id = tc.optString("id").ifBlank { "tc-${UUID.randomUUID()}" },
                    name = name,
                    arguments = args
                )
            }
        }
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw LlmException("LLM response had no text content")
        }
        return LlmResponse(text = content, toolCalls = toolCalls)
    }

    private fun chatAnthropicStream(
        messages: List<LlmChatMessage>,
        enableTools: Boolean,
        onToken: (String) -> Unit
    ): LlmResponse {
        val endpoint = config.baseUrl.trim().trimEnd('/') + "/messages"
        val conn = openPost(endpoint).apply {
            setRequestProperty("x-api-key", config.apiKey.trim())
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("Accept", "text/event-stream, application/json")
        }
        val systemText = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val nonSystem = JSONArray()
        for (m in messages.filter { it.role != "system" }) {
            val mappedRole = if (m.role == "assistant") "assistant" else "user"
            nonSystem.put(JSONObject().put("role", mappedRole).put("content", m.content))
        }
        val body = JSONObject().apply {
            put("model", config.model.trim())
            put("max_tokens", 2048)
            put("stream", true)
            if (systemText.isNotBlank()) put("system", systemText)
            put("messages", nonSystem)
            if (enableTools) {
                put("tools", AgentToolSchemas.anthropicToolsJson())
            }
        }

        val textBuf = StringBuilder()
        val toolAcc = linkedMapOf<Int, MutableToolAccumulator>()
        try {
            writeBody(conn, body.toString())
            val code = conn.responseCode
            if (code !in 200..299) {
                throw LlmException("Anthropic HTTP $code: ${readAll(conn.errorStream).take(500)}")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    when (event.optString("type")) {
                        "content_block_start" -> {
                            val idx = event.optInt("index", 0)
                            val block = event.optJSONObject("content_block")
                            if (block?.optString("type") == "tool_use") {
                                toolAcc[idx] = MutableToolAccumulator(
                                    id = block.optString("id", "tc-${UUID.randomUUID()}"),
                                    name = block.optString("name", "")
                                )
                            }
                        }
                        "content_block_delta" -> {
                            val idx = event.optInt("index", 0)
                            val delta = event.optJSONObject("delta") ?: continue
                            when (delta.optString("type")) {
                                "text_delta" -> {
                                    val t = delta.optString("text", "")
                                    if (t.isNotEmpty()) {
                                        textBuf.append(t)
                                        onToken(t)
                                    }
                                }
                                "input_json_delta" -> {
                                    val partial = delta.optString("partial_json", "")
                                    if (partial.isNotEmpty()) {
                                        toolAcc[idx]?.argsJson?.append(partial)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val toolCalls = toolAcc.values.mapNotNull { it.toRequest() }
            if (textBuf.isEmpty() && toolCalls.isEmpty()) {
                throw LlmException("Anthropic response had no content or tool calls")
            }
            return LlmResponse(text = textBuf.toString(), toolCalls = toolCalls)
        } finally {
            conn.disconnect()
        }
    }

    private fun chatGeminiStream(
        messages: List<LlmChatMessage>,
        enableTools: Boolean,
        onToken: (String) -> Unit
    ): LlmResponse {
        val encodedKey = URLEncoder.encode(config.apiKey.trim(), "UTF-8")
        val endpoint = config.baseUrl.trim().trimEnd('/') +
            "/models/${config.model.trim()}:streamGenerateContent?alt=sse&key=$encodedKey"
        val conn = openPost(endpoint).apply {
            setRequestProperty("Accept", "text/event-stream, application/json")
        }
        val systemText = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val contents = JSONArray()
        for (m in messages.filter { it.role != "system" }) {
            val role = if (m.role == "assistant") "model" else "user"
            contents.put(
                JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().put("text", m.content)))
                }
            )
        }
        val body = JSONObject().apply {
            if (systemText.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText)))
                )
            }
            put("contents", contents)
            if (enableTools) {
                put("tools", AgentToolSchemas.geminiToolsJson())
            }
        }

        val textBuf = StringBuilder()
        val toolCalls = mutableListOf<ToolCallRequest>()
        try {
            writeBody(conn, body.toString())
            val code = conn.responseCode
            if (code !in 200..299) {
                throw LlmException("Gemini HTTP $code: ${readAll(conn.errorStream).take(500)}")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val candidates = obj.optJSONArray("candidates") ?: continue
                    if (candidates.length() == 0) continue
                    val parts = candidates.getJSONObject(0)
                        .optJSONObject("content")
                        ?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val t = part.optString("text", "")
                        if (t.isNotEmpty()) {
                            textBuf.append(t)
                            onToken(t)
                        }
                        val fn = part.optJSONObject("functionCall")
                        if (fn != null) {
                            val name = fn.optString("name", "").trim()
                            if (name.isNotEmpty()) {
                                toolCalls += ToolCallRequest(
                                    id = "gemini-${UUID.randomUUID()}",
                                    name = name,
                                    arguments = fn.optJSONObject("args") ?: JSONObject()
                                )
                            }
                        }
                    }
                }
            }
            if (textBuf.isEmpty() && toolCalls.isEmpty()) {
                throw LlmException("Gemini response had no content or tool calls")
            }
            return LlmResponse(text = textBuf.toString(), toolCalls = toolCalls)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Connects to a remote self-hosted OpenBot / AG-UI SSE endpoint as a thin client.
     */
    private fun chatAgUiStream(
        messages: List<LlmChatMessage>,
        onToken: (String) -> Unit
    ): LlmResponse {
        val endpoint = config.baseUrl.trim()
        val conn = openPost(endpoint).apply {
            if (config.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            setRequestProperty("Accept", "text/event-stream")
        }
        val msgArr = JSONArray()
        for ((idx, m) in messages.withIndex()) {
            msgArr.put(
                JSONObject().apply {
                    put("id", "msg-$idx")
                    put("role", m.role)
                    put("content", m.content)
                }
            )
        }
        val body = JSONObject().apply {
            put("threadId", "android-client")
            put("runId", UUID.randomUUID().toString())
            put("messages", msgArr)
        }

        val textBuf = StringBuilder()
        val toolAcc = linkedMapOf<String, MutableToolAccumulator>()
        try {
            writeBody(conn, body.toString())
            val code = conn.responseCode
            if (code !in 200..299) {
                throw LlmException("AG-UI HTTP $code: ${readAll(conn.errorStream).take(500)}")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    when (event.optString("type")) {
                        "TEXT_MESSAGE_CONTENT" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                textBuf.append(delta)
                                onToken(delta)
                            }
                        }
                        "TOOL_CALL_START" -> {
                            val id = event.optString("toolCallId", UUID.randomUUID().toString())
                            val name = event.optString("toolCallName", "")
                            toolAcc[id] = MutableToolAccumulator(id = id, name = name)
                        }
                        "TOOL_CALL_ARGS" -> {
                            val id = event.optString("toolCallId", "")
                            val delta = event.optString("delta", "")
                            toolAcc[id]?.argsJson?.append(delta)
                        }
                        "RUN_ERROR" -> {
                            throw LlmException("AG-UI error: ${event.optString("message", "unknown error")}")
                        }
                    }
                }
            }
            val toolCalls = toolAcc.values.mapNotNull { it.toRequest() }
            return LlmResponse(
                text = textBuf.toString().ifBlank { if (toolCalls.isEmpty()) "(AG-UI run completed)" else "" },
                toolCalls = toolCalls
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun openPost(endpoint: String): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

    private fun writeBody(conn: HttpURLConnection, payload: String) {
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
    }

    private fun readAll(stream: java.io.InputStream?): String = try {
        stream?.let { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        } ?: ""
    } catch (_: Exception) {
        ""
    }

    private class MutableToolAccumulator(
        var id: String = "tc-${UUID.randomUUID()}",
        var name: String = "",
        val argsJson: StringBuilder = StringBuilder()
    ) {
        fun toRequest(): ToolCallRequest? {
            val cleanName = name.trim()
            if (cleanName.isEmpty()) return null
            val raw = argsJson.toString().trim().ifEmpty { "{}" }
            val args = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            return ToolCallRequest(id = id, name = cleanName, arguments = args)
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
