package top.steins.autologin.network

import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

class HttpLogInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val timestamp = System.currentTimeMillis()

        // 日志采集绝不能改变实际发出的请求或业务侧读取到的响应。
        val requestBodyString = captureRequestBody(originalRequest)
        val loggedUrl = redactSensitiveQueryParams(originalRequest.url)

        return try {
            val response = chain.proceed(originalRequest)
            val responseBodyString = captureResponseBody(response)

            HttpLogStorage.add(
                HttpLogEntry(
                    id = System.nanoTime(),
                    method = originalRequest.method,
                    url = loggedUrl,
                    statusCode = response.code,
                    timestamp = timestamp,
                    requestBody = requestBodyString,
                    responseBody = responseBodyString,
                    error = null
                )
            )

            response
        } catch (e: IOException) {
            HttpLogStorage.add(
                HttpLogEntry(
                    id = System.nanoTime(),
                    method = originalRequest.method,
                    url = loggedUrl,
                    statusCode = 0,
                    timestamp = timestamp,
                    requestBody = requestBodyString,
                    responseBody = "",
                    error = e.message ?: "未知网络错误"
                )
            )
            throw e
        }
    }

    private fun captureRequestBody(request: Request): String {
        val body = request.body ?: return ""
        if (body.isDuplex() || body.isOneShot()) {
            return "(请求体不可重复读取，未记录)"
        }

        return try {
            val contentLength = body.contentLength()
            if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
                return "(请求体过大或长度未知，未记录)"
            }

            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        } catch (e: Exception) {
            "(无法读取请求体: ${e.message})"
        }
    }

    private fun captureResponseBody(response: Response): String = try {
        val bytes = response.peekBody(MAX_RESPONSE_BODY_BYTES + 1).bytes()
        val text = bytes.toString(StandardCharsets.UTF_8)
        if (bytes.size > MAX_RESPONSE_BODY_BYTES) {
            "$text\n$TRUNCATION_SUFFIX"
        } else {
            text
        }
    } catch (e: Exception) {
        "(无法读取响应体: ${e.message})"
    }

    /**
     * 脱敏 URL 查询串中的敏感参数。仅改写日志副本，实际请求保持原样。
     */
    private fun redactSensitiveQueryParams(url: HttpUrl): String {
        val hasSensitiveParam = (0 until url.querySize).any { index ->
            url.queryParameterName(index)?.lowercase(Locale.ROOT) in SENSITIVE_QUERY_PARAMS
        }
        if (!hasSensitiveParam) return url.toString()

        val builder = url.newBuilder().query(null)
        for (index in 0 until url.querySize) {
            val name = url.queryParameterName(index)
            val value = if (name?.lowercase(Locale.ROOT) in SENSITIVE_QUERY_PARAMS) {
                REDACTED_VALUE
            } else {
                url.queryParameterValue(index)
            }
            builder.addQueryParameter(name, value)
        }
        return builder.build().toString()
    }

    companion object {
        private const val MAX_REQUEST_BODY_BYTES = 2_000L
        private const val MAX_RESPONSE_BODY_BYTES = 64L * 1024
        private const val REDACTED_VALUE = "***"
        private val TRUNCATION_SUFFIX =
            "…(响应体超过 ${MAX_RESPONSE_BODY_BYTES / 1024} KiB，已截断)"

        // WLGN 使用 DDDDD/upass，Eportal 使用 user_account/user_password，
        // 自助服务 SSO 使用 XOR 编码的 user_account 与 wlan_user_ip。
        private val SENSITIVE_QUERY_PARAMS = setOf(
            "ddddd",
            "upass",
            "user_account",
            "user_password",
            "wlan_user_ip"
        )
    }
}
