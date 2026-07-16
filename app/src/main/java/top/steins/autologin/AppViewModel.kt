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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.SelfServiceRepository
import top.steins.autologin.network.checkLoginStatus
import top.steins.autologin.network.getCurrentNetworkInfo
import top.steins.autologin.network.hasWifiLocationPermission
import top.steins.autologin.network.login

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
    private val accountOperationMutex = Mutex()
    private val _uiState = MutableStateFlow(
        AppUiState(hasLocationPermission = hasWifiLocationPermission(application))
    )
    val uiState = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var networkRefreshDebounceJob: Job? = null
    private var refreshGeneration = 0L

    init {
        observeDefaultNetworkChanges(application)
            .conflate()
            .collectInViewModel { scheduleNetworkRefresh() }

        settingsRepository.targetWifiConfigChanges.collectInViewModel { refreshStatus() }
    }

    fun onLocationPermissionChanged() {
        _uiState.value = _uiState.value.copy(
            hasLocationPermission = hasWifiLocationPermission(getApplication())
        )
        refreshStatus()
    }

    fun refreshStatus(clearSession: Boolean = false) {
        startRefresh(clearSession = clearSession, attempts = 1, initialDelayMs = 0)
    }

    fun confirmLogin() {
        startRefresh(
            clearSession = false,
            attempts = LOGIN_CONFIRMATION_ATTEMPTS,
            initialDelayMs = LOGIN_CONFIRMATION_INITIAL_DELAY_MS
        )
    }

    private fun scheduleNetworkRefresh() {
        networkRefreshDebounceJob?.cancel()
        networkRefreshDebounceJob = viewModelScope.launch {
            delay(NETWORK_REFRESH_DEBOUNCE_MS)
            refreshStatus()
        }
    }

    private fun startRefresh(clearSession: Boolean, attempts: Int, initialDelayMs: Long) {
        refreshGeneration += 1
        val generation = refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            accountOperationMutex.withLock {
                if (initialDelayMs > 0) delay(initialDelayMs)
                repeat(attempts) { attempt ->
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

private fun observeDefaultNetworkChanges(context: Context): Flow<Unit> = callbackFlow {
    val connectivityManager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyNetworkChange()

        override fun onLost(network: Network) = notifyNetworkChange()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            notifyNetworkChange()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            notifyNetworkChange()
        }

        private fun notifyNetworkChange() {
            trySend(Unit)
        }
    }

    try {
        connectivityManager.registerDefaultNetworkCallback(callback)
        trySend(Unit)
    } catch (error: SecurityException) {
        close(error)
    }

    awaitClose {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
