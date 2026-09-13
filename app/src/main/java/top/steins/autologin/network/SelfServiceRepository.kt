package top.steins.autologin.network

import android.content.Context
import androidx.annotation.StringRes
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
import org.json.JSONException
import org.json.JSONObject
import top.steins.autologin.R
import java.io.IOException
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
    data class Success(
        val overview: AccountOverview,
        val warningMessage: String = "",
        val isDeviceListAvailable: Boolean = true,
        val canLogoutDevices: Boolean = true
    ) : AccountOverviewResult
    data class Failure(
        val message: String,
        val isRetryable: Boolean = false
    ) : AccountOverviewResult
}

sealed interface DeviceLogoutResult {
    data object Success : DeviceLogoutResult
    data class Failure(val message: String) : DeviceLogoutResult
    data class Indeterminate(val message: String) : DeviceLogoutResult
}

/**
 * 账号自助服务的入口抽象，便于 ViewModel 单元测试注入替身。
 */
interface SelfServiceGateway {
    suspend fun loadAccountOverview(lgnUsername: String, wlanUserIp: String): AccountOverviewResult

    suspend fun logoutDevice(macAddress: String): DeviceLogoutResult

    suspend fun clearSession()
}

/**
 * 通过校园网关的单点登录进入自助服务系统。
 *
 * Cookie 和 CSRF token 仅保存在内存中；应用重启、账号切换或网络切换后都需要重新建立会话。
 */
class SelfServiceRepository(context: Context) : SelfServiceGateway {

    private val appContext = context.applicationContext
    private val cookieJar = InMemoryCookieJar()
    private val requestMutex = Mutex()

