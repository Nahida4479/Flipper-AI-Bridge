package com.flipperclaude.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val role: String, val text: String)

class ClaudeException(message: String) : Exception(message)

class ClaudeClient(private val store: SecureStore) {

    fun ask(history: List<ChatTurn>, lang: String): String {
        val key = store.apiKey
        if (key.isEmpty()) throw ClaudeException("API key not set in phone app")
        val messages = JSONArray()
        for (turn in normalize(history)) {
            messages.put(
                JSONObject().put("role", turn.role).put("content", turn.text)
            )
        }
        if (messages.length() == 0) throw ClaudeException("Empty question")
        val body = JSONObject()
            .put("model", store.model)
            .put("max_tokens", MAX_TOKENS)
            .put("output_config", JSONObject().put("effort", store.effort))
            .put("system", systemPrompt(lang))
            .put("messages", messages)

        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ClaudeException(errorMessage(code, text))
            return extractText(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun normalize(history: List<ChatTurn>): List<ChatTurn> {
        val out = ArrayList<ChatTurn>()
        for (turn in history) {
            if (turn.text.isBlank()) continue
            if (out.isEmpty() && turn.role != "user") continue
            if (out.isNotEmpty() && out.last().role == turn.role) {
                val last = out.removeAt(out.size - 1)
                out.add(ChatTurn(turn.role, last.text + "\n" + turn.text))
            } else {
                out.add(turn)
            }
        }
        return out
    }

    private fun systemPrompt(lang: String): String {
        val base = "You answer on the tiny 128x64 monochrome screen of a Flipper Zero. " +
            "Be extremely concise: at most about 300 characters. Plain text only: no markdown, " +
            "no lists with symbols, no emoji. Reply in Polish if the user writes in Polish, " +
            "in English if the user writes in English, and in English for any other language. " +
            "Hint language setting on the device: $lang."
        val extra = store.extraPrompt.trim()
        return if (extra.isEmpty()) base else "$base\n$extra"
    }

    private fun extractText(json: String): String {
        val root = JSONObject(json)
        if (root.optString("stop_reason") == "refusal") {
            throw ClaudeException("Request declined by safety filter")
        }
        val content = root.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().trim()
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: ""
        } catch (e: Exception) {
            ""
        }
        val short = detail.take(120)
        return if (short.isEmpty()) "API error $code" else "API $code: $short"
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MAX_TOKENS = 4096
    }
}
