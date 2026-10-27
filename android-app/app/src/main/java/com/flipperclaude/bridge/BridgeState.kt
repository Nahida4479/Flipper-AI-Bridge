package com.flipperclaude.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class LogEntry(val question: String, val answer: String, val error: Boolean)

data class BridgeUiState(
    val running: Boolean = false,
    val bleStatus: String = "Off",
    val usbStatus: String = "Off",
    val busy: Boolean = false,
    val log: List<LogEntry> = emptyList()
)

object BridgeState {
    val state = MutableStateFlow(BridgeUiState())

    fun update(block: (BridgeUiState) -> BridgeUiState) = state.update(block)

    fun addLog(entry: LogEntry) = state.update {
        it.copy(log = (listOf(entry) + it.log).take(30))
    }

    fun clearLog() = state.update { it.copy(log = emptyList()) }
}
