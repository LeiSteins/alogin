package top.steins.autologin.network

import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginStatusParsingTest {

    @Test
    fun recheckLoginStatus_confirmsLoggedInStateFromLoginPageRedirect() = runTest {
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            when (host) {
                "10.21.221.98" -> response(
                    chain = chain,
                    code = 302,
                    headers = mapOf("Location" to "https://lgn.bjut.edu.cn/")
                )
                else -> throw AssertionError("Unexpected host: $host")
            }
        }

        val result = recheckLoginStatus(client)

        assertEquals(LoginRecheckResult.LoggedIn, result)
        assertEquals(listOf("10.21.221.98"), requestedHosts)
    }

    @Test
    fun recheckLoginStatus_confirmsLoggedOutStateFromLoginPage() = runTest {
        val loginPage = "<html><head><title>上网登录页</title></head></html>"
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            when (host) {
                "10.21.221.98" -> response(chain, body = loginPage)
                else -> throw AssertionError("Unexpected host: $host")
            }
        }

        val result = recheckLoginStatus(client)

        assertEquals(LoginRecheckResult.LoggedOut, result)
        assertEquals(listOf("10.21.221.98"), requestedHosts)
    }

    @Test
    fun recheckLoginStatus_usesSecondLoginPageWhenFirstIsUnavailable() = runTest {
        val logoutPage = "<html><head><title>注销页</title></head></html>"
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            when (host) {
                "10.21.221.98" -> throw IOException("unavailable")
                "wlgn.bjut.edu.cn" -> response(chain, body = logoutPage)
                else -> throw AssertionError("Unexpected host: $host")
            }
        }

        val result = recheckLoginStatus(client)

        assertEquals(LoginRecheckResult.LoggedIn, result)
        assertEquals(listOf("10.21.221.98", "wlgn.bjut.edu.cn"), requestedHosts)
    }

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

    private fun clientWithInterceptor(block: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(Interceptor(block))
            .build()

    private fun response(
        chain: Interceptor.Chain,
        code: Int = 200,
        body: String = "",
        headers: Map<String, String> = emptyMap()
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .headers(headers.toHeaders())
        .body(body.toResponseBody())
        .build()
}
