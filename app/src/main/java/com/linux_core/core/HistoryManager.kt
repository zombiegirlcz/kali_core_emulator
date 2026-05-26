package com.linux_core.core

import android.content.Context
import android.content.SharedPreferences

class HistoryManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("terminal_history", Context.MODE_PRIVATE)
    private val _history = mutableListOf<String>()

    val history: List<String> get() = _history

    init {
        val savedHistory = prefs.getString("commands_list", "") ?: ""
        if (savedHistory.isNotEmpty()) {
            _history.addAll(savedHistory.split("\n"))
        }
    }

    fun addCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        // Move to top if exists, or add to top
        _history.remove(trimmed)
        _history.add(0, trimmed)

        // Limit history size
        if (_history.size > 100) {
            _history.removeAt(_history.size - 1)
        }

        saveHistory()
    }

    fun getSuggestions(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return _history.filter { it.startsWith(input, ignoreCase = true) && it != input }.take(5)
    }

    private fun saveHistory() {
        prefs.edit().putString("commands_list", _history.joinToString("\n")).apply()
    }
}
