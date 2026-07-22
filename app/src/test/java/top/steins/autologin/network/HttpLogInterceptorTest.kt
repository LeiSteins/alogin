package top.steins.autologin.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HttpLogInterceptorTest {

    @Test
    fun interceptor_keepsFullRequestAndResponseAvailableToBusinessCode() {
        val requestBody = "request=" + "x".repeat(128)
        val responseBody = "response=" + "y".repeat(4_096)
        val receivedRequestBody = AtomicReference<String>()
        val serverFailure = AtomicReference<Throwable?>()

        ServerSocket(0).use { server ->
            val serverThread = thread(start = true) {
                try {
                    server.accept().use { socket ->
                        val (headers, body) = socket.getInputStream().readHttpRequest()
                        val contentLength = Regex("""Content-Length: (\d+)""", RegexOption.IGNORE_CASE)
                            .find(headers)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                            ?: 0
                        receivedRequestBody.set(body.copyOf(contentLength).toString(StandardCharsets.UTF_8))

                        val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                        socket.getOutputStream().use { output ->
                            output.write(
                                (
                                        "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                                "Content-Length: ${responseBytes.size}\r\n" +
                                                "Connection: close\r\n\r\n"
                                        ).toByteArray(StandardCharsets.UTF_8)
                            )
                            output.write(responseBytes)
                        }
                    }
                } catch (error: Throwable) {
                    serverFailure.set(error)
                }
            }

            HttpLogStorage.clear()
            try {
                val client = OkHttpClient.Builder()
                    .addInterceptor(HttpLogInterceptor())
                    .build()
                val response = client.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:${server.localPort}/login")
                        .post(requestBody.toRequestBody("text/plain".toMediaType()))
                        .build()
                ).execute()

                response.use {
                    assertEquals(responseBody, it.body?.string())
                }
                serverThread.join()

                assertNull(serverFailure.get())
                assertEquals(requestBody, receivedRequestBody.get())
                val log = HttpLogStorage.logs.value.single()
                assertEquals(requestBody, log.requestBody)
                assertEquals(responseBody, log.responseBody)
            } finally {
                HttpLogStorage.clear()
            }
        }
    }
}

private fun java.io.InputStream.readHttpRequest(): Pair<String, ByteArray> {
    val headerBytes = ByteArrayOutputStream()
    var previous = 0
    var previousPrevious = 0
    var previousPreviousPrevious = 0
    while (true) {
        val next = read()
        check(next >= 0) { "连接在 HTTP 头结束前关闭" }
        headerBytes.write(next)
        if (previousPreviousPrevious == '\r'.code && previousPrevious == '\n'.code &&
            previous == '\r'.code && next == '\n'.code
        ) {
            break
        }
        previousPreviousPrevious = previousPrevious
        previousPrevious = previous
        previous = next
    }

    val headers = headerBytes.toString(StandardCharsets.UTF_8)
    val contentLength = Regex("""Content-Length: (\d+)""", RegexOption.IGNORE_CASE)
        .find(headers)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: 0
    return headers to readNBytes(contentLength)
}