    private val client = OkHttpClient.Builder()
        .allowCampusCertificateErrors()
        .cookieJar(cookieJar)
        .connectTimeout(CAMPUS_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CAMPUS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CAMPUS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(HttpLogInterceptor(httpLogMessageProvider(appContext)))
        .build()

    @Volatile
    private var csrfToken: String? = null

    override suspend fun loadAccountOverview(
        lgnUsername: String,
        wlanUserIp: String
    ): AccountOverviewResult = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val account = lgnUsername.substringBefore("@").trim()
            if (account.isBlank()) {
                return@withLock AccountOverviewResult.Failure(
                    appContext.getString(R.string.self_service_missing_account)
                )
            }
            if (!wlanUserIp.isUsableIpv4()) {
                return@withLock AccountOverviewResult.Failure(
                    appContext.getString(R.string.login_no_valid_campus_ip)
                )
            }

            clearSessionLocked()

            var stage = AccountOverviewLoadStage.REQUEST_SSO_CREDENTIALS
            try {
                val ssoResponse = execute(buildSsoRequest(account, wlanUserIp))
                stage = AccountOverviewLoadStage.PARSE_SSO_CREDENTIALS
                val ssoData = parseJsonObject(
                    text = ssoResponse.body,
                    marker = null,
                    dataDescriptionRes = R.string.self_service_data_sso_credentials
                )
                if (ssoData.optInt("result") != 1) {
                    val message = ssoData.optString("msg").ifBlank {
                        appContext.getString(R.string.self_service_missing_sso_credentials)
                    }
                    throw SelfServiceException(message)
                }

                val authUrl = ssoData.optString("self_auth_url")
                if (authUrl.isBlank()) {
                    throw SelfServiceException(
                        appContext.getString(R.string.self_service_missing_sso_url)
                    )
                }
                val parsedAuthUrl = authUrl.toHttpUrlOrNull()
                    ?: throw SelfServiceException(
                        appContext.getString(R.string.self_service_invalid_sso_url)
                    )

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
                val userData = parseJsonObject(
                    text = myMacResponse.body,
                    marker = "})(",
                    dataDescriptionRes = R.string.self_service_data_account_info
                )
                val token = SelfServiceParsing.extractCsrfToken(myMacResponse.body)

                val deviceList = try {
                    stage = AccountOverviewLoadStage.REQUEST_DEVICE_LIST
                    val macListResponse = execute(
                        Request.Builder()
                            .url(
                                selfServiceUrl("getMacList").newBuilder()
                                    .addQueryParameter("pageSize", "10")
                                    .addQueryParameter("pageNumber", "1")
                                    .addQueryParameter("sortName", "2")
                                    .addQueryParameter("sortOrder", "DESC")
                                    .build()
                            )
                            .get()
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", SELF_SERVICE_REFERER)
                            .build()
                    )

                    stage = AccountOverviewLoadStage.PARSE_DEVICE_LIST
                    if (macListResponse.body.isBlank()) {
                        DeviceListLoadResult(
                            rows = emptyList(),
                            errorMessage = appContext.getString(
                                R.string.self_service_empty_device_list
                            ),
                            isAvailable = false
                        )
                    } else {
                        val deviceRows = parseDeviceRows(macListResponse.body)
                        if (deviceRows.isEmpty()) {
                            DeviceListLoadResult(
                                rows = emptyList(),
                                errorMessage = appContext.getString(
                                    R.string.self_service_empty_device_list
                                ),
                                isAvailable = false
                            )
                        } else {
                            DeviceListLoadResult(rows = deviceRows)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // 账号页数据已成功取得；设备列表请求或解析失败时仅隐藏设备区域。
                    DeviceListLoadResult(
                        rows = emptyList(),
                        errorMessage = error.toUserMessage(
                            appContext,
                            stage.unexpectedErrorMessageRes
                        ),
                        isAvailable = false
                    )
                }
                stage = AccountOverviewLoadStage.BUILD_ACCOUNT_OVERVIEW
                csrfToken = token
                val warnings = buildList {
                    if (!deviceList.isAvailable) add(deviceList.errorMessage)
                    if (token == null) {
                        add(appContext.getString(R.string.self_service_missing_device_token))
                    }
                }
                AccountOverviewResult.Success(
                    AccountOverview(
                        username = userData.optString("userName").ifBlank { account },
                        usedFlowMb = userData.optString("internetDownFlow"),
                        remainingFlowMb = userData.optString("leftFlow"),
                        remainingMoneyYuan = userData.optString("leftMoney"),
                        devices = mergeDevices(
                            userData.optString("macAddress"),
                            deviceList.rows
                        )
                    ),
                    warningMessage = warnings.joinToString(separator = "\n"),
                    isDeviceListAvailable = deviceList.isAvailable,
                    canLogoutDevices = token != null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clearSessionLocked()
                AccountOverviewResult.Failure(
                    message = error.toUserMessage(appContext, stage.unexpectedErrorMessageRes),
                    isRetryable = error is IOException && error !is SelfServiceException
                )
            }
        }
    }

    override suspend fun logoutDevice(macAddress: String): DeviceLogoutResult = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val token = csrfToken
                ?: return@withLock DeviceLogoutResult.Failure(
                    appContext.getString(R.string.logout_session_expired)
                )
            val mac = SelfServiceParsing.canonicalMac(macAddress)
                ?: return@withLock DeviceLogoutResult.Failure(
                    appContext.getString(R.string.logout_invalid_mac)
                )

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
                        result.message?.let {
                            appContext.getString(R.string.logout_failed_with_reason, it)
                        } ?: appContext.getString(R.string.logout_failed_default)
                    )
                    DeviceLogoutResponse.Unknown -> DeviceLogoutResult.Indeterminate(
                        appContext.getString(R.string.logout_indeterminate)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DeviceLogoutResult.Failure(
                    error.toUserMessage(appContext, R.string.logout_failed_generic)
                )
            }
        }
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
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
            .addQueryParameter("callback", SelfServiceParsing.xorEncode("dr1004"))
            .addQueryParameter("self_type", SelfServiceParsing.xorEncode("1"))
            .addQueryParameter("user_account", SelfServiceParsing.xorEncode(account))
            .addQueryParameter("user_password", "")
            .addQueryParameter("wlan_user_mac", SelfServiceParsing.xorEncode("000000000000"))
            .addQueryParameter("wlan_user_ip", SelfServiceParsing.xorEncode(wlanUserIp))
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
                throw SelfServiceException(
                    appContext.getString(R.string.self_service_http_error, response.code)
                )
            }
            return HttpResponse(body)
        }
    }

    private fun parseDeviceRows(response: String): List<DeviceRow> =
        SelfServiceParsing.parseDeviceRows(response) ?: throw SelfServiceException(
            appContext.getString(R.string.self_service_device_list_format_invalid)
        )

    private fun mergeDevices(accountMacs: String, deviceRows: List<DeviceRow>): List<AccountDevice> =
        SelfServiceParsing.mergeDevices(
            accountMacs = accountMacs,
            rows = deviceRows,
            unknownStatusLabel = appContext.getString(R.string.device_status_unknown)
        )

    private fun parseJsonObject(
        text: String,
        marker: String?,
        @StringRes dataDescriptionRes: Int
    ): JSONObject {
        val description = appContext.getString(dataDescriptionRes)
        return when (val extraction = SelfServiceParsing.findJsonObject(text, marker)) {
            is JsonObjectExtraction.Found -> try {
                JSONObject(extraction.text)
            } catch (_: JSONException) {
                throw SelfServiceException(
                    appContext.getString(
                        R.string.self_service_data_parse_failed,
                        description
                    )
                )
            }

            JsonObjectExtraction.Missing -> throw SelfServiceException(
                appContext.getString(
                    R.string.self_service_data_format_invalid,
                    description
                )
            )

            JsonObjectExtraction.Incomplete -> throw SelfServiceException(
                appContext.getString(
                    R.string.self_service_data_incomplete,
                    description
                )
            )
        }
    }

    private fun Exception.toUserMessage(
        context: Context,
        @StringRes unexpectedErrorMessageRes: Int
    ): String = when (this) {
        is SelfServiceException -> message
            ?: context.getString(R.string.self_service_request_failed)

        is IOException -> context.getString(
            R.string.network_error_with_reason,
            message ?: context.getString(R.string.network_check_connection)
        )
        else -> context.getString(unexpectedErrorMessageRes)
    }

    private data class HttpResponse(val body: String)

    private data class DeviceListLoadResult(
        val rows: List<DeviceRow>,
        val errorMessage: String = "",
        val isAvailable: Boolean = true
    )

    internal enum class AccountOverviewLoadStage(
        @param:StringRes val unexpectedErrorMessageRes: Int
    ) {
        REQUEST_SSO_CREDENTIALS(R.string.self_service_stage_request_sso),
        PARSE_SSO_CREDENTIALS(R.string.self_service_stage_parse_sso),
        OPEN_SELF_SERVICE_SESSION(R.string.self_service_stage_open_session),
        REQUEST_ACCOUNT_PAGE(R.string.self_service_stage_request_account_page),
        PARSE_ACCOUNT_PAGE(R.string.self_service_stage_parse_account_page),
        REQUEST_DEVICE_LIST(R.string.self_service_stage_request_device_list),
        PARSE_DEVICE_LIST(R.string.self_service_stage_parse_device_list),
        BUILD_ACCOUNT_OVERVIEW(R.string.self_service_stage_build_overview)
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
