package com.example.edgeaiagent

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.edgeaiagent.tutor.TutorAssistant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Loading : UiState
    data class Ready(val backend: String, val initMs: Long) : UiState
    data class Error(val message: String) : UiState
    data class ModelMissing(val pushCommand: String) : UiState
}

data class ChatMessage(val fromUser: Boolean, val text: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val assistant: Assistant = TutorAssistant(app)

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    private val _steps = MutableStateFlow<List<String>>(emptyList())
    val steps: StateFlow<List<String>> = _steps

    private val _replyStats = MutableStateFlow("")
    val replyStats: StateFlow<String> = _replyStats

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val backend = assistant.start()
                _state.value = UiState.Ready(backend, SystemClock.elapsedRealtime() - t0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelMissingException) {
                _state.value = UiState.ModelMissing(e.pushCommand)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank() || _state.value !is UiState.Ready || _generating.value) return
        _generating.value = true
        _replyStats.value = ""
        _steps.value = emptyList()
        _messages.update { it + ChatMessage(true, text) }

        viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "send: $text")
            val t0 = SystemClock.elapsedRealtime()
            val answer = try {
                assistant.chat(text, ::addStep)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "error", e)
                val reason = e.message?.lineSequence()?.firstOrNull().orEmpty().take(120)
                addStep("error: ${e::class.simpleName}: $reason")
                "Sorry, something went wrong (${e::class.simpleName}): $reason"
            }
            val totalMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "done: ${answer.length} chars in $totalMs ms")
            _replyStats.value = "total $totalMs ms · ${answer.length} chars"
            _messages.update { it + ChatMessage(false, answer) }
            _generating.value = false
        }
    }

    private fun addStep(line: String) {
        Log.i(TAG, line)
        _steps.update { it + line }
    }

    private fun fail(e: Exception) {
        Log.e(TAG, "error", e)
        _state.value = UiState.Error("${e::class.simpleName}: ${e.message}")
    }

    override fun onCleared() {
        assistant.close()
    }
}
