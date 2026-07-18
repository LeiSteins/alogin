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
}
