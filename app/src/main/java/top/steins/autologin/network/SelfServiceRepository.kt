package top.steins.autologin.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AccountDevice(
    val macAddress: String,
    val status: String,
    val ipAddress: String,
    val isOnline: Boolean?
)

data class AccountOverview(
    val username: String,
    val usedFlowMb: String,
    val remainingFlowMb: String,
    val remainingMoneyYuan: String,
    val devices: List<AccountDevice>
)

sealed interface AccountOverviewResult {
    data class Success(val overview: AccountOverview) : AccountOverviewResult
    data class Failure(val message: String) : AccountOverviewResult
}

sealed interface DeviceLogoutResult {
    data object Success : DeviceLogoutResult
    data class Failure(val message: String) : DeviceLogoutResult
}

/**
 * 通过校园网关的单点登录进入自助服务系统。
 *
 * Cookie 和 CSRF token 仅保存在内存中；应用重启、账号切换或网络切换后都需要重新建立会话。
 */
class SelfServiceRepository {

    private val cookieJar = InMemoryCookieJar()
    private val requestMutex = Mutex()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(HttpLogInterceptor())
        .build()

    @Volatile
    private var csrfToken: String? = null

    suspend fun loadAccountOverview(
        lgnUsername: String,
        wlanUserIp: String
    ): AccountOverviewResult = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val account = lgnUsername.substringBefore("@").trim()
            if (account.isBlank()) {
                return@withLock AccountOverviewResult.Failure("未能获取当前校园网账号")
            }
            if (!wlanUserIp.isUsableIpv4()) {
                return@withLock AccountOverviewResult.Failure("未获取到有效的校园网 IP 地址")
            }

            clearSessionLocked()

            var stage = AccountOverviewLoadStage.REQUEST_SSO_CREDENTIALS
            try {
                val ssoResponse = execute(buildSsoRequest(account, wlanUserIp))
                stage = AccountOverviewLoadStage.PARSE_SSO_CREDENTIALS
                val ssoData = extractJsonObject(
                    text = ssoResponse.body,
                    dataDescription = "校园网关返回的自助服务凭证"
                )
                if (ssoData.optInt("result") != 1) {
                    val message = ssoData.optString("msg").ifBlank { "校园网关未返回自助服务凭证" }
                    throw SelfServiceException(message)
                }

                val authUrl = ssoData.optString("self_auth_url")
                if (authUrl.isBlank()) {
                    throw SelfServiceException("校园网关未返回自助服务地址")
                }
                val parsedAuthUrl = authUrl.toHttpUrlOrNull()
                    ?: throw SelfServiceException("校园网关返回的自助服务地址无效，请稍后重试")

                // 访问跳转地址以建立 jfself 会话；CookieJar 会保存重定向过程中的会话 Cookie。
                stage = AccountOverviewLoadStage.OPEN_SELF_SERVICE_SESSION
                execute(
                    Request.Builder()
                        .url(parsedAuthUrl)
                        .get()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )

                stage = AccountOverviewLoadStage.REQUEST_ACCOUNT_PAGE
                val myMacResponse = execute(
                    Request.Builder()
                        .url(selfServiceUrl("myMac"))
                        .get()
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", SELF_SERVICE_REFERER)
                        .build()
                )
                stage = AccountOverviewLoadStage.PARSE_ACCOUNT_PAGE
                val token = extractCsrfToken(myMacResponse.body)
                    ?: throw SelfServiceException("未能获取设备操作凭证，请刷新后重试")
                val userData = extractJsonObject(
                    text = myMacResponse.body,
                    marker = "})(",
                    dataDescription = "自助服务返回的账号信息"
                )

                stage = AccountOverviewLoadStage.REQUEST_DEVICE_LIST
                val macListResponse = execute(
                    Request.Builder()
                        .url(
                            selfServiceUrl("getMacList").newBuilder()
                                .addQueryParameter("pageSize", "100")
                                .addQueryParameter("pageNumber", "1")
                                .addQueryParameter("sortName", "2")
                                .addQueryParameter("sortOrder", "DESC")
                                .addQueryParameter("_", System.currentTimeMillis().toString())
                                .build()
                        )
                        .get()
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", SELF_SERVICE_REFERER)
                        .build()
                )

                stage = AccountOverviewLoadStage.PARSE_DEVICE_LIST
                val deviceRows = parseDeviceRows(macListResponse.body)
                stage = AccountOverviewLoadStage.BUILD_ACCOUNT_OVERVIEW
                csrfToken = token
                AccountOverviewResult.Success(
                    AccountOverview(
                        username = userData.optString("userName").ifBlank { account },
                        usedFlowMb = userData.optString("internetDownFlow"),
                        remainingFlowMb = userData.optString("leftFlow"),
                        remainingMoneyYuan = userData.optString("leftMoney"),
                        devices = mergeDevices(
                            userData.optString("macAddress"),
                            deviceRows
                        )
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clearSessionLocked()
                AccountOverviewResult.Failure(
                    error.toUserMessage(stage.unexpectedErrorMessage)
                )
            }
        }
    }

