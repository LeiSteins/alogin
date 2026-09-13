package top.steins.autologin.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import top.steins.autologin.R
import java.io.IOException
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

private val loginClientLock = Any()

@Volatile
private var sharedLoginClient: OkHttpClient? = null

private fun okHttpClient(context: Context): OkHttpClient =
    sharedLoginClient ?: synchronized(loginClientLock) {
        sharedLoginClient ?: OkHttpClient.Builder()
            .allowCampusCertificateErrors()
            .connectTimeout(CAMPUS_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CAMPUS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CAMPUS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor(HttpLogInterceptor(httpLogMessageProvider(context.applicationContext)))
            .build()
            .also { sharedLoginClient = it }
    }

suspend fun login(
    context: Context,
    username: String,
    password: String,
    wlanUserIp: String
): LoginResult =
    withContext(Dispatchers.IO) {
        try {
            when (detectLoginPortal(context)) {
                LoginPortal.Wlgn -> loginWithWlgn(context, username, password)
                LoginPortal.Eportal ->
                    loginWithEportal(context, username, password, wlanUserIp)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoginResult.NetworkError(
                context.getString(
                    R.string.login_network_error,
                    error.message ?: context.getString(R.string.error_unknown)
                )
            )
        }
    }

private enum class LoginPortal {
    Wlgn,
    Eportal
}

private suspend fun detectLoginPortal(context: Context): LoginPortal {
    val request = Request.Builder()
        .url("http://10.21.221.98/")
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .build()

    okHttpClient(context).executeCancellable(request).use { response ->
        when {
            response.code in 300..399 -> {
                val location = response.header("Location").orEmpty()
                return if (location.contains("eportal", ignoreCase = true)) {
                    LoginPortal.Eportal
                } else {
                    LoginPortal.Wlgn
                }
            }

            response.isSuccessful -> {
                val responseHint = response.peekBody(PORTAL_HINT_BODY_BYTES).string()
                return when {
                    responseHint.contains("wlgn", ignoreCase = true) -> LoginPortal.Wlgn
                    responseHint.contains("eportal", ignoreCase = true) -> LoginPortal.Eportal
                    // 校园网当前的成功探测页未携带明确标记时，保持既有 Eportal 兼容路径。
                    else -> LoginPortal.Eportal
                }
            }

            else -> throw IOException(
                context.getString(R.string.login_portal_detection_failed, response.code)
            )
        }
    }
}

private suspend fun loginWithWlgn(
    context: Context,
    username: String,
    password: String
): LoginResult {
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
        .url(buildLoginUrl("https", WLGN_HOST, null, "drcom/login", params))
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Referer", "https://$WLGN_HOST/a79.htm")
        .header("Accept", "*/*")
        .build()

    return executeLoginRequest(context, request)
}

private suspend fun loginWithEportal(
    context: Context,
    username: String,
    password: String,
    wlanUserIp: String
): LoginResult {
    if (!wlanUserIp.isUsableIpv4()) {
        return LoginResult.Failure(context.getString(R.string.login_no_valid_campus_ip))
    }

    val account = if (username.contains("@")) username else "$username@campus"
    val params = listOf(
        "callback" to "dr1003",
        "login_method" to "1",
        "user_account" to account,
        "user_password" to password,
        "wlan_user_ip" to wlanUserIp,
        "wlan_user_ipv6" to "",
        "wlan_user_mac" to "000000000000",
        "wlan_ac_ip" to "",
        "wlan_ac_name" to "",
        "jsVersion" to "4.2.1",
        "terminal_type" to "1",
        "lang" to "zh",
        "v" to "9842"
    )

    val request = Request.Builder()
        .url(buildLoginUrl("http", EPORTAL_HOST, EPORTAL_PORT, "eportal/portal/login", params))
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .header("Referer", "http://$EPORTAL_HOST/")
        .build()

    return executeLoginRequest(context, request)
}

private fun buildLoginUrl(
    scheme: String,
    host: String,
    port: Int?,
    path: String,
    params: List<Pair<String, String>>
): HttpUrl = HttpUrl.Builder()
    .scheme(scheme)
    .host(host)
    .apply { port?.let { selectedPort -> this.port(selectedPort) } }
    .addPathSegments(path)
    .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
    .build()

private suspend fun executeLoginRequest(context: Context, request: Request): LoginResult {
    okHttpClient(context).executeCancellable(request).use { response ->
        if (!response.isSuccessful) {
            return LoginResult.Failure(
                context.getString(R.string.login_service_http_error, response.code)
            )
        }
        return when (val parsed = LoginResponseParser.parse(response.body?.string().orEmpty())) {
            LoginParseResult.Success -> LoginResult.Success
            is LoginParseResult.Failure -> LoginResult.Failure(
                loginFailureMessage(context, parsed.serverMessage)
            )
            LoginParseResult.Unknown -> LoginResult.Failure(
                context.getString(R.string.login_unknown_response)
            )
        }
    }
}

private fun loginFailureMessage(context: Context, serverMessage: String?): String = when {
    serverMessage.equals("ldap auth error", ignoreCase = true) ->
        context.getString(R.string.login_ldap_error)

    serverMessage.isNullOrBlank() -> context.getString(R.string.login_failed_default)
    else -> context.getString(R.string.login_failed_with_reason, serverMessage)
}

suspend fun checkLoginStatus(context: Context): LoginStatus = withContext(Dispatchers.IO) {
    try {
        if (!awaitDefaultNetworkDnsReady(context)) {
            return@withContext LoginStatus(
                isLoggedIn = false,
                error = context.getString(R.string.status_dns_not_ready)
            )
        }
        retryAfterDnsFailure {
            fetchLoginStatus(context)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        LoginStatus(
            isLoggedIn = false,
            error = error.message ?: context.getString(R.string.error_unknown)
        )
    }
}

private suspend fun fetchLoginStatus(context: Context): LoginStatus {
    val request = Request.Builder()
        .url("https://lgn.bjut.edu.cn/")
        .get()
        .header("User-Agent", USER_AGENT)
        .build()

    okHttpClient(context).executeCancellable(request).use { response ->
        if (response.code !in 200..399) {
            return LoginStatus(
                isLoggedIn = false,
                error = context.getString(R.string.status_http_code, response.code)
            )
        }

        // 服务器返回 GB2312；日志拦截器只查看副本，不会改变这里的原始字节。
        val body = response.body?.bytes()
            ?.toString(Charset.forName("GB2312"))
            .orEmpty()
        val isLoggedIn = body.contains("Dr.COMWebLoginID_1.htm") ||
                body.contains("<title>注销页</title>")

        return if (isLoggedIn) {
            LoginStatus(
                isLoggedIn = true,
                uid = body.extractPageVariable("uid"),
                flow = body.extractPageVariable("flow"),
                time = body.extractPageVariable("time"),
                v4ip = body.extractPageVariable("v4ip")
            )
        } else {
            LoginStatus(isLoggedIn = false)
        }
    }
}

private fun String.extractPageVariable(name: String): String =
    Regex("$name\\s*=\\s*'([^']*)'").find(this)?.groupValues?.get(1)?.trim().orEmpty()

private const val WLGN_HOST = "wlgn.bjut.edu.cn"
private const val EPORTAL_HOST = "10.21.221.98"
private const val EPORTAL_PORT = 801
private const val PORTAL_HINT_BODY_BYTES = 8_192L
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0"
