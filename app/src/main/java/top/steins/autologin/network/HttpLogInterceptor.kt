package top.steins.autologin.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException

class HttpLogInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val timestamp = System.currentTimeMillis()

        // 日志采集绝不能改变实际发出的请求或业务侧读取到的响应。
        val requestBodyString = captureRequestBody(originalRequest)

        return try {
            val response = chain.proceed(originalRequest)
            val responseBodyString = captureResponseBody(response)

            HttpLogStorage.add(
                HttpLogEntry(
                    id = System.nanoTime(),
                    method = originalRequest.method,
                    url = originalRequest.url.toString(),
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
                    url = originalRequest.url.toString(),
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
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
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
        response.peekBody(MAX_BODY_BYTES).string()
    } catch (e: Exception) {
        "(无法读取响应体: ${e.message})"
    }

    companion object {
        private const val MAX_BODY_BYTES = 2_000L
    }
}