    suspend fun logoutDevice(macAddress: String): DeviceLogoutResult = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val token = csrfToken
                ?: return@withLock DeviceLogoutResult.Failure("会话已失效，请先刷新账号信息")
            val mac = canonicalMac(macAddress)
                ?: return@withLock DeviceLogoutResult.Failure("MAC 地址格式无效")

            try {
                val response = execute(
                    Request.Builder()
                        .url(
                            selfServiceUrl("unbindmac").newBuilder()
                                .addQueryParameter("mac", mac)
                                .addQueryParameter("ajaxCsrfToken", token)
                                .build()
                        )
                        .get()
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", SELF_SERVICE_REFERER)
                        .build()
                )

                when (val result = DeviceLogoutResponseParser.parse(response.body)) {
                    DeviceLogoutResponse.Success -> DeviceLogoutResult.Success
                    is DeviceLogoutResponse.Failure -> DeviceLogoutResult.Failure(
                        result.message?.let { "解绑失败：$it" }
                            ?: "校园网系统未能解绑该设备"
                    )
                    DeviceLogoutResponse.Unknown -> DeviceLogoutResult.Failure(
                        "无法确认解绑结果，请刷新设备列表确认"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DeviceLogoutResult.Failure(
                    error.toUserMessage("处理设备下线结果失败，请稍后重试")
                )
            }
        }
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            clearSessionLocked()
        }
    }

    private fun clearSessionLocked() {
        csrfToken = null
        cookieJar.clear()
    }

    private fun buildSsoRequest(account: String, wlanUserIp: String): Request {
        // 固定字段来自自助服务入口的实际请求；仅账号和当前 WLAN IP 需要每次重新编码。
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(GATEWAY_HOST)
            .port(802)
            .addPathSegments("eportal/portal/self")
            .addQueryParameter("callback", xorEncode("dr1004"))
            .addQueryParameter("self_type", xorEncode("1"))
            .addQueryParameter("user_account", xorEncode(account))
            .addQueryParameter("user_password", "")
            .addQueryParameter("wlan_user_mac", xorEncode("000000000000"))
            .addQueryParameter("wlan_user_ip", xorEncode(wlanUserIp))
            .addQueryParameter("jsVersion", ENCRYPTED_JS_VERSION)
            .addQueryParameter("program_index", ENCRYPTED_PROGRAM_INDEX)
            .addQueryParameter("page_index", ENCRYPTED_PAGE_INDEX)
            .addQueryParameter("encrypt", "1")
            .addQueryParameter("v", "4082")
            .addQueryParameter("lang", "zh")
            .build()

        return Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
    }

    private fun selfServiceUrl(endpoint: String): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(SELF_SERVICE_HOST)
        .addPathSegments("Self/service/$endpoint")
        .build()

    private suspend fun execute(request: Request): HttpResponse {
        client.executeCancellable(request).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SelfServiceException("自助服务请求失败（HTTP ${response.code}）")
            }
            return HttpResponse(body)
        }
    }

    private fun parseDeviceRows(response: String): List<DeviceRow> {
        val rows = try {
            JSONObject(response).optJSONArray("rows") ?: JSONArray()
        } catch (_: JSONException) {
            throw SelfServiceException("自助服务返回的设备列表格式异常，请稍后重试")
        }
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONArray(index) ?: continue
                val mac = canonicalMac(row.optString(1)) ?: continue
                val status = row.optString(0).trim()
                add(
                    DeviceRow(
                        mac = mac,
                        status = status,
                        ipAddress = row.optString(4).trim(),
                        isOnline = status.toOnlineState()
                    )
                )
            }
        }
    }

    private fun mergeDevices(accountMacs: String, deviceRows: List<DeviceRow>): List<AccountDevice> {
        val devices = linkedMapOf<String, AccountDevice>()

        accountMacs.split(';')
            .mapNotNull(::canonicalMac)
            .forEach { mac ->
                devices[mac] = AccountDevice(
                    macAddress = formatMac(mac),
                    status = "状态未知",
                    ipAddress = "",
                    isOnline = null
                )
            }

        deviceRows.forEach { row ->
            devices[row.mac] = AccountDevice(
                macAddress = formatMac(row.mac),
                status = row.status.ifBlank { "状态未知" },
                ipAddress = row.ipAddress,
                isOnline = row.isOnline
            )
        }

        return devices.values.sortedWith(
            compareByDescending<AccountDevice> { it.isOnline == true }
                .thenBy { it.macAddress }
        )
    }

    private fun extractJsonObject(
        text: String,
        marker: String? = null,
        dataDescription: String
    ): JSONObject {
        val searchStart = marker?.let { text.indexOf(it).takeIf { index -> index >= 0 } } ?: 0
        val objectStart = text.indexOf('{', searchStart)
        if (objectStart < 0) {
            throw SelfServiceException("${dataDescription}格式异常，请稍后重试")
        }

        var depth = 0
        var inString = false
        var isEscaped = false
        for (index in objectStart until text.length) {
            val char = text[index]
            when (char) {
                '\\' -> if (inString) isEscaped = !isEscaped
                '"' -> if (!isEscaped) inString = !inString
                '{' -> if (!inString) depth += 1
                '}' -> if (!inString) {
                    depth -= 1
                    if (depth == 0) {
                        return try {
                            JSONObject(text.substring(objectStart, index + 1))
                        } catch (_: JSONException) {
                            throw SelfServiceException("${dataDescription}无法解析，请稍后重试")
                        }
                    }
                }
            }
            if (char != '\\') isEscaped = false
        }
        throw SelfServiceException("${dataDescription}不完整，请稍后重试")
    }

    private fun extractCsrfToken(text: String): String? =
        CSRF_TOKEN_PATTERN.find(text)?.groupValues?.getOrNull(1)

    private fun Exception.toUserMessage(unexpectedErrorMessage: String): String = when (this) {
        is SelfServiceException -> message ?: "自助服务请求失败"
        is IOException -> "网络错误：${message ?: "请检查网络连接"}"
        else -> unexpectedErrorMessage
    }

    private data class HttpResponse(val body: String)

    private data class DeviceRow(
        val mac: String,
        val status: String,
        val ipAddress: String,
        val isOnline: Boolean?
    )

    internal enum class AccountOverviewLoadStage(val unexpectedErrorMessage: String) {
        REQUEST_SSO_CREDENTIALS("请求自助服务登录凭证时发生异常，请稍后重试"),
        PARSE_SSO_CREDENTIALS("解析自助服务登录凭证时发生异常，请稍后重试"),
        OPEN_SELF_SERVICE_SESSION("建立自助服务会话时发生异常，请稍后重试"),
        REQUEST_ACCOUNT_PAGE("请求账号信息页面时发生异常，请稍后重试"),
        PARSE_ACCOUNT_PAGE("解析账号基本信息时发生异常，请稍后重试"),
        REQUEST_DEVICE_LIST("请求设备列表时发生异常，请稍后重试"),
        PARSE_DEVICE_LIST("解析设备列表时发生异常，请稍后重试"),
        BUILD_ACCOUNT_OVERVIEW("整理账号信息时发生异常，请稍后重试")
    }

    companion object {
        private const val GATEWAY_HOST = "lgn.bjut.edu.cn"
        private const val SELF_SERVICE_HOST = "jfself.bjut.edu.cn"
        private const val SELF_SERVICE_REFERER = "https://jfself.bjut.edu.cn/Self/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0"

        // 以下值为网关入口所需的固定加密参数，来源于用户提供的抓包示例。
        private const val ENCRYPTED_JS_VERSION = "2238243824"
        private const val ENCRYPTED_PROGRAM_INDEX = "79225954737327212323222f212e2723"
        private const val ENCRYPTED_PAGE_INDEX = "755e577b7c4e27212323222f212e2320"
        private val CSRF_TOKEN_PATTERN = Regex(
            """ajaxCsrfToken.*?([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})""",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}

private class InMemoryCookieJar : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            this.cookies.removeAll { it.expiresAt <= now }
            cookies.forEach { incoming ->
                this.cookies.removeAll {
                    it.name == incoming.name &&
                            it.domain == incoming.domain &&
                            it.path == incoming.path
                }
                if (incoming.expiresAt > now) {
                    this.cookies.add(incoming)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            cookies.removeAll { it.expiresAt <= now }
            cookies.filter { it.matches(url) }
        }
    }

    fun clear() {
        synchronized(lock) {
            cookies.clear()
        }
    }
}

private class SelfServiceException(message: String) : IOException(message)

private fun xorEncode(value: String): String = buildString(value.length * 2) {
    value.forEach { char ->
        append((char.code xor 0x16).toString(16).padStart(2, '0'))
    }
}

private fun canonicalMac(value: String): String? {
    val mac = value.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
    return mac.takeIf {
        it.length == 12 && it.all { char -> char in '0'..'9' || char in 'A'..'F' }
    }
}

private fun formatMac(mac: String): String = mac.chunked(2).joinToString(":")

private fun String.toOnlineState(): Boolean? = when {
    trim() == "1" -> true
    trim() == "0" -> false
    contains("离线") -> false
    contains("在线") -> true
    else -> null
}
