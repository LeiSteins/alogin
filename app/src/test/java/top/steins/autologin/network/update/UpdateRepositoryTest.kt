package top.steins.autologin.network.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRepositoryTest {

    @Test
    fun parseLatestUpdate_selectsHighestSemanticVersion() {
        val html = """
            <a href="../">../</a>
            <a href="alogin-v0.0.9.apk">alogin-v0.0.9.apk</a>
            <a href="alogin-v0.10.0.apk">alogin-v0.10.0.apk</a>
            <a href="alogin-v0.2.0.apk">alogin-v0.2.0.apk</a>
        """.trimIndent()

        val update = parseLatestUpdate(html)

        assertEquals("0.10.0", update?.version)
        assertEquals("alogin-v0.10.0.apk", update?.fileName)
        assertEquals(
            "https://aloginupdate.steins.top/alogin-v0.10.0.apk",
            update?.downloadUrl
        )
    }

    @Test
    fun parseLatestUpdate_ignoresUntrustedOrInvalidLinks() {
        val html = """
            <a href="https://example.com/alogin-v9.0.0.apk">external</a>
            <a href="../alogin-v8.0.0.apk">parent</a>
            <a href="other-v7.0.0.apk">other app</a>
            <a href="alogin-v1.2.apk">invalid version</a>
        """.trimIndent()

        assertNull(parseLatestUpdate(html))
    }

    @Test
    fun parseLatestUpdate_supportsNginxSingleQuotedLinks() {
        val update = parseLatestUpdate("<a href='alogin-v1.2.3.apk'>download</a>")

        assertEquals("1.2.3", update?.version)
    }
}
