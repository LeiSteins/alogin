package top.steins.autologin.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HttpLogEntryType {
    HTTP_REQUEST,
    ACCOUNT_INFO_REFRESH
}

data class HttpLogEntry(
    val id: Long,
    val method: String,
    val url: String,
    val statusCode: Int,
    val timestamp: Long,
    val requestBody: String,
    val responseBody: String,
    val error: String?,
    val type: HttpLogEntryType = HttpLogEntryType.HTTP_REQUEST,
    val eventMessage: String = ""
)

object HttpLogStorage {
    private val _logs = MutableStateFlow<List<HttpLogEntry>>(emptyList())
    val logs: StateFlow<List<HttpLogEntry>> = _logs.asStateFlow()

    fun add(entry: HttpLogEntry) {
        synchronized(this) {
            _logs.value = _logs.value + entry
        }
    }

    fun logAccountInfoRefresh(reason: String) {
        add(
            HttpLogEntry(
                id = System.nanoTime(),
                method = "REFRESH",
                url = "app://account-info/refresh",
                statusCode = 0,
                timestamp = System.currentTimeMillis(),
                requestBody = "",
                responseBody = "",
                error = null,
                type = HttpLogEntryType.ACCOUNT_INFO_REFRESH,
                eventMessage = reason
            )
        )
    }

    fun clear() {
        synchronized(this) {
            _logs.value = emptyList()
        }
    }
}
