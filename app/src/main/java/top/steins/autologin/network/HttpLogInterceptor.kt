package top.steins.autologin.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

class HttpLogInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val timestamp = System.currentTimeMillis()

        // 捕获请求体并重建请求
        val (requestBodyString, requestForChain) = captureRequestBody(originalRequest)

        return try {
            val response = chain.proceed(requestForChain)
            val responseBodyString = try {
                response.body?.string()?.take(MAX_BODY_LENGTH) ?: ""
            } catch (e: Exception) {
                "(无法读取响应体: ${e.message})"
            }

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

            // 重建响应体，使调用方仍能读取
            val contentType = response.body?.contentType()
            response.newBuilder()
                .body(responseBodyString.toResponseBody(contentType))
                .build()
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

    private fun captureRequestBody(request: Request): Pair<String, Request> {
        val body = request.body ?: return "" to request
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val bodyString = buffer.readUtf8().take(MAX_BODY_LENGTH)
            val clonedBody = buffer.clone().readByteString()
                .toRequestBody(body.contentType())
            val newRequest = request.newBuilder()
                .method(request.method, clonedBody)
                .build()
            bodyString to newRequest
        } catch (e: Exception) {
            "(无法读取请求体: ${e.message})" to request
        }
    }

    companion object {
        private const val MAX_BODY_LENGTH = 2000
    }
}
