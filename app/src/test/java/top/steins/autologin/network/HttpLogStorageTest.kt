package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpLogStorageTest {

    @Test
    fun logAccountInfoRefresh_recordsReasonAsRefreshEvent() {
        HttpLogStorage.clear()
        try {
            HttpLogStorage.logAccountInfoRefresh("用户点击刷新账号信息")

            val entry = HttpLogStorage.logs.value.single()
            assertEquals(HttpLogEntryType.ACCOUNT_INFO_REFRESH, entry.type)
            assertEquals("用户点击刷新账号信息", entry.eventMessage)
            assertEquals("REFRESH", entry.method)
        } finally {
            HttpLogStorage.clear()
        }
    }

    @Test
    fun add_keepsOnlyTheLatestTwoHundredEntries() {
        HttpLogStorage.clear()
        try {
            repeat(250) { index ->
                HttpLogStorage.add(
                    HttpLogEntry(
                        id = index.toLong(),
                        method = "GET",
                        url = "http://example.com/$index",
                        statusCode = 200,
                        timestamp = index.toLong(),
                        requestBody = "",
                        responseBody = "",
                        error = null
                    )
                )
            }

            val logs = HttpLogStorage.logs.value
            assertEquals(200, logs.size)
            assertEquals(50L, logs.first().id)
            assertEquals(249L, logs.last().id)
        } finally {
            HttpLogStorage.clear()
        }
    }
}
