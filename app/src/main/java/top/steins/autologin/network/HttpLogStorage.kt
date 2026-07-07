package top.steins.autologin.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HttpLogEntry(
    val id: Long,
    val method: String,
    val url: String,
    val statusCode: Int,
    val timestamp: Long,
    val requestBody: String,
    val responseBody: String,
    val error: String?
)

object HttpLogStorage {
    private val _logs = MutableStateFlow<List<HttpLogEntry>>(emptyList())
    val logs: StateFlow<List<HttpLogEntry>> = _logs.asStateFlow()

    fun add(entry: HttpLogEntry) {
        synchronized(this) {
            _logs.value = _logs.value + entry
        }
    }

    fun clear() {
        synchronized(this) {
            _logs.value = emptyList()
        }
    }
}
