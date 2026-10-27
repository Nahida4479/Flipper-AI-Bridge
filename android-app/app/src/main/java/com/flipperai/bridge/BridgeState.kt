package com.flipperai.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class LogEntry(val question: String, val answer: String, val error: Boolean)

data class BridgeUiState(
    val running: Boolean = false,
    val bleStatus: String = "Off",
    val usbStatus: String = "Off",
    val busy: Boolean = false,
    val log: List<LogEntry> = emptyList()
)

object BridgeState {
    const val MAX_LOG = 15
    private const val MAX_TEXT = 1500

    val state = MutableStateFlow(BridgeUiState())
    private var store: SecureStore? = null

    fun update(block: (BridgeUiState) -> BridgeUiState) = state.update(block)

    @Synchronized
    fun attach(context: Context) {
        if (store != null) return
        val s = SecureStore(context.applicationContext)
        store = s
        val loaded = load(s)
        if (loaded.isNotEmpty()) state.update { it.copy(log = loaded) }
    }

    fun addLog(entry: LogEntry) {
        state.update { it.copy(log = (listOf(entry) + it.log).take(MAX_LOG)) }
        save()
    }

    fun clearLog() {
        state.update { it.copy(log = emptyList()) }
        save()
    }

    private fun load(s: SecureStore): List<LogEntry> = try {
        val array = JSONArray(s.requestLog.ifEmpty { "[]" })
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            LogEntry(o.optString("q"), o.optString("a"), o.optBoolean("e"))
        }.take(MAX_LOG)
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save() {
        val s = store ?: return
        val array = JSONArray()
        for (entry in state.value.log.take(MAX_LOG)) {
            array.put(
                JSONObject()
                    .put("q", entry.question.take(MAX_TEXT))
                    .put("a", entry.answer.take(MAX_TEXT))
                    .put("e", entry.error)
            )
        }
        s.requestLog = array.toString()
    }
}
