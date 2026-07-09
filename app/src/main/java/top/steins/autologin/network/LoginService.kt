package top.steins.autologin.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class LoginStatus(
    val isLoggedIn: Boolean,
    val uid: String = "",
    val flow: String = "",
    val time: String = "",
    val v4ip: String = "",
    val error: String = ""
)

sealed class LoginResult {
    data object Success : LoginResult()
    data class Failure(val message: String) : LoginResult()
    data class NetworkError(val message: String) : LoginResult()
}

private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .followRedirects(false)
    .addInterceptor(HttpLogInterceptor())
    .build()

suspend fun login(username: String, password: String, wlanUserIp: String): LoginResult = withContext(Dispatchers.IO) {
    try {
        when (detectLoginPortal()) {
            LoginPortal.Wlgn -> loginWithWlgn(username, password)
            LoginPortal.Eportal -> loginWithEportal(username, password, wlanUserIp)
        }
    } catch (e: Exception) {
        LoginResult.NetworkError("网络错误：${e.message ?: "未知错误"}")
    }
}

private enum class LoginPortal {
    Wlgn,
    Eportal
}

private fun detectLoginPortal(): LoginPortal {
    val request = Request.Builder()
        .url("http://10.21.221.98/")
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .build()

    okHttpClient.newCall(request).execute().use { response ->
        return if (response.code in 300..399) {
            LoginPortal.Wlgn
        } else {
            LoginPortal.Eportal
        }
    }
}

private fun loginWithWlgn(username: String, password: String): LoginResult {
    val params = listOf(
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

    val request = Request.Builder()
        .url("https://$WLGN_HOST/drcom/login?${params.toQueryString()}")
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Referer", "https://$WLGN_HOST/a79.htm")
        .header("Accept", "*/*")
        .build()

    return executeLoginRequest(request)
}

private fun loginWithEportal(username: String, password: String, wlanUserIp: String): LoginResult {
    val account = if (username.contains("@")) username else "$username@campus"
    val params = listOf(
        "callback" to "dr1003",
        "login_method" to "1",
        "user_account" to account,
        "user_password" to password,
        "wlan_user_ip" to wlanUserIp.takeIf { it.isUsableIpv4() }.orEmpty(),
        "wlan_user_ipv6" to "",
        "wlan_user_mac" to "000000000000",
        "wlan_ac_ip" to "",
        "wlan_ac_name" to "",
        "jsVersion" to "4.2.1",
        "terminal_type" to "1",
        "lang" to "zh-cn",
        "v" to "9842",
        "lang" to "zh"
    )

    val request = Request.Builder()
        .url("http://10.21.221.98:801/eportal/portal/login?${params.toQueryString()}")
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .header("Referer", "http://10.21.221.98/")
        .build()

    return executeLoginRequest(request)
}

private fun executeLoginRequest(request: Request): LoginResult {
    okHttpClient.newCall(request).execute().use { response ->
        val responseText = response.body?.string() ?: ""

        return when {
            responseText.contains("\"result\":1") ||
                    responseText.contains("\"result\": 1") ||
                    responseText.contains("认证成功") ||
                    responseText.contains("登录成功") ->
                LoginResult.Success
            responseText.contains("\"result\":0") ||
                    responseText.contains("\"result\": 0") ||
                    responseText.contains("认证失败") ||
                    responseText.contains("登录失败") ->
                LoginResult.Failure("登录失败，请检查用户名和密码")
            else ->
                LoginResult.Failure("服务器返回未知响应")
        }
    }
}

private fun List<Pair<String, String>>.toQueryString(): String =
    joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }

private fun String.isUsableIpv4(): Boolean =
    matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

suspend fun checkLoginStatus(): LoginStatus = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("https://lgn.bjut.edu.cn/")
            .get()
            .header("User-Agent", USER_AGENT)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.code in 200..399) {
                // OkHttp 默认 UTF-8，服务器返回 GB2312，需手动解码
                val body = try {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    bytes.toString(Charset.forName("GB2312"))
                } catch (e: Exception) {
                    response.body?.string() ?: ""
                }

                // 检查是否为注销页（已登录）
                val isLoggedIn = body.contains("Dr.COMWebLoginID_1.htm")
                        || body.contains("<title>注销页</title>")

                if (isLoggedIn) {
                    val uid = Regex("uid\\s*=\\s*'([^']*)'").find(body)?.groupValues?.get(1)?.trim() ?: ""
                    val flow = Regex("flow\\s*=\\s*'([^']*)'").find(body)?.groupValues?.get(1)?.trim() ?: ""
                    val time = Regex("time\\s*=\\s*'([^']*)'").find(body)?.groupValues?.get(1)?.trim() ?: ""
                    val v4ip = Regex("v4ip\\s*=\\s*'([^']*)'").find(body)?.groupValues?.get(1)?.trim() ?: ""

                    LoginStatus(isLoggedIn = true, uid = uid, flow = flow, time = time, v4ip = v4ip)
                } else {
                    LoginStatus(isLoggedIn = false)
                }
            } else {
                LoginStatus(isLoggedIn = false, error = "HTTP ${response.code}")
            }
        }
    } catch (e: Exception) {
        LoginStatus(isLoggedIn = false, error = e.message ?: "未知错误")
    }
}

private const val WLGN_HOST = "wlgn.bjut.edu.cn"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0"
