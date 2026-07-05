package top.steins.autologin.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class LoginResult {
    data object Success : LoginResult()
    data class Failure(val message: String) : LoginResult()
    data class NetworkError(val message: String) : LoginResult()
}

suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
    try {
        val params = mapOf(
            "callback" to "dr1003",
            "DDDDD" to username,
            "upass" to password,
            "0MKKey" to "123456",
            "R1" to "0",
            "R2" to "",
            "R3" to "0",
            "R6" to "0",
            "para" to "00",
            "v6ip" to "",
            "terminal_type" to "1",
            "lang" to "zh-cn",
            "jsVersion" to "4.1",
            "v" to "3050"
        )

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val url = URL("https://wlgn.bjut.edu.cn/drcom/login?$queryString")

        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0")
            setRequestProperty("Referer", "https://wlgn.bjut.edu.cn/a79.htm")
            setRequestProperty("Accept", "*/*")
        }

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
            connection.disconnect()

            when {
                responseText.contains("\"result\":1") || responseText.contains("\"result\": 1") ->
                    LoginResult.Success
                responseText.contains("\"result\":0") || responseText.contains("\"result\": 0") ->
                    LoginResult.Failure("登录失败，请检查用户名和密码")
                else ->
                    LoginResult.Failure("服务器返回未知响应")
            }
        } else {
            connection.disconnect()
            LoginResult.Failure("请求失败 (HTTP $responseCode)")
        }
    } catch (e: Exception) {
        LoginResult.NetworkError("网络错误：${e.message ?: "未知错误"}")
    }
}
