package top.steins.autologin

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.data.TargetWifiConfigChange
import top.steins.autologin.data.TargetWifiConfigChangeType
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.SelfServiceRepository
import top.steins.autologin.network.checkLoginStatus
import top.steins.autologin.network.getCurrentNetworkInfo
import top.steins.autologin.network.hasWifiLocationPermission
import top.steins.autologin.network.login
import top.steins.autologin.network.update.SemanticVersion
import top.steins.autologin.network.update.UpdateDownloadResult
import top.steins.autologin.network.update.UpdateRepository
import top.steins.autologin.network.update.UpdateState

data class AppUiState(
    val wifiName: String = "加载中…",
    val ipAddress: String = "加载中…",
    val isOnline: Boolean = false,
    val accountOverview: AccountOverview? = null,
    val isAccountInfoLoading: Boolean = false,
    val accountInfoError: String = "",
    val networkStatusError: String = "",
    val hasLocationPermission: Boolean = false
)

/**
 * 协调网络变化、认证状态和自助服务会话，避免 Compose 生命周期与并发网络请求互相覆盖状态。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val settingsRepository = SettingsRepository(application)

    private val selfServiceRepository = SelfServiceRepository()
    private val updateRepository = UpdateRepository()
    private val accountOperationMutex = Mutex()
    private val _uiState = MutableStateFlow(
        AppUiState(hasLocationPermission = hasWifiLocationPermission(application))
    )
    val uiState = _uiState.asStateFlow()
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()
    private val _updateMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val updateMessages = _updateMessages.asSharedFlow()

    private var refreshJob: Job? = null
    private var networkRefreshDebounceJob: Job? = null
    private var updateCheckJob: Job? = null
    private var refreshGeneration = 0L

    init {
        observeDefaultNetworkChanges(application)
            .conflate()
            .collectInViewModel { change -> scheduleNetworkRefresh(change) }

        settingsRepository.targetWifiConfigChanges.collectInViewModel { change ->
            startRefresh(
                trigger = AccountInfoRefreshTrigger.TargetWifiConfigurationChanged(change),
                clearSession = false,
                attempts = 1,
                initialDelayMs = 0
            )
        }

        checkForUpdates()
    }

    fun onLocationPermissionChanged() {
        val hasLocationPermission = hasWifiLocationPermission(getApplication())
        _uiState.value = _uiState.value.copy(
            hasLocationPermission = hasLocationPermission
        )
        startRefresh(
            trigger = AccountInfoRefreshTrigger.LocationPermissionResult(hasLocationPermission),
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun refreshStatus(clearSession: Boolean = false) {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.ManualNetworkStatusCheck,
            clearSession = clearSession,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun refreshAccountInfo() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.ManualAccountInfoRefresh,
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun retryAccountInfo() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.AccountInfoRetry,
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun refreshAfterDeviceLogout(successfulDeviceCount: Int) {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.DeviceLogoutSucceeded(successfulDeviceCount),
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun confirmLogin() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.LoginConfirmation,
            clearSession = false,
            attempts = LOGIN_CONFIRMATION_ATTEMPTS,
            initialDelayMs = LOGIN_CONFIRMATION_INITIAL_DELAY_MS
        )
    }

    fun checkForUpdates(manual: Boolean = false) {
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            runCatching {
                updateRepository.fetchLatestUpdate(BuildConfig.VERSION_NAME)
            }.onSuccess { update ->
                val state = if (SemanticVersion.isNewer(update.version, BuildConfig.VERSION_NAME)) {
                    UpdateState.Available(update)
                } else {
                    UpdateState.UpToDate(update.version)
                }
                _updateState.value = state
                if (manual) {
                    val message = when (state) {
                        is UpdateState.Available -> getApplication<Application>().getString(
                            R.string.update_found,
                            state.update.version
                        )
                        is UpdateState.UpToDate -> getApplication<Application>().getString(
                            R.string.update_latest_toast
                        )
                        else -> null
                    }
                    message?.let { _updateMessages.emit(it) }
                }
            }.onFailure { error ->
                _updateState.value = UpdateState.Error(
                    error.message ?: "检查更新失败"
                )
                if (manual) {
                    _updateMessages.emit(
                        getApplication<Application>().getString(R.string.update_failed_toast)
                    )
                }
            }
        }
    }

    fun downloadAvailableUpdate() {
        val update = (_updateState.value as? UpdateState.Available)?.update ?: return
        val message = when (updateRepository.downloadUpdate(getApplication(), update)) {
            UpdateDownloadResult.Enqueued -> R.string.update_download_enqueued
            UpdateDownloadResult.OpenedInBrowser -> R.string.update_opened_in_browser
            UpdateDownloadResult.Failed -> R.string.update_download_failed
        }
        _updateMessages.tryEmit(getApplication<Application>().getString(message))
    }

    private fun scheduleNetworkRefresh(change: DefaultNetworkChange) {
        networkRefreshDebounceJob?.cancel()
        networkRefreshDebounceJob = viewModelScope.launch {
            delay(NETWORK_REFRESH_DEBOUNCE_MS)
            startRefresh(
                trigger = AccountInfoRefreshTrigger.DefaultNetworkChanged(change),
                clearSession = false,
                attempts = 1,
                initialDelayMs = 0
            )
        }
    }

    private fun startRefresh(
        trigger: AccountInfoRefreshTrigger,
        clearSession: Boolean,
        attempts: Int,
        initialDelayMs: Long
    ) {
        refreshGeneration += 1
        val generation = refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            accountOperationMutex.withLock {
                if (initialDelayMs > 0) delay(initialDelayMs)
                repeat(attempts) { attempt ->
                    HttpLogStorage.logAccountInfoRefresh(
                        trigger.description(attempt + 1, attempts)
                    )
                    refreshStatusInternal(generation, clearSession = clearSession && attempt == 0)
                    if (_uiState.value.isOnline || _uiState.value.networkStatusError.isNotBlank()) {
                        return@withLock
                    }
                    if (attempt < attempts - 1) delay(LOGIN_CONFIRMATION_RETRY_DELAY_MS)
                }
            }
        }
    }

    suspend fun login(): LoginResult = accountOperationMutex.withLock {
        val networkInfo = getCurrentNetworkInfo(
            getApplication(),
            canReadWifiName = _uiState.value.hasLocationPermission
        )
        if (networkInfo.isWifi) {
            settingsRepository.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        val username = settingsRepository.username.value
        val password = settingsRepository.password.value
        when {
            username.isBlank() || password.isBlank() -> {
                LoginResult.Failure("请先在账号管理中配置账号和密码")
            }

            !networkInfo.isWifi || networkInfo.wifiName !in settingsRepository.targetWifis.value -> {
                LoginResult.Failure("请连接已配置的目标 WiFi 后再登录")
            }

            else -> login(username, password, networkInfo.ipAddress)
        }
    }

    suspend fun logoutDevice(macAddress: String): DeviceLogoutResult = accountOperationMutex.withLock {
        selfServiceRepository.logoutDevice(macAddress)
    }

    private suspend fun refreshStatusInternal(generation: Long, clearSession: Boolean) {
        if (clearSession) selfServiceRepository.clearSession()

        val networkInfo = getCurrentNetworkInfo(
            getApplication(),
            canReadWifiName = _uiState.value.hasLocationPermission
        )
        if (networkInfo.isWifi) {
            settingsRepository.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        val isTargetWifi = networkInfo.isWifi &&
                networkInfo.wifiName in settingsRepository.targetWifis.value

        if (!isTargetWifi) {
            selfServiceRepository.clearSession()
            updateState(generation) {
                copy(
                    wifiName = networkInfo.wifiName,
                    ipAddress = networkInfo.ipAddress,
                    isOnline = false,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = "",
                    networkStatusError = ""
                )
            }
            return
        }

        updateState(generation) {
            copy(
                wifiName = networkInfo.wifiName,
                ipAddress = networkInfo.ipAddress,
                isOnline = false,
                accountOverview = null,
                isAccountInfoLoading = false,
                accountInfoError = "",
                networkStatusError = ""
            )
        }

        val status = checkLoginStatus()
        if (!status.isLoggedIn) {
            selfServiceRepository.clearSession()
            updateState(generation) {
                copy(
                    isOnline = false,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = "",
                    networkStatusError = status.error
                )
            }
            return
        }

        // 自助服务账号必须与当前校园网认证账号一致，直接采用 lgn 注销页返回的 uid。
        val username = status.uid
        if (username.isBlank()) {
            selfServiceRepository.clearSession()
            updateState(generation) {
                copy(
                    isOnline = true,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = "未能从校园网状态获取当前账号",
                    networkStatusError = ""
                )
            }
            return
        }

        updateState(generation) {
            copy(
                isOnline = true,
                isAccountInfoLoading = true,
                accountInfoError = "",
                networkStatusError = ""
            )
        }

        when (val result = selfServiceRepository.loadAccountOverview(username, networkInfo.ipAddress)) {
            is AccountOverviewResult.Success -> updateState(generation) {
                copy(
                    isOnline = true,
                    accountOverview = result.overview,
                    isAccountInfoLoading = false,
                    accountInfoError = ""
                )
            }

            is AccountOverviewResult.Failure -> updateState(generation) {
                copy(
                    isOnline = true,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = result.message
                )
            }
        }
    }

    private fun updateState(generation: Long, transform: AppUiState.() -> AppUiState) {
        if (generation == refreshGeneration) {
            _uiState.value = _uiState.value.transform()
        }
    }

    private fun <T> Flow<T>.collectInViewModel(onValue: (T) -> Unit) {
        viewModelScope.launch {
            collect { onValue(it) }
        }
    }

    private companion object {
        const val NETWORK_REFRESH_DEBOUNCE_MS = 250L
        const val LOGIN_CONFIRMATION_ATTEMPTS = 3
        const val LOGIN_CONFIRMATION_INITIAL_DELAY_MS = 500L
        const val LOGIN_CONFIRMATION_RETRY_DELAY_MS = 1_000L
    }
}

private sealed interface AccountInfoRefreshTrigger {
    fun description(attempt: Int, totalAttempts: Int): String

    data class DefaultNetworkChanged(val change: DefaultNetworkChange) : AccountInfoRefreshTrigger {
        override fun description(attempt: Int, totalAttempts: Int): String = when (change) {
            DefaultNetworkChange.INITIAL -> "应用启动后检测当前 IP 地址和 WiFi SSID"
            DefaultNetworkChange.IP_ADDRESS_CHANGED -> "默认网络 IP 地址已变化"
            DefaultNetworkChange.SSID_CHANGED -> "默认网络 WiFi SSID 已变化"
            DefaultNetworkChange.IP_ADDRESS_AND_SSID_CHANGED -> "默认网络 IP 地址和 WiFi SSID 已变化"
        }
    }

    data class LocationPermissionResult(val granted: Boolean) : AccountInfoRefreshTrigger {
        override fun description(attempt: Int, totalAttempts: Int): String =
            "位置权限请求结果：${if (granted) "已授权" else "未授权"}"
    }

    data class TargetWifiConfigurationChanged(
        val change: TargetWifiConfigChange
    ) : AccountInfoRefreshTrigger {
        override fun description(attempt: Int, totalAttempts: Int): String = when (change.type) {
            TargetWifiConfigChangeType.ADDED -> "已添加目标 WiFi：${change.ssid}"
            TargetWifiConfigChangeType.REMOVED -> "已移除目标 WiFi：${change.ssid}"
        }
    }

    data object ManualNetworkStatusCheck : AccountInfoRefreshTrigger {
        override fun description(attempt: Int, totalAttempts: Int): String =
            "用户点击检查网络状态"
    }

    data object ManualAccountInfoRefresh : AccountInfoRefreshTrigger {
        override fun description(attempt: Int, totalAttempts: Int): String =
            "用户点击刷新账号信息"
    }

    data object AccountInfoRetry : AccountInfoRefreshTrigger {
        override fun description(attempt: Int, totalAttempts: Int): String =
            "账号信息加载失败后点击重新获取"
    }

    data class DeviceLogoutSucceeded(
        val successfulDeviceCount: Int
    ) : AccountInfoRefreshTrigger {
        override fun description(attempt: Int, totalAttempts: Int): String =
            "已成功使 $successfulDeviceCount 台设备下线后刷新账号与设备状态"
    }

    data object LoginConfirmation : AccountInfoRefreshTrigger {
        override fun description(attempt: Int, totalAttempts: Int): String =
            "登录成功后的账号信息确认（第 $attempt/$totalAttempts 次）"
    }
}

internal enum class DefaultNetworkChange {
    INITIAL,
    IP_ADDRESS_CHANGED,
    SSID_CHANGED,
    IP_ADDRESS_AND_SSID_CHANGED
}

internal data class NetworkRefreshSnapshot(
    val ipAddress: String,
    val ssid: String?,
    val isSsidReadable: Boolean = true
)

internal fun detectDefaultNetworkChange(
    previous: NetworkRefreshSnapshot?,
    current: NetworkRefreshSnapshot
): DefaultNetworkChange? {
    if (previous == null) return DefaultNetworkChange.INITIAL

    val ipAddressChanged = previous.ipAddress != current.ipAddress
    val ssidChanged = previous.isSsidReadable && current.isSsidReadable &&
            previous.ssid != current.ssid
    return when {
        ipAddressChanged && ssidChanged -> DefaultNetworkChange.IP_ADDRESS_AND_SSID_CHANGED
        ipAddressChanged -> DefaultNetworkChange.IP_ADDRESS_CHANGED
        ssidChanged -> DefaultNetworkChange.SSID_CHANGED
        else -> null
    }
}

private fun observeDefaultNetworkChanges(context: Context): Flow<DefaultNetworkChange> = callbackFlow {
    val connectivityManager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager
    val snapshotLock = Any()
    var previousSnapshot: NetworkRefreshSnapshot? = null

    fun notifyNetworkStateChanged() {
        val canReadWifiName = hasWifiLocationPermission(context)
        val currentSnapshot = runCatching {
            val networkInfo = getCurrentNetworkInfo(context, canReadWifiName)
            NetworkRefreshSnapshot(
                ipAddress = networkInfo.ipAddress,
                ssid = networkInfo.wifiName.takeIf { networkInfo.isWifi && canReadWifiName },
                isSsidReadable = canReadWifiName
            )
        }.getOrNull() ?: return

        val change = synchronized(snapshotLock) {
            detectDefaultNetworkChange(previousSnapshot, currentSnapshot).also {
                previousSnapshot = currentSnapshot
            }
        }
        change?.let(::trySend)
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyNetworkStateChanged()

        override fun onLost(network: Network) = notifyNetworkStateChanged()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            notifyNetworkStateChanged()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            notifyNetworkStateChanged()
        }
    }

    try {
        connectivityManager.registerDefaultNetworkCallback(callback)
        notifyNetworkStateChanged()
    } catch (error: SecurityException) {
        close(error)
    }

    awaitClose {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
