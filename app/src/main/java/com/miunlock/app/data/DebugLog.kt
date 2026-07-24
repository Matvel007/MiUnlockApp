package com.miunlock.app.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

data class DebugEvent(val time: Instant, val message: String)

object DebugLog {
    private const val MAX_EVENTS = 200
    private val _events = MutableStateFlow<List<DebugEvent>>(emptyList())
    val events = _events.asStateFlow()

    fun add(message: String) {
        _events.value = (_events.value + DebugEvent(Instant.now(), message)).takeLast(MAX_EVENTS)
        Log.d(TAG, message)
    }

    fun clear() {
        _events.value = emptyList()
        Log.d(TAG, "Журнал очищен")
    }

    private const val TAG = "MiUnlockDebug"
}
