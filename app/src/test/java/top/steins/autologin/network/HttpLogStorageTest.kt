package top.steins.autologin.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HttpLogStorageTest {

    @Before
    fun setUp() {
        HttpLogStorage.setEnabled(true)
    }

    @After
    fun tearDown() {
        HttpLogStorage.setEnabled(false)
    }

    @Test
    fun add_whenDisabled_doesNotRecordEntry() {
        HttpLogStorage.setEnabled(false)

        HttpLogStorage.add(testEntry(1))

        assertEquals(emptyList<HttpLogEntry>(), HttpLogStorage.logs.value)
    }

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
                HttpLogStorage.add(testEntry(index.toLong()))
            }

            val logs = HttpLogStorage.logs.value
            assertEquals(200, logs.size)
            assertEquals(50L, logs.first().id)
            assertEquals(249L, logs.last().id)
        } finally {
            HttpLogStorage.clear()
        }
    }

    private fun testEntry(id: Long) = HttpLogEntry(
        id = id,
        method = "GET",
        url = "http://example.com/$id",
        statusCode = 200,
        timestamp = id,
        requestBody = "",
        responseBody = "",
        error = null
    )
}
