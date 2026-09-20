package top.steins.autologin.network

import java.nio.charset.Charset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginStatusParsingTest {

    @Test
    fun isCampusLoginPage_recognizesUtf8Title() {
        val body = "<html><head><title>上网登录页</title></head></html>"

        assertTrue(body.toByteArray(Charsets.UTF_8).isCampusLoginPage())
    }

    @Test
    fun isCampusLoginPage_recognizesGb2312Title() {
        val body = "<HTML><HEAD><TITLE>  上网登录页  </TITLE></HEAD></HTML>"

        assertTrue(body.toByteArray(Charset.forName("GB2312")).isCampusLoginPage())
    }

    @Test
    fun isCampusLoginPage_rejectsOtherPages() {
        val body = "<html><head><title>注销页</title></head></html>"

        assertFalse(body.toByteArray(Charsets.UTF_8).isCampusLoginPage())
    }
}
