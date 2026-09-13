package top.steins.autologin

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.data.AppearanceMode
import top.steins.autologin.data.CredentialSaveResult
import top.steins.autologin.data.SettingsGateway
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.data.TargetWifiConfigChange
import top.steins.autologin.data.TargetWifiConfigChangeType
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.DefaultNetworkChange
import top.steins.autologin.network.DefaultNetworkEnvironment
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.HttpLogEntry
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SelfServiceGateway
import top.steins.autologin.network.SelfServiceRepository
import top.steins.autologin.network.hasWifiLocationPermission
import top.steins.autologin.network.update.SemanticVersion
import top.steins.autologin.network.update.UpdateDownloadResult
import top.steins.autologin.network.update.UpdateGateway
import top.steins.autologin.network.update.UpdateRepository
import top.steins.autologin.network.update.UpdateState

/** 字符串解析入口，让 ViewModel 不依赖 Android Context。 */
fun interface AppStrings {
    fun get(@StringRes resId: Int, vararg formatArgs: Any): String
}

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
 *
 * 所有依赖均通过构造函数注入，界面不直接接触存储与网络实现。
 */
class AppViewModel(
    private val strings: AppStrings,
    private val settings: SettingsGateway,
    private val selfService: SelfServiceGateway,
    private val updates: UpdateGateway,
    private val network: NetworkEnvironment,
    private val hasLocationPermission: () -> Boolean
) : ViewModel() {

    val username: StateFlow<String> = settings.username
    val password: StateFlow<String> = settings.password
    val targetWifis: StateFlow<List<String>> = settings.targetWifis
    val appearanceMode: StateFlow<AppearanceMode> = settings.appearanceMode
    val credentialResetPending: StateFlow<Boolean> = settings.credentialResetPending
    val httpLogs: StateFlow<List<HttpLogEntry>> = HttpLogStorage.logs

    private val accountOperationMutex = Mutex()

    private val _uiState = MutableStateFlow(
        AppUiState(
            wifiName = strings.get(R.string.loading),
            ipAddress = strings.get(R.string.loading),
            hasLocationPermission = hasLocationPermission()
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _updateMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val updateMessages: SharedFlow<String> = _updateMessages.asSharedFlow()

    private var refreshJob: Job? = null
    private var networkRefreshDebounceJob: Job? = null
    private var updateCheckJob: Job? = null
    private var refreshGeneration = 0L

    init {
        network.observeDefaultNetworkChanges()
            .conflate()
            .collectInViewModel { change -> scheduleNetworkRefresh(change) }

        settings.targetWifiConfigChanges.collectInViewModel { change ->
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
        val granted = hasLocationPermission()
        _uiState.update { it.copy(hasLocationPermission = granted) }
        startRefresh(
            trigger = AccountInfoRefreshTrigger.LocationPermissionResult(granted),
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun saveCredentials(username: String, password: String): CredentialSaveResult =
        settings.saveCredentials(username, password)

    fun addTargetWifi(ssid: String) = settings.addTargetWifi(ssid)

    fun removeTargetWifi(ssid: String) = settings.removeTargetWifi(ssid)

    fun saveAppearanceMode(mode: AppearanceMode) = settings.saveAppearanceMode(mode)

    fun acknowledgeCredentialReset() = settings.acknowledgeCredentialReset()

    fun clearHttpLogs() = HttpLogStorage.clear()

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
                updates.fetchLatestUpdate(BuildConfig.VERSION_NAME)
            }.onSuccess { update ->
                val state = if (SemanticVersion.isNewer(update.version, BuildConfig.VERSION_NAME)) {
                    UpdateState.Available(update)
                } else {
                    UpdateState.UpToDate(update.version)
                }
                _updateState.value = state
                if (manual) {
                    val message = when (state) {
                        is UpdateState.Available -> strings.get(
                            R.string.update_found,
                            state.update.version
                        )
                        is UpdateState.UpToDate -> strings.get(R.string.update_latest_toast)
                        else -> null
                    }
                    message?.let { _updateMessages.emit(it) }
                }
            }.onFailure { error ->
                _updateState.value = UpdateState.Error(
                    error.message ?: strings.get(R.string.update_check_failed)
                )
                if (manual) {
                    _updateMessages.emit(strings.get(R.string.update_failed_toast))
                }
            }
        }
    }

    fun downloadAvailableUpdate() {
        val update = (_updateState.value as? UpdateState.Available)?.update ?: return
        val message = when (updates.downloadUpdate(update)) {
            UpdateDownloadResult.Enqueued -> R.string.update_download_enqueued
            UpdateDownloadResult.OpenedInBrowser -> R.string.update_opened_in_browser
            UpdateDownloadResult.Failed -> R.string.update_download_failed
        }
        _updateMessages.tryEmit(strings.get(message))
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
                        trigger.description(strings, attempt + 1, attempts)
                    )
                    val outcome = refreshStatusInternal(
                        generation = generation,
                        clearSession = clearSession && attempt == 0,
                        showFinalError = attempt == attempts - 1
                    )
                    if (outcome == RefreshAttemptOutcome.Complete) {
                        return@withLock
                    }
                    if (attempt < attempts - 1) delay(REFRESH_RETRY_DELAY_MS)
                }
            }
        }
    }

    suspend fun login(): LoginResult = accountOperationMutex.withLock {
        val networkInfo = network.fetchCurrentNetworkInfo(
            canReadWifiName = _uiState.value.hasLocationPermission
        )
        if (networkInfo.isWifi) {
            settings.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        val username = settings.username.value
        val password = settings.password.value
        when {
            username.isBlank() || password.isBlank() -> {
                LoginResult.Failure(strings.get(R.string.login_no_credentials))
            }

            networkInfo.isCellular -> {
                LoginResult.Failure(strings.get(R.string.login_cellular_blocked))
            }

            !networkInfo.isWifi || networkInfo.wifiName !in settings.targetWifis.value -> {
                LoginResult.Failure(strings.get(R.string.login_wrong_network))
            }

            else -> network.performLogin(username, password, networkInfo.ipAddress)
        }
    }

    suspend fun logoutDevice(macAddress: String): DeviceLogoutResult =
        accountOperationMutex.withLock {
            selfService.logoutDevice(macAddress)
        }

    private suspend fun refreshStatusInternal(
        generation: Long,
        clearSession: Boolean,
        showFinalError: Boolean
    ): RefreshAttemptOutcome {
        if (clearSession) selfService.clearSession()

        val networkInfo = network.fetchCurrentNetworkInfo(
            canReadWifiName = _uiState.value.hasLocationPermission
        )
        if (networkInfo.isWifi) {
            settings.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        val isTargetWifi = networkInfo.isWifi &&
                networkInfo.wifiName in settings.targetWifis.value

        if (!isTargetWifi) {
            selfService.clearSession()
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
            return RefreshAttemptOutcome.Complete
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

        val status = network.fetchLoginStatus()
        if (!status.isLoggedIn) {
            selfService.clearSession()
            updateState(generation) {
                copy(
                    isOnline = false,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = "",
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = if (showFinalError) status.error else ""
                )
            }
            return RefreshAttemptOutcome.RetryableFailure
        }

        // 自助服务账号必须与当前校园网认证账号一致，直接采用 lgn 注销页返回的 uid。
        val username = status.uid
        if (username.isBlank()) {
            selfService.clearSession()
            updateState(generation) {
                copy(
                    isOnline = true,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = strings.get(R.string.account_info_fetch_failed),
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = ""
                )
            }
            return RefreshAttemptOutcome.Complete
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

        return when (val result = selfService.loadAccountOverview(username, networkInfo.ipAddress)) {
            is AccountOverviewResult.Success -> {
                updateState(generation) {
                    copy(
                        isOnline = true,
                        accountOverview = result.overview,
                        isAccountInfoLoading = false,
                        accountInfoError = result.warningMessage,
                        isDeviceListAvailable = result.isDeviceListAvailable,
                        canLogoutDevices = result.canLogoutDevices
                    )
                }
                RefreshAttemptOutcome.Complete
            }

            is AccountOverviewResult.Failure -> {
                updateState(generation) {
                    copy(
                        isOnline = true,
                        accountOverview = null,
                        isAccountInfoLoading = result.isRetryable && !showFinalError,
                        accountInfoError = if (!result.isRetryable || showFinalError) {
                            result.message
                        } else {
                            ""
                        },
                        isDeviceListAvailable = false,
                        canLogoutDevices = false
                    )
                }
                if (result.isRetryable) {
                    RefreshAttemptOutcome.RetryableFailure
                } else {
                    RefreshAttemptOutcome.Complete
                }
            }
        }
    }

    private fun updateState(generation: Long, transform: AppUiState.() -> AppUiState) {
        if (generation == refreshGeneration) {
            _uiState.update { it.transform() }
        }
    }

    private fun <T> Flow<T>.collectInViewModel(onValue: (T) -> Unit) {
        viewModelScope.launch {
            collect { onValue(it) }
        }
    }

    companion object {
        const val NETWORK_REFRESH_DEBOUNCE_MS = 250L
        const val NETWORK_REFRESH_ATTEMPTS = 5
        const val FOREGROUND_REFRESH_INITIAL_DELAY_MS = 800L
        const val FOREGROUND_REFRESH_ATTEMPTS = 3
        const val LOGIN_CONFIRMATION_ATTEMPTS = 3
        const val LOGIN_CONFIRMATION_INITIAL_DELAY_MS = 500L
        const val REFRESH_RETRY_DELAY_MS = 1_000L

        /**
         * 组装生产依赖。Application 仅在这里接入，保证 ViewModel 本体可脱离
         * Android 框架进行单元测试。
         */
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    check(modelClass.isAssignableFrom(AppViewModel::class.java)) {
                        "Unexpected ViewModel class: ${modelClass.name}"
                    }
                    return AppViewModel(
                        strings = AppStrings { resId, args ->
                            application.getString(resId, *args)
                        },
                        settings = SettingsRepository(application),
                        selfService = SelfServiceRepository(application),
                        updates = UpdateRepository(application),
                        network = DefaultNetworkEnvironment(application),
                        hasLocationPermission = { hasWifiLocationPermission(application) }
                    ) as T
                }
            }
    }
}

private enum class RefreshAttemptOutcome {
    Complete,
    RetryableFailure
}

private sealed interface AccountInfoRefreshTrigger {
    fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String

    data class DefaultNetworkChanged(val change: DefaultNetworkChange) : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String {
            val messageRes = when (change) {
                DefaultNetworkChange.INITIAL -> R.string.refresh_trigger_initial
                DefaultNetworkChange.IP_ADDRESS_CHANGED -> R.string.refresh_trigger_ip_changed
                DefaultNetworkChange.SSID_CHANGED -> R.string.refresh_trigger_ssid_changed
                DefaultNetworkChange.IP_ADDRESS_AND_SSID_CHANGED ->
                    R.string.refresh_trigger_ip_ssid_changed
            }
            return strings.get(messageRes)
        }
    }

    data class LocationPermissionResult(val granted: Boolean) : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String {
            val permissionText = strings.get(
                if (granted) R.string.permission_granted else R.string.permission_denied
            )
            return strings.get(R.string.refresh_trigger_permission_result, permissionText)
        }
    }

    data object AppForegrounded : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_foreground, attempt, totalAttempts)
    }

    data class TargetWifiConfigurationChanged(
        val change: TargetWifiConfigChange
    ) : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String {
            val messageRes = when (change.type) {
                TargetWifiConfigChangeType.ADDED -> R.string.refresh_trigger_wifi_added
                TargetWifiConfigChangeType.REMOVED -> R.string.refresh_trigger_wifi_removed
            }
            return strings.get(messageRes, change.ssid)
        }
    }

    data object ManualNetworkStatusCheck : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_manual_network_check)
    }

    data object ManualAccountInfoRefresh : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_manual_account_refresh)
    }

    data object AccountInfoRetry : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_account_retry)
    }

    data class DeviceLogoutSucceeded(
        val successfulDeviceCount: Int
    ) : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(
                R.string.refresh_trigger_device_logout_succeeded,
                successfulDeviceCount
            )
    }

    data object DeviceLogoutIndeterminate : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_device_logout_indeterminate)
    }

    data object LoginConfirmation : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_login_confirmation, attempt, totalAttempts)
    }
}
