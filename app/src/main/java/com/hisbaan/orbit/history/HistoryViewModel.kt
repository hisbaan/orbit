package com.hisbaan.orbit.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisbaan.orbit.OrbitApp
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(
    /** Decrypted and on screen. False until the user authenticates, and again once Orbit leaves the screen. */
    val unlocked: Boolean = false,
    val loading: Boolean = false,
    val conversations: List<StoredConversation> = emptyList(),
    /** The authentication ran out before every conversation was read. */
    val incomplete: Boolean = false,
    /** The conversation open in detail. */
    val selected: String? = null,
    val message: String? = null,
)

/**
 * The decrypted history, held only while it's on screen: [lock] drops it, and reading it again
 * takes another authentication.
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as OrbitApp).history
    private val io = (app as OrbitApp).historyIo
    private var loading: Job? = null

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    /** Reads the history; call right after the user authenticates, while the keys are unlocked. */
    fun load() {
        loading?.cancel()
        _state.value = HistoryUiState(loading = true)
        loading = viewModelScope.launch {
            _state.value = try {
                val loaded = withContext(io) { store.load() }
                HistoryUiState(unlocked = true, conversations = loaded.conversations, incomplete = loaded.incomplete)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HistoryLockedException) {
                HistoryUiState(message = "Orbit couldn't confirm it was you. Try again.")
            } catch (e: HistoryKeysLostException) {
                HistoryUiState(unlocked = true, message = "The history was cleared because the screen lock changed.")
            } catch (e: Exception) {
                EventLog.log("history", "Couldn't read the history: ${e::class.simpleName}")
                HistoryUiState(message = "Couldn't read the history: ${e.message}")
            }
        }
    }

    /** Forgets everything decrypted. */
    fun lock() {
        loading?.cancel()
        _state.value = HistoryUiState()
    }

    fun select(id: String?) = _state.update { it.copy(selected = id) }

    fun delete(id: String) {
        _state.update { s -> s.copy(conversations = s.conversations.filter { it.id != id }, selected = null) }
        viewModelScope.launch { withContext(io) { store.delete(id) } }
    }
}
