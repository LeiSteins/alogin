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
    val wifiName: String = "",
    val ipAddress: String = "",
    val isOnline: Boolean = false,
    val accountOverview: AccountOverview? = null,
    val isAccountInfoLoading: Boolean = false,
    val accountInfoError: String = "",
    val isDeviceListAvailable: Boolean = false,
    val canLogoutDevices: Boolean = false,
    val networkStatusError: String = "",
    val hasLocationPermission: Boolean = false
)

/**
 * 协调网络变化、认证状态和自助服务会话，避免 Compose 生命周期与并发网络请求互相覆盖状态。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val settingsRepository = SettingsRepository(application)

    private val selfServiceRepository = SelfServiceRepository(application)
    private val updateRepository = UpdateRepository()
    private val accountOperationMutex = Mutex()
    private val _uiState = MutableStateFlow(
        AppUiState(
            wifiName = getApplication<Application>().getString(R.string.loading),
            ipAddress = getApplication<Application>().getString(R.string.loading),
            hasLocationPermission = hasWifiLocationPermission(application)
        )
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

    /**
     * 部分设备刚回到前台时会短暂返回脱敏 SSID；延迟并重试，避免误判为非目标 WiFi。
     */
    fun onAppForegrounded() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.AppForegrounded,
            clearSession = false,
            attempts = FOREGROUND_REFRESH_ATTEMPTS,
            initialDelayMs = FOREGROUND_REFRESH_INITIAL_DELAY_MS
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

    fun refreshAfterIndeterminateDeviceLogout() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.DeviceLogoutIndeterminate,
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
                    error.message
                        ?: getApplication<Application>().getString(R.string.update_check_failed)
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
                attempts = NETWORK_REFRESH_ATTEMPTS,
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
                        trigger.description(getApplication(), attempt + 1, attempts)
                    )
                    refreshStatusInternal(
                        generation = generation,
                        clearSession = clearSession && attempt == 0,
                        showNetworkStatusError = attempt == attempts - 1
                    )
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
                LoginResult.Failure(
                    getApplication<Application>().getString(R.string.login_no_credentials)
                )
            }

            networkInfo.isCellular -> {
                LoginResult.Failure(
                    getApplication<Application>().getString(R.string.login_cellular_blocked)
                )
            }

            !networkInfo.isWifi || networkInfo.wifiName !in settingsRepository.targetWifis.value -> {
                LoginResult.Failure(
                    getApplication<Application>().getString(R.string.login_wrong_network)
                )
            }

            else -> login(getApplication(), username, password, networkInfo.ipAddress)
        }
    }

    suspend fun logoutDevice(macAddress: String): DeviceLogoutResult = accountOperationMutex.withLock {
        selfServiceRepository.logoutDevice(macAddress)
    }

    private suspend fun refreshStatusInternal(
        generation: Long,
        clearSession: Boolean,
        showNetworkStatusError: Boolean
    ) {
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
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
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
                isDeviceListAvailable = false,
                canLogoutDevices = false,
                networkStatusError = ""
            )
        }

        val status = checkLoginStatus(getApplication())
        if (!status.isLoggedIn) {
            selfServiceRepository.clearSession()
            updateState(generation) {
                copy(
                    isOnline = false,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = "",
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = if (showNetworkStatusError) status.error else ""
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
                    accountInfoError = getApplication<Application>().getString(
                        R.string.account_info_fetch_failed
                    ),
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
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
                isDeviceListAvailable = false,
                canLogoutDevices = false,
                networkStatusError = ""
            )
        }

        when (val result = selfServiceRepository.loadAccountOverview(username, networkInfo.ipAddress)) {
            is AccountOverviewResult.Success -> updateState(generation) {
                copy(
                    isOnline = true,
                    accountOverview = result.overview,
                    isAccountInfoLoading = false,
                    accountInfoError = result.warningMessage,
                    isDeviceListAvailable = result.isDeviceListAvailable,
                    canLogoutDevices = result.canLogoutDevices
                )
            }

            is AccountOverviewResult.Failure -> updateState(generation) {
                copy(
                    isOnline = true,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = result.message,
                    isDeviceListAvailable = false,
                    canLogoutDevices = false
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
        const val NETWORK_REFRESH_ATTEMPTS = 5
        const val FOREGROUND_REFRESH_INITIAL_DELAY_MS = 800L
        const val FOREGROUND_REFRESH_ATTEMPTS = 3
        const val LOGIN_CONFIRMATION_ATTEMPTS = 3
        const val LOGIN_CONFIRMATION_INITIAL_DELAY_MS = 500L
        const val LOGIN_CONFIRMATION_RETRY_DELAY_MS = 1_000L
    }
}

private sealed interface AccountInfoRefreshTrigger {
    fun description(context: Context, attempt: Int, totalAttempts: Int): String

    data class DefaultNetworkChanged(val change: DefaultNetworkChange) : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String {
            val messageRes = when (change) {
                DefaultNetworkChange.INITIAL -> R.string.refresh_trigger_initial
                DefaultNetworkChange.IP_ADDRESS_CHANGED -> R.string.refresh_trigger_ip_changed
                DefaultNetworkChange.SSID_CHANGED -> R.string.refresh_trigger_ssid_changed
                DefaultNetworkChange.IP_ADDRESS_AND_SSID_CHANGED ->
                    R.string.refresh_trigger_ip_ssid_changed
            }
            return context.getString(messageRes)
        }
    }

    data class LocationPermissionResult(val granted: Boolean) : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String {
            val permissionText = context.getString(
                if (granted) R.string.permission_granted else R.string.permission_denied
            )
            return context.getString(R.string.refresh_trigger_permission_result, permissionText)
        }
    }

    data object AppForegrounded : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String =
            context.getString(R.string.refresh_trigger_foreground, attempt, totalAttempts)
    }

    data class TargetWifiConfigurationChanged(
        val change: TargetWifiConfigChange
    ) : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String {
            val messageRes = when (change.type) {
                TargetWifiConfigChangeType.ADDED -> R.string.refresh_trigger_wifi_added
                TargetWifiConfigChangeType.REMOVED -> R.string.refresh_trigger_wifi_removed
            }
            return context.getString(messageRes, change.ssid)
        }
    }

    data object ManualNetworkStatusCheck : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String =
            context.getString(R.string.refresh_trigger_manual_network_check)
    }

    data object ManualAccountInfoRefresh : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String =
            context.getString(R.string.refresh_trigger_manual_account_refresh)
    }

    data object AccountInfoRetry : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String =
            context.getString(R.string.refresh_trigger_account_retry)
    }

    data class DeviceLogoutSucceeded(
        val successfulDeviceCount: Int
    ) : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String =
            context.getString(
                R.string.refresh_trigger_device_logout_succeeded,
                successfulDeviceCount
            )
    }

    data object DeviceLogoutIndeterminate : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String =
            context.getString(R.string.refresh_trigger_device_logout_indeterminate)
    }

    data object LoginConfirmation : AccountInfoRefreshTrigger {
        override fun description(context: Context, attempt: Int, totalAttempts: Int): String =
            context.getString(R.string.refresh_trigger_login_confirmation, attempt, totalAttempts)
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
